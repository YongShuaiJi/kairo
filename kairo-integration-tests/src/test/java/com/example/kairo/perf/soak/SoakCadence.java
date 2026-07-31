package com.example.kairo.perf.soak;

import java.time.Duration;

/**
 * The fixed M2-D soak cadence (&sect;9.4): "每分钟采集时间序列摘要；持续执行真实调用，
 * 每 5 分钟执行一批增强/更新/卸载，每 30 分钟执行 Agent/Platform 断连恢复".
 *
 * <p>These three intervals are the <b>explicit, fixed</b> production cadence. They are the
 * same for the production {@link SoakClock.WallClock} (real RC {@code PT2H} / RELEASE
 * {@code P7D}) and for the test-only {@link SoakClock.AcceleratedClock}: only the rate at
 * which wall time advances differs, never the cadence. {@code SoakCadenceTest} verifies they
 * equal {@code PT1M} / {@code PT5M} / {@code PT30M} verbatim so they cannot drift.
 *
 * @param summaryInterval     time-series summary every 1 minute (&sect;9.4)
 * @param batchInterval       enhance/update/partial-unload/full-unload batch every 5 minutes
 * @param disconnectInterval  Agent/Platform disconnect/recovery every 30 minutes
 */
public record SoakCadence(Duration summaryInterval, Duration batchInterval, Duration disconnectInterval) {

    /** The documented fixed M2-D cadence (&sect;9.4): 1m / 5m / 30m. */
    public static final SoakCadence DOCUMENTED = new SoakCadence(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30));
}
