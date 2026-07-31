package net.slimelabs.slslite.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SLSLimboProtocolCatalogTest {

  @TempDir Path temporaryDirectory;

  @Test
  void readsSupportedProtocolsFromPinnedRuntime() throws Exception {
    SLSLimboInstaller.SLSLimboInstallation installation =
        new SLSLimboInstaller(temporaryDirectory)
            .install(
                25582,
                new net.slimelabs.slslite.config.ForwardingConfig(
                    net.slimelabs.slslite.config.ForwardingMode.NONE,
                    true,
                    temporaryDirectory.resolve("unused.secret")),
                -1);

    Map<Integer, String> protocols = SLSLimboProtocolCatalog.inspect(installation.runtimeJar());

    assertEquals("1.21.4", protocols.get(769));
    assertTrue(protocols.containsValue("26.1"));
  }
}
