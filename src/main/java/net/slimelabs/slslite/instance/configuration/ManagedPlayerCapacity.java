package net.slimelabs.slslite.instance.configuration;

/** Separates the public matchmaking limit from bounded backend force-join headroom. */
public final class ManagedPlayerCapacity {

  private ManagedPlayerCapacity() {}

  public static int backendLimit(int publicLimit, int proxyLimit) {
    if (publicLimit <= 0) {
      throw new IllegalArgumentException("publicLimit must be positive");
    }
    if (proxyLimit < 0) {
      throw new IllegalArgumentException("proxyLimit must not be negative");
    }
    int minimumWithForceHeadroom =
        publicLimit == Integer.MAX_VALUE ? Integer.MAX_VALUE : publicLimit + 1;
    return Math.max(minimumWithForceHeadroom, proxyLimit);
  }
}
