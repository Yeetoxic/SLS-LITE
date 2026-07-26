package net.slimelabs.slslite.install;

public final class SoftwareInstallationException extends Exception {
    public SoftwareInstallationException(String message) {
        super(message);
    }

    public SoftwareInstallationException(String message, Throwable cause) {
        super(message, cause);
    }
}
