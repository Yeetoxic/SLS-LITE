# Compatibility

SLS-LITE reuses useful SLS concepts on one host but is not a lightweight mode
of the distributed SLS stack.

Compatibility statements use four permanent scope labels:

- **SLS and SLS-LITE:** the same usable operator contract exists in both.
- **Full SLS only:** distributed or container behavior SLS-LITE does not claim.
- **SLS-LITE only:** local functionality without a full-SLS equivalent.
- **Adapted for local mode:** the same intent with a documented single-host
  implementation.

Distributed nodes, daemon/container administration, arbitrary host mounts,
container resource enforcement, and the Protocube/S4J HTTP surfaces are not
provided. Java child-process admission is not container isolation.

Native protocol claims remain limited to tested paths. With ViaVersion
installed, Minecraft 26.2 is the forward-client minimum rather than a maximum:
newer clients may connect when that installed ViaVersion build reports a valid
translation to the backend baseline. Exact tested rows remain separately marked
because dynamic protocol support does not certify game or plugin behavior.

Canonical matrices: [Compatibility](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/compatibility/README.md), [SLS Main](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/compatibility/SLS_Main.md), and [Protocol Compatibility](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/networking/Protocol_Compatibility.md).
