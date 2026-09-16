# Running Natively

If you’d rather run the JAR directly, Jupiter needs Java 25, Git, and ripgrep. It checks for `rg` during startup.

## Build

```bash
./mvnw package
```

## Start Jupiter

The normal bootstrap reads a versioned NUL-delimited configuration envelope once from stdin until EOF:

```bash
entered_key="$(openssl rand -base64 32)"
bootstrap_envelope() {
  printf 'JUPITER_BOOTSTRAP_V1\0'
  printf 'JUPITER_ENCRYPTION_KEY\0%s\0' "$entered_key"
  # Add runtime variables as NAME\0VALUE\0 records when needed.
  # printf 'NAME\0%s\0' "$VALUE"
}
exec {bootstrap_fd}< <(bootstrap_envelope)
unset entered_key

env -u JUPITER_ENCRYPTION_KEY java \
  -XX:+DisableAttachMechanism \
  --enable-native-access=ALL-UNNAMED \
  -Dserver.port=7272 \
  -jar target/jupiter-0.0.1-SNAPSHOT.jar <&${bootstrap_fd}-
```

The Base64 value must decode to exactly 32 bytes. The anonymous file descriptor carries the versioned NUL-delimited
`JUPITER_BOOTSTRAP_V1\0NAME\0VALUE\0` envelope, not a raw key or newline-delimited input. Add desired runtime variables
as additional records; those variables are available to Spring without entering the JVM `/proc` environment. Never put
the key in arguments or a temporary file.

The command looks a little more elaborate than `java -jar`, but there’s a reason for it: the key never needs to live in
the target JVM environment or command line.

`--enable-native-access=ALL-UNNAMED` allows Jupiter’s Linux process-hardening call; `-XX:+DisableAttachMechanism`
disables JVM attach.

## Enter the key interactively

Use the same envelope when entering an existing key interactively:

```bash
read -r -s -p 'Encryption key: ' entered_key; printf '\n'
bootstrap_envelope() {
  printf 'JUPITER_BOOTSTRAP_V1\0'
  printf 'JUPITER_ENCRYPTION_KEY\0%s\0' "$entered_key"
}
exec {bootstrap_fd}< <(bootstrap_envelope)
unset entered_key

env -u JUPITER_ENCRYPTION_KEY java \
  -XX:+DisableAttachMechanism \
  --enable-native-access=ALL-UNNAMED \
  -Dserver.port=7272 \
  -jar target/jupiter-0.0.1-SNAPSHOT.jar <&${bootstrap_fd}-
```

## Why not `spring-boot:run`?

For production-like use, the packaged JAR is recommended. It makes stdin ownership, the bootstrap envelope, and the JVM
hardening flags explicit instead of relying on Maven to mediate them correctly.
