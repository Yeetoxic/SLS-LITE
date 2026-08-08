package net.slimelabs.slslite.instance;

public final class InstanceOperationException extends Exception {

  public enum Kind {
    GENERIC,
    BLUEPRINT_CAPACITY
  }

  private final Kind kind;
  private final String instanceId;
  private final int currentPlayers;
  private final int maxPlayers;

  public InstanceOperationException(String message) {
    this(message, null);
  }

  public InstanceOperationException(String message, Throwable cause) {
    super(message, cause);
    this.kind = Kind.GENERIC;
    this.instanceId = null;
    this.currentPlayers = -1;
    this.maxPlayers = -1;
  }

  private InstanceOperationException(
      String message, Kind kind, String instanceId, int currentPlayers, int maxPlayers) {
    super(message);
    this.kind = kind;
    this.instanceId = instanceId;
    this.currentPlayers = currentPlayers;
    this.maxPlayers = maxPlayers;
  }

  public static InstanceOperationException blueprintCapacity(
      String instanceId, int currentPlayers, int maxPlayers) {
    return new InstanceOperationException(
        "Instance is full: " + instanceId,
        Kind.BLUEPRINT_CAPACITY,
        instanceId,
        currentPlayers,
        maxPlayers);
  }

  public Kind kind() {
    return kind;
  }

  public String instanceId() {
    return instanceId;
  }

  public int currentPlayers() {
    return currentPlayers;
  }

  public int maxPlayers() {
    return maxPlayers;
  }
}
