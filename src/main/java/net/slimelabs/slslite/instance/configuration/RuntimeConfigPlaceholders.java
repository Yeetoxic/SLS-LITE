package net.slimelabs.slslite.instance.configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.slimelabs.slslite.instance.InstancePreparationException;

final class RuntimeConfigPlaceholders {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z][a-z0-9_]*)}");

  private RuntimeConfigPlaceholders() {}

  static Map<String, String> strings(Map<String, String> configured, Map<String, String> values)
      throws InstancePreparationException {
    Map<String, String> rendered = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : configured.entrySet()) {
      rendered.put(entry.getKey(), render(entry.getValue(), values));
    }
    return rendered;
  }

  static Map<String, Map<String, Object>> yaml(
      Map<String, Map<String, Object>> configured, Map<String, String> values)
      throws InstancePreparationException {
    Map<String, Map<String, Object>> rendered = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Object>> entry : configured.entrySet()) {
      rendered.put(entry.getKey(), objectMap(entry.getValue(), values));
    }
    return rendered;
  }

  static Map<String, Map<String, String>> text(
      Map<String, Map<String, String>> configured, Map<String, String> values)
      throws InstancePreparationException {
    Map<String, Map<String, String>> rendered = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, String>> entry : configured.entrySet()) {
      rendered.put(entry.getKey(), strings(entry.getValue(), values));
    }
    return rendered;
  }

  private static Map<String, Object> objectMap(
      Map<String, Object> configured, Map<String, String> values)
      throws InstancePreparationException {
    Map<String, Object> rendered = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : configured.entrySet()) {
      rendered.put(entry.getKey(), object(entry.getValue(), values));
    }
    return rendered;
  }

  private static Object object(Object configured, Map<String, String> values)
      throws InstancePreparationException {
    if (configured instanceof String string) {
      return render(string, values);
    }
    if (configured instanceof Map<?, ?> map) {
      Map<String, Object> typed = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        typed.put(String.valueOf(entry.getKey()), object(entry.getValue(), values));
      }
      return typed;
    }
    if (configured instanceof List<?> list) {
      java.util.ArrayList<Object> rendered = new java.util.ArrayList<>(list.size());
      for (Object value : list) {
        rendered.add(object(value, values));
      }
      return List.copyOf(rendered);
    }
    return configured;
  }

  private static String render(String configured, Map<String, String> values)
      throws InstancePreparationException {
    Matcher matcher = PLACEHOLDER.matcher(configured);
    StringBuilder rendered = new StringBuilder();
    while (matcher.find()) {
      String replacement = values.get(matcher.group(1));
      if (replacement == null) {
        throw new InstancePreparationException(
            "Unsupported runtime configuration placeholder: {" + matcher.group(1) + "}");
      }
      matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(rendered);
    return rendered.toString();
  }
}
