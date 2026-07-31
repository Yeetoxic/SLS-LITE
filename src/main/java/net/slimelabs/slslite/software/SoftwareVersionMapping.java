package net.slimelabs.slslite.software;

import java.util.ArrayList;
import java.util.List;

public record SoftwareVersionMapping(String image, String constraint) {

  public SoftwareVersionMapping {
    if (image == null || image.isBlank()) {
      throw new IllegalArgumentException("Software mapping image must not be blank");
    }
    if (constraint == null || constraint.isBlank()) {
      throw new IllegalArgumentException("Software mapping constraint must not be blank");
    }
    image = image.trim();
    constraint = constraint.trim();
    parseConstraint(constraint);
  }

  public boolean matches(String version) {
    List<Integer> candidate = parseVersion(version);
    for (Constraint part : parseConstraint(constraint)) {
      int comparison = compare(candidate, part.version());
      if (!part.operator().matches(comparison)) {
        return false;
      }
    }
    return true;
  }

  public static List<Integer> parseVersion(String configured) {
    if (configured == null || configured.isBlank()) {
      throw new IllegalArgumentException("Software version must not be blank");
    }
    String value = configured.trim();
    int length = 0;
    while (length < value.length()) {
      char current = value.charAt(length);
      if (!Character.isDigit(current) && current != '.') {
        break;
      }
      length++;
    }
    String numeric = value.substring(0, length);
    if (numeric.isBlank() || numeric.startsWith(".") || numeric.endsWith(".")) {
      throw new IllegalArgumentException(
          "Software version must start with numeric segments: " + configured);
    }
    List<Integer> parsed = new ArrayList<>();
    for (String segment : numeric.split("\\.")) {
      try {
        parsed.add(Integer.parseInt(segment));
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException(
            "Invalid numeric software version segment: " + segment, exception);
      }
    }
    return List.copyOf(parsed);
  }

  private static List<Constraint> parseConstraint(String configured) {
    List<Constraint> parsed = new ArrayList<>();
    for (String token : configured.trim().split("\\s+")) {
      Operator operator = Operator.EQUAL;
      String version = token;
      for (Operator candidate : Operator.PARSE_ORDER) {
        if (token.startsWith(candidate.symbol)) {
          operator = candidate;
          version = token.substring(candidate.symbol.length());
          break;
        }
      }
      parsed.add(new Constraint(operator, parseVersion(version)));
    }
    if (parsed.isEmpty()) {
      throw new IllegalArgumentException("Software mapping constraint is empty");
    }
    return List.copyOf(parsed);
  }

  private static int compare(List<Integer> left, List<Integer> right) {
    int length = Math.max(left.size(), right.size());
    for (int index = 0; index < length; index++) {
      int leftPart = index < left.size() ? left.get(index) : 0;
      int rightPart = index < right.size() ? right.get(index) : 0;
      int comparison = Integer.compare(leftPart, rightPart);
      if (comparison != 0) {
        return comparison;
      }
    }
    return 0;
  }

  private record Constraint(Operator operator, List<Integer> version) {}

  private enum Operator {
    GREATER_OR_EQUAL(">=") {
      @Override
      boolean matches(int comparison) {
        return comparison >= 0;
      }
    },
    LESS_OR_EQUAL("<=") {
      @Override
      boolean matches(int comparison) {
        return comparison <= 0;
      }
    },
    EQUAL("==") {
      @Override
      boolean matches(int comparison) {
        return comparison == 0;
      }
    },
    GREATER(">") {
      @Override
      boolean matches(int comparison) {
        return comparison > 0;
      }
    },
    LESS("<") {
      @Override
      boolean matches(int comparison) {
        return comparison < 0;
      }
    },
    SINGLE_EQUAL("=") {
      @Override
      boolean matches(int comparison) {
        return comparison == 0;
      }
    };

    private static final List<Operator> PARSE_ORDER =
        List.of(GREATER_OR_EQUAL, LESS_OR_EQUAL, EQUAL, GREATER, LESS, SINGLE_EQUAL);

    private final String symbol;

    Operator(String symbol) {
      this.symbol = symbol;
    }

    abstract boolean matches(int comparison);
  }
}
