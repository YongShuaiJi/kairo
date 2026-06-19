package com.example.runtimemock.agent.core;

@FunctionalInterface
public interface RecordingEventSink {

    RecordingEventSink NOOP = event -> {
    };

    void accept(RecordedInvocation event);
}
