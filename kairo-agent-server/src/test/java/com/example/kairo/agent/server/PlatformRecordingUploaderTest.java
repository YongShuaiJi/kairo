package com.example.kairo.agent.server;

import com.example.kairo.agent.core.RecordedInvocation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformRecordingUploaderTest {

    @Test
    void requeuesFailedAndEveryNotYetAttemptedSessionBatch() {
        RecordedInvocation first = invocation("session-a", "event-1");
        RecordedInvocation second = invocation("session-b", "event-2");
        RecordedInvocation third = invocation("session-c", "event-3");
        List<Map.Entry<String, List<RecordedInvocation>>> batches = List.of(
                new AbstractMap.SimpleImmutableEntry<>("session-a", List.of(first)),
                new AbstractMap.SimpleImmutableEntry<>("session-b", List.of(second)),
                new AbstractMap.SimpleImmutableEntry<>("session-c", List.of(third)));
        ArrayBlockingQueue<RecordedInvocation> queue = new ArrayBlockingQueue<>(3);

        PlatformRecordingUploader.RequeueResult result =
                PlatformRecordingUploader.requeuePending(batches, 1, queue);

        assertThat(result.pendingEvents()).isEqualTo(2);
        assertThat(result.requeuedEvents()).isEqualTo(2);
        assertThat(result.droppedEvents()).isZero();
        assertThat(queue).containsExactly(second, third);
    }

    @Test
    void reportsAnyEventsThatCannotBeRequeued() {
        RecordedInvocation first = invocation("session-a", "event-1");
        RecordedInvocation second = invocation("session-b", "event-2");
        List<Map.Entry<String, List<RecordedInvocation>>> batches = List.of(
                new AbstractMap.SimpleImmutableEntry<>("session-a", List.of(first)),
                new AbstractMap.SimpleImmutableEntry<>("session-b", List.of(second)));
        ArrayBlockingQueue<RecordedInvocation> queue = new ArrayBlockingQueue<>(1);

        PlatformRecordingUploader.RequeueResult result =
                PlatformRecordingUploader.requeuePending(batches, 0, queue);

        assertThat(result.pendingEvents()).isEqualTo(2);
        assertThat(result.requeuedEvents()).isEqualTo(1);
        assertThat(result.droppedEvents()).isEqualTo(1);
        assertThat(queue).containsExactly(first);
    }

    private static RecordedInvocation invocation(String sessionId, String eventId) {
        return new RecordedInvocation(sessionId, eventId, "trace-1", "kairo-recording-v1",
                Instant.EPOCH, Map.of("className", "example.Target"), List.of(), null, Map.of());
    }
}
