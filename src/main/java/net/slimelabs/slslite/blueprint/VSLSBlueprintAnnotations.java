package net.slimelabs.slslite.blueprint;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

public final class VSLSBlueprintAnnotations {

    private static final String NAMESPACE = "vsls";
    private static final String MATCHMAKING = "matchmaking";

    private VSLSBlueprintAnnotations() {
    }

    public static boolean dontStopWhenEmpty(Map<String, Object> annotations) {
        Object value = namespace(annotations).get("dont-stop-when-empty");
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException(
                    "annotations.vsls.dont-stop-when-empty must be a boolean"
            );
        }
        return booleanValue;
    }

    public static OptionalInt maxInstances(Map<String, Object> annotations) {
        return positiveInteger(
                namespace(annotations).get("max-instances"),
                "annotations.vsls.max-instances"
        );
    }

    public static OptionalInt maxPlayers(Map<String, Object> annotations) {
        Map<?, ?> matchmaking = matchmaking(annotations);
        return positiveInteger(
                matchmaking.get("maxPlayers"),
                "annotations.vsls.matchmaking.maxPlayers"
        );
    }

    public static Optional<String> gameType(Map<String, Object> annotations) {
        Object value = matchmaking(annotations).get("gameType");
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String configured) || configured.isBlank()) {
            throw new IllegalArgumentException(
                    "annotations.vsls.matchmaking.gameType must be a "
                            + "non-blank string"
            );
        }
        return Optional.of(configured.trim());
    }

    public static List<String> onJoinCommands(Map<String, Object> annotations) {
        Object value = namespace(annotations).get("on-join");
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> entries)) {
            throw new IllegalArgumentException(
                    "annotations.vsls.on-join must be a list"
            );
        }
        if (entries.size() > 32) {
            throw new IllegalArgumentException(
                    "annotations.vsls.on-join must not contain more than 32 actions"
            );
        }
        List<String> commands = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            if (!(entries.get(index) instanceof Map<?, ?> action)) {
                throw new IllegalArgumentException(
                        "annotations.vsls.on-join[" + index + "] must be an object"
                );
            }
            Object configured = action.get("run");
            if (!(configured instanceof String command) || command.isBlank()) {
                throw new IllegalArgumentException(
                        "annotations.vsls.on-join[" + index
                                + "].run must be a non-blank string"
                );
            }
            String normalized = command.trim();
            if (normalized.length() > 4096
                    || normalized.indexOf('\n') >= 0
                    || normalized.indexOf('\r') >= 0
                    || normalized.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "annotations.vsls.on-join[" + index
                                + "].run must be one line of at most 4096 characters"
                );
            }
            commands.add(normalized);
        }
        return List.copyOf(commands);
    }

    public static void validate(Map<String, Object> annotations) {
        dontStopWhenEmpty(annotations);
        maxInstances(annotations);
        maxPlayers(annotations);
        gameType(annotations);
        onJoinCommands(annotations);
    }

    private static Map<?, ?> matchmaking(Map<String, Object> annotations) {
        Object value = namespace(annotations).get(MATCHMAKING);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "annotations.vsls.matchmaking must be an object"
            );
        }
        return map;
    }

    private static Map<?, ?> namespace(Map<String, Object> annotations) {
        Object value = annotations.get(NAMESPACE);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("annotations.vsls must be an object");
        }
        return map;
    }

    private static OptionalInt positiveInteger(Object value, String path) {
        if (value == null) {
            return OptionalInt.empty();
        }
        BigInteger parsed;
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            parsed = BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof BigInteger integer) {
            parsed = integer;
        } else {
            throw new IllegalArgumentException(
                    path + " must be a positive integer"
            );
        }
        if (parsed.signum() <= 0
                || parsed.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(
                    path + " must be between 1 and " + Integer.MAX_VALUE
            );
        }
        return OptionalInt.of(parsed.intValue());
    }
}
