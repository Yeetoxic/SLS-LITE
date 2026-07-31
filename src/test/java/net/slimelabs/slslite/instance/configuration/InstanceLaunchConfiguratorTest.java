package net.slimelabs.slslite.instance.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.config.ForwardingMode;
import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessSpec;
import net.slimelabs.slslite.software.SoftwareConfigurator;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareReleaseChannel;
import net.slimelabs.slslite.software.SoftwareRuntime;
import net.slimelabs.slslite.software.SoftwareSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class InstanceLaunchConfiguratorTest {

  @TempDir Path temporaryDirectory;

  @Test
  void appliesAllConfigurationBeforeBuildingProcessSpec() throws Exception {
    Path instance = temporaryDirectory.resolve("instances/game.abc123");
    Files.createDirectories(instance);
    Files.writeString(instance.resolve("server.properties"), "motd=original\nonline-mode=true\n");
    Files.writeString(instance.resolve("custom.conf"), "feature=old\nuntouched=yes\n");
    Files.writeString(instance.resolve("bukkit.yml"), "settings:\n  allow-end: true\n");
    Blueprint blueprint =
        blueprint(
            Map.of("motd", "blueprint"),
            Map.of("bukkit.yml", Map.of("settings", Map.of("allow-end", false))),
            Map.of("custom.conf", Map.of("feature=", "feature=new")));
    InstanceLaunchConfigurator configurator = configurator();

    ProcessSpec spec =
        configurator.configure(
            profile(SoftwareConfigurator.PAPER), blueprint, "game.abc123", instance, 25571);

    Properties properties = properties(instance.resolve("server.properties"));
    assertEquals("blueprint", properties.getProperty("motd"));
    assertEquals("hard", properties.getProperty("difficulty"));
    assertEquals("25571", properties.getProperty("server-port"));
    assertEquals("42", properties.getProperty("max-players"));
    assertEquals("false", properties.getProperty("online-mode"));
    assertEquals("feature=new\nuntouched=yes\n", Files.readString(instance.resolve("custom.conf")));
    assertEquals(false, map(yaml(instance.resolve("bukkit.yml")).get("settings")).get("allow-end"));
    Map<String, Object> velocity =
        map(map(yaml(instance.resolve("config/paper-global.yml")).get("proxies")).get("velocity"));
    assertEquals(false, velocity.get("enabled"));
    assertTrue(spec.command().contains("-Dsls.port=25571"));
    assertTrue(spec.command().contains("-jar"));
    assertEquals(instance.toAbsolutePath().normalize(), spec.workingDirectory());
    assertEquals(Map.of("FEATURE_FLAG", "enabled"), spec.environment());
  }

  @Test
  void genericSoftwareSkipsPaperForwardingFiles() throws Exception {
    Path instance = temporaryDirectory.resolve("instances/game.abc123");
    Files.createDirectories(instance);

    configurator()
        .configure(
            profile(SoftwareConfigurator.GENERIC),
            blueprint(Map.of(), Map.of(), Map.of()),
            "game.abc123",
            instance,
            25571);

    assertFalse(Files.exists(instance.resolve("spigot.yml")));
    assertFalse(Files.exists(instance.resolve("config/paper-global.yml")));
    assertTrue(Files.isRegularFile(instance.resolve("server.properties")));
  }

  private InstanceLaunchConfigurator configurator() {
    return new InstanceLaunchConfigurator(
        new ForwardingConfig(
            ForwardingMode.NONE, false, temporaryDirectory.resolve("missing.secret")),
        new JavaJarProcessSpecFactory(temporaryDirectory));
  }

  private static SoftwareProfile profile(SoftwareConfigurator configurator) {
    return new SoftwareProfile(
        "paper",
        "Paper",
        SoftwareRuntime.JAVA_JAR,
        configurator,
        SoftwareSource.MANUAL,
        SoftwareReleaseChannel.STABLE,
        false,
        "java",
        Map.of(),
        "software/paper/{version}",
        "paper.jar",
        List.of("-Dsls.port={port}"),
        List.of("--nogui"),
        Map.of(
            "motd", "profile",
            "difficulty", "hard"),
        "Done",
        180,
        "stop",
        30);
  }

  private static Blueprint blueprint(
      Map<String, String> serverProperties,
      Map<String, Map<String, Object>> yamlConfigs,
      Map<String, Map<String, String>> textConfigs) {
    return new Blueprint(
        "game",
        "Game",
        "game",
        "paper",
        "1.21.11",
        null,
        null,
        512,
        42,
        1,
        false,
        serverProperties,
        yamlConfigs,
        textConfigs,
        Map.of(),
        List.of(),
        List.of(),
        Map.of("FEATURE_FLAG", "enabled"));
  }

  private static Properties properties(Path path) throws Exception {
    Properties properties = new Properties();
    try (Reader input = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(input);
    }
    return properties;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> yaml(Path path) throws Exception {
    try (InputStream input = Files.newInputStream(path)) {
      return (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
