package com.example.other;

/**
 * Business class in a non-baseline package (not {@code java.*}, not
 * {@code com.example.kairo.*}) used by the EXTENDED allow-list tests. It is deliberately
 * non-sensitive so that only the allow-list gate (not the sensitive deny-list) decides
 * whether it may be referenced.
 */
public class OtherThing {

    public OtherThing() {
    }

    public String greet() {
        return "other";
    }

    public static String staticGreet() {
        return "static-other";
    }
}
