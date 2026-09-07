#!/bin/bash
set -euo pipefail

USERNAME=${USERNAME:-jupiter}
PORT=${PORT:-7272}
WITH_UID=${WITH_UID:-1000}
WITH_GID=${WITH_GID:-1000}
INIT_SCRIPT=${INIT_SCRIPT:-/init.sh}
INIT_USER_SCRIPT=${INIT_USER_SCRIPT:-/init-user.sh}

# Remove the key before any setup process. It is supplied to Java only through stdin.
encryption_key_present=false
encryption_key_value=""
if [[ ${JUPITER_ENCRYPTION_KEY+x} == x ]]; then
  encryption_key_present=true
  encryption_key_value=$JUPITER_ENCRYPTION_KEY
  declare +x encryption_key_value
  unset JUPITER_ENCRYPTION_KEY
fi

if [[ $encryption_key_present != true || -z $encryption_key_value ]]; then
  echo "JUPITER_ENCRYPTION_KEY is required and must not be blank" >&2
  exit 1
fi

# su - resets the environment. Preserve only variables added after the image was built.
declare -A image_env_names=()
while IFS= read -r -d '' env_name; do
  image_env_names["$env_name"]=1
done < /etc/jupiter-image-env-names

declare -a forwarded_env_names=()
declare -A forwarded_env_names_seen=()
while IFS= read -r -d '' env_entry; do
  env_name=${env_entry%%=*}
  if [[ "$env_name" != JUPITER_ENCRYPTION_KEY ]] && [[ "$env_name" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]] && [[ -z ${image_env_names[$env_name]+present} ]] && [[ -z ${forwarded_env_names_seen[$env_name]+present} ]]; then
    forwarded_env_names+=("$env_name")
    forwarded_env_names_seen["$env_name"]=1
  fi
done < <(env -0)

forwarded_env_list=""
if ((${#forwarded_env_names[@]})); then
  forwarded_env_list=$(IFS=,; printf '%s' "${forwarded_env_names[*]}")
fi

su_args=()
if [[ -n "$forwarded_env_list" ]]; then
  su_args=(-w "$forwarded_env_list")
fi

groupadd -g "$WITH_GID" "$USERNAME" || echo "Group exists"
useradd -u "$WITH_UID" -g "$WITH_GID" -m -s /bin/bash "$USERNAME" || echo "User exists"

chmod 644 /opt/jupiter.jar
chown -R "${USERNAME}:${USERNAME}" "/home/$USERNAME/.jupiter"

if [[ -f "$INIT_SCRIPT" ]]; then
  echo "Init script found. Running as root..."
  bash "$INIT_SCRIPT"
fi

if [[ -f "$INIT_USER_SCRIPT" ]]; then
  echo "User init script found. Running as $USERNAME..."
  su - "$USERNAME" -c "bash $INIT_USER_SCRIPT"
fi

echo "Starting Jupiter as $USERNAME on port $PORT"

# Keep the key out of the target environment and command string. The writer is
# short-lived; the parent clears its copy before replacing itself with su.
exec {encryption_key_fd}< <(printf '%s\n' "$encryption_key_value")
encryption_key_value=""
unset encryption_key_present
exec su "${su_args[@]}" - "$USERNAME" -c \
  'exec /opt/java/openjdk/bin/java -XX:+DisableAttachMechanism --enable-native-access=ALL-UNNAMED -Dserver.port="$1" -jar /opt/jupiter.jar' \
  bash "$PORT" <&${encryption_key_fd}-
