package net.slimelabs.slslite.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.diagnostics.InstanceOutputBatch;

/**
 * Delivers bounded managed-process output without waiting on Velocity's command thread.
 */
final class ConsoleOutputSessions implements AutoCloseable {

  static final int CAPTURE_LINES = 8;
  static final int FOLLOW_BATCH_LINES = 16;
  static final int DISPLAY_LINE_LENGTH = 320;
  static final int MAX_CONCURRENT_CAPTURES = 16;
  static final Duration CAPTURE_QUIET_PERIOD = Duration.ofMillis(250);
  static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(2);
  static final Duration FOLLOW_QUIET_PERIOD = Duration.ofMillis(100);
  static final Duration FOLLOW_POLL_TIMEOUT = Duration.ofSeconds(1);

  private final ConcurrentMap<Object, FollowSession> followers = new ConcurrentHashMap<>();
  private final ConcurrentMap<CaptureKey, Thread> captures = new ConcurrentHashMap<>();
  private final Semaphore capturePermits = new Semaphore(MAX_CONCURRENT_CAPTURES);
  private final AtomicBoolean closed = new AtomicBoolean();

  boolean capture(CommandSource source, ManagedInstance instance, long cursor) {
    return capture(source, new ManagedOutputFeed(instance), cursor);
  }

  boolean capture(CommandSource source, OutputFeed feed, long cursor) {
    CaptureKey captureKey = new CaptureKey(key(source), feed.id());
    if (closed.get() || !capturePermits.tryAcquire()) {
      return false;
    }
    Thread thread =
        Thread.ofVirtual()
            .name("sls-lite-console-capture-" + feed.id())
            .unstarted(
                () -> {
                  try {
                    InstanceOutputBatch batch =
                        feed.awaitAfter(
                            cursor, CAPTURE_LINES, CAPTURE_QUIET_PERIOD, CAPTURE_TIMEOUT);
                    if (closed.get() || !canDeliver(source)) {
                      return;
                    }
                    if (batch.droppedLines() > 0) {
                      sendDropped(source, feed.id(), batch.droppedLines());
                    }
                    if (batch.lines().isEmpty()) {
                      source.sendMessage(
                          CommandMessages.message(
                              "No new console output was captured from "
                                  + feed.id()
                                  + " within "
                                  + CAPTURE_TIMEOUT.toSeconds()
                                  + " seconds.",
                              NamedTextColor.GRAY));
                      return;
                    }
                    batch.lines().forEach(line -> sendLine(source, feed.id(), line));
                  } finally {
                    captures.remove(captureKey, Thread.currentThread());
                    capturePermits.release();
                  }
                });
    if (closed.get() || captures.putIfAbsent(captureKey, thread) != null) {
      capturePermits.release();
      return false;
    }
    thread.start();
    return true;
  }

  boolean follow(CommandSource source, ManagedInstance instance) {
    return follow(source, new ManagedOutputFeed(instance));
  }

  boolean follow(CommandSource source, OutputFeed feed) {
    if (closed.get()) {
      return false;
    }
    Object key = key(source);
    FollowSession next = new FollowSession(key, source, feed);
    FollowSession previous = followers.put(key, next);
    if (previous != null) {
      previous.stop();
    }
    next.start();
    return previous != null;
  }

  boolean unfollow(CommandSource source) {
    FollowSession removed = followers.remove(key(source));
    if (removed == null) {
      return false;
    }
    removed.stop();
    return true;
  }

  boolean isFollowing(CommandSource source) {
    return followers.containsKey(key(source));
  }

  void remove(UUID playerId) {
    FollowSession removed = followers.remove(playerId);
    if (removed != null) {
      removed.stop();
    }
  }

  int followerCount() {
    return followers.size();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    followers.values().forEach(FollowSession::stop);
    followers.clear();
    captures.values().forEach(Thread::interrupt);
    captures.clear();
  }

  private void runFollow(FollowSession session) {
    long cursor = session.feed.cursor();
    try {
      while (!closed.get()
          && !session.stopped.get()
          && !session.feed.stopped().isDone()
          && canDeliver(session.source)) {
        InstanceOutputBatch batch =
            session.feed.awaitAfter(
                cursor, FOLLOW_BATCH_LINES, FOLLOW_QUIET_PERIOD, FOLLOW_POLL_TIMEOUT);
        cursor = batch.cursor();
        if (batch.droppedLines() > 0) {
          sendDropped(session.source, session.feed.id(), batch.droppedLines());
        }
        batch.lines().forEach(line -> sendLine(session.source, session.feed.id(), line));
      }
    } finally {
      followers.remove(session.key, session);
    }
  }

  private static void sendLine(CommandSource source, String instanceId, String line) {
    source.sendMessage(
        CommandMessages.prefix()
            .append(Component.text("[" + instanceId + "] ", NamedTextColor.DARK_AQUA))
            .append(Component.text(bound(line), NamedTextColor.GRAY)));
  }

  private static void sendDropped(CommandSource source, String instanceId, long droppedLines) {
    source.sendMessage(
        CommandMessages.message(
            "Skipped "
                + droppedLines
                + " expired retained line(s) while reading console output from "
                + instanceId
                + ".",
            NamedTextColor.YELLOW));
  }

  private static String bound(String line) {
    String normalized = line == null ? "" : line.replace('\r', ' ').replace('\n', ' ').strip();
    if (normalized.length() <= DISPLAY_LINE_LENGTH) {
      return normalized;
    }
    return normalized.substring(0, DISPLAY_LINE_LENGTH - 1) + "\u2026";
  }

  private static boolean canDeliver(CommandSource source) {
    return !(source instanceof Player player) || player.isActive();
  }

  private static Object key(CommandSource source) {
    return source instanceof Player player ? player.getUniqueId() : source;
  }

  private record CaptureKey(Object source, String instanceId) {}

  interface OutputFeed {

    String id();

    long cursor();

    InstanceOutputBatch awaitAfter(
        long cursor, int maximumLines, Duration quietPeriod, Duration timeout);

    CompletableFuture<Integer> stopped();
  }

  private record ManagedOutputFeed(ManagedInstance instance) implements OutputFeed {

    @Override
    public String id() {
      return instance.id();
    }

    @Override
    public long cursor() {
      return instance.outputCursor();
    }

    @Override
    public InstanceOutputBatch awaitAfter(
        long cursor, int maximumLines, Duration quietPeriod, Duration timeout) {
      return instance.awaitOutputAfter(cursor, maximumLines, quietPeriod, timeout);
    }

    @Override
    public CompletableFuture<Integer> stopped() {
      return instance.stoppedFuture();
    }
  }

  private final class FollowSession {

    private final Object key;
    private final CommandSource source;
    private final OutputFeed feed;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private Thread thread;

    private FollowSession(Object key, CommandSource source, OutputFeed feed) {
      this.key = key;
      this.source = source;
      this.feed = feed;
    }

    private void start() {
      thread =
          Thread.ofVirtual()
              .name("sls-lite-console-follow-" + feed.id())
              .start(() -> runFollow(this));
    }

    private void stop() {
      stopped.set(true);
      Thread current = thread;
      if (current != null) {
        current.interrupt();
      }
    }
  }
}
