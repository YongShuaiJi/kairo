package com.example.runtimemock.api;

public interface ScriptLog {

    ScriptLog NOOP = new ScriptLog() {
        @Override
        public void debug(String message) {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message, Throwable throwable) {
        }
    };

    void debug(String message);

    void info(String message);

    void warn(String message);

    void error(String message, Throwable throwable);
}
