package net.slimelabs.slslite.blueprint;

import java.util.Map;
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
        Object value = namespace(annotations).get(MATCHMAKING);
        if (!(value instanceof Map<?, ?> matchmaking)) {
            return OptionalInt.empty();
        }
        return positiveInteger(matchmaking.get("maxPlayers"));
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
