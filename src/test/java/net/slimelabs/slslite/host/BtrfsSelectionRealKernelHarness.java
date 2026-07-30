package net.slimelabs.slslite.host;

import net.slimelabs.slslite.config.StorageStrategy;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opt-in exact-path Btrfs capability and explicit-selection gate.
 */
public final class BtrfsSelectionRealKernelHarness {

    private BtrfsSelectionRealKernelHarness() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                    "Expected one empty disposable Btrfs path"
            );
        }
        Path path = Path.of(arguments[0]).toAbsolutePath().normalize();
        Files.createDirectories(path);
        HostStorageCapabilityChecker.StorageCheck check =
                new HostStorageCapabilityChecker().checkWithSelection(
                        path,
                        StorageStrategy.BTRFS
                );
        StorageStrategy selected = check.selection()
                .selected()
                .orElseThrow(() -> new IllegalStateException(
                        "Btrfs capability selection was unavailable"
                ));
        if (selected != StorageStrategy.BTRFS) {
            throw new IllegalStateException(
                    "Expected explicit Btrfs selection but selected "
                            + selected.configValue()
                );
        }
        System.out.println(
                "Btrfs exact-path probe enabled explicit Btrfs selection"
        );
    }
}
