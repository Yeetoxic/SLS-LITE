package net.slimelabs.slslite.instance.storage;

import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.config.StorageConfig;
import net.slimelabs.slslite.config.StorageStrategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * Opt-in fake-provider process gate for the snapshot-helper protocol.
 */
public final class SnapshotHookRealProcessHarness {

    private SnapshotHookRealProcessHarness() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                    "Expected one empty disposable root"
            );
        }
        Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Root is missing: " + root);
        }
        Path helper = script(root.resolve("provider"), """
                #!/bin/sh
                operation="$3"
                source_path=""
                target_path=""
                shift 3
                while [ "$#" -gt 0 ]; do
                  case "$1" in
                    --source) source_path="$2"; shift 2 ;;
                    --target) target_path="$2"; shift 2 ;;
                    *) shift ;;
                  esac
                done
                state_path="${target_path}.provider-state"
                case "$operation" in
                  probe) ;;
                  prepare) cp -a "$source_path" "$target_path" ;;
                  suspend) [ ! -e "$target_path" ] || mv "$target_path" "$state_path" ;;
                  resume) [ ! -e "$state_path" ] || mv "$state_path" "$target_path" ;;
                  delete)
                    rm -rf -- "$target_path" "$state_path"
                    ;;
                  *) exit 64 ;;
                esac
                echo "sls-snapshot-helper-v1 ok"
                """);
        Path content = Files.createDirectories(root.resolve("content"));
        Path instances = Files.createDirectories(content.resolve("instances"));
        Path software = Files.createDirectories(content.resolve("software"));
        Path source = Files.createDirectories(content.resolve("worlds/source"));
        Files.writeString(software.resolve("server.jar"), "server");
        Files.writeString(source.resolve("level.dat"), "source");
        StorageConfig storage = new StorageConfig(
                StorageStrategy.SNAPSHOT_HOOK,
                helper,
                5
        );
        new SnapshotHookClient(helper, 5).probe(instances);
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                instances,
                content,
                storage,
                StorageStrategy.SNAPSHOT_HOOK
        );
        BlueprintVolume volume = new BlueprintVolume(
                "world",
                "worlds/source",
                "/world",
                BlueprintVolume.Mode.COW
        );
        Path prepared = preparer.prepare(
                "game.x82odk",
                software,
                List.of(volume)
        );
        Files.writeString(prepared.resolve("world/level.dat"), "private");
        preparer.suspend("game.x82odk");
        preparer.resume("game.x82odk");
        require(
                "private",
                Files.readString(prepared.resolve("world/level.dat"))
        );
        preparer.delete("game.x82odk");
        if (Files.exists(prepared)) {
            throw new IllegalStateException(
                    "Fake provider instance survived deletion"
            );
        }

        Path malformed = script(root.resolve("malformed"), """
                #!/bin/sh
                echo "not-the-protocol"
                """);
        expectFailure(() -> new SnapshotHookClient(
                malformed,
                5
        ).probe(instances), "malformed");

        Path timeout = script(root.resolve("timeout"), """
                #!/bin/sh
                sleep 5
                echo "sls-snapshot-helper-v1 ok"
                """);
        expectFailure(() -> new SnapshotHookClient(
                timeout,
                1
        ).probe(instances), "timed out");
        System.out.println(
                "SLS-LITE snapshot-helper process protocol PASS"
        );
    }

    private static Path script(Path path, String content) throws Exception {
        Files.writeString(path, content);
        Files.setPosixFilePermissions(
                path,
                PosixFilePermissions.fromString("rwx------")
        );
        return path;
    }

    private static void expectFailure(
            ThrowingOperation operation,
            String message
    ) throws Exception {
        try {
            operation.run();
        } catch (IOException exception) {
            if (exception.getMessage().contains(message)) {
                return;
            }
            throw exception;
        }
        throw new IllegalStateException(
                "Expected snapshot helper failure containing " + message
        );
    }

    private static void require(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Expected " + expected + " but found " + actual
            );
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {

        void run() throws Exception;
    }
}
