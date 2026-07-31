package net.slimelabs.slslite.instance.diagnostics;

import java.util.List;

public record InstanceOutputBatch(long cursor, List<String> lines, long droppedLines) {

  public InstanceOutputBatch {
    if (cursor < 0 || droppedLines < 0) {
      throw new IllegalArgumentException(
          "Output cursor and dropped-line count must be non-negative");
    }
    lines = List.copyOf(lines);
  }
}
