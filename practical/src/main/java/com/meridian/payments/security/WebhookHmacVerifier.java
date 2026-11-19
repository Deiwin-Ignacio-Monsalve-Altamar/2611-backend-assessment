package com.meridian.payments.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies HMAC-SHA256 webhook signatures using Spring's HttpHeaders and ResponseStatusException.
 */
@Component
public class WebhookHmacVerifier {

    private final String andinaSecret;
    private final String quetzalSecret;
    private final long maxSkewSeconds;

    public WebhookHmacVerifier(
            @Value("${meridian.webhooks.secrets.andina}") String andinaSecret,
            @Value("${meridian.webhooks.secrets.quetzal}") String quetzalSecret,
            @Value("${meridian.webhooks.max-timestamp-skew-seconds:300}") long maxSkewSeconds) {
        this.andinaSecret = andinaSecret;
        this.quetzalSecret = quetzalSecret;
        this.maxSkewSeconds = maxSkewSeconds;
    }

    public void verify(String provider, String rawBody, HttpHeaders headers) {
        switch (provider.trim().toLowerCase()) {
            case "andina" -> verifyAndina(rawBody, headers);
            case "quetzal" -> verifyQuetzal(rawBody, headers);
            default -> { /* Let controller validate unknown provider */ }
        }
    }

    private void verifyAndina(String rawBody, HttpHeaders headers) {
        String timestamp = headers.getFirst("X-Andina-Timestamp");
        String signature = headers.getFirst("X-Andina-Signature");
        if (timestamp == null || signature == null) {
            throw unauthorized("Missing Andina signature headers");
        }

        validateTimestamp(timestamp);
        byte[] expected = hmacSha256(andinaSecret, timestamp.trim() + "." + rawBody);
        byte[] provided = decodeBase64(signature.trim());

        if (!MessageDigest.isEqual(expected, provided)) {
            throw unauthorized("Invalid Andina HMAC signature");
        }
    }

    private void verifyQuetzal(String rawBody, HttpHeaders headers) {
        String header = headers.getFirst("X-Quetzal-Signature");
        if (header == null) {
            throw unauthorized("Missing X-Quetzal-Signature header");
        }

        String timestamp = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && "t".equals(kv[0])) timestamp = kv[1].trim();
            if (kv.length == 2 && "v1".equals(kv[0])) v1 = kv[1].trim();
        }
        if (timestamp == null || v1 == null) {
            throw unauthorized("Malformed X-Quetzal-Signature header");
        }

        validateTimestamp(timestamp);
        byte[] expected = hmacSha256(quetzalSecret, timestamp + "." + rawBody);
        byte[] provided = decodeHex(v1);

        if (!MessageDigest.isEqual(expected, provided)) {
            throw unauthorized("Invalid Quetzal HMAC signature");
        }
    }

    private void validateTimestamp(String rawTimestamp) {
        try {
            String value = rawTimestamp.trim();
            Instant ts = value.chars().allMatch(Character::isDigit)
                    ? Instant.ofEpochSecond(Long.parseLong(value))
                    : OffsetDateTime.parse(value).toInstant();
            if (Math.abs(Instant.now().getEpochSecond() - ts.getEpochSecond()) > maxSkewSeconds) {
                throw unauthorized("Webhook timestamp outside allowed window");
            }
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unauthorized("Invalid webhook timestamp");
        }
    }

    private byte[] hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private byte[] decodeBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw unauthorized("Malformed Base64 signature");
        }
    }

    private byte[] decodeHex(String value) {
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException e) {
            throw unauthorized("Malformed hex signature");
        }
    }

    private ResponseStatusException unauthorized(String reason) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
    }
}
