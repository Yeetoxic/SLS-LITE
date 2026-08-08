package net.slimelabs.slslite.config;

import java.nio.file.Path;

public record SLSConfig(
    int totalMemoryMiB,
    int maxManagedProcesses,
    int portRangeStart,
    int portRangeEnd,
    int queueTimeoutSeconds,
    BlueprintSelectionMode blueprintSelectionMode,
    int idleShutdownSeconds,
    ManagedOutputConfig managedOutput,
    ForwardingConfig forwarding,
    SecurityConfig security,
    SLSLimboConfig limbo,
    LobbyConfig lobby,
    StorageConfig storage,
    DetailedLoggingConfig detailedLogging,
    TransferActionBarConfig transferActionBar,
    ViaVersionSyncPolicy viaVersionSyncPolicy,
    DiagnosticRetentionConfig diagnosticRetention,
    SoftwareConfig software,
    Path instancesDirectory) {

  public SLSConfig(
      int totalMemoryMiB,
      int maxManagedProcesses,
      int portRangeStart,
      int portRangeEnd,
      int queueTimeoutSeconds,
      BlueprintSelectionMode blueprintSelectionMode,
      int idleShutdownSeconds,
      ManagedOutputConfig managedOutput,
      ForwardingConfig forwarding,
      SecurityConfig security,
      SLSLimboConfig limbo,
      LobbyConfig lobby,
      StorageConfig storage,
      DetailedLoggingConfig detailedLogging,
      TransferActionBarConfig transferActionBar,
      ViaVersionSyncPolicy viaVersionSyncPolicy,
      Path instancesDirectory) {
    this(
        totalMemoryMiB,
        maxManagedProcesses,
        portRangeStart,
        portRangeEnd,
        queueTimeoutSeconds,
        blueprintSelectionMode,
        idleShutdownSeconds,
        managedOutput,
        forwarding,
        security,
        limbo,
        lobby,
        storage,
        detailedLogging,
        transferActionBar,
        viaVersionSyncPolicy,
        DiagnosticRetentionConfig.defaults(),
        SoftwareConfig.defaults(),
        instancesDirectory);
  }

  public SLSConfig(
      int totalMemoryMiB,
      int maxManagedProcesses,
      int portRangeStart,
      int portRangeEnd,
      int queueTimeoutSeconds,
      BlueprintSelectionMode blueprintSelectionMode,
      int idleShutdownSeconds,
      ManagedOutputConfig managedOutput,
      ForwardingConfig forwarding,
      SecurityConfig security,
      SLSLimboConfig limbo,
      LobbyConfig lobby,
      StorageConfig storage,
      DetailedLoggingConfig detailedLogging,
      TransferActionBarConfig transferActionBar,
      Path instancesDirectory) {
    this(
        totalMemoryMiB,
        maxManagedProcesses,
        portRangeStart,
        portRangeEnd,
        queueTimeoutSeconds,
        blueprintSelectionMode,
        idleShutdownSeconds,
        managedOutput,
        forwarding,
        security,
        limbo,
        lobby,
        storage,
        detailedLogging,
        transferActionBar,
        ViaVersionSyncPolicy.AUTO,
        DiagnosticRetentionConfig.defaults(),
        SoftwareConfig.defaults(),
        instancesDirectory);
  }

  public SLSConfig(
      int totalMemoryMiB,
      int maxManagedProcesses,
      int portRangeStart,
      int portRangeEnd,
      int queueTimeoutSeconds,
      BlueprintSelectionMode blueprintSelectionMode,
      int idleShutdownSeconds,
      ManagedOutputConfig managedOutput,
      ForwardingConfig forwarding,
      SecurityConfig security,
      SLSLimboConfig limbo,
      LobbyConfig lobby,
      StorageConfig storage,
      DetailedLoggingConfig detailedLogging,
      Path instancesDirectory) {
    this(
        totalMemoryMiB,
        maxManagedProcesses,
        portRangeStart,
        portRangeEnd,
        queueTimeoutSeconds,
        blueprintSelectionMode,
        idleShutdownSeconds,
        managedOutput,
        forwarding,
        security,
        limbo,
        lobby,
        storage,
        detailedLogging,
        TransferActionBarConfig.defaults(),
        ViaVersionSyncPolicy.AUTO,
        DiagnosticRetentionConfig.defaults(),
        SoftwareConfig.defaults(),
        instancesDirectory);
  }

  public SLSConfig(
      int totalMemoryMiB,
      int maxManagedProcesses,
      int portRangeStart,
      int portRangeEnd,
      int queueTimeoutSeconds,
      int idleShutdownSeconds,
      ManagedOutputConfig managedOutput,
      ForwardingConfig forwarding,
      SecurityConfig security,
      SLSLimboConfig limbo,
      LobbyConfig lobby,
      StorageConfig storage,
      DetailedLoggingConfig detailedLogging,
      Path instancesDirectory) {
    this(
        totalMemoryMiB,
        maxManagedProcesses,
        portRangeStart,
        portRangeEnd,
        queueTimeoutSeconds,
        BlueprintSelectionMode.FIRST_AVAILABLE,
        idleShutdownSeconds,
        managedOutput,
        forwarding,
        security,
        limbo,
        lobby,
        storage,
        detailedLogging,
        TransferActionBarConfig.defaults(),
        ViaVersionSyncPolicy.AUTO,
        DiagnosticRetentionConfig.defaults(),
        SoftwareConfig.defaults(),
        instancesDirectory);
  }

  public SLSConfig(
      int totalMemoryMiB,
      int maxManagedProcesses,
      int portRangeStart,
      int portRangeEnd,
      int queueTimeoutSeconds,
      int idleShutdownSeconds,
      ManagedOutputConfig managedOutput,
      ForwardingConfig forwarding,
      SecurityConfig security,
      SLSLimboConfig limbo,
      LobbyConfig lobby,
      StorageConfig storage,
      Path instancesDirectory) {
    this(
        totalMemoryMiB,
        maxManagedProcesses,
        portRangeStart,
        portRangeEnd,
        queueTimeoutSeconds,
        BlueprintSelectionMode.FIRST_AVAILABLE,
        idleShutdownSeconds,
        managedOutput,
        forwarding,
        security,
        limbo,
        lobby,
        storage,
        DetailedLoggingConfig.defaults(),
        TransferActionBarConfig.defaults(),
        ViaVersionSyncPolicy.AUTO,
        DiagnosticRetentionConfig.defaults(),
        SoftwareConfig.defaults(),
        instancesDirectory);
  }

  public SLSConfig(
      int totalMemoryMiB,
      int maxManagedProcesses,
      int portRangeStart,
      int portRangeEnd,
      int queueTimeoutSeconds,
      int idleShutdownSeconds,
      ManagedOutputConfig managedOutput,
      ForwardingConfig forwarding,
      SecurityConfig security,
      SLSLimboConfig limbo,
      LobbyConfig lobby,
      Path instancesDirectory) {
    this(
        totalMemoryMiB,
        maxManagedProcesses,
        portRangeStart,
        portRangeEnd,
        queueTimeoutSeconds,
        BlueprintSelectionMode.FIRST_AVAILABLE,
        idleShutdownSeconds,
        managedOutput,
        forwarding,
        security,
        limbo,
        lobby,
        new StorageConfig(StorageStrategy.AUTO),
        DetailedLoggingConfig.defaults(),
        TransferActionBarConfig.defaults(),
        ViaVersionSyncPolicy.AUTO,
        DiagnosticRetentionConfig.defaults(),
        SoftwareConfig.defaults(),
        instancesDirectory);
  }

  public SLSConfig {
    if (totalMemoryMiB <= 0) {
      throw new IllegalArgumentException("totalMemoryMiB must be positive");
    }
    if (maxManagedProcesses <= 0) {
      throw new IllegalArgumentException("maxManagedProcesses must be positive");
    }
    if (portRangeStart < 1024 || portRangeStart > 65535) {
      throw new IllegalArgumentException("portRangeStart must be between 1024 and 65535");
    }
    if (portRangeEnd < portRangeStart || portRangeEnd > 65535) {
      throw new IllegalArgumentException("portRangeEnd must be between portRangeStart and 65535");
    }
    int availablePorts = portRangeEnd - portRangeStart + 1;
    if (maxManagedProcesses > availablePorts) {
      throw new IllegalArgumentException(
          "maxManagedProcesses must not exceed the configured port count of " + availablePorts);
    }
    if (queueTimeoutSeconds <= 0) {
      throw new IllegalArgumentException("queueTimeoutSeconds must be positive");
    }
    if (blueprintSelectionMode == null) {
      throw new IllegalArgumentException("blueprintSelectionMode is required");
    }
    if (idleShutdownSeconds < 0) {
      throw new IllegalArgumentException("idleShutdownSeconds must not be negative");
    }
    if (managedOutput == null) {
      throw new IllegalArgumentException("managed output configuration is required");
    }
    if (forwarding == null) {
      throw new IllegalArgumentException("forwarding configuration is required");
    }
    if (security == null) {
      throw new IllegalArgumentException("security configuration is required");
    }
    if (limbo == null) {
      throw new IllegalArgumentException("SLS-Limbo configuration is required");
    }
    if (lobby == null) {
      throw new IllegalArgumentException("lobby configuration is required");
    }
    if (storage == null) {
      throw new IllegalArgumentException("storage configuration is required");
    }
    if (detailedLogging == null) {
      throw new IllegalArgumentException("detailed logging configuration is required");
    }
    if (transferActionBar == null) {
      throw new IllegalArgumentException("transfer action-bar configuration is required");
    }
    if (viaVersionSyncPolicy == null) {
      throw new IllegalArgumentException("ViaVersion synchronization policy is required");
    }
    if (diagnosticRetention == null) {
      throw new IllegalArgumentException("diagnostic retention configuration is required");
    }
    if (software == null) {
      throw new IllegalArgumentException("software configuration is required");
    }
    instancesDirectory = instancesDirectory.toAbsolutePath().normalize();
  }
}
