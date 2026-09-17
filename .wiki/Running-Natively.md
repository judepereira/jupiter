# Running Natively

If you’d rather run the JAR directly, Jupiter needs Java 25, Git, and ripgrep. It checks for `rg` during startup.

## Build

```bash
./mvnw package
```

## Start Jupiter

Generate an encryption key once and keep it for later starts. The following launcher supplies it without placing the key
in the Java command line or environment:

```bash
entered_key="$(openssl rand -base64 32)"
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

The Base64 key must decode to exactly 32 bytes. Keep using the same key with an existing database.

For an existing installation, replace the first line with an interactive read instead of generating a new key:

```bash
read -r -s -p 'Encryption key: ' entered_key; printf '\n'
```

The JVM flags shown above are part of Jupiter's recommended native launch configuration. For key-management details, see
[Encryption and Key Management](Encryption-and-Key-Management).
