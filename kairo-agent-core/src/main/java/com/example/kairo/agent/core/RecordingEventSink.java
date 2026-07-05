package com.example.kairo.agent.core;

@FunctionalInterface
public interface RecordingEventSink {

    RecordingEventSink NOOP = event -> {
    };

    void accept(RecordedInvocation event);
}
