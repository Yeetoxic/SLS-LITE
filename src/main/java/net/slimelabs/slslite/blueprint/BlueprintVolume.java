package net.slimelabs.slslite.blueprint;

public record BlueprintVolume(
        String name,
        String source,
        String target,
        Mode mode
) {

    public BlueprintVolume {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Volume name must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Volume source must not be blank");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("Volume target must not be blank");
        }
        if (mode == null) {
            throw new IllegalArgumentException("Volume mode is required");
        }
        name = name.trim();
        source = source.trim();
        target = target.trim();
    }

    public enum Mode {
        COW,
        RO,
        RW
    }
}
