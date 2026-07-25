package net.slimelabs.slslite.config;

import java.util.Locale;

public enum ForwardingMode {
    NONE,
    MODERN;

    public static ForwardingMode parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "forwarding.mode must be 'none' or 'modern'",
                    exception
            );
        }
    }
}
