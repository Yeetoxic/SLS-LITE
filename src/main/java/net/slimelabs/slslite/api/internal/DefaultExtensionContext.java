package net.slimelabs.slslite.api.internal;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.slimelabs.slslite.api.BlueprintReadinessChecker;
import net.slimelabs.slslite.api.BlueprintView;
import net.slimelabs.slslite.api.ExtensionContext;
import net.slimelabs.slslite.api.ExtensionDiagnosticContributor;
import net.slimelabs.slslite.api.InstanceReadyAction;
import net.slimelabs.slslite.api.InstanceView;
import net.slimelabs.slslite.api.NamespacedAnnotations;
import net.slimelabs.slslite.api.PostTransferAction;
import net.slimelabs.slslite.api.QueueTicket;
import net.slimelabs.slslite.api.SLSLiteApiException;
import net.slimelabs.slslite.api.event.SLSLiteEvent;
import net.slimelabs.slslite.api.event.Subscription;
import org.slf4j.Logger;

final class DefaultExtensionContext implements ExtensionContext {

  private static final int MAX_REGISTRATIONS = 256;

  private final String namespace;
  private final DefaultSLSLiteApi api;
  private final Logger logger;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicInteger registrationCount = new AtomicInteger();
  private final Set<OwnedRegistration> registrations = ConcurrentHashMap.newKeySet();
  private final List<OwnedActionRegistration<InstanceReadyAction>> instanceReadyActions =
      new CopyOnWriteArrayList<>();
  private final List<OwnedActionRegistration<PostTransferAction>> postTransferActions =
      new CopyOnWriteArrayList<>();

  DefaultExtensionContext(String namespace, DefaultSLSLiteApi api, Logger logger) {
    this.namespace = namespace;
    this.api = api;
    this.logger = logger;
  }

  @Override
  public String namespace() {
    return namespace;
  }

  @Override
  public boolean closed() {
    return closed.get() || api.closed();
  }

  @Override
  public Subscription subscribe(Consumer<? super SLSLiteEvent> listener) {
    java.util.Objects.requireNonNull(listener, "listener");
    reserve();
    OwnedEventRegistration registration = new OwnedEventRegistration(listener);
    registrations.add(registration);
    try {
      registration.install(api.subscribe(registration::accept));
      rejectIfClosed(registration);
      return registration;
    } catch (RuntimeException exception) {
      registration.close();
      throw exception;
    }
  }

  @Override
  public NamespacedAnnotations annotations(BlueprintView blueprint) {
    if (closed()) {
      throw closedFailure();
    }
    java.util.Objects.requireNonNull(blueprint, "blueprint");
    Object configured = blueprint.annotations().get(namespace);
    if (configured == null) {
      return new NamespacedAnnotations(namespace, java.util.Map.of());
    }
    if (!(configured instanceof java.util.Map<?, ?> map)) {
      throw new IllegalArgumentException(
          "Blueprint annotation namespace " + namespace + " must be an object");
    }
    java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
    map.forEach(
        (key, value) -> {
          if (!(key instanceof String stringKey)) {
            throw new IllegalArgumentException("Blueprint annotation keys must be strings");
          }
          values.put(stringKey, value);
        });
    return new NamespacedAnnotations(namespace, values);
  }

  @Override
  public Subscription onBlueprintReadiness(BlueprintReadinessChecker checker) {
    java.util.Objects.requireNonNull(checker, "checker");
    reserve();
    OwnedBlueprintReadinessRegistration registration = new OwnedBlueprintReadinessRegistration();
    registrations.add(registration);
    try {
      registration.install(api.registerBlueprintReadiness(namespace, checker));
      rejectIfClosed(registration);
      return registration;
    } catch (RuntimeException exception) {
      registration.close();
      throw exception;
    }
  }

  @Override
  public Subscription onDiagnostics(ExtensionDiagnosticContributor contributor) {
    java.util.Objects.requireNonNull(contributor, "contributor");
    reserve();
    OwnedDiagnosticRegistration registration = new OwnedDiagnosticRegistration();
    registrations.add(registration);
    try {
      registration.install(api.registerDiagnostics(namespace, contributor));
      rejectIfClosed(registration);
      return registration;
    } catch (RuntimeException exception) {
      registration.close();
      throw exception;
    }
  }

  @Override
  public Subscription onInstanceReady(Consumer<? super InstanceReadyAction> action) {
    return registerAction(instanceReadyActions, action);
  }

  @Override
  public Subscription onPostTransfer(Consumer<? super PostTransferAction> action) {
    return registerAction(postTransferActions, action);
  }

  @Override
  public <T> Subscription onComplete(
      CompletionStage<? extends T> stage, BiConsumer<? super T, ? super Throwable> callback) {
    java.util.Objects.requireNonNull(stage, "stage");
    java.util.Objects.requireNonNull(callback, "callback");
    reserve();
    OwnedFutureRegistration<T> registration = new OwnedFutureRegistration<>(callback);
    registrations.add(registration);
    try {
      rejectIfClosed(registration);
      stage.whenComplete(registration::complete);
      return registration;
    } catch (RuntimeException exception) {
      registration.close();
      throw exception;
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    registrations.forEach(OwnedRegistration::close);
    api.release(this);
  }

  void closeFromApi() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    registrations.forEach(OwnedRegistration::close);
  }

  Runnable captureInstanceReady(
      InstanceView instance, BlueprintView blueprint, java.time.Instant at) {
    List<OwnedActionRegistration<InstanceReadyAction>> recipients =
        List.copyOf(instanceReadyActions);
    if (recipients.isEmpty()) {
      return null;
    }
    InstanceReadyAction action = new InstanceReadyAction(instance, annotations(blueprint), at);
    return () -> recipients.forEach(recipient -> recipient.accept(action));
  }

  Runnable capturePostTransfer(
      QueueTicket ticket, boolean instanceCreated, BlueprintView blueprint, java.time.Instant at) {
    List<OwnedActionRegistration<PostTransferAction>> recipients = List.copyOf(postTransferActions);
    if (recipients.isEmpty()) {
      return null;
    }
    PostTransferAction action =
        new PostTransferAction(ticket, instanceCreated, annotations(blueprint), at);
    return () -> recipients.forEach(recipient -> recipient.accept(action));
  }

  private <T> Subscription registerAction(
      List<OwnedActionRegistration<T>> actions, Consumer<? super T> action) {
    java.util.Objects.requireNonNull(action, "action");
    reserve();
    OwnedActionRegistration<T> registration = new OwnedActionRegistration<>(actions, action);
    registrations.add(registration);
    actions.add(registration);
    try {
      rejectIfClosed(registration);
      return registration;
    } catch (RuntimeException exception) {
      registration.close();
      throw exception;
    }
  }

  private void reserve() {
    if (closed()) {
      throw closedFailure();
    }
    if (registrationCount.incrementAndGet() > MAX_REGISTRATIONS) {
      registrationCount.decrementAndGet();
      throw new SLSLiteApiException(
          SLSLiteApiException.Code.REJECTED,
          "Extension context registration limit reached for " + namespace);
    }
    if (closed()) {
      registrationCount.decrementAndGet();
      throw closedFailure();
    }
  }

  private void rejectIfClosed(OwnedRegistration registration) {
    if (closed()) {
      registration.close();
      throw closedFailure();
    }
  }

  private SLSLiteApiException closedFailure() {
    return new SLSLiteApiException(
        SLSLiteApiException.Code.CLOSED, "Extension context is closed: " + namespace);
  }

  private abstract class OwnedRegistration implements Subscription {

    private final AtomicBoolean active = new AtomicBoolean(true);

    final boolean claimCompletion() {
      if (!active.compareAndSet(true, false)) {
        return false;
      }
      release();
      return !closed();
    }

    final boolean active() {
      return active.get() && !closed.get();
    }

    @Override
    public void close() {
      if (active.compareAndSet(true, false)) {
        release();
      }
    }

    private void release() {
      registrations.remove(this);
      registrationCount.decrementAndGet();
      releaseDelegate();
    }

    abstract void releaseDelegate();
  }

  private final class OwnedEventRegistration extends OwnedRegistration {

    private final Consumer<? super SLSLiteEvent> listener;
    private volatile Subscription delegate;

    private OwnedEventRegistration(Consumer<? super SLSLiteEvent> listener) {
      this.listener = listener;
    }

    private void install(Subscription delegate) {
      this.delegate = delegate;
      if (!active()) {
        delegate.close();
      }
    }

    private void accept(SLSLiteEvent event) {
      if (!active()) {
        return;
      }
      try {
        listener.accept(event);
      } catch (RuntimeException exception) {
        close();
        throw exception;
      }
    }

    @Override
    void releaseDelegate() {
      Subscription current = delegate;
      if (current != null) {
        current.close();
      }
    }
  }

  private final class OwnedBlueprintReadinessRegistration extends OwnedRegistration {

    private volatile Subscription delegate;

    private void install(Subscription delegate) {
      this.delegate = delegate;
      if (!active()) {
        delegate.close();
      }
    }

    @Override
    void releaseDelegate() {
      Subscription current = delegate;
      if (current != null) {
        current.close();
      }
    }
  }

  private final class OwnedDiagnosticRegistration extends OwnedRegistration {

    private volatile Subscription delegate;

    private void install(Subscription delegate) {
      this.delegate = delegate;
      if (!active()) {
        delegate.close();
      }
    }

    @Override
    void releaseDelegate() {
      Subscription current = delegate;
      if (current != null) {
        current.close();
      }
    }
  }

  private final class OwnedFutureRegistration<T> extends OwnedRegistration {

    private final BiConsumer<? super T, ? super Throwable> callback;

    private OwnedFutureRegistration(BiConsumer<? super T, ? super Throwable> callback) {
      this.callback = callback;
    }

    private void complete(T value, Throwable failure) {
      if (!claimCompletion()) {
        return;
      }
      try {
        callback.accept(value, failure);
      } catch (RuntimeException exception) {
        logger.warn(
            "SLS-LITE extension completion callback failed for {}: {}",
            namespace,
            exception.getClass().getSimpleName());
      }
    }

    @Override
    void releaseDelegate() {}
  }

  private final class OwnedActionRegistration<T> extends OwnedRegistration {

    private final List<OwnedActionRegistration<T>> owners;
    private final Consumer<? super T> action;

    private OwnedActionRegistration(
        List<OwnedActionRegistration<T>> owners, Consumer<? super T> action) {
      this.owners = owners;
      this.action = action;
    }

    private void accept(T input) {
      if (!active()) {
        return;
      }
      try {
        action.accept(input);
      } catch (RuntimeException exception) {
        close();
        logger.warn(
            "Disabled failing SLS-LITE extension action for {}: {}",
            namespace,
            exception.getClass().getSimpleName());
      }
    }

    @Override
    void releaseDelegate() {
      owners.remove(this);
    }
  }
}
