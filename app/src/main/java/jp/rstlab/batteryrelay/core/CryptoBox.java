package jp.rstlab.batteryrelay.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Small audited-primitive wrapper: P-256 ECDH, HKDF-SHA256, and AES-256-GCM. */
public final class CryptoBox {
    public static final int AES_KEY_BYTES = 32;
    public static final int GCM_NONCE_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoBox() {}

    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return generator.generateKeyPair();
    }

    public static PublicKey decodePublicKey(byte[] encoded) throws GeneralSecurityException {
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
        if (!(key instanceof ECPublicKey)
                || ((ECPublicKey) key).getParams().getCurve().getField().getFieldSize() != 256) {
            throw new GeneralSecurityException("P-256 public key required");
        }
        return key;
    }

    public static byte[] derivePairKey(
            PrivateKey ownPrivate,
            PublicKey peerPublic,
            byte[] salt,
            String pairingCode,
            String shareId
    ) throws GeneralSecurityException {
        String info = "BatteryRelay/v1/pair/" + shareId + "/" + pairingCode;
        byte[] secret = sharedSecret(ownPrivate, peerPublic);
        try {
            return hkdfSha256(secret, salt,
                    info.getBytes(StandardCharsets.UTF_8), AES_KEY_BYTES);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    public static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static int randomSixDigitCode() {
        return RANDOM.nextInt(1_000_000);
    }

    /** 128-bit human-transferable secret.  Crockford-like alphabet avoids 0/O and 1/I. */
    public static String randomPairingSecret() {
        final char[] alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
        byte[] entropy = randomBytes(16);
        StringBuilder out = new StringBuilder(26);
        int buffer = 0;
        int bits = 0;
        for (byte value : entropy) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(alphabet[(buffer >>> bits) & 31]);
            }
        }
        if (bits > 0) out.append(alphabet[(buffer << (5 - bits)) & 31]);
        Arrays.fill(entropy, (byte) 0);
        return out.toString();
    }

    public static byte[] encrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad)
            throws GeneralSecurityException {
        requireLengths(key, nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        if (aad != null) cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    public static byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad)
            throws GeneralSecurityException {
        requireLengths(key, nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        if (aad != null) cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext);
    }

    public static byte[] aad(String operation, String id) {
        return ("BatteryRelay/v1/" + operation + "/" + id).getBytes(StandardCharsets.UTF_8);
    }

    public static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] unb64(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static byte[] sharedSecret(PrivateKey ownPrivate, PublicKey peerPublic)
            throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(ownPrivate);
        agreement.doPhase(peerPublic, true);
        return agreement.generateSecret();
    }

    static byte[] hkdfSha256(byte[] inputKey, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        byte[] effectiveSalt = salt == null || salt.length == 0 ? new byte[mac.getMacLength()] : salt;
        mac.init(new SecretKeySpec(effectiveSalt, "HmacSHA256"));
        byte[] pseudoRandomKey = mac.doFinal(inputKey);

        byte[] output = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        int block = 1;
        while (offset < length) {
            mac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));
            mac.update(previous);
            if (info != null) mac.update(info);
            mac.update((byte) block);
            previous = mac.doFinal();
            int count = Math.min(previous.length, length - offset);
            System.arraycopy(previous, 0, output, offset, count);
            offset += count;
            block++;
        }
        Arrays.fill(pseudoRandomKey, (byte) 0);
        return output;
    }

    private static void requireLengths(byte[] key, byte[] nonce) {
        if (key == null || key.length != AES_KEY_BYTES) {
            throw new IllegalArgumentException("AES-256 key required");
        }
        if (nonce == null || nonce.length != GCM_NONCE_BYTES) {
            throw new IllegalArgumentException("12-byte GCM nonce required");
        }
    }
}
