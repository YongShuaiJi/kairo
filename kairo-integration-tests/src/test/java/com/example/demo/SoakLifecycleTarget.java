package com.example.demo;

/**
 * Dedicated class for the soak harness's five-minute enhance/update/physical-unload lifecycle.
 * It deliberately lives outside Kairo's ignored agent packages and is distinct from the hot
 * {@link OrderService} target so lifecycle retransformation cannot create obsolete versions of
 * the continuously invoked class.
 */
public final class SoakLifecycleTarget {
    public int calculateScore(int base) {
        return base * 2;
    }
}
