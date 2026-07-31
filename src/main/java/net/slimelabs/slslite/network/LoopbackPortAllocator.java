package net.slimelabs.slslite.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.LinkedHashSet;
import java.util.Set;

public final class LoopbackPortAllocator {

  private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

  private final int rangeStart;
  private final int rangeEnd;
  private final Set<Integer> reservations = new LinkedHashSet<>();

  public LoopbackPortAllocator(int rangeStart, int rangeEnd) {
    if (rangeStart < 1024 || rangeStart > 65535) {
      throw new IllegalArgumentException("rangeStart must be between 1024 and 65535");
    }
    if (rangeEnd < rangeStart || rangeEnd > 65535) {
      throw new IllegalArgumentException("rangeEnd must be between rangeStart and 65535");
    }
    this.rangeStart = rangeStart;
    this.rangeEnd = rangeEnd;
  }

  public synchronized int allocate() throws PortAllocationException {
    for (int port = rangeStart; port <= rangeEnd; port++) {
      if (!reservations.contains(port) && isAvailable(port)) {
        reservations.add(port);
        return port;
      }
    }
    throw new PortAllocationException(
        "No available loopback port in range " + rangeStart + "-" + rangeEnd);
  }

  public synchronized boolean release(int port) {
    return reservations.remove(port);
  }

  public synchronized Set<Integer> reservations() {
    return Set.copyOf(reservations);
  }

  private static boolean isAvailable(int port) {
    try (ServerSocket socket = new ServerSocket()) {
      socket.setReuseAddress(false);
      socket.bind(new InetSocketAddress(LOOPBACK, port));
      return true;
    } catch (IOException exception) {
      return false;
    }
  }
}
