package net.slimelabs.slslite.blueprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mixin/blueprint overlay merge. Later overlays win per Protocube helpers.go.
 */
final class OverlayMerger {

  private OverlayMerger() {}

  static Overlay merge(Overlay base, Overlay overlay) {
    if (overlay == null || overlay.isEmpty()) {
      return base == null ? Overlay.empty() : base;
    }
    if (base == null || base.isEmpty()) {
      return overlay;
    }
    return new Overlay(
        mergeServer(base.server(), overlay.server()),
        mergeState(base.state(), overlay.state()),
        mergeAnnotations(base.annotations(), overlay.annotations()));
  }

  private static Overlay.Server mergeServer(Overlay.Server base, Overlay.Server overlay) {
    if (overlay == null) {
      return base;
    }
    if (base == null) {
      return overlay;
    }
    return new Overlay.Server(
        overlay.software() != null ? overlay.software() : base.software(),
        overlay.version() != null ? overlay.version() : base.version(),
        overlay.image() != null ? overlay.image() : base.image(),
        overlay.path() != null ? overlay.path() : base.path(),
        overlay.memoryLimitMiB() != null ? overlay.memoryLimitMiB() : base.memoryLimitMiB(),
        overlay.maxInstances() != null ? overlay.maxInstances() : base.maxInstances(),
        mergeConfigs(base.configs(), overlay.configs()));
  }

  private static Overlay.State mergeState(Overlay.State base, Overlay.State overlay) {
    if (overlay == null) {
      return base;
    }
    if (base == null) {
      return overlay;
    }

    ArrayList<BlueprintVolume> volumes = new ArrayList<>(base.volumes());
    LinkedHashMap<String, Integer> volumeByName = new LinkedHashMap<>();
    for (int index = 0; index < volumes.size(); index++) {
      volumeByName.put(volumes.get(index).name(), index);
    }
    for (BlueprintVolume volume : overlay.volumes()) {
      Integer existing = volumeByName.get(volume.name());
      if (existing != null) {
        volumes.set(existing, volume);
      } else {
        volumeByName.put(volume.name(), volumes.size());
        volumes.add(volume);
      }
    }

    ArrayList<BlueprintCopy> copies = new ArrayList<>(base.copies());
    copies.addAll(overlay.copies());

    ArrayList<BlueprintPersistentFile> persistentFiles = new ArrayList<>(base.persistentFiles());
    LinkedHashMap<String, Integer> filesByName = new LinkedHashMap<>();
    for (int index = 0; index < persistentFiles.size(); index++) {
      filesByName.put(persistentFiles.get(index).name(), index);
    }
    for (BlueprintPersistentFile file : overlay.persistentFiles()) {
      Integer existing = filesByName.get(file.name());
      if (existing != null) {
        persistentFiles.set(existing, file);
      } else {
        filesByName.put(file.name(), persistentFiles.size());
        persistentFiles.add(file);
      }
    }

    LinkedHashMap<String, String> environment = new LinkedHashMap<>(base.environment());
    environment.putAll(overlay.environment());

    return new Overlay.State(volumes, copies, persistentFiles, environment);
  }

  static Map<String, Object> mergeAnnotations(
      Map<String, Object> base, Map<String, Object> overlay) {
    if (overlay == null || overlay.isEmpty()) {
      return Overlay.copyAnnotations(base);
    }
    if (base == null || base.isEmpty()) {
      return Overlay.copyAnnotations(overlay);
    }
    LinkedHashMap<String, Object> out = new LinkedHashMap<>(Overlay.copyAnnotations(base));
    for (Map.Entry<String, Object> entry : overlay.entrySet()) {
      Object existing = out.get(entry.getKey());
      Map<String, Object> baseMap = annotationMap(existing);
      Map<String, Object> overlayMap = annotationMap(entry.getValue());
      if (baseMap != null && overlayMap != null) {
        out.put(entry.getKey(), mergeAnnotations(baseMap, overlayMap));
      } else {
        out.put(entry.getKey(), Overlay.copyAnnotationValue(entry.getValue()));
      }
    }
    return java.util.Collections.unmodifiableMap(out);
  }

  private static Map<String, Overlay.Config> mergeConfigs(
      Map<String, Overlay.Config> base, Map<String, Overlay.Config> overlay) {
    if (overlay == null || overlay.isEmpty()) {
      return copyConfigs(base);
    }
    if (base == null || base.isEmpty()) {
      return copyConfigs(overlay);
    }
    LinkedHashMap<String, Overlay.Config> out = new LinkedHashMap<>(copyConfigs(base));
    out.putAll(overlay);
    return Map.copyOf(out);
  }

  private static Map<String, Overlay.Config> copyConfigs(Map<String, Overlay.Config> configs) {
    if (configs == null || configs.isEmpty()) {
      return Map.of();
    }
    return Map.copyOf(configs);
  }

  private static Map<String, Object> annotationMap(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      return null;
    }
    LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        return null;
      }
      converted.put(key, entry.getValue());
    }
    return converted;
  }
}
