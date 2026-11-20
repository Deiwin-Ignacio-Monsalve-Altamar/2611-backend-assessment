package com.meridian.payments.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Normalizes payer-entered policy references into Meridian's uppercase canonical format:
 * POL-<COUNTRY>-<9 digits> (e.g., POL-CO-000187342).
 */
@Component
public class PolicyNumberNormalizer {

    private static final Pattern STRICT_PATTERN = Pattern.compile("^POL-(MX|CO|CL)-(\\d{9})$");
    private static final Pattern LENIENT_PATTERN = Pattern.compile("^(?:POL[-\\s_]?(MX|CO|CL)?[-\\s_]?)?(\\d{1,9})$");

    public String normalize(String rawReference, String expectedCountry) {
        if (rawReference == null || rawReference.isBlank()) {
            throw new IllegalArgumentException("Policy reference cannot be blank");
        }

        String cleaned = rawReference.trim().toUpperCase();

        // 1. Fast path: already in canonical POL-<CC>-<9 digits> format
        Matcher strict = STRICT_PATTERN.matcher(cleaned);
        if (strict.matches()) {
            if (!strict.group(1).equals(expectedCountry)) {
                throw new IllegalArgumentException("Policy country prefix does not match provider country");
            }
            return cleaned;
        }

        // 2. Lenient path: pad short numeric references or fix lowercase/missing prefix
        Matcher lenient = LENIENT_PATTERN.matcher(cleaned);
        if (lenient.matches()) {
            String parsedCountry = lenient.group(1);
            if (parsedCountry != null && !parsedCountry.equals(expectedCountry)) {
                throw new IllegalArgumentException("Policy country prefix does not match provider country");
            }
            long sequence = Long.parseLong(lenient.group(2));
            if (sequence <= 0) {
                throw new IllegalArgumentException("Policy sequence must be positive");
            }
            return "POL-" + expectedCountry + "-" + String.format("%09d", sequence);
        }

        throw new IllegalArgumentException("Unrecognizable policy reference format: " + rawReference);
    }
}
