# Running Natively

If you’d rather run the JAR directly, Jupiter needs Java 25, Git, and ripgrep. It checks for `rg` during startup.

## Build

```bash
./mvnw package
```

## Start Jupiter

The normal bootstrap reads the database encryption key once from stdin until EOF:

```bash
entered_key="$(openssl rand -base64 32)"
exec {key_fd}< <(printf '%s\n' "$entered_key")
unset entered_key

env -u JUPITER_ENCRYPTION_KEY java \
  -XX:+DisableAttachMechanism \
  --enable-native-access=ALL-UNNAMED \
  -Dserver.port=7272 \
  -jar target/jupiter-0.0.1-SNAPSHOT.jar <&${key_fd}-
```

The Base64 value must decode to exactly 32 bytes.

The command looks a little more elaborate than `java -jar`, but there’s a reason for it: the key never needs to live in the target JVM environment or command line.

`--enable-native-access=ALL-UNNAMED` allows Jupiter’s Linux process-hardening call; `-XX:+DisableAttachMechanism` disables JVM attach.

## Enter the key interactively

```bash
read -r -s -p 'Encryption key: ' entered_key; printf '\n'
exec {key_fd}< <(printf '%s\n' "$entered_key")
unset entered_key

env -u JUPITER_ENCRYPTION_KEY java \
  -XX:+DisableAttachMechanism \
  --enable-native-access=ALL-UNNAMED \
  -Dserver.port=7272 \
  -jar target/jupiter-0.0.1-SNAPSHOT.jar <&${key_fd}-
```

## Why not `spring-boot:run`?

For production-like use, the packaged JAR is recommended. It makes stdin ownership and the JVM hardening flags explicit instead of relying on Maven to mediate them correctly.
