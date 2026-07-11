package com.example.kairo.api;

public enum ScriptSessionStatus {
    CREATED, VALIDATED, APPLIED, EXPIRED, REVERTED, FAILED;

    public boolean terminal() {
        return this == EXPIRED || this == REVERTED || this == FAILED;
    }
}
