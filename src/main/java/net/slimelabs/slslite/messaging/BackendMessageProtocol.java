package net.slimelabs.slslite.messaging;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

final class BackendMessageProtocol {

  static final int VERSION = 1;
  static final int MAX_PAYLOAD_BYTES = 4096;
  static final int MAX_IDENTIFIER_BYTES = 128;
  static final int MAX_COMMAND_BYTES = 512;
  private static final int ACTION_MATCHMAKE = 1;
  private static final int ACTION_COMMAND = 2;
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

  private BackendMessageProtocol() {}

  static BackendMessageRequest decode(byte[] payload) throws ProtocolException {
    if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
      throw new ProtocolException(
          "payload size must be between 1 and " + MAX_PAYLOAD_BYTES + " bytes");
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
      int version = input.readUnsignedByte();
      if (version != VERSION) {
        throw new ProtocolException("unsupported protocol version " + version);
      }
      int action = input.readUnsignedByte();
      UUID requestId = new UUID(input.readLong(), input.readLong());
      if (requestId.getMostSignificantBits() == 0L && requestId.getLeastSignificantBits() == 0L) {
        throw new ProtocolException("request id must not be nil");
      }
      BackendMessageRequest request =
          switch (action) {
            case ACTION_MATCHMAKE ->
                new BackendMessageRequest.Matchmake(
                    requestId,
                    identifier(readString(input, MAX_IDENTIFIER_BYTES, "registry"), "registry"),
                    identifier(readString(input, MAX_IDENTIFIER_BYTES, "target"), "target"));
            case ACTION_COMMAND ->
                new BackendMessageRequest.Command(
                    requestId, readString(input, MAX_COMMAND_BYTES, "command"));
            default -> throw new ProtocolException("unknown action " + action);
          };
      if (input.available() != 0) {
        throw new ProtocolException("payload contains trailing data");
      }
      return request;
    } catch (EOFException exception) {
      throw new ProtocolException("payload ended before all required fields", exception);
    } catch (IOException exception) {
      throw new ProtocolException("unable to decode payload", exception);
    }
  }

  static byte[] encodeMatchmake(UUID requestId, String registry, String target) {
    return encode(ACTION_MATCHMAKE, requestId, registry, target);
  }

  static byte[] encodeCommand(UUID requestId, String command) {
    return encode(ACTION_COMMAND, requestId, command);
  }

  private static byte[] encode(int action, UUID requestId, String... values) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeByte(VERSION);
        output.writeByte(action);
        output.writeLong(requestId.getMostSignificantBits());
        output.writeLong(requestId.getLeastSignificantBits());
        for (String value : values) {
          byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
          output.writeShort(encoded.length);
          output.write(encoded);
        }
      }
      return bytes.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode in-memory backend message", exception);
    }
  }

  private static String readString(DataInputStream input, int maximumBytes, String field)
      throws IOException, ProtocolException {
    int length = input.readUnsignedShort();
    if (length == 0 || length > maximumBytes) {
      throw new ProtocolException(
          field + " length must be between 1 and " + maximumBytes + " bytes");
    }
    byte[] encoded = input.readNBytes(length);
    if (encoded.length != length) {
      throw new EOFException(field + " is truncated");
    }
    try {
      String decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(encoded))
              .toString();
      if (decoded.isBlank()) {
        throw new ProtocolException(field + " must not be blank");
      }
      return decoded;
    } catch (CharacterCodingException exception) {
      throw new ProtocolException(field + " is not valid UTF-8", exception);
    }
  }

  private static String identifier(String value, String field) throws ProtocolException {
    if (!IDENTIFIER.matcher(value).matches()) {
      throw new ProtocolException(field + " contains unsupported characters");
    }
    return value;
  }

  static final class ProtocolException extends Exception {

    private ProtocolException(String message) {
      super(message);
    }

    private ProtocolException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
