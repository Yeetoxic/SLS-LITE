package net.slimelabs.slslite.instance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

final class InstanceLogBuffer {

    static final int CAPACITY = 1_000;
    static final int MAX_LINE_LENGTH = 1_024;

    private final ArrayDeque<String> lines = new ArrayDeque<>(CAPACITY);

    synchronized void append(String line) {
        String retained = line.length() <= MAX_LINE_LENGTH
                ? line
                : line.substring(0, MAX_LINE_LENGTH) + "... [truncated]";
        if (lines.size() == CAPACITY) {
            lines.removeFirst();
        }
        lines.addLast(retained);
    }

    synchronized InstanceLogPage page(int page, int linesPerPage) {
        if (page <= 0) {
            throw new IllegalArgumentException("page must be positive");
        }
        if (linesPerPage <= 0) {
            throw new IllegalArgumentException("linesPerPage must be positive");
        }

        List<String> snapshot = new ArrayList<>(lines);
        int total = snapshot.size();
        long offset = ((long) page - 1L) * linesPerPage;
        int end = (int) Math.max(0L, total - offset);
        int start = Math.max(0, end - linesPerPage);
        List<String> selected = end == 0
                ? List.of()
                : List.copyOf(snapshot.subList(start, end));
        return new InstanceLogPage(selected, total, CAPACITY);
    }

    synchronized int size() {
        return lines.size();
    }
}
