package net.slimelabs.slslite.blueprint;

import java.time.Duration;
import java.util.Map;

public record BlueprintLifecyclePolicy(
        boolean keepAlive,
        Duration idleTimeout
) {

    public static final String NAMESPACE = "sls-lite";
    public static final String KEEP_ALIVE = "keep-alive";
    public static final String STOP_WHEN_EMPTY = "stop-when-empty";
    public static final String IDLE_SHUTDOWN_SECONDS = "idle-shutdown-seconds";

    public BlueprintLifecyclePolicy {
        if (idleTimeout == null || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must not be negative");
        }
    }

    public static BlueprintLifecyclePolicy from(
            Blueprint blueprint,
            int defaultIdleShutdownSeconds
    ) {
        Map<String, Object> annotations = blueprint.annotations();
        boolean keepAlive = booleanAnnotation(annotations, KEEP_ALIVE, false);
        boolean stopWhenEmpty = booleanAnnotation(
                annotations,
                STOP_WHEN_EMPTY,
                true
        );
        int idleSeconds = integerAnnotation(
                annotations,
                IDLE_SHUTDOWN_SECONDS,
                defaultIdleShutdownSeconds
        );
        return new BlueprintLifecyclePolicy(
                blueprint.save() || keepAlive || !stopWhenEmpty || idleSeconds == 0,
                Duration.ofSeconds(idleSeconds)
        );
    }

    private static boolean booleanAnnotation(
            Map<String, Object> annotations,
            String key,
            boolean defaultValue
    ) {
        Object value = annotation(annotations, key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException(
                    "annotation '" + NAMESPACE + "." + key + "' must be true or false"
            );
        }
        return booleanValue;
    }

    private static int integerAnnotation(
            Map<String, Object> annotations,
            String key,
            int defaultValue
    ) {
        Object value = annotation(annotations, key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)
                || number.intValue() < 0
                || number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException(
                    "annotation '" + NAMESPACE + "." + key
                            + "' must be a non-negative integer"
            );
        }
        return number.intValue();
    }

    private static Object annotation(Map<String, Object> annotations, String key) {
        Object flattened = annotations.get(NAMESPACE + "." + key);
        if (flattened != null) {
            return flattened;
        }
        Object namespace = annotations.get(NAMESPACE);
        if (namespace == null) {
            return null;
        }
        if (!(namespace instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException(
                    "annotation namespace '" + NAMESPACE + "' must be an object"
            );
        }
        return values.get(key);
    }
}
