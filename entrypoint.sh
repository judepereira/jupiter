#!/bin/bash
set -euo pipefail

USERNAME=${USERNAME:-jupiter}
PORT=${PORT:-7272}

WITH_UID=${WITH_UID:-1000}
WITH_GID=${WITH_GID:-1000}
INIT_SCRIPT=${INIT_SCRIPT:-/init.sh}
INIT_USER_SCRIPT=${INIT_USER_SCRIPT:-/init-user.sh}
GROUPADD_COMMAND=${GROUPADD_COMMAND:-groupadd}
USERADD_COMMAND=${USERADD_COMMAND:-useradd}
CHMOD_COMMAND=${CHMOD_COMMAND:-chmod}
CHOWN_COMMAND=${CHOWN_COMMAND:-chown}
SU_COMMAND=${SU_COMMAND:-su}
ENV_COMMAND=${ENV_COMMAND:-env}
IMAGE_ENV_NAMES_FILE=${IMAGE_ENV_NAMES_FILE:-/etc/jupiter-image-env-names}
JAR_PATH=${JAR_PATH:-/opt/jupiter.jar}
JAVA_PATH=${JAVA_PATH:-/opt/java/openjdk/bin/java}

"$GROUPADD_COMMAND" -g "$WITH_GID" "$USERNAME" || echo "Group exists"
"$USERADD_COMMAND" -u "$WITH_UID" -g "$WITH_GID" -m -s /bin/bash "$USERNAME" || echo "User exists"
"$CHMOD_COMMAND" 644 "$JAR_PATH"
"$CHOWN_COMMAND" -R "${USERNAME}:${USERNAME}" "/home/$USERNAME/.jupiter"

# The key intentionally remains available to both initialization scripts.
if [[ ${JUPITER_ENCRYPTION_KEY+x} != x || -z $JUPITER_ENCRYPTION_KEY ]]; then
  echo "JUPITER_ENCRYPTION_KEY is required and must not be blank" >&2
  exit 1
fi
declare -A image_env_names=()
while IFS= read -r -d '' env_name; do image_env_names["$env_name"]=1; done < "$IMAGE_ENV_NAMES_FILE"

if [[ -f "$INIT_SCRIPT" ]]; then echo "Init script found. Running as root..."; bash "$INIT_SCRIPT"; fi

# Login su resets root identity variables while retaining runtime variables for user init.
declare -A launcher_env_names=(
  [USERNAME]=1 [PORT]=1 [WITH_UID]=1 [WITH_GID]=1
  [INIT_SCRIPT]=1 [INIT_USER_SCRIPT]=1
  [GROUPADD_COMMAND]=1 [USERADD_COMMAND]=1 [CHMOD_COMMAND]=1 [CHOWN_COMMAND]=1
  [SU_COMMAND]=1 [ENV_COMMAND]=1 [IMAGE_ENV_NAMES_FILE]=1 [JAR_PATH]=1 [JAVA_PATH]=1
)
is_launcher_name() { [[ -n ${launcher_env_names[$1]+present} ]]; }

declare -a runtime_env_names=()
declare -A runtime_env_seen=()
while IFS= read -r -d '' env_entry; do
  env_name=${env_entry%%=*}
  if [[ "$env_name" != HOME && "$env_name" != USER && "$env_name" != LOGNAME ]] && ! is_launcher_name "$env_name" \
      && ( [[ "$env_name" == JUPITER_ENCRYPTION_KEY ]] || [[ "$env_name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ && -z ${image_env_names[$env_name]+present} ]] ); then
    if [[ -z ${runtime_env_seen[$env_name]+present} ]]; then
      runtime_env_names+=("$env_name")
      runtime_env_seen["$env_name"]=1
    fi
  fi
done < <("$ENV_COMMAND" -0)
runtime_env_list=""
if ((${#runtime_env_names[@]})); then
  runtime_env_list=$(IFS=,; printf '%s' "${runtime_env_names[*]}")
fi
user_init_su_args=()
[[ -n "$runtime_env_list" ]] && user_init_su_args=(-w "$runtime_env_list")

if [[ -f "$INIT_USER_SCRIPT" ]]; then echo "User init script found. Running as $USERNAME..."; "$SU_COMMAND" "${user_init_su_args[@]}" - "$USERNAME" -c "bash \"$INIT_USER_SCRIPT\""; fi

declare -a captured_names=()
declare -A captured_seen=()
declare -A captured_values=()
while IFS= read -r -d '' env_entry; do
  env_name=${env_entry%%=*}
  if ! is_launcher_name "$env_name" && ( [[ "$env_name" == JUPITER_ENCRYPTION_KEY ]] || [[ "$env_name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ && -z ${image_env_names[$env_name]+present} ]] ); then
    if [[ -z ${captured_seen[$env_name]+present} ]]; then captured_names+=("$env_name"); captured_seen["$env_name"]=1; fi
    captured_values["$env_name"]=${env_entry#*=}
  fi
done < <("$ENV_COMMAND" -0)

# `su -` starts with a clean environment. Preserve only variables baked into
# the image; captured runtime variables are delivered through stdin and never
# forwarded to the JVM environment.
image_env_list=""
if ((${#image_env_names[@]})); then
  image_env_list=$(IFS=,; printf '%s' "${!image_env_names[*]}")
fi
su_args=(); [[ -n "$image_env_list" ]] && su_args=(-w "$image_env_list")

envelope() {
  for env_name in "${captured_names[@]}"; do
    printf '%s=%s\n' "$env_name" "${captured_values[$env_name]}"
  done
}
launch_username=$USERNAME
launch_port=$PORT
launch_java=$JAVA_PATH
launch_jar=$JAR_PATH
for env_name in "${captured_names[@]}"; do unset "$env_name"; done

exec {bootstrap_fd}< <(envelope)
# su preserves UID/GID behavior while the command removes all captured values before Java starts.
exec "$SU_COMMAND" "${su_args[@]}" - "$launch_username" -c \
  'for n in $1; do unset "$n"; done; exec "$3" -XX:+DisableAttachMechanism --enable-native-access=ALL-UNNAMED -Dserver.port="$2" -jar "$4"' \
  bash "${captured_names[*]}" "$launch_port" "$launch_java" "$launch_jar" <&${bootstrap_fd}-
