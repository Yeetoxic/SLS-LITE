package net.slimelabs.slslite.api.event;

/** Lifecycle status of one lobby provider. */
public enum LobbyServiceStatus {
  EXTERNAL,
  STARTING,
  READY,
  RECOVERING,
  OFFLINE,
  SHUTTING_DOWN
}
