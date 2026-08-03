package net.slimelabs.slslite.api;

/** Semantic version of the public SLS-LITE Java API contract. */
public record ApiVersion(int major, int minor) implements Comparable<ApiVersion> {

  public static final ApiVersion CURRENT = new ApiVersion(1, 0);

  public ApiVersion {
    if (major < 1 || minor < 0) {
      throw new IllegalArgumentException("API version must be positive");
    }
  }

  @Override
  public int compareTo(ApiVersion other) {
    int majorComparison = Integer.compare(major, other.major);
    return majorComparison != 0 ? majorComparison : Integer.compare(minor, other.minor);
  }

  @Override
  public String toString() {
    return major + "." + minor;
  }
}
