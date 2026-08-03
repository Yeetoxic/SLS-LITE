package net.slimelabs.slslite.api.event;

/** Sanitized reason family for a rejected catalog reload. */
public enum CatalogReloadFailureCategory {
  NONE,
  IO,
  VALIDATION,
  INTERNAL
}
