package com.judepereira.jupiter.testsupport;

import com.judepereira.jupiter.security.EncryptionKey;
import com.judepereira.jupiter.security.TextEncryptor;

public final class TestEncryptionSupport {
    public static final String KEY = "K9fFdgQTSimRghWh99cnOZ9UoPEzz97NKf16loLUKYw=";
    private static final TextEncryptor ENCRYPTOR = new TextEncryptor(EncryptionKey.fromBase64(KEY));

    private TestEncryptionSupport() {
    }

    public static EncryptionKey encryptionKey() {
        return EncryptionKey.fromBase64(KEY);
    }

    public static TextEncryptor encryptor() {
        return ENCRYPTOR;
    }

    public static String encrypt(String table, String column, String value) {
        return value == null ? null : ENCRYPTOR.encrypt(value, table + "." + column);
    }
}
