package com.example.runtimemock.core;

import java.util.concurrent.ThreadLocalRandom;

public final class SamplingPolicy {

    public boolean shouldRun(int percentage) {
        if (percentage <= 0) {
            return false;
        }
        if (percentage >= 100) {
            return true;
        }
        return ThreadLocalRandom.current().nextInt(100) < percentage;
    }
}
