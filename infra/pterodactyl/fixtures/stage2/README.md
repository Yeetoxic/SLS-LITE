# SLS v0.2.0 Compatibility Fixture

This fixture reproduces the deployed/manual SLS v0.2.0 compatibility run and
remains the regression input for that pinned contract. The `stage2` directory
and fixture IDs are retained as test-data provenance so recorded logs, commands,
and artifact evidence remain reproducible.

It supplements two copied, attributed compatibility files:

- `src/test/resources/compatibility/sls-v0.2.0/software/paper.yml`
- `infra/pterodactyl/state/imports/stage1/modern-blueprints/minigames/wildfire.yaml`

The deployment copy must not modify either source file or any source world.

## Active Definitions

- The existing managed `lobby` remains the safe entry and return point.
- The copied modern `wildfire` definition proves that a world-backed blueprint
  from the owner's modern SLS corpus loads and runs directly.
- `stage2_undead.yml` exercises the shared blueprint language against an
  existing immutable adventure world:
  - modern software/version selection;
  - properties configuration;
  - COW volume preparation;
  - `state.copy`;
  - `state.env`;
  - vSLS lifecycle, matchmaking, and `on-join` annotations;
  - arbitrary nested metadata.

## Rejected Definitions

Files under `rejected/` use `.yaml.example`, so recursive blueprint loading
does not activate them. Test each separately by copying it into the active
blueprint directory with a `.yml` extension.

`state.mounts` must fail during `/sls reload`. A shared `mode: rw` volume is
valid modern syntax, so it must load and then fail before instance preparation
when `/sls start compatibility stage2_reject_rw` is attempted. Remove the
copied file and reload after each case.

Catalog reload is atomic: the previously active definitions must remain
available after the rejected mount reload.
