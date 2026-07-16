package com.example.kairo.api.config;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static com.example.kairo.api.config.KairoConfigCatalog.Channel.ENVIRONMENT;
import static com.example.kairo.api.config.KairoConfigCatalog.Channel.SPRING_PROPERTY;
import static com.example.kairo.api.config.KairoConfigCatalog.ValueType.STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KairoConfigCatalogTest {

    @Test
    void catalogIsUniqueImmutableAndCoversEveryPublicComponent() {
        assertThat(KairoConfigCatalog.entries()).hasSizeGreaterThan(90);
        assertThat(KairoConfigCatalog.identities())
                .hasSameSizeAs(KairoConfigCatalog.entries())
                .hasSameSizeAs(new HashSet<>(KairoConfigCatalog.identities()));
        assertThat(KairoConfigCatalog.entries())
                .extracting(KairoConfigCatalog.Binding::component)
                .contains("platform", "sidecar", "cli", "mcp", "web", "smoke");
        assertThat(KairoConfigCatalog.entries())
                .noneMatch(binding -> binding.key().startsWith("KAIRO_MATRIX_JDK_"));
        assertThatThrownBy(() -> KairoConfigCatalog.entries().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sensitiveValuesAreRedactedAndRepresentativeBindingsAreExact() {
        assertThat(KairoConfigCatalog.entries().stream()
                .filter(KairoConfigCatalog.Binding::sensitive))
                .allMatch(binding -> KairoConfigCatalog.REDACTED.equals(binding.defaultValue()));
        assertThat(KairoConfigCatalog.require(SPRING_PROPERTY, "platform",
                "kairo.platform.idempotency.lease-millis").defaultValue()).isEqualTo("30000");
        assertThat(KairoConfigCatalog.require(ENVIRONMENT, "sidecar",
                "KAIRO_PLATFORM_URL").defaultValue()).isEqualTo("http://platform:18280");
        assertThat(KairoConfigCatalog.require(ENVIRONMENT, "cli",
                "KAIRO_PLATFORM_URL").defaultPresent()).isFalse();
        assertThat(KairoConfigCatalog.require(ENVIRONMENT, "web",
                "KAIRO_WEB_SESSION_KEY").sensitive()).isTrue();
    }

    @Test
    void invalidMetadataFailsAtConstruction() {
        assertThatThrownBy(() -> new KairoConfigCatalog.Binding(
                ENVIRONMENT, "platform", "KAIRO_TOKEN", STRING, "plain-text",
                true, true, false, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redacted");
        assertThatThrownBy(() -> new KairoConfigCatalog.Binding(
                ENVIRONMENT, "platform", "KAIRO_OLD", STRING, "",
                false, false, true, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replacement");
    }
}
