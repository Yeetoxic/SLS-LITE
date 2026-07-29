package net.slimelabs.slslite.blueprint;

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
        return value instanceof Boolean booleanValue && booleanValue;
    }

    public static OptionalInt maxInstances(Map<String, Object> annotations) {
        return positiveInteger(namespace(annotations).get("max-instances"));
    }

    public static OptionalInt maxPlayers(Map<String, Object> annotations) {
        Map<?, ?> matchmaking = matchmaking(annotations);
        return positiveInteger(matchmaking.get("maxPlayers"));
    }

    public static Optional<String> gameType(Map<String, Object> annotations) {
        Object value = matchmaking(annotations).get("gameType");
        if (!(value instanceof String configured) || configured.isBlank()) {
            return Optional.empty();
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
        gameType(annotations);
        onJoinCommands(annotations);
    }

    private static Map<?, ?> matchmaking(Map<String, Object> annotations) {
        Object value = namespace(annotations).get(MATCHMAKING);
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Map<?, ?> namespace(Map<String, Object> annotations) {
        Object value = annotations.get(NAMESPACE);
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static OptionalInt positiveInteger(Object value) {
        if (!(value instanceof Number number)) {
            return OptionalInt.empty();
        }
        int parsed = number.intValue();
        return parsed > 0 ? OptionalInt.of(parsed) : OptionalInt.empty();
    }
}
