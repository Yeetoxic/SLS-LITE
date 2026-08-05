package net.slimelabs.slslite.messaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BackendMessageProtocolTest {

  @Test
  void roundTripsMatchmakingAndCommandRequests() throws Exception {
    UUID matchmakingId = UUID.randomUUID();
    UUID commandId = UUID.randomUUID();

    assertEquals(
        new BackendMessageRequest.Matchmake(matchmakingId, "minigames", "arena"),
        BackendMessageProtocol.decode(
            BackendMessageProtocol.encodeMatchmake(matchmakingId, "minigames", "arena")));
    assertEquals(
        new BackendMessageRequest.Command(commandId, "sls list"),
        BackendMessageProtocol.decode(BackendMessageProtocol.encodeCommand(commandId, "sls list")));
  }

  @Test
  void rejectsUnsupportedVersionActionNilIdAndTrailingData() {
    byte[] valid = BackendMessageProtocol.encodeMatchmake(UUID.randomUUID(), "minigames", "arena");
    byte[] version = valid.clone();
    version[0] = 2;
    byte[] action = valid.clone();
    action[1] = 99;
    byte[] nilId = valid.clone();
    Arrays.fill(nilId, 2, 18, (byte) 0);
    byte[] trailing = Arrays.copyOf(valid, valid.length + 1);

    assertThrows(BackendMessageProtocol.ProtocolException.class, () -> decode(version));
    assertThrows(BackendMessageProtocol.ProtocolException.class, () -> decode(action));
    assertThrows(BackendMessageProtocol.ProtocolException.class, () -> decode(nilId));
    assertThrows(BackendMessageProtocol.ProtocolException.class, () -> decode(trailing));
  }

  @Test
  void rejectsTruncatedOversizedAndInvalidUtf8Payloads() {
    byte[] valid = BackendMessageProtocol.encodeCommand(UUID.randomUUID(), "sls list");
    byte[] truncated = Arrays.copyOf(valid, valid.length - 1);
    byte[] oversized = new byte[BackendMessageProtocol.MAX_PAYLOAD_BYTES + 1];
    byte[] invalidUtf8 = valid.clone();
    invalidUtf8[invalidUtf8.length - 1] = (byte) 0xFF;

    assertThrows(BackendMessageProtocol.ProtocolException.class, () -> decode(truncated));
    assertThrows(BackendMessageProtocol.ProtocolException.class, () -> decode(oversized));
    assertThrows(BackendMessageProtocol.ProtocolException.class, () -> decode(invalidUtf8));
  }

  @Test
  void encodingIsStableForSameRequest() {
    UUID requestId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    assertArrayEquals(
        BackendMessageProtocol.encodeCommand(requestId, "sls join minigames arena"),
        BackendMessageProtocol.encodeCommand(requestId, "sls join minigames arena"));
  }

  @Test
  void maintainedPaperSenderDeclaresTheSameProtocolConstants() throws Exception {
    String sender =
        Files.readString(
            Path.of(
                "examples/paper-backend-sender/src/main/java/net/slimelabs/examples/slslite/paper/SLSLiteBackendSenderExample.java"));

    assertEquals(true, sender.contains("CHANNEL = \"slslite:request\""));
    assertEquals(true, sender.contains("VERSION = 1"));
    assertEquals(true, sender.contains("ACTION_MATCHMAKE = 1"));
    assertEquals(true, sender.contains("ACTION_COMMAND = 2"));
    assertEquals(true, sender.contains("MAX_PAYLOAD_BYTES = 4096"));
    assertEquals(true, sender.contains("MAX_IDENTIFIER_BYTES = 128"));
    assertEquals(true, sender.contains("MAX_COMMAND_BYTES = 512"));
  }

  private static BackendMessageRequest decode(byte[] payload) throws Exception {
    return BackendMessageProtocol.decode(payload);
  }
}
