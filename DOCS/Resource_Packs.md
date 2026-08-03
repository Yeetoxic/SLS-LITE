# Resource Packs

## Current Support

SLS-LITE copies a world-local `resources.zip` with the rest of a blueprint
volume. This preserves historical worlds without modifying their contents, but
copying the archive does not make it downloadable by multiplayer clients.

Minecraft clients fetch server resource packs from an HTTP or HTTPS URL. An
operator can currently configure that URL through the supported modern-style
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

SLS-LITE preserves this annotation but does not yet resolve the logical ID to a
public URL. The historical regression archive contains
`volumes/worlds/minigames/combat_cube/resources.zip`, matching the current
convention. The imported Meteor Miners and Missile Wars worlds do not currently
contain a conventional client resource-pack ZIP.

## Planned SLS-LITE Flow

The intended self-contained integration is:

1. Discover an explicitly configured pack or a conventional
   `<world>/resources.zip`.
2. Validate the ZIP, compute its SHA-1, and associate it with the blueprint's
   logical `resource_pack` ID.
3. Serve it from an optional bounded static-pack endpoint when the hosting
   allocation provides a client-reachable port, or use an operator-provided
   public base URL.
4. Ask Velocity to offer, replace, or clear the pack during server transfers.
5. Report acceptance, rejection, download failure, and unsupported-protocol
   behavior without requiring another plugin.

The built-in endpoint cannot create a public allocation or bypass a hosting
provider's firewall. Operators without an exposed HTTP port must use normal
static hosting or a CDN.
