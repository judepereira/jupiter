#!/bin/bash
set -euo pipefail

USERNAME=${USERNAME:-jupiter}
PORT=${PORT:-7272}
WITH_UID=${WITH_UID:-1000}
WITH_GID=${WITH_GID:-1000}

groupadd -g $WITH_GID $USERNAME
useradd -u $WITH_UID -g $WITH_GID -m -s /bin/bash $USERNAME

if [[ -f /init.sh ]]; then
  echo "Init script found. Running as root..."
  bash /init.sh
fi

chmod 644 /opt/jupiter.jar

echo "Starting Jupiter as $USERNAME on port $PORT"

su - $USERNAME -c "/opt/java/openjdk/bin/java -jar -Dserver.port=$PORT /opt/jupiter.jar"
