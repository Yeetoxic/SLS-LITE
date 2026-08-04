# Compatibility

SLS-LITE reuses useful SLS concepts on one host but is not a lightweight mode
of the distributed SLS stack.

Compatibility statements use four meanings:

- **Supported:** same usable contract in SLS-LITE.
- **Adapted:** same intent with documented single-host behavior.
- **SLS-LITE only:** local functionality without a full-SLS equivalent.
- **Intentionally unsupported or deferred:** unavailable and never silently
  treated as enforced.

Distributed nodes, daemon/container administration, arbitrary host mounts,
container resource enforcement, and the Protocube/S4J HTTP surfaces are not
provided. Java child-process admission is not container isolation.

Protocol support is limited to exact tested native or translated paths. A new
Minecraft, Velocity, ViaVersion, Paper, or Java release is not automatically
supported because an adjacent version worked.

Canonical matrices: [Compatibility](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Compatibility.md), [SLS v0.2.0](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/SLS_v0.2.0_Compatibility.md), and [Protocol Compatibility](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Protocol_Compatibility.md).
