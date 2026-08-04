package net.slimelabs.slslite.api;

import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.slimelabs.slslite.api.event.SLSLiteEvent;
import net.slimelabs.slslite.api.event.Subscription;

/** Owned lifecycle boundary for one trusted extension's callbacks and subscriptions. */
public interface ExtensionContext extends AutoCloseable {

  String namespace();

  boolean closed();

  Subscription subscribe(Consumer<? super SLSLiteEvent> listener);

  <T> Subscription onComplete(
      CompletionStage<? extends T> stage, BiConsumer<? super T, ? super Throwable> callback);

  @Override
  void close();
}
