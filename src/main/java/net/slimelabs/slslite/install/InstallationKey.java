package net.slimelabs.slslite.install;

public record InstallationKey(String softwareId, String version) {
    @Override
    public String toString() {
        return softwareId + ":" + version;
    }
}
