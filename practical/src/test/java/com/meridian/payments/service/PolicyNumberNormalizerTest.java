package com.meridian.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PolicyNumberNormalizerTest {

    private final PolicyNumberNormalizer normalizer = new PolicyNumberNormalizer();

    @Test
    void shouldNormalizeColombianAndMexicanPolicyReferencesWithZeroPadding() {
        assertEquals("POL-CO-000088412", normalizer.normalize("  pol co 88412 ", "CO"));
        assertEquals("POL-MX-000030219", normalizer.normalize("POL_MX_30219", "MX"));
        assertEquals("POL-CO-000012345", normalizer.normalize("POL 12345", "CO"));
    }

    @Test
    void shouldRejectBlankOrInvalidReferences() {
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize("   ", "CO"));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize("INVALID-REF", "MX"));
    }
}
