# Lifecycle Concurrency And Failure Matrix

This is the Stage 3 lifecycle contract. A request either acquires the named
ownership, joins an already accepted operation, or returns the stable conflict
described below. No loser may release resources owned by the winner.

## Simultaneous Operation Matrix

| First accepted | Concurrent request | Stable result | Required postcondition |
| --- | --- | --- | --- |
| start/create | same blueprint start/create | A distinct ID is admitted only if blueprint, memory, process, and port limits still permit it; otherwise a bounded admission rejection. | Every published instance owns one unique ID, reservation, port, directory, process slot, and backend name. |
| start/create | stop/kill same ID | Stop/kill records cancellation and returns the one terminal future. | Preparation rolls back; no registration, port, memory, mount, staging directory, or child remains. |
| start/create | proxy shutdown | Shutdown closes admission before cancelling preparation. | Late callbacks cannot republish the instance; durable residue is left only for startup reconciliation. |
| stop | stop same ID | Both callers receive the same terminal future. | Cleanup runs once for the exact instance identity. |
| stop | kill same ID | Force termination may accelerate the same terminal operation. | Registration is removed before admissions are released; persistent data remains, ephemeral data is deleted when ownership is proven. |
| restart/reset/delete | restart/reset/delete same ID | One operation wins; all others receive the stable lifecycle-conflict response. | The winner exclusively owns the persistent transaction and its rollback/tombstone. |
| automatic recovery | restart/reset/delete | The administrative operation wins once its pending marker is published; a recovery already admitted becomes the active instance that the operation stops. | At most one active object exists for the persistent ID. |
| unexpected crash | automatic recovery | Persistent `restart-on-crash` policy schedules at most one retry with bounded exponential backoff. | Attempt budget survives short runtimes and resets only after the configured stable interval. |
| intentional stop/kill | automatic recovery | Intentional termination cancels retry and stable-reset tasks. | No replacement is created. |
| join | join by same player | Existing queue entry wins; duplicate request is rejected with its current destination. | One queue ticket and one capacity reservation per player. |
| dequeue | transfer callback | The queue state transition decides the winner. | A cancelled entry cannot initiate a later transfer; a transfer already begun completes normally and dequeue reports no queued entry. |
| join | maintenance enable | A ready existing instance remains selectable. Creation linearizes under the instance-manager monitor: accepted before the gate, or rejected after it. | Maintenance never stops active instances or releases their ownership. |
| cleanup | installation/warmup | Installation admission and cleanup snapshot serialize; active logical keys and resolved directories protect publication staging. | Cleanup cannot remove an active or referenced provider cache. |
| cleanup | cleanup | The installation lifecycle monitor serializes requests. | Candidate bounds and ownership validation apply independently to each completed report. |
| reload | reload | Catalog monitor serializes candidate validation and immutable publication. | Readers see one complete compatible blueprint/software snapshot. |
| reload | start/join | The operation resolves one immutable catalog revision. | A launched instance never mixes blueprint and software revisions. |
| lobby recovery | explicit lobby cycle/stop | Lobby generation token invalidates superseded callbacks; intentional-stop state suppresses retry. | Only the current generation may publish routing. |
| any operation | proxy shutdown | Shutdown closes admission, queues, recovery schedulers, installers, and process supervision in dependency order. | Remaining durable state is reconciliation-safe and no callback starts new blocking work after the deadline. |

## Admission And Conflict Rules

- Manager admission is linearized while holding the short-lived manager
  monitor. It acquires memory, then a port, then publishes the active object;
  failure unwinds in reverse.
- Instance-local stop, process attachment, registration, and cleanup use the
  instance monitor. Cleanup additionally identity-checks the manager registry.
- Persistent restart, reset, and delete use mutually exclusive pending sets.
  A duplicate request receives `Instance restart, reset, or delete is already
  in progress: <id>` and never joins or alters the winning operation.
- Maintenance mode blocks only brand-new IDs. Stops, cleanup, ready-instance
  joins, and same-ID persistent recovery remain available so a host can drain
  naturally.
- Automatic non-lobby recovery is explicit through `restart-on-crash`, requires
  `save: true`, and must not be enabled on the managed lobby because
  `lobby.recovery` owns that lifecycle.

## Terminal Ownership Audit

Every terminal race must prove all of the following:

- memory reservation is either held by one active instance or released;
- loopback port is either held by that instance or reusable;
- Velocity registration exists only for a ready active instance;
- process-supervisor ownership ends only after verified child exit;
- queue and drain markers no longer reference terminal instances;
- mounts/helpers are suspended before directory deletion or replacement;
- installation staging and storage transactions either commit atomically,
  roll back, or remain durably recognizable by reconciliation; and
- bounded in-memory/file diagnostics close without delaying the shutdown
  deadline.

Focused unit tests cover each ownership boundary. The documented
Pterodactyl/Velocity workflow remains the process, registration, port, lobby,
and persistent-recovery integration proof.
