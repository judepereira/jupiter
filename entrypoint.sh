#!/bin/bash
set -euo pipefail

USERNAME=${USERNAME:-jupiter}
PORT=${PORT:-7272}
WITH_UID=${WITH_UID:-1000}
WITH_GID=${WITH_GID:-1000}

groupadd -g $WITH_GID $USERNAME || echo "Group exists"
useradd -u $WITH_UID -g $WITH_GID -m -s /bin/bash $USERNAME || echo "User exists"

chmod 644 /opt/jupiter.jar
chown -R $USERNAME:$USERNAME /home/$USERNAME/.jupiter

if [[ -f /init.sh ]]; then
  echo "Init script found. Running as root..."
  bash /init.sh
fi

if [[ -f /init-user.sh ]]; then
  echo "User init script found. Running as $USERNAME..."
  su - $USERNAME -c "bash /init-user.sh"
fi

echo "Starting Jupiter as $USERNAME on port $PORT"

su -w JUPITER_HTTP_AUTH_PASSWORD,JUPITER_HTTP_AUTH_USERNAME - $USERNAME -c \
  "/opt/java/openjdk/bin/java -jar -Dserver.port=$PORT /opt/jupiter.jar"
