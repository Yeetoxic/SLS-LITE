package net.slimelabs.slslite.log;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class CorrelationIds {

  private static final AtomicLong SEQUENCE = new AtomicLong();

  private CorrelationIds() {}

  public static String next(String category) {
    String prefix = category == null ? "op" : category.toLowerCase(Locale.ROOT);
    if (!prefix.matches("[a-z][a-z0-9-]{0,15}")) {
      throw new IllegalArgumentException("Invalid correlation category");
    }
    long time = System.currentTimeMillis();
    long sequence = SEQUENCE.getAndIncrement() & 0xfffffL;
    return prefix
        + "-"
        + Long.toUnsignedString(time, 36)
        + "-"
        + Long.toUnsignedString(sequence, 36);
  }
}
