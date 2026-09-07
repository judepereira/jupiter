package com.judepereira.jupiter.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class TextEncryptor {
    private static final String PREFIX = "JUPITER-ENCRYPTED-V1-AES-256-GCM:";
    private static final int NONCE_SIZE = 12;
    private static final int TAG_SIZE_BITS = 128;
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String BLIND_INDEX_PREFIX = "jupiter-blind-index:v1:";
    private static final byte[] AES_INFO = "Jupiter encryption v1 AES-256 key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HMAC_INFO = "Jupiter encryption v1 HMAC-SHA-256 key".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec aesKey;
    private final SecretKeySpec hmacKey;
    private final java.security.SecureRandom random = new java.security.SecureRandom();

    public TextEncryptor(EncryptionKey key) {
        byte[] master = key.bytes();
        try {
            aesKey = new SecretKeySpec(hkdf(master, AES_INFO, 32), "AES");
            hmacKey = new SecretKeySpec(hkdf(master, HMAC_INFO, 32), "HmacSHA256");
        } finally {
            Arrays.fill(master, (byte) 0);
        }
    }

    public String encrypt(String plaintext, String aad) {
        if (plaintext == null) return null;
        try {
            byte[] nonce = new byte[NONCE_SIZE];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_SIZE_BITS, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException exception) {
            throw new EncryptionException("Encryption failed", exception);
        }
    }

    public String decrypt(String value, String aad) {
        if (value == null) return null;
        if (!value.startsWith(PREFIX)) throw new EncryptionException("Malformed encrypted value");
        try {
            byte[] packed = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (packed.length < NONCE_SIZE + 16) throw new EncryptionException("Malformed encrypted value");
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_SIZE_BITS, packed, 0, NONCE_SIZE));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(packed, NONCE_SIZE, packed.length - NONCE_SIZE), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new EncryptionException("Unable to decrypt value", exception);
        }
    }

    public String blindIndex(String value, String domain) {
        if (value == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            mac.update((BLIND_INDEX_PREFIX + domain + ":").getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new EncryptionException("Blind index failed", exception);
        }
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private static byte[] hkdf(byte[] ikm, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] salt = new byte[mac.getMacLength()];
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] output = new byte[length];
            byte[] previous = new byte[0];
            int offset = 0;
            for (int counter = 1; offset < length; counter++) {
                mac.reset();
                mac.update(previous);
                mac.update(info);
                mac.update((byte) counter);
                previous = mac.doFinal();
                int count = Math.min(previous.length, length - offset);
                System.arraycopy(previous, 0, output, offset, count);
                offset += count;
            }
            Arrays.fill(prk, (byte) 0);
            Arrays.fill(previous, (byte) 0);
            return output;
        } catch (GeneralSecurityException exception) {
            throw new EncryptionException("Key derivation failed", exception);
        }
    }

    public static class EncryptionException extends RuntimeException {
        public EncryptionException(String message) { super(message); }
        public EncryptionException(String message, Throwable cause) { super(message, cause); }
    }
}
