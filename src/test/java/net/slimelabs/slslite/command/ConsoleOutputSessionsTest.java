package net.slimelabs.slslite.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.slimelabs.slslite.instance.diagnostics.InstanceOutputBatch;
import org.junit.jupiter.api.Test;

final class ConsoleOutputSessionsTest {

  @Test
  void oneShotCaptureDeliversOnlyTheBoundedBatchOffThread() throws Exception {
    ConsoleOutputSessions sessions = new ConsoleOutputSessions();
    FakeFeed feed = new FakeFeed("server.abcdef");
    feed.offer(List.of("first", "x".repeat(ConsoleOutputSessions.DISPLAY_LINE_LENGTH + 10)));
    List<Component> messages = new ArrayList<>();
    CountDownLatch delivered = new CountDownLatch(2);

    sessions.capture(source(messages, delivered), feed, 0);

    assertTrue(delivered.await(1, TimeUnit.SECONDS));
    assertEquals(2, messages.size());
    assertTrue(plainText(messages.getFirst()).contains("[server.abcdef] first"));
    assertTrue(plainText(messages.getLast()).endsWith("\u2026"));
    sessions.close();
  }

  @Test
  void followIsOptInReplaceableAndCanBeStoppedWithoutBlocking() throws Exception {
    ConsoleOutputSessions sessions = new ConsoleOutputSessions();
    FakeFeed first = new FakeFeed("server.first");
    FakeFeed second = new FakeFeed("server.second");
    List<Component> messages = new ArrayList<>();
    CountDownLatch delivered = new CountDownLatch(1);
    CommandSource source = source(messages, delivered);

    assertEquals(
        ConsoleOutputSessions.FollowStatus.STARTED, sessions.follow(source, first).status());
    assertTrue(sessions.isFollowing(source));
    ConsoleOutputSessions.FollowResult moved = sessions.follow(source, second);
    assertEquals(ConsoleOutputSessions.FollowStatus.MOVED, moved.status());
    assertEquals("server.first", moved.previousInstanceId());
    assertEquals(1, sessions.followerCount());
    second.offer(List.of("live line"));

    assertTrue(delivered.await(1, TimeUnit.SECONDS));
    assertTrue(plainText(messages.getFirst()).contains("[server.second] live line"));
    assertEquals(Optional.of("server.second"), sessions.unfollow(source));
    assertFalse(sessions.isFollowing(source));
    assertEquals(0, sessions.followerCount());
    sessions.close();
  }

  @Test
  void closeStopsAllFollowersAndFurtherStartsAreRejected() {
    ConsoleOutputSessions sessions = new ConsoleOutputSessions();
    CommandSource source = source(new ArrayList<>(), new CountDownLatch(0));
    FakeFeed feed = new FakeFeed("server.abcdef");
    assertEquals(
        ConsoleOutputSessions.FollowStatus.STARTED, sessions.follow(source, feed).status());

    sessions.close();

    assertEquals(0, sessions.followerCount());
    assertEquals(ConsoleOutputSessions.FollowStatus.CLOSED, sessions.follow(source, feed).status());
  }

  @Test
  void globalFollowerCapacityIsHardBoundedAndSwitchingStillWorks() {
    ConsoleOutputSessions sessions = new ConsoleOutputSessions();
    List<CommandSource> sources = new ArrayList<>();
    for (int index = 0; index < ConsoleOutputSessions.MAX_FOLLOWERS; index++) {
      CommandSource source = source(new ArrayList<>(), new CountDownLatch(0));
      sources.add(source);
      assertEquals(
          ConsoleOutputSessions.FollowStatus.STARTED,
          sessions.follow(source, new FakeFeed("server-" + index)).status());
    }
    CommandSource overflow = source(new ArrayList<>(), new CountDownLatch(0));
    assertEquals(
        ConsoleOutputSessions.FollowStatus.CAPACITY,
        sessions.follow(overflow, new FakeFeed("overflow")).status());
    assertEquals(
        ConsoleOutputSessions.FollowStatus.MOVED,
        sessions.follow(sources.getFirst(), new FakeFeed("replacement")).status());
    assertEquals(ConsoleOutputSessions.MAX_FOLLOWERS, sessions.followerCount());
    sessions.close();
  }

  @Test
  void permissionLossAndInstanceStopEndSessionsWithOneReason() throws Exception {
    ConsoleOutputSessions sessions = new ConsoleOutputSessions();
    AtomicBoolean allowed = new AtomicBoolean(true);
    FakeFeed permissionFeed = new FakeFeed("permission");
    List<Component> permissionMessages = new ArrayList<>();
    CountDownLatch permissionEnded = new CountDownLatch(1);
    CommandSource permissionSource = source(permissionMessages, permissionEnded);
    assertEquals(
        ConsoleOutputSessions.FollowStatus.STARTED,
        sessions.follow(permissionSource, permissionFeed, allowed::get).status());
    allowed.set(false);
    permissionFeed.offer(List.of("wake"));
    assertTrue(permissionEnded.await(2, TimeUnit.SECONDS));
    assertTrue(plainText(permissionMessages.getLast()).contains("permission was lost"));

    FakeFeed stoppedFeed = new FakeFeed("stopped");
    List<Component> stoppedMessages = new ArrayList<>();
    CountDownLatch stopped = new CountDownLatch(1);
    assertEquals(
        ConsoleOutputSessions.FollowStatus.STARTED,
        sessions.follow(source(stoppedMessages, stopped), stoppedFeed).status());
    stoppedFeed.stop();
    assertTrue(stopped.await(2, TimeUnit.SECONDS));
    assertTrue(plainText(stoppedMessages.getLast()).contains("instance stopped"));
    sessions.close();
  }

  @Test
  void noisyBatchesAreTruncatedAndCoalesced() throws Exception {
    ConsoleOutputSessions sessions = new ConsoleOutputSessions();
    FakeFeed feed = new FakeFeed("noisy");
    List<Component> messages = new ArrayList<>();
    CountDownLatch delivered = new CountDownLatch(ConsoleOutputSessions.FOLLOW_BATCH_LINES + 1);
    sessions.follow(source(messages, delivered), feed);
    feed.offer(java.util.stream.IntStream.range(0, 30).mapToObj(index -> "line-" + index).toList());

    assertTrue(delivered.await(2, TimeUnit.SECONDS));
    assertEquals(ConsoleOutputSessions.FOLLOW_BATCH_LINES + 1, messages.size());
    assertTrue(plainText(messages.getLast()).contains("Coalesced 24 noisy output line(s)"));
    sessions.close();
  }

  @Test
  void retentionLossIsSummarizedAndDisconnectRemovesThePlayerSession() throws Exception {
    ConsoleOutputSessions sessions = new ConsoleOutputSessions();
    FakeFeed feed = new FakeFeed("retention");
    List<Component> messages = new ArrayList<>();
    CountDownLatch delivered = new CountDownLatch(2);
    Player player = player(UUID.randomUUID(), messages, delivered);
    sessions.follow(player, feed);
    feed.offer(List.of("retained"), 17);

    assertTrue(delivered.await(2, TimeUnit.SECONDS));
    assertTrue(plainText(messages.getFirst()).contains("Skipped 17 expired retained line(s)"));
    sessions.remove(player.getUniqueId());
    assertEquals(0, sessions.followerCount());
    sessions.close();
  }

  @Test
  void oneShotCapturesAreBoundedAndDuplicateRequestsAreRejected() {
    ConsoleOutputSessions sessions = new ConsoleOutputSessions();
    CommandSource source = source(new ArrayList<>(), new CountDownLatch(0));

    assertTrue(sessions.capture(source, new FakeFeed("duplicate"), 0));
    assertFalse(sessions.capture(source, new FakeFeed("duplicate"), 0));
    for (int index = 1; index < ConsoleOutputSessions.MAX_CONCURRENT_CAPTURES; index++) {
      assertTrue(sessions.capture(source, new FakeFeed("server-" + index), 0));
    }
    assertFalse(sessions.capture(source, new FakeFeed("over-capacity"), 0));

    sessions.close();
  }

  private static CommandSource source(List<Component> messages, CountDownLatch delivered) {
    return (CommandSource)
        Proxy.newProxyInstance(
            CommandSource.class.getClassLoader(),
            new Class<?>[] {CommandSource.class},
            (proxy, method, arguments) -> {
              if ("sendMessage".equals(method.getName())) {
                synchronized (messages) {
                  messages.add((Component) arguments[0]);
                }
                delivered.countDown();
              }
              return defaultValue(method.getReturnType());
            });
  }

  private static Player player(UUID playerId, List<Component> messages, CountDownLatch delivered) {
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> {
              if ("getUniqueId".equals(method.getName())) {
                return playerId;
              }
              if ("isActive".equals(method.getName())) {
                return true;
              }
              if ("sendMessage".equals(method.getName())) {
                synchronized (messages) {
                  messages.add((Component) arguments[0]);
                }
                delivered.countDown();
              }
              return defaultValue(method.getReturnType());
            });
  }

  private static String plainText(Component component) {
    StringBuilder text = new StringBuilder();
    appendPlainText(component, text);
    return text.toString();
  }

  private static void appendPlainText(Component component, StringBuilder output) {
    if (component instanceof TextComponent textComponent) {
      output.append(textComponent.content());
    }
    component.children().forEach(child -> appendPlainText(child, output));
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class || type == short.class || type == byte.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    return null;
  }

  private static final class FakeFeed implements ConsoleOutputSessions.OutputFeed {

    private final String id;
    private final AtomicLong cursor = new AtomicLong();
    private final LinkedBlockingQueue<InstanceOutputBatch> batches = new LinkedBlockingQueue<>();
    private final CompletableFuture<Integer> stopped = new CompletableFuture<>();

    private FakeFeed(String id) {
      this.id = id;
    }

    private void offer(List<String> lines) {
      offer(lines, 0);
    }

    private void offer(List<String> lines, long droppedLines) {
      long next = cursor.addAndGet(lines.size());
      batches.add(new InstanceOutputBatch(next, lines, droppedLines));
    }

    private void stop() {
      stopped.complete(0);
      batches.offer(new InstanceOutputBatch(cursor.get(), List.of(), 0));
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public long cursor() {
      return cursor.get() - batches.stream().mapToLong(batch -> batch.lines().size()).sum();
    }

    @Override
    public InstanceOutputBatch awaitAfter(
        long afterCursor, int maximumLines, Duration quietPeriod, Duration timeout) {
      try {
        InstanceOutputBatch batch = batches.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return batch == null ? new InstanceOutputBatch(afterCursor, List.of(), 0) : batch;
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return new InstanceOutputBatch(afterCursor, List.of(), 0);
      }
    }

    @Override
    public CompletableFuture<Integer> stopped() {
      return stopped;
    }
  }
}
