package net.slimelabs.slslite.api.event;

import java.time.Instant;

/** Marker for events published through the versioned SLS-LITE API. */
public sealed interface SLSLiteEvent
    permits ApiShutdownEvent,
        CatalogReloadEvent,
        InstanceFailureEvent,
        InstanceLifecycleEvent,
        LobbyStatusEvent,
        PlayerMatchmakingEvent,
        ReconciliationEvent,
        SoftwareInstallationEvent {

  /** Returns the positive, monotonically increasing provider-lifetime event sequence. */
  long sequence();

  /** Returns the instant associated with the published state transition. */
  Instant occurredAt();
}
