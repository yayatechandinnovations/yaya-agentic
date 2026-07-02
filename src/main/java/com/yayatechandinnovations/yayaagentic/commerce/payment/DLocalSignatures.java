package com.yayatechandinnovations.yayaagentic.commerce.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * dLocal {@code V2-HMAC-SHA256} signing — shared by the outbound checkout call
 * and inbound webhook verification. Signature = HMAC-SHA256(secretKey,
 * {@code xLogin + xDate + body}), hex-encoded. See {@code ecommerce-chat-design.md} §11.
 */
public final class DLocalSignatures {

    private DLocalSignatures() {}

    public static String hmacHex(String message, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new PaymentException("failed to compute dLocal HMAC", e);
        }
    }
}
