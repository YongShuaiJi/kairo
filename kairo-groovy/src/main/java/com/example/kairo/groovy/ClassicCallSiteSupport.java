package com.example.kairo.groovy;

/**
 * Small statically-dispatched helpers for operations that classic Groovy call sites
 * cannot invoke safely on strongly encapsulated JDK classes.
 */
public final class ClassicCallSiteSupport {

    private ClassicCallSiteSupport() {
    }

    public static Object[] cloneArguments(Object[] arguments) {
        return arguments.clone();
    }

}
