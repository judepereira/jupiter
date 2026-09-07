package com.judepereira.jupiter.security;

import com.judepereira.jupiter.testsupport.TestEncryptionSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextEncryptorTests {
    private static final EncryptionKey KEY = EncryptionKey.fromBase64(TestEncryptionSupport.KEY);

    @Test void encryptsWithRandomNonceAndRoundTrips() {
        var crypto = new TextEncryptor(KEY);
        var first = crypto.encrypt("secret", "projects.name");
        var second = crypto.encrypt("secret", "projects.name");
        assertNotEquals(first, second);
        assertEquals("secret", crypto.decrypt(first, "projects.name"));
        assertThrows(TextEncryptor.EncryptionException.class, () -> crypto.decrypt(first, "projects.other"));
    }

    @Test void rejectsTamperingAndInvalidKeys() {
        var crypto = new TextEncryptor(KEY);
        var encrypted = crypto.encrypt("secret", "x.y");
        var tampered = encrypted.substring(0, encrypted.length() - 1) + (encrypted.endsWith("A") ? "B" : "A");
        assertThrows(TextEncryptor.EncryptionException.class, () -> crypto.decrypt(tampered, "x.y"));
        assertThrows(IllegalStateException.class, () -> EncryptionKey.fromBase64("not-base64"));
        assertThrows(IllegalArgumentException.class, () -> new EncryptionKey(new byte[31]));
    }

    @Test void blindIndexesAreDeterministicAndDomainSeparated() {
        var crypto = new TextEncryptor(KEY);
        assertEquals(crypto.blindIndex("secret", "x"), crypto.blindIndex("secret", "x"));
        assertNotEquals(crypto.blindIndex("secret", "x"), crypto.blindIndex("secret", "y"));
    }

    @Test void nullIsPreserved() {
        var crypto = new TextEncryptor(KEY);
        assertNull(crypto.encrypt(null, "x.y"));
        assertNull(crypto.decrypt(null, "x.y"));
    }
}
