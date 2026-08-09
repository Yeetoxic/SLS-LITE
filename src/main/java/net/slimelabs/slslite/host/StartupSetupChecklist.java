package net.slimelabs.slslite.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Produces a fixed-size startup assessment; verbose probe output remains elsewhere. */
public final class StartupSetupChecklist {

  public static final String SETUP_GUIDE =
      "https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Getting_Started.md";
  private static final int MAX_CONSOLE_ACTIONS = 2;

  private StartupSetupChecklist() {}

  public static Report assess(Input input) {
    Objects.requireNonNull(input, "input");
    List<Finding> findings = new ArrayList<>();
    findings.add(Finding.ready("Plugin", "core initialization completed"));
    if (input.hostFailures() > 0) {
      findings.add(
          Finding.blocker(
              "Host", input.hostFailures() + " required host capability check(s) failed"));
    } else {
      findings.add(
          Finding.ready("Host", "required Java, storage, port, and process probes passed"));
    }
    if (input.forwardingSecretProblem() != null) {
      findings.add(Finding.action("Forwarding", input.forwardingSecretProblem()));
    } else if (input.developmentForwarding()) {
      findings.add(
          Finding.development("Forwarding", "mode=none is valid for isolated development only"));
    } else {
      findings.add(Finding.ready("Forwarding", "proxy mode, online mode, and secret agree"));
    }
    findings.add(Finding.ready("Routing", input.routingDescription()));
    if (input.loadedBlueprints() == 0) {
      findings.add(
          Finding.action(
              "Blueprints",
              "no blueprints loaded; copy template.yml.example to a .yml file and customize it"));
    } else {
      findings.add(
          Finding.ready("Blueprints", input.loadedBlueprints() + " valid blueprint(s) loaded"));
    }
    if (input.rejectedBlueprints() > 0) {
      findings.add(
          Finding.action(
              "Blueprints",
              input.rejectedBlueprints()
                  + " invalid file(s) rejected; valid siblings remain available"));
    }
    if (input.eulaGates() > 0) {
      findings.add(
          Finding.action(
              "Software",
              input.eulaGates() + " required provider download(s) await explicit EULA acceptance"));
    } else {
      findings.add(Finding.ready("Software", "loaded definitions have no active EULA gate"));
    }
    findings.add(
        Finding.ready(
            "Admission",
            input.maxManagedProcesses()
                + " process(es), "
                + input.managedMemoryMiB()
                + " MiB, "
                + input.portCount()
                + " loopback port(s) configured"));
    findings.add(Finding.ready("Storage", "selected " + input.storageStrategy()));
    return new Report(findings);
  }

  public enum Level {
    READY,
    DEVELOPMENT,
    ACTION,
    BLOCKER
  }

  public record Finding(Level level, String topic, String message) {
    public Finding {
      Objects.requireNonNull(level, "level");
      Objects.requireNonNull(topic, "topic");
      Objects.requireNonNull(message, "message");
    }

    static Finding ready(String topic, String message) {
      return new Finding(Level.READY, topic, message);
    }

    static Finding development(String topic, String message) {
      return new Finding(Level.DEVELOPMENT, topic, message);
    }

    static Finding action(String topic, String message) {
      return new Finding(Level.ACTION, topic, message);
    }

    static Finding blocker(String topic, String message) {
      return new Finding(Level.BLOCKER, topic, message);
    }
  }

  public record Input(
      int hostFailures,
      boolean developmentForwarding,
      String forwardingSecretProblem,
      String routingDescription,
      int loadedBlueprints,
      int rejectedBlueprints,
      int eulaGates,
      int maxManagedProcesses,
      int managedMemoryMiB,
      int portCount,
      String storageStrategy) {
    public Input {
      if (hostFailures < 0
          || loadedBlueprints < 0
          || rejectedBlueprints < 0
          || eulaGates < 0
          || maxManagedProcesses <= 0
          || managedMemoryMiB <= 0
          || portCount <= 0) {
        throw new IllegalArgumentException("Checklist counts are outside their valid range");
      }
      Objects.requireNonNull(routingDescription, "routingDescription");
      Objects.requireNonNull(storageStrategy, "storageStrategy");
    }
  }

  public record Report(List<Finding> findings) {
    public Report {
      findings = List.copyOf(findings);
    }

    public long count(Level level) {
      return findings.stream().filter(finding -> finding.level() == level).count();
    }

    public String consoleSummary() {
      long blockers = count(Level.BLOCKER);
      long actions = count(Level.ACTION);
      long development = count(Level.DEVELOPMENT);
      StringBuilder summary = new StringBuilder();
      if (blockers > 0) {
        summary.append("BLOCKED (").append(blockers).append(')');
      } else if (actions > 0) {
        summary.append("ACTION NEEDED (").append(actions).append(')');
      } else {
        summary.append("READY");
        if (development > 0) {
          summary.append(" (").append(development).append(" development choice(s))");
        }
      }
      List<String> priorities =
          findings.stream()
              .filter(
                  finding -> finding.level() == Level.BLOCKER || finding.level() == Level.ACTION)
              .limit(MAX_CONSOLE_ACTIONS)
              .map(Finding::message)
              .toList();
      if (!priorities.isEmpty()) {
        summary.append(": ").append(String.join("; ", priorities));
        if (blockers + actions > priorities.size()) {
          summary.append("; see details");
        }
      }
      return summary.append(". Setup: ").append(SETUP_GUIDE).toString();
    }
  }
}
