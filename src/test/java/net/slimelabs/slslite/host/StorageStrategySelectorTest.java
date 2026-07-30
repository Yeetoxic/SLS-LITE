package net.slimelabs.slslite.host;

import net.slimelabs.slslite.config.StorageStrategy;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageStrategySelectorTest {

    private final StorageStrategySelector selector = new StorageStrategySelector();

    @Test
    void autoUsesOnlyImplementedAndDetectedStrategies() {
        StorageStrategySelection selection = selector.select(
                StorageStrategy.AUTO,
                EnumSet.of(StorageStrategy.COPY, StorageStrategy.REFLINK),
                EnumSet.of(StorageStrategy.COPY)
        );

        assertTrue(selection.available());
        assertEquals(StorageStrategy.COPY, selection.selected().orElseThrow());
    }

    @Test
    void autoPrefersReflinkAfterItsImplementationIsEnabled() {
        StorageStrategySelection selection = selector.select(
                StorageStrategy.AUTO,
                EnumSet.of(
                        StorageStrategy.COPY,
                        StorageStrategy.REFLINK,
                        StorageStrategy.OVERLAY
                ),
                EnumSet.of(
                        StorageStrategy.COPY,
                        StorageStrategy.REFLINK,
                        StorageStrategy.OVERLAY
                )
        );

        assertEquals(StorageStrategy.REFLINK, selection.selected().orElseThrow());
    }

    @Test
    void explicitStrategyFailsWhenImplementationIsDisabled() {
        StorageStrategySelection selection = selector.select(
                StorageStrategy.BTRFS,
                EnumSet.of(StorageStrategy.COPY, StorageStrategy.BTRFS),
                EnumSet.of(StorageStrategy.COPY)
        );

        assertFalse(selection.available());
        assertTrue(selection.detail().contains("not implemented"));
    }

    @Test
    void explicitStrategyFailsWhenCapabilityIsMissing() {
        StorageStrategySelection selection = selector.select(
                StorageStrategy.OVERLAY,
                EnumSet.of(StorageStrategy.COPY),
                EnumSet.of(StorageStrategy.COPY, StorageStrategy.OVERLAY)
        );

        assertFalse(selection.available());
        assertTrue(selection.detail().contains("not detected"));
    }

    @Test
    void autoUsesFuseOverlayAfterHigherPriorityBackendsAreUnavailable() {
        StorageStrategySelection selection = selector.select(
                StorageStrategy.AUTO,
                EnumSet.of(
                        StorageStrategy.COPY,
                        StorageStrategy.FUSE_OVERLAY
                ),
                EnumSet.of(
                        StorageStrategy.COPY,
                        StorageStrategy.FUSE_OVERLAY
                )
        );

        assertEquals(
                StorageStrategy.FUSE_OVERLAY,
                selection.selected().orElseThrow()
        );
    }

    @Test
    void snapshotHookIsNeverSelectedAutomatically() {
        StorageStrategySelection selection = selector.select(
                StorageStrategy.AUTO,
                EnumSet.of(StorageStrategy.COPY, StorageStrategy.SNAPSHOT_HOOK),
                EnumSet.of(StorageStrategy.COPY, StorageStrategy.SNAPSHOT_HOOK)
        );

        assertEquals(StorageStrategy.COPY, selection.selected().orElseThrow());
    }

    @Test
    void selectsExpectedStrategyForNamedHostingProfiles() {
        List<Profile> profiles = List.of(
                new Profile(
                        "restricted ext4 shared host",
                        EnumSet.of(StorageStrategy.COPY),
                        StorageStrategy.COPY
                ),
                new Profile(
                        "XFS reflink host",
                        EnumSet.of(
                                StorageStrategy.COPY,
                                StorageStrategy.REFLINK,
                                StorageStrategy.OVERLAY
                        ),
                        StorageStrategy.REFLINK
                ),
                new Profile(
                        "eligible Btrfs host",
                        EnumSet.of(
                                StorageStrategy.COPY,
                                StorageStrategy.BTRFS,
                                StorageStrategy.OVERLAY
                        ),
                        StorageStrategy.BTRFS
                ),
                new Profile(
                        "kernel overlay host",
                        EnumSet.of(
                                StorageStrategy.COPY,
                                StorageStrategy.OVERLAY
                        ),
                        StorageStrategy.OVERLAY
                ),
                new Profile(
                        "rootless FUSE host",
                        EnumSet.of(
                                StorageStrategy.COPY,
                                StorageStrategy.FUSE_OVERLAY
                        ),
                        StorageStrategy.FUSE_OVERLAY
                )
        );
        EnumSet<StorageStrategy> implemented =
                EnumSet.allOf(StorageStrategy.class);

        for (Profile profile : profiles) {
            StorageStrategySelection selection = selector.select(
                    StorageStrategy.AUTO,
                    profile.detected(),
                    implemented
            );
            assertEquals(
                    profile.expected(),
                    selection.selected().orElseThrow(),
                    profile.name()
            );
        }
    }

    private record Profile(
            String name,
            EnumSet<StorageStrategy> detected,
            StorageStrategy expected
    ) {
    }
}
