package net.slimelabs.examples.slslite.paper;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class SLSLiteBackendSenderExample extends JavaPlugin {

  private static final String CHANNEL = "slslite:request";

  @Override
  public void onEnable() {
    getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
  }

  @Override
  public boolean onCommand(
      CommandSender sender, Command command, String label, String[] arguments) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage("This example requires a carrier player.");
      return true;
    }
    if (arguments.length == 3 && arguments[0].equalsIgnoreCase("join")) {
      player.sendPluginMessage(
          this,
          CHANNEL,
          SLSLiteProtocolV1.matchmake(UUID.randomUUID(), arguments[1], arguments[2]));
      player.sendMessage("Sent SLS-LITE matchmaking request for " + arguments[1] + "/" + arguments[2] + ".");
      return true;
    }
    if (arguments.length >= 2 && arguments[0].equalsIgnoreCase("command")) {
      String forwarded = String.join(" ", Arrays.copyOfRange(arguments, 1, arguments.length));
      if (!forwarded.toLowerCase(Locale.ROOT).startsWith("sls")) {
        player.sendMessage("This example only sends SLS command roots.");
        return true;
      }
      player.sendPluginMessage(
          this, CHANNEL, SLSLiteProtocolV1.command(UUID.randomUUID(), forwarded));
      player.sendMessage("Sent SLS-LITE command request.");
      return true;
    }
    return false;
  }

  static final class SLSLiteProtocolV1 {

    static final int VERSION = 1;
    static final int ACTION_MATCHMAKE = 1;
    static final int ACTION_COMMAND = 2;
    static final int MAX_PAYLOAD_BYTES = 4096;
    static final int MAX_IDENTIFIER_BYTES = 128;
    static final int MAX_COMMAND_BYTES = 512;
    private static final Pattern IDENTIFIER =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private SLSLiteProtocolV1() {}

    static byte[] matchmake(UUID requestId, String registry, String target) {
      return encode(
          ACTION_MATCHMAKE,
          requestId,
          field(registry, MAX_IDENTIFIER_BYTES, true),
          field(target, MAX_IDENTIFIER_BYTES, true));
    }

    static byte[] command(UUID requestId, String command) {
      return encode(ACTION_COMMAND, requestId, field(command, MAX_COMMAND_BYTES, false));
    }

    private static byte[] encode(int action, UUID requestId, String... values) {
      if (requestId == null
          || (requestId.getMostSignificantBits() == 0L
              && requestId.getLeastSignificantBits() == 0L)) {
        throw new IllegalArgumentException("Request ID must be a non-nil UUID");
      }
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
        byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_PAYLOAD_BYTES) {
          throw new IllegalArgumentException("Protocol payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }
        return payload;
      } catch (IOException exception) {
        throw new IllegalStateException("Unable to encode an in-memory request", exception);
      }
    }

    private static String field(String value, int maximumBytes, boolean identifier) {
      if (value == null
          || value.isBlank()
          || value.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
        throw new IllegalArgumentException(
            "Protocol field must contain 1-" + maximumBytes + " UTF-8 bytes");
      }
      for (int index = 0; index < value.length(); index++) {
        if (Character.isISOControl(value.charAt(index))) {
          throw new IllegalArgumentException("Protocol field contains control characters");
        }
      }
      if (identifier && !IDENTIFIER.matcher(value).matches()) {
        throw new IllegalArgumentException("Protocol identifier contains unsupported characters");
      }
      return value;
    }
  }
}
