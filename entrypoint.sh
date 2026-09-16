#!/bin/bash
set -euo pipefail

USERNAME=${USERNAME:-jupiter}
PORT=${PORT:-7272}
WITH_UID=${WITH_UID:-1000}
WITH_GID=${WITH_GID:-1000}
INIT_SCRIPT=${INIT_SCRIPT:-/init.sh}
INIT_USER_SCRIPT=${INIT_USER_SCRIPT:-/init-user.sh}

groupadd -g "$WITH_GID" "$USERNAME" || echo "Group exists"
useradd -u "$WITH_UID" -g "$WITH_GID" -m -s /bin/bash "$USERNAME" || echo "User exists"
chmod 644 /opt/jupiter.jar
chown -R "${USERNAME}:${USERNAME}" "/home/$USERNAME/.jupiter"

# The key intentionally remains available to both initialization scripts.
if [[ ${JUPITER_ENCRYPTION_KEY+x} != x || -z $JUPITER_ENCRYPTION_KEY ]]; then
  echo "JUPITER_ENCRYPTION_KEY is required and must not be blank" >&2
  exit 1
fi
declare -A image_env_names=()
while IFS= read -r -d '' env_name; do image_env_names["$env_name"]=1; done < /etc/jupiter-image-env-names

if [[ -f "$INIT_SCRIPT" ]]; then echo "Init script found. Running as root..."; bash "$INIT_SCRIPT"; fi
if [[ -f "$INIT_USER_SCRIPT" ]]; then echo "User init script found. Running as $USERNAME..."; su -m "$USERNAME" -c "bash $INIT_USER_SCRIPT"; fi

declare -a captured_names=()
declare -A captured_seen=()
declare -A captured_values=()
while IFS= read -r -d '' env_entry; do
  env_name=${env_entry%%=*}
  if [[ "$env_name" == JUPITER_ENCRYPTION_KEY || ( "$env_name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ && -z ${image_env_names[$env_name]+present} ) ]]; then
    if [[ -z ${captured_seen[$env_name]+present} ]]; then captured_names+=("$env_name"); captured_seen["$env_name"]=1; fi
    captured_values["$env_name"]=${env_entry#*=}
  fi
done < <(env -0)

# `su -` starts with a clean environment. Preserve only variables baked into
# the image; captured runtime variables are delivered through stdin and never
# forwarded to the JVM environment.
image_env_list=""
if ((${#image_env_names[@]})); then
  image_env_list=$(IFS=,; printf '%s' "${!image_env_names[*]}")
fi
su_args=(); [[ -n "$image_env_list" ]] && su_args=(-w "$image_env_list")

envelope() {
  printf 'JUPITER_BOOTSTRAP_V1\0'
  for env_name in "${captured_names[@]}"; do
    printf '%s\0%s\0' "$env_name" "${captured_values[$env_name]}"
  done
}
for env_name in "${captured_names[@]}"; do unset "$env_name"; done

exec {bootstrap_fd}< <(envelope)
# su preserves UID/GID behavior while the command removes all captured values before Java starts.
exec su "${su_args[@]}" - "$USERNAME" -c \
  'for n in $1; do unset "$n"; done; exec /opt/java/openjdk/bin/java -XX:+DisableAttachMechanism --enable-native-access=ALL-UNNAMED -Dserver.port="$2" -jar /opt/jupiter.jar' \
  bash "${captured_names[*]}" "$PORT" <&${bootstrap_fd}-
