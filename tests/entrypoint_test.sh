#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/home" "$tmp/etc"
printf 'PATH\0' > "$tmp/etc/image-env-names"
cat > "$tmp/init.sh" <<'EOF'
#!/bin/bash
[[ -z ${JUPITER_ENCRYPTION_KEY+x} ]]
EOF
cat > "$tmp/init-user.sh" <<'EOF'
#!/bin/bash
[[ -z ${JUPITER_ENCRYPTION_KEY+x} ]]
EOF

for command in groupadd useradd chmod chown; do
  cat > "$tmp/bin/$command" <<'EOF'
#!/bin/sh
exit 0
EOF
done
cat > "$tmp/bin/su" <<'EOF'
#!/bin/bash
set -euo pipefail
[[ -z ${JUPITER_ENCRYPTION_KEY+x} ]]
if [[ "$*" == *init-user.sh* ]]; then
  : > "$STUB_INIT"
  exit 0
fi

# Parse the production su invocation, then run its -c command like su would.
# Rewriting only Java's absolute path keeps this a command-semantics test.
command=''
shell0=''
port=''
while (($#)); do
  case $1 in
    -w) shift 2 ;;
    -) shift 2 ;;
    -c) command=$2; shift 2; shell0=$1; port=$2; break ;;
    *) shift ;;
  esac
done
[[ -n "$command" && -n "$shell0" && -n "$port" ]]
printf '%s\n' "$*" > "$STUB_SU_ARGS"
printf '%s\n' "$command" > "$STUB_ARGS"
( env -0 | tr '\0' '\n' | sed 's/=.*//' ) > "$STUB_ENV_NAMES"
! grep -q -- 'JUPITER_ENCRYPTION_KEY' "$STUB_ARGS"
! grep -qx 'JUPITER_ENCRYPTION_KEY' "$STUB_ENV_NAMES"
grep -qx 'JUPITER_HTTP_AUTH_USERNAME' "$STUB_ENV_NAMES"
grep -qx 'JUPITER_HTTP_AUTH_PASSWORD' "$STUB_ENV_NAMES"
command=${command//\/opt\/java\/openjdk\/bin\/java/$STUB_JAVA}
exec bash -c "$command" "$shell0" "$port"
EOF
cat > "$tmp/fake-java" <<'EOF'
#!/bin/bash
set -euo pipefail
( env -0 | tr '\0' '\n' | sed 's/=.*//' ) > "$STUB_JAVA_ENV_NAMES"
printf '%s\n' "$*" > "$STUB_JAVA_ARGS"
cat > "$STUB_STDIN"
# cat returning proves the one-shot writer closed the stream with EOF.
[[ -z ${JUPITER_ENCRYPTION_KEY+x} ]]
! grep -q -- "$STUB_KEY" "$STUB_JAVA_ARGS" "$STUB_JAVA_ENV_NAMES"
exit "${STUB_STATUS:-0}"
EOF
chmod +x "$tmp/bin/su" "$tmp/fake-java"
chmod +x "$tmp/bin"/*

key='ZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmY='
PATH="$tmp/bin:$PATH" \
STUB_STDIN="$tmp/stdin" STUB_JAVA="$tmp/fake-java" STUB_KEY="$key" \
STUB_JAVA_ENV_NAMES="$tmp/java-env-names" STUB_JAVA_ARGS="$tmp/java-args" STUB_SU_ARGS="$tmp/su-args" \
STUB_ENV_NAMES="$tmp/env-names" STUB_ARGS="$tmp/args" STUB_INIT="$tmp/init" \
STUB_STATUS=0 JUPITER_HTTP_AUTH_USERNAME=test-user JUPITER_HTTP_AUTH_PASSWORD=test-password \
INIT_SCRIPT="$tmp/init.sh" INIT_USER_SCRIPT="$tmp/init-user.sh" JUPITER_ENCRYPTION_KEY="$key" USERNAME=jupiter PORT=8123 WITH_UID=1000 WITH_GID=1000 \
  bash <(sed "s#/etc/jupiter-image-env-names#$tmp/etc/image-env-names#" "$root/entrypoint.sh")

[[ $(cat "$tmp/stdin") == "$key" ]]
[[ ! -s "$tmp/key-env" ]]
! grep -q -- "$key" "$tmp/args" "$tmp/su-args" "$tmp/env-names"
[[ -f "$tmp/init" ]]

if output=$(PATH="$tmp/bin:$PATH" STUB_STDIN="$tmp/missing-stdin" STUB_ENV_NAMES="$tmp/missing-env" STUB_ARGS="$tmp/missing-args" STUB_INIT="$tmp/missing-init" \
  INIT_SCRIPT="$tmp/init.sh" INIT_USER_SCRIPT="$tmp/init-user.sh" env -u JUPITER_ENCRYPTION_KEY \
  bash <(sed "s#/etc/jupiter-image-env-names#$tmp/etc/image-env-names#" "$root/entrypoint.sh") 2>&1); then
  echo 'missing key unexpectedly succeeded' >&2
  exit 1
fi
[[ "$output" == *'JUPITER_ENCRYPTION_KEY is required'* ]]

if PATH="$tmp/bin:$PATH" STUB_STDIN="$tmp/blank-stdin" STUB_ENV_NAMES="$tmp/blank-env" STUB_ARGS="$tmp/blank-args" STUB_INIT="$tmp/blank-init" \
  INIT_SCRIPT="$tmp/init.sh" INIT_USER_SCRIPT="$tmp/init-user.sh" JUPITER_ENCRYPTION_KEY='' \
  bash <(sed "s#/etc/jupiter-image-env-names#$tmp/etc/image-env-names#" "$root/entrypoint.sh") >/dev/null 2>&1; then
  echo 'blank key unexpectedly succeeded' >&2
  exit 1
fi

set +e
PATH="$tmp/bin:$PATH" STUB_STDIN="$tmp/status-stdin" STUB_JAVA="$tmp/fake-java" STUB_KEY="$key" \
  STUB_JAVA_ENV_NAMES="$tmp/status-java-env" STUB_JAVA_ARGS="$tmp/status-java-args" STUB_SU_ARGS="$tmp/status-su-args" \
  STUB_ENV_NAMES="$tmp/status-env" STUB_ARGS="$tmp/status-args" STUB_INIT="$tmp/status-init" STUB_STATUS=37 \
  INIT_SCRIPT="$tmp/init.sh" INIT_USER_SCRIPT="$tmp/init-user.sh" JUPITER_ENCRYPTION_KEY="$key" \
  JUPITER_HTTP_AUTH_USERNAME=test-user JUPITER_HTTP_AUTH_PASSWORD=test-password USERNAME=jupiter PORT=8123 \
  bash <(sed "s#/etc/jupiter-image-env-names#$tmp/etc/image-env-names#" "$root/entrypoint.sh") >/dev/null 2>&1
status=$?
set -e
[[ $status -eq 37 ]]
printf 'entrypoint stdin hardening: ok\n'
