package com.example.kairo.sidecar;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadMaskerTest {

    @Test
    @SuppressWarnings("unchecked")
    void masksSensitiveFieldsAndTokenizesConfiguredFields() {
        MaskingPolicy policy = new MaskingPolicy(
                Set.of(),
                Map.of("user.email", MaskingAction.TOKENIZE),
                MaskingAction.MASK,
                "***",
                8,
                1_000,
                64 * 1024
        );
        PayloadMasker masker = new PayloadMasker(
                policy,
                new StableTokenizer("dataset-domain-key".getBytes(StandardCharsets.UTF_8)),
                "orders-prod-dataset-v1"
        );

        Object masked = masker.mask(Map.of(
                "user", Map.of(
                        "email", "alice@example.com",
                        "name", "Alice"
                ),
                "authorization", "Bearer secret-token",
                "order", Map.of("amount", 12.30)
        ));

        Map<String, Object> root = (Map<String, Object>) masked;
        Map<String, Object> user = (Map<String, Object>) root.get("user");
        Map<String, Object> order = (Map<String, Object>) root.get("order");

        assertThat(user.get("email")).asString().startsWith("tok_");
        assertThat(user.get("email")).isEqualTo(masker.mask(Map.of("user", Map.of("email", "alice@example.com")))
                instanceof Map<?, ?> secondRoot
                ? ((Map<?, ?>) secondRoot.get("user")).get("email")
                : null);
        assertThat(user.get("name")).isEqualTo("Alice");
        assertThat(root.get("authorization")).isEqualTo("***");
        assertThat(order.get("amount")).isEqualTo(12.30);
    }
}
