package net.slimelabs.slslite.api.event;

import java.time.Instant;

/** Marker for events published through the versioned SLS-LITE API. */
public sealed interface SLSLiteEvent permits InstanceLifecycleEvent {

  long sequence();

  Instant occurredAt();
}
