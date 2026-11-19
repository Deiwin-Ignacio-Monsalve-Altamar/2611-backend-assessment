package com.meridian.payments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class WebhookIntakeIntegrationTest {

    private static final String ANDINA_SECRET = "local-andina-dev-secret";
    private static final String QUETZAL_SECRET = "local-quetzal-dev-secret";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldAcceptValidAndinaWebhookAndIgnoreDuplicates() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String payload = """
                {
                  "notificacion_id": "notif-test-001",
                  "transaccion": {
                    "id": "TRX-CO-1001",
                    "estado": "APROBADA",
                    "valor": 450000,
                    "moneda": "COP",
                    "referencia": "POL-CO-88412",
                    "fecha_transaccion": "2026-11-19T18:00:00-05:00"
                  }
                }
                """;
        String signature = signBase64(ANDINA_SECRET, timestamp + "." + payload);

        // First delivery -> 202 Accepted
        mockMvc.perform(post("/v1/webhooks/andina")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Andina-Timestamp", String.valueOf(timestamp))
                        .header("X-Andina-Signature", signature)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.paymentKey").value("ANDINA:TRX-CO-1001"));

        // Duplicate delivery -> 200 OK (DUPLICATE)
        mockMvc.perform(post("/v1/webhooks/andina")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Andina-Timestamp", String.valueOf(timestamp))
                        .header("X-Andina-Signature", signature)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
    }

    @Test
    void shouldIgnoreOutOfOrderStateRegression() throws Exception {
        long timestamp = Instant.now().getEpochSecond();

        // 1. Captured arrives first
        String capturedPayload = """
                {
                  "notificacion_id": "notif-order-2",
                  "transaccion": {
                    "id": "TRX-CO-2002",
                    "estado": "APROBADA",
                    "valor": 120000,
                    "moneda": "COP",
                    "referencia": "POL-CO-55110",
                    "fecha_transaccion": "2026-11-19T18:05:00-05:00"
                  }
                }
                """;
        mockMvc.perform(post("/v1/webhooks/andina")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Andina-Timestamp", String.valueOf(timestamp))
                        .header("X-Andina-Signature", signBase64(ANDINA_SECRET, timestamp + "." + capturedPayload))
                        .content(capturedPayload))
                .andExpect(status().isAccepted());

        // 2. Late Pending arrives afterward -> ignored with 200 OK
        String latePendingPayload = """
                {
                  "notificacion_id": "notif-order-1",
                  "transaccion": {
                    "id": "TRX-CO-2002",
                    "estado": "PENDIENTE",
                    "valor": 120000,
                    "moneda": "COP",
                    "referencia": "POL-CO-55110",
                    "fecha_transaccion": "2026-11-19T18:01:00-05:00"
                  }
                }
                """;
        mockMvc.perform(post("/v1/webhooks/andina")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Andina-Timestamp", String.valueOf(timestamp))
                        .header("X-Andina-Signature", signBase64(ANDINA_SECRET, timestamp + "." + latePendingPayload))
                        .content(latePendingPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT_OF_ORDER_IGNORED"));
    }

    @Test
    void shouldAcceptValidQuetzalWebhook() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String payload = """
                {
                  "id": "evt_mx_3003",
                  "type": "charge.succeeded",
                  "created": %d,
                  "data": {
                    "charge": {
                      "id": "ch_mx_3003",
                      "amount": 125000,
                      "currency": "MXN",
                      "reference": "POL MX 30219",
                      "paid_at": %d
                    }
                  }
                }
                """.formatted(timestamp, timestamp);
        String v1 = signHex(QUETZAL_SECRET, timestamp + "." + payload);

        mockMvc.perform(post("/v1/webhooks/quetzal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Quetzal-Signature", "t=" + timestamp + ",v1=" + v1)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.paymentKey").value("QUETZAL:ch_mx_3003"));
    }

    @Test
    void shouldRejectInvalidSignatureWith401() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String payload = "{\"id\":\"evt_bad\"}";

        mockMvc.perform(post("/v1/webhooks/quetzal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Quetzal-Signature", "t=" + timestamp + ",v1=deadbeef")
                        .content(payload))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectInvalidPayloadWith400ProblemDetail() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String invalidPayload = """
                {
                  "notificacion_id": "notif-bad-001",
                  "transaccion": {
                    "id": "TRX-CO-9999",
                    "estado": "APROBADA",
                    "valor": -500,
                    "moneda": "COP",
                    "referencia": "POL-CO-88412",
                    "fecha_transaccion": "2026-11-19T18:00:00-05:00"
                  }
                }
                """;
        String signature = signBase64(ANDINA_SECRET, timestamp + "." + invalidPayload);

        mockMvc.perform(post("/v1/webhooks/andina")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Andina-Timestamp", String.valueOf(timestamp))
                        .header("X-Andina-Signature", signature)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    private String signBase64(String secret, String data) throws Exception {
        return Base64.getEncoder().encodeToString(hmacSha256(secret, data));
    }

    private String signHex(String secret, String data) throws Exception {
        return HexFormat.of().formatHex(hmacSha256(secret, data));
    }

    private byte[] hmacSha256(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}
