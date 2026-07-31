package net.slimelabs.slslite.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class LoopbackPortAllocatorTest {

  @Test
  void reservesAndReleasesAvailablePort() throws Exception {
    int port = findAvailablePort();
    LoopbackPortAllocator allocator = new LoopbackPortAllocator(port, port);

    assertEquals(port, allocator.allocate());
    assertTrue(allocator.reservations().contains(port));
    assertThrows(PortAllocationException.class, allocator::allocate);
    assertTrue(allocator.release(port));
    assertFalse(allocator.release(port));
    assertEquals(port, allocator.allocate());
  }

  @Test
  void skipsPortAlreadyBoundOnLoopback() throws Exception {
    try (ServerSocket occupied = new ServerSocket()) {
      occupied.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      int port = occupied.getLocalPort();
      LoopbackPortAllocator allocator = new LoopbackPortAllocator(port, port);

      assertThrows(PortAllocationException.class, allocator::allocate);
    }
  }

  private static int findAvailablePort() throws Exception {
    try (ServerSocket socket = new ServerSocket()) {
      socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      return socket.getLocalPort();
    }
  }
}
