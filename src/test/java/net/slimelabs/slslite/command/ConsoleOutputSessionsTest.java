package net.slimelabs.slslite.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.command.CommandSource;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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

    assertFalse(sessions.follow(source, first));
    assertTrue(sessions.isFollowing(source));
    assertTrue(sessions.follow(source, second));
    assertEquals(1, sessions.followerCount());
    second.offer(List.of("live line"));

    assertTrue(delivered.await(1, TimeUnit.SECONDS));
    assertTrue(plainText(messages.getFirst()).contains("[server.second] live line"));
    assertTrue(sessions.unfollow(source));
    assertFalse(sessions.isFollowing(source));
    assertEquals(0, sessions.followerCount());
    sessions.close();
  }

  @Test
  void closeStopsAllFollowersAndFurtherStartsAreRejected() {
    ConsoleOutputSessions sessions = new ConsoleOutputSessions();
    CommandSource source = source(new ArrayList<>(), new CountDownLatch(0));
    FakeFeed feed = new FakeFeed("server.abcdef");
    assertFalse(sessions.follow(source, feed));

    sessions.close();

    assertEquals(0, sessions.followerCount());
    assertFalse(sessions.follow(source, feed));
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
      long next = cursor.addAndGet(lines.size());
      batches.add(new InstanceOutputBatch(next, lines, 0));
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
