package net.slimelabs.slslite.blueprint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flattens mixin {@code extends} chains and applies blueprint {@code includes}.
 *
 * <p>Failed mixins and blueprints become rejections; valid siblings remain.
 */
final class DefinitionResolver {

  private DefinitionResolver() {}

  static ResolveResult resolve(
      Map<String, MixinCandidate> mixins, Map<String, BlueprintCandidate> blueprints) {
    Map<String, Mixin> rawById = new LinkedHashMap<>();
    mixins.forEach((id, candidate) -> rawById.put(id, candidate.mixin()));

    Map<String, Mixin> resolvedById = new LinkedHashMap<>();
    List<BlueprintRepository.Rejection> rejections = new ArrayList<>();
    HashSet<String> visiting = new HashSet<>();
    ArrayList<String> stack = new ArrayList<>();

    for (MixinCandidate candidate : mixins.values()) {
      try {
        resolveMixin(candidate.mixin().id(), rawById, resolvedById, visiting, stack);
      } catch (BlueprintException exception) {
        rejections.add(new BlueprintRepository.Rejection(candidate.path(), exception.getMessage()));
      }
    }

    Map<String, Blueprint> resolvedBlueprints = new LinkedHashMap<>();
    for (BlueprintCandidate candidate : blueprints.values()) {
      try {
        Blueprint resolved = resolveBlueprint(candidate.document(), resolvedById);
        resolvedBlueprints.put(resolved.id(), resolved);
      } catch (BlueprintException exception) {
        rejections.add(new BlueprintRepository.Rejection(candidate.path(), exception.getMessage()));
      }
    }

    return new ResolveResult(Map.copyOf(resolvedById), Map.copyOf(resolvedBlueprints), rejections);
  }

  private static Mixin resolveMixin(
      String id,
      Map<String, Mixin> rawById,
      Map<String, Mixin> resolvedById,
      HashSet<String> visiting,
      ArrayList<String> stack)
      throws BlueprintException {
    Mixin cached = resolvedById.get(id);
    if (cached != null) {
      return cached;
    }
    if (visiting.contains(id)) {
      throw new BlueprintException(
          "mixin inheritance cycle detected: " + formatMixinCycle(stack, id));
    }
    Mixin raw = rawById.get(id);
    if (raw == null) {
      throw new BlueprintException("unknown mixin \"" + id + "\"");
    }

    visiting.add(id);
    stack.add(id);
    try {
      Overlay acc = Overlay.empty();
      for (String parentId : raw.extendsMixins()) {
        Mixin parent = resolveMixin(parentId, rawById, resolvedById, visiting, stack);
        acc = OverlayMerger.merge(acc, parent.overlay());
      }
      acc = OverlayMerger.merge(acc, raw.overlay());
      Mixin resolved = raw.withOverlay(acc);
      resolvedById.put(id, resolved);
      return resolved;
    } finally {
      stack.removeLast();
      visiting.remove(id);
    }
  }

  private static Blueprint resolveBlueprint(BlueprintDocument document, Map<String, Mixin> mixins)
      throws BlueprintException {
    Overlay acc = Overlay.empty();
    for (String id : document.includes()) {
      Mixin mixin = mixins.get(id);
      if (mixin == null) {
        throw new BlueprintException(document.source() + ": includes unknown mixin \"" + id + "\"");
      }
      acc = OverlayMerger.merge(acc, mixin.overlay());
    }
    acc = OverlayMerger.merge(acc, document.overlay());
    return BlueprintComposer.compose(document, acc);
  }

  static String formatMixinCycle(List<String> stack, String id) {
    int start = 0;
    for (int index = 0; index < stack.size(); index++) {
      if (stack.get(index).equals(id)) {
        start = index;
        break;
      }
    }
    ArrayList<String> parts = new ArrayList<>(stack.subList(start, stack.size()));
    parts.add(id);
    return String.join(" -> ", parts);
  }

  record MixinCandidate(String path, Mixin mixin) {
    MixinCandidate {
      java.util.Objects.requireNonNull(path, "path");
      java.util.Objects.requireNonNull(mixin, "mixin");
    }
  }

  record BlueprintCandidate(String path, BlueprintDocument document) {
    BlueprintCandidate {
      java.util.Objects.requireNonNull(path, "path");
      java.util.Objects.requireNonNull(document, "document");
    }
  }

  record ResolveResult(
      Map<String, Mixin> mixins,
      Map<String, Blueprint> blueprints,
      List<BlueprintRepository.Rejection> rejections) {
    ResolveResult {
      mixins = Map.copyOf(mixins);
      blueprints = Map.copyOf(blueprints);
      rejections = List.copyOf(rejections);
    }
  }
}
