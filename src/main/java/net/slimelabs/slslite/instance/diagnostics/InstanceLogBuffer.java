package net.slimelabs.slslite.instance.diagnostics;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;

final class InstanceLogBuffer {

  static final int DEFAULT_CAPACITY = 1_000;
  static final int CAPACITY = DEFAULT_CAPACITY;
  static final int MAX_LINE_LENGTH = 1_024;

  private final int capacity;
  private final ArrayDeque<Entry> lines;
  private long cursor;

  InstanceLogBuffer() {
    this(DEFAULT_CAPACITY);
  }

  InstanceLogBuffer(int capacity) {
    if (capacity < 0) {
      throw new IllegalArgumentException("capacity must not be negative");
    }
    this.capacity = capacity;
    this.lines = new ArrayDeque<>(Math.max(1, capacity));
  }

  synchronized void append(String line) {
    String retained =
        line.length() <= MAX_LINE_LENGTH
            ? line
            : line.substring(0, MAX_LINE_LENGTH) + "... [truncated]";
    ++cursor;
    if (capacity == 0) {
      notifyAll();
      return;
    }
    if (lines.size() == capacity) {
      lines.removeFirst();
    }
    lines.addLast(new Entry(cursor, retained));
    notifyAll();
  }

  synchronized InstanceLogPage page(int page, int linesPerPage) {
    if (page <= 0) {
      throw new IllegalArgumentException("page must be positive");
    }
    if (linesPerPage <= 0) {
      throw new IllegalArgumentException("linesPerPage must be positive");
    }

    List<String> snapshot = lines.stream().map(Entry::line).toList();
    int total = snapshot.size();
    long offset = ((long) page - 1L) * linesPerPage;
    int end = (int) Math.max(0L, total - offset);
    int start = Math.max(0, end - linesPerPage);
    List<String> selected = end == 0 ? List.of() : List.copyOf(snapshot.subList(start, end));
    return new InstanceLogPage(selected, total, capacity);
  }

  synchronized int size() {
    return lines.size();
  }

  synchronized long cursor() {
    return cursor;
  }

  int capacity() {
    return capacity;
  }

  synchronized InstanceOutputBatch awaitAfter(
      long afterCursor, int maximumLines, Duration quietPeriod, Duration timeout) {
    if (afterCursor < 0) {
      throw new IllegalArgumentException("afterCursor must be non-negative");
    }
    if (maximumLines <= 0) {
      throw new IllegalArgumentException("maximumLines must be positive");
    }
    if (quietPeriod.isNegative() || timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("Output wait durations are invalid");
    }

    long deadline = System.nanoTime() + timeout.toNanos();
    long observedCursor = cursor;
    long quietDeadline = Long.MAX_VALUE;
    while (true) {
      List<Entry> available =
          lines.stream().filter(entry -> entry.cursor() > afterCursor).limit(maximumLines).toList();
      if (!available.isEmpty()) {
        if (available.size() == maximumLines) {
          return batch(afterCursor, available);
        }
        if (cursor != observedCursor || quietDeadline == Long.MAX_VALUE) {
          observedCursor = cursor;
          quietDeadline = System.nanoTime() + quietPeriod.toNanos();
        }
        if (System.nanoTime() >= quietDeadline) {
          return batch(afterCursor, available);
        }
      }

      long wakeAt = Math.min(deadline, quietDeadline);
      long remaining = wakeAt - System.nanoTime();
      if (remaining <= 0) {
        return batch(afterCursor, available);
      }
      try {
        long millis = Math.max(1L, Duration.ofNanos(remaining).toMillis());
        wait(millis);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return batch(afterCursor, available);
      }
    }
  }

  private InstanceOutputBatch batch(long afterCursor, List<Entry> entries) {
    long earliestRetained = lines.isEmpty() ? cursor + 1 : lines.getFirst().cursor();
    long dropped = Math.max(0L, earliestRetained - afterCursor - 1L);
    long nextCursor =
        entries.isEmpty() ? Math.max(afterCursor, cursor) : entries.getLast().cursor();
    return new InstanceOutputBatch(nextCursor, entries.stream().map(Entry::line).toList(), dropped);
  }

  private record Entry(long cursor, String line) {}
}
