/**
 * Stable, implementation-independent contracts for trusted Velocity extensions integrating with
 * SLS-LITE.
 *
 * <p>Extensions discover {@link net.slimelabs.slslite.api.SLSLiteApi} through {@link
 * net.slimelabs.slslite.api.SLSLiteApiProvider}, check advertised {@link
 * net.slimelabs.slslite.api.Capability capabilities}, and should use an owned {@link
 * net.slimelabs.slslite.api.ExtensionContext} for callbacks that must be released during plugin
 * shutdown. Implementations and types below the {@code internal} package are not public API.
 */
package net.slimelabs.slslite.api;
