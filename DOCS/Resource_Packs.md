# Resource Packs

[Documentation home](README.md)

## Supported Behavior

SLS-LITE copies a world-local `resources.zip` with the rest of a blueprint
volume. This preserves historical worlds without modifying their contents, but
copying the archive does not make it downloadable by multiplayer clients.

Minecraft clients fetch server resource packs from an HTTP or HTTPS URL. An
operator can configure that URL through the supported modern-style
`server.properties` patch:

```yaml
server:
  configs:
    server.properties:
      parser: properties
      find:
        resource-pack: "https://cdn.example.net/packs/combat-cube.zip"
        resource-pack-sha1: "0123456789abcdef0123456789abcdef01234567"
        require-resource-pack: true
```

The URL must be reachable from each player's computer. A path inside the
Pterodactyl container, including `world/resources.zip`, is not a client-reachable
URL. The SHA-1 must describe the exact ZIP served by that URL.

Older Minecraft releases support `resource-pack` and `resource-pack-sha1`.
Options such as `require-resource-pack`, prompts, pack IDs, stacking, and
explicit removal must only be used on protocol versions that support them.

No additional Paper or Velocity plugin is required for the basic
`server.properties` flow.

## Modern SLS Compatibility

Modern SLS blueprints may identify a logical pack through an annotation:

```yaml
annotations:
  resource_pack: "combat_cube"
```

SLS-LITE preserves this annotation but does not resolve the logical ID to a
public URL. The historical regression archive contains
`volumes/worlds/minigames/combat_cube/resources.zip`, matching the established
convention. The imported Meteor Miners and Missile Wars worlds do not
contain a conventional client resource-pack ZIP.

## Unavailable Behavior

SLS-LITE does not host resource packs, compute or publish pack URLs,
resolve logical annotation IDs, or apply, replace, or clear packs during a
Velocity transfer. Operators must use client-reachable static hosting or a CDN
and the supported version-appropriate `server.properties` fields.
