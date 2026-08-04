/**
 * Immutable, ordered notifications exposed by the SLS-LITE Java extension API.
 *
 * <p>Event listeners must remain non-blocking and close their {@link
 * net.slimelabs.slslite.api.event.Subscription} when no longer needed. Event sequence numbers are
 * scoped to one provider lifetime.
 */
package net.slimelabs.slslite.api.event;
