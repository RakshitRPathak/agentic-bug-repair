package dev.rakshit.repair.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Verifies GitHub's {@code X-Hub-Signature-256} header: {@code sha256=} + HMAC-SHA256 of the raw body under the shared secret. */
public final class Signature {
    private Signature() {}

    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison, so response timing leaks nothing about how many leading characters matched. */
    public static boolean valid(String secret, byte[] body, String header) {
        if (secret == null || secret.isEmpty() || header == null) return false;
        byte[] expected = sign(secret, body).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, header.trim().getBytes(StandardCharsets.UTF_8));
    }
}
