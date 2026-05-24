package com.example;

/** Trivial class so {@code compileJava} has something cacheable to do. */
public final class Hello {
    private Hello() {}

    public static String greeting() {
        return "hello from silo cache e2e";
    }
}
