package com.example.kairo.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnhancementLocationTest {

    @Test
    void legacyPhaseRoundTripsThroughMethodLocations() {
        for (InvokePhase phase : InvokePhase.values()) {
            EnhancementLocation location = EnhancementLocation.fromPhase(phase);
            assertThat(location.isMethodLocation()).isTrue();
            assertThat(location.toLegacyPhase()).isEqualTo(phase);
        }
    }

    @Test
    void finallyLocationProjectsToReturnAndIsObserveOnly() {
        assertThat(EnhancementLocation.METHOD_FINALLY.toLegacyPhase()).isEqualTo(InvokePhase.RETURN);
        assertThat(EnhancementLocation.METHOD_FINALLY.isFinallyLocation()).isTrue();
        assertThat(EnhancementLocation.METHOD_FINALLY.mayMutateOutcome()).isFalse();
        assertThat(EnhancementLocation.METHOD_FINALLY.isReturnLocation()).isFalse();
        assertThat(EnhancementLocation.METHOD_FINALLY.isThrowLocation()).isFalse();
    }

    @Test
    void constructorLocationsGroupAndProjectCorrectly() {
        assertThat(EnhancementLocation.CONSTRUCTOR_AFTER_SUPER.isConstructorLocation()).isTrue();
        assertThat(EnhancementLocation.CONSTRUCTOR_AFTER_SUPER.isEnterLocation()).isTrue();
        assertThat(EnhancementLocation.CONSTRUCTOR_AFTER_SUPER.toLegacyPhase()).isEqualTo(InvokePhase.BEFORE);

        assertThat(EnhancementLocation.CONSTRUCTOR_RETURN.toLegacyPhase()).isEqualTo(InvokePhase.RETURN);
        assertThat(EnhancementLocation.CONSTRUCTOR_THROW.toLegacyPhase()).isEqualTo(InvokePhase.THROWS);
    }

    @Test
    void callSiteLocationsGroupAndProjectCorrectly() {
        assertThat(EnhancementLocation.CALL_BEFORE.isCallSiteLocation()).isTrue();
        assertThat(EnhancementLocation.CALL_BEFORE.isEnterLocation()).isTrue();
        assertThat(EnhancementLocation.CALL_RETURN.toLegacyPhase()).isEqualTo(InvokePhase.RETURN);
        assertThat(EnhancementLocation.CALL_THROW.toLegacyPhase()).isEqualTo(InvokePhase.THROWS);
    }

    @Test
    void mutateOutcomeAllowedEverywhereExceptFinally() {
        for (EnhancementLocation location : EnhancementLocation.values()) {
            assertThat(location.mayMutateOutcome()).isEqualTo(!location.isFinallyLocation());
        }
    }

    @Test
    void fromPhaseRejectsNull() {
        assertThatThrownBy(() -> EnhancementLocation.fromPhase(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
