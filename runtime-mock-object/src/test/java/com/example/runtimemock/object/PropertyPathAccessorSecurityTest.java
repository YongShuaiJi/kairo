package com.example.runtimemock.object;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertyPathAccessorSecurityTest {

    private final PropertyPathAccessor accessor = new PropertyPathAccessor();

    @Test
    void rejectsClassAndClassLoaderTraversal() {
        assertThatThrownBy(() -> accessor.get(new Target(), "class.classLoader"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden property path segment");
    }

    @Test
    void rejectsReflectiveTargetsEvenThroughMaps() {
        Map<String, Object> values = Map.of("loader", getClass().getClassLoader());

        assertThatThrownBy(() -> accessor.get(values, "loader.parent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Property access is forbidden");
    }

    private static final class Target {
        private String value = "safe";
    }
}
