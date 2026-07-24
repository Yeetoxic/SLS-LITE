package net.slimelabs.slslite.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class YamlValues {

    private YamlValues() {
    }

    public static Map<String, Object> asMap(Object value, String key, Path path)
            throws ConfigurationException {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw error(path, "'" + key + "' must be an object");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String stringKey)) {
                throw error(path, "'" + key + "' contains a non-string key");
            }
            result.put(stringKey, entry.getValue());
        }
        return result;
    }

    public static Map<String, Object> optionalMap(
            Map<String, Object> parent,
            String key,
            Path path
    ) throws ConfigurationException {
        if (!parent.containsKey(key)) {
            return Map.of();
        }
        return asMap(parent.get(key), key, path);
    }

    public static String requiredString(Map<String, Object> values, String key, Path path)
            throws ConfigurationException {
        Object value = values.get(key);
        if (value == null || value.toString().isBlank()) {
            throw error(path, "missing required value '" + key + "'");
        }
        return value.toString().trim();
    }

    public static String optionalString(
            Map<String, Object> values,
            String key,
            String defaultValue,
            Path path
    ) throws ConfigurationException {
        Object value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw error(path, "'" + key + "' must be a non-blank string");
        }
        return stringValue.trim();
    }

    public static int optionalPositiveInt(
            Map<String, Object> values,
            String key,
            int defaultValue,
            Path path
    ) throws ConfigurationException {
        Object value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)
                || number.intValue() <= 0
                || number.doubleValue() != number.intValue()) {
            throw error(path, "'" + key + "' must be a positive integer");
        }
        return number.intValue();
    }

    public static int optionalNonNegativeInt(
            Map<String, Object> values,
            String key,
            int defaultValue,
            Path path
    ) throws ConfigurationException {
        Object value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)
                || number.intValue() < 0
                || number.doubleValue() != number.intValue()) {
            throw error(path, "'" + key + "' must be a non-negative integer");
        }
        return number.intValue();
    }

    public static List<String> optionalStringList(
            Map<String, Object> values,
            String key,
            List<String> defaultValue,
            Path path
    ) throws ConfigurationException {
        Object value = values.get(key);
        if (value == null) {
            return List.copyOf(defaultValue);
        }
        if (!(value instanceof List<?> rawList)) {
            throw error(path, "'" + key + "' must be a list of strings");
        }

        List<String> result = new ArrayList<>();
        for (Object item : rawList) {
            if (!(item instanceof String stringItem) || stringItem.isBlank()) {
                throw error(path, "'" + key + "' must contain only non-blank strings");
            }
            result.add(stringItem);
        }
        return List.copyOf(result);
    }

    public static ConfigurationException error(Path path, String message) {
        return new ConfigurationException(path + ": " + message);
    }
}
