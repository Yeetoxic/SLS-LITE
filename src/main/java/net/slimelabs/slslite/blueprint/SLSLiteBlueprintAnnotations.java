package net.slimelabs.slslite.blueprint;

import java.math.BigInteger;
import java.util.Map;
import java.util.OptionalInt;

public final class SLSLiteBlueprintAnnotations {

  private static final String NAMESPACE = "sls-lite";

  private SLSLiteBlueprintAnnotations() {}

  public static OptionalInt maxPlayers(Map<String, Object> annotations) {
    return positiveInteger(namespace(annotations).get("max-players"));
  }

  private static Map<?, ?> namespace(Map<String, Object> annotations) {
    Object value = annotations.get(NAMESPACE);
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("annotations.sls-lite must be an object");
    }
    return map;
  }

  private static OptionalInt positiveInteger(Object value) {
    if (value == null) {
      return OptionalInt.empty();
    }
    BigInteger parsed;
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      parsed = BigInteger.valueOf(((Number) value).longValue());
    } else if (value instanceof BigInteger integer) {
      parsed = integer;
    } else {
      throw new IllegalArgumentException(
          "annotations.sls-lite.max-players must be a positive integer");
    }
    if (parsed.signum() <= 0 || parsed.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
      throw new IllegalArgumentException(
          "annotations.sls-lite.max-players must be between 1 and " + Integer.MAX_VALUE);
    }
    return OptionalInt.of(parsed.intValue());
  }
}
