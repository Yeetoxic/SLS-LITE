package net.slimelabs.slslite.api.event;

/** Terminal outcome of an atomic catalog reload attempt. */
public enum CatalogReloadStatus {
  COMMITTED,
  REJECTED
}
