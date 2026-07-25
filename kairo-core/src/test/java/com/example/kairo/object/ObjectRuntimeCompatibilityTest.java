package com.example.kairo.object;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compatibility gate for the {@code com.example.kairo.object} sources migrated verbatim from the
 * former {@code kairo-object} module into {@code kairo-core}. Asserts that object construction,
 * property path access, type conversion and throwable creation behave as before the move.
 */
class ObjectRuntimeCompatibilityTest {

    private final DefaultRuntimeObjectFactory factory = new DefaultRuntimeObjectFactory();
    private final PropertyPathAccessor accessor = new PropertyPathAccessor();

    @Test
    void convertsJsonToTargetType() {
        Sample sample = (Sample) factory.fromJson("{\"name\":\"kairo\",\"count\":7}", Sample.class, null);
        assertThat(sample.name()).isEqualTo("kairo");
        assertThat(sample.count()).isEqualTo(7);
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> factory.fromJson("{not json}", Sample.class, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsNewInstancesForCommonTypes() {
        assertThat(factory.newInstance(String.class)).isEqualTo("");
        assertThat(factory.newInstance(int.class)).isEqualTo(0);
        assertThat(factory.newInstance(boolean.class)).isEqualTo(false);
        assertThat(factory.newInstance(java.util.ArrayList.class)).isInstanceOf(java.util.ArrayList.class);
    }

    @Test
    void rejectsInterfaceInstantiation() {
        assertThatThrownBy(() -> factory.newInstance(List.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot create instance of " + List.class.getName());
    }

    @Test
    void createsThrowableByName() {
        Throwable throwable = factory.newThrowable(IllegalStateException.class.getName(), "boom", null);
        assertThat(throwable).isInstanceOf(IllegalStateException.class);
        assertThat(throwable.getMessage()).isEqualTo("boom");
    }

    @Test
    void rejectsNonThrowableClassName() {
        assertThatThrownBy(() -> factory.newThrowable(Object.class.getName(), "x", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a Throwable");
    }

    @Test
    void readsAndWritesPropertiesThroughAccessors() {
        Holder holder = new Holder();
        assertThat(factory.getProperty(holder, "value")).isEqualTo("old");
        factory.setProperty(holder, "value", "new");
        assertThat(factory.getProperty(holder, "value")).isEqualTo("new");
    }

    @Test
    void readsAndWritesMapEntries() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", "kairo");
        assertThat(accessor.get(values, "name")).isEqualTo("kairo");
        accessor.set(values, "name", "updated");
        assertThat(accessor.get(values, "name")).isEqualTo("updated");
    }

    @Test
    void rejectsClassAndClassLoaderTraversal() {
        assertThatThrownBy(() -> accessor.get(new Holder(), "class.classLoader"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden property path segment");
    }

    @Test
    void typeConverterConvertsNumbersAndCollections() {
        assertThat(TypeConverter.convert("7", int.class)).isEqualTo(7);
        assertThat(TypeConverter.convert("42", Integer.class)).isEqualTo(42);
        assertThat(TypeConverter.convert("true", boolean.class)).isEqualTo(true);
        assertThat((Object) TypeConverter.convert(List.of("a", "b"), String[].class))
                .isInstanceOf(String[].class);
    }

    @Test
    void typeConverterWrapsAndDefaultsPrimitives() {
        assertThat(TypeConverter.wrap(int.class)).isEqualTo(Integer.class);
        assertThat(TypeConverter.defaultPrimitiveValue(long.class)).isEqualTo(0L);
        assertThatThrownBy(() -> TypeConverter.defaultPrimitiveValue(String.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    public record Sample(String name, int count) {
    }

    public static final class Holder {
        private String value = "old";

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
