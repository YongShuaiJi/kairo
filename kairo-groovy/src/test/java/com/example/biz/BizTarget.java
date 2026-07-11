package com.example.biz;

/**
 * Business class used by EXTENDED-tier tests. Deliberately placed in a non-baseline
 * package (not {@code java.*}, not {@code com.example.kairo.*}) so that it is usable only
 * when explicitly declared in {@code allowedClasses}.
 */
public class BizTarget {

    public String greet(String name) {
        return "hello " + name;
    }
}
