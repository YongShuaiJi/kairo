package com.example.bytecode;

/**
 * In-process target for {@code BytecodeHttpIntegrationTest}. Lives outside the
 * {@code com.example.kairo.} prefix so Kairo's transformer does not ignore it,
 * and is plain enough that Byte Buddy can weave and re-read its bytes.
 */
public class SampleService {

    public int compute(int value) {
        return value * 2;
    }

    public String greet(String name) {
        return "hello " + name;
    }
}
