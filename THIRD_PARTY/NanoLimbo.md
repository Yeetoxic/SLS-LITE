# NanoLimbo Runtime Notice

SLS-LITE currently bundles an unmodified NanoLimbo runtime for its experimental
SLS-Limbo virtual lobby.

## Pinned Component

- Project: NanoLimbo
- Version: 1.13.0
- Source: <https://github.com/Nan1t/NanoLimbo>
- Corresponding revision:
  <https://github.com/Nan1t/NanoLimbo/tree/d192d57d1d4a5fdc7b87643f453d82cb7b9b4242>
- Commit: `d192d57d1d4a5fdc7b87643f453d82cb7b9b4242`
- License: GNU General Public License version 3
- Included binary: `src/main/resources/limbo/nanolimbo-1.13.0.jar`
- Binary size: 5,375,615 bytes
- Binary SHA-256:
  `4811d42364287913c6ef601016f77049989d875d4741f7ce4c2b8d5364106de1`

The full NanoLimbo license is included as
[NanoLimbo-LICENSE.txt](NanoLimbo-LICENSE.txt). License and notice files for
dependencies packaged by NanoLimbo remain present inside its nested runtime JAR.

## Build Record

The bundled binary was produced from the pinned, unmodified source revision on
July 25, 2026 with:

```powershell
.\gradlew.bat shadowJar
```

The resulting `build/libs/NanoLimbo-1.13.0-all.jar` was renamed for its resource
path. No NanoLimbo source or bytecode modifications were made. SLS-LITE writes a
runtime configuration and launches that JAR as a separate child process.

Before a public SLS-LITE release containing this binary, the release process
must verify the checksum again and ensure the exact corresponding source remains
available from an SLS-LITE-controlled release location, not only an external
repository link.
