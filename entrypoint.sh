#!/bin/bash
set -euo pipefail

USERNAME=${USERNAME:-jupiter}
PORT=${PORT:-7272}
WITH_UID=${WITH_UID:-1000}
WITH_GID=${WITH_GID:-1000}

# su - resets the environment. Preserve only variables added after the image was built.
declare -A image_env_names=()
while IFS= read -r -d '' env_name; do
  image_env_names["$env_name"]=1
done < /etc/jupiter-image-env-names

declare -a forwarded_env_names=()
declare -A forwarded_env_names_seen=()
while IFS= read -r -d '' env_entry; do
  env_name=${env_entry%%=*}
  if [[ "$env_name" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]] && [[ -z ${image_env_names[$env_name]+present} ]] && [[ -z ${forwarded_env_names_seen[$env_name]+present} ]]; then
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

if [[ -f /init.sh ]]; then
  echo "Init script found. Running as root..."
  bash /init.sh
fi

if [[ -f /init-user.sh ]]; then
  echo "User init script found. Running as $USERNAME..."
  su - "$USERNAME" -c "bash /init-user.sh"
fi

echo "Starting Jupiter as $USERNAME on port $PORT"

su "${su_args[@]}" - "$USERNAME" -c \
  '/opt/java/openjdk/bin/java -jar -Dserver.port="$1" /opt/jupiter.jar' -- "$PORT"
