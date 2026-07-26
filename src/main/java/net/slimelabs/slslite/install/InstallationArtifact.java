package net.slimelabs.slslite.install;

public record InstallationArtifact(
        long size,
        String digestAlgorithm,
        String checksum
) {

    public InstallationArtifact {
        if (size <= 0) {
            throw new IllegalArgumentException("Artifact size must be positive");
        }
        if (!"SHA-1".equals(digestAlgorithm)
                && !"SHA-256".equals(digestAlgorithm)) {
            throw new IllegalArgumentException(
                    "Unsupported digest algorithm: " + digestAlgorithm
            );
        }
        if (checksum == null || checksum.isBlank()) {
            throw new IllegalArgumentException("Artifact checksum is required");
        }
        checksum = checksum.toLowerCase(java.util.Locale.ROOT);
    }
}
