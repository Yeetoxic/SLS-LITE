package net.slimelabs.slslite.api;

import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.slimelabs.slslite.api.event.SLSLiteEvent;
import net.slimelabs.slslite.api.event.Subscription;

/** Owned lifecycle boundary for one trusted extension's callbacks and subscriptions. */
public interface ExtensionContext extends AutoCloseable {

  /** Returns this context's normalized, exclusively owned namespace. */
  String namespace();

  /** Returns whether this context has released its registrations. */
  boolean closed();

  /**
   * Registers an owned event listener.
   *
   * @param listener non-blocking event consumer
   * @return idempotent handle that removes only this registration
   */
  Subscription subscribe(Consumer<? super SLSLiteEvent> listener);

  /**
   * Selects and deeply copies this extension's annotation object from a blueprint.
   *
   * @param blueprint immutable blueprint view
   * @return bounded annotation values, or an empty value map when absent
   */
  NamespacedAnnotations annotations(BlueprintView blueprint);

  /**
   * Registers an owned action delivered after matching instances reach READY.
   *
   * @param action non-blocking action consumer
   * @return idempotent registration handle
   */
  Subscription onInstanceReady(Consumer<? super InstanceReadyAction> action);

  /**
   * Registers an owned action delivered after a queue request actually transfers its player.
   *
   * @param action non-blocking action consumer
   * @return idempotent registration handle
   */
  Subscription onPostTransfer(Consumer<? super PostTransferAction> action);

  /**
   * Owns a completion callback so closing this context suppresses callbacks that have not begun.
   *
   * @param stage asynchronous API result
   * @param callback completion consumer receiving either a result or failure
   * @param <T> result type
   * @return idempotent registration handle
   */
  <T> Subscription onComplete(
      CompletionStage<? extends T> stage, BiConsumer<? super T, ? super Throwable> callback);

  /** Releases every subscription, action, and incomplete completion callback owned here. */
  @Override
  void close();
}
