package net.slimelabs.slslite.api.event;

/** Idempotent handle for an API event subscription. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {

  /** Idempotently removes the owned registration. */
  @Override
  void close();
}
