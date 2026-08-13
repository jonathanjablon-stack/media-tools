package com.carstream.app;

final class Strings {
    private Strings() { }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
