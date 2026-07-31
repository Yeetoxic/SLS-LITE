package net.slimelabs.slslite.lobby;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SLSLimboProtocolCatalog {

  private static final String VERSION_CLASS = "ua.nanit.limbo.protocol.registry.Version";

  private SLSLimboProtocolCatalog() {}

  public static Map<Integer, String> inspect(Path runtimeJar) throws IOException {
    try (URLClassLoader loader =
        new URLClassLoader(
            new java.net.URL[] {runtimeJar.toUri().toURL()},
            ClassLoader.getPlatformClassLoader())) {
      Class<?> versionClass = Class.forName(VERSION_CLASS, true, loader);
      Method protocolNumber = versionClass.getMethod("getProtocolNumber");
      Method displayName = versionClass.getMethod("getDisplayName");
      Method supported = versionClass.getMethod("isSupported");
      Map<Integer, String> protocols = new LinkedHashMap<>();
      for (Object version : versionClass.getEnumConstants()) {
        if (!(boolean) supported.invoke(version)) {
          continue;
        }
        protocols.put((int) protocolNumber.invoke(version), (String) displayName.invoke(version));
      }
      return Map.copyOf(protocols);
    } catch (ClassNotFoundException
        | NoSuchMethodException
        | IllegalAccessException
        | InvocationTargetException
        | LinkageError exception) {
      throw new IOException("Unable to inspect bundled SLS-Limbo protocol support", exception);
    }
  }
}
