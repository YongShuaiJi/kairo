package com.example.runtimemock.core;

import com.example.runtimemock.api.MethodMetadata;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionValidatorTest {

    private final DecisionValidator validator = new DecisionValidator();

    @Test
    void validatesAndConvertsPrimitiveArguments() throws Exception {
        Method method = Target.class.getMethod("score", int.class);
        Object[] converted = validator.validateArguments(new MethodMetadata(method, MethodDescriptor.of(method)),
                new Object[]{5L});

        assertThat(converted).containsExactly(5);
    }

    @Test
    void rejectsNullPrimitiveReturnValue() throws Exception {
        Method method = Target.class.getMethod("score", int.class);

        assertThatThrownBy(() -> validator.validateReturnValue(
                new MethodMetadata(method, MethodDescriptor.of(method)), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsRuntimeExceptionsWithoutDeclaration() throws Exception {
        Method method = Target.class.getMethod("score", int.class);
        RuntimeException exception = new RuntimeException("mocked");

        assertThat(validator.validateThrowable(new MethodMetadata(method, MethodDescriptor.of(method)), exception))
                .isSameAs(exception);
    }

    public static final class Target {
        public int score(int value) {
            return value;
        }
    }
}
