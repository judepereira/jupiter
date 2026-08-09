#!/bin/bash
set -euo pipefail

USERNAME=${USERNAME:-jupiter}
PORT=${PORT:-7272}

useradd --create-home --home-dir /home/${USERNAME} --shell /bin/bash ${USERNAME}

if [[ -f /init.sh ]]; then
  echo "Init script found. Running as root..."
  bash /init.sh
fi

chmod 644 /opt/jupiter.jar

echo "Starting Jupiter as $USERNAME on port $PORT"

su - $USERNAME -c "/opt/java/openjdk/bin/java -jar -Dserver.port=$PORT /opt/jupiter.jar"
