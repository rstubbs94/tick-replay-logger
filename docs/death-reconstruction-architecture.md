# Tick Replay Logger - capture and local export

The plugin records client-observed state and events for later review. This is an
implementation guide; the active work queue is [TODO](TODO.md), and the current
export contract is [schema 1.3](export-schema-v1.3.md).

## Capture flow

```text
client callbacks and settled per-tick observations
  -> scoped rolling journal and world buffers
  -> local death recording or manual snapshot
  -> in-plugin replay or local JSON/clipboard export
```

The journal retains HP, prayer, movement, actions and damage. Rich snapshots also
retain available actor, equipment, inventory, projectile, graphic, object, terrain,
collision and variable channels. Capture settings and coverage describe which
channels were enabled, available or partial.

## Observations and identity

Callback samples retain their original tick, client cycle, sequence and available
actor/native-context fields. A later settled sample does not replace a missing
callback value. NPC/player lifetimes and scene/view/plane scope remain separate;
reused slots and logical reloads must not join unrelated entities.

Scene-object and ground-item membership is reconciled from exposed scene arrays
and bridge links on each captured tick. Independently readable fields remain
available when optional metadata fails. Such failure is not an observation that
the entity disappeared. Prior positions or names are not carried forward as
current observations after a failed read.

## Damage and attribution

Record the observed amount, recipient, hitsplat type, ownership, impact tick and
available callback context. Preserve local incoming damage, local-owned damage
and other observed hits on NPCs separately, including observed zeroes.

Source matching can use recorded projectile, actor, animation, interaction and
surface evidence. A source label is accompanied by its attribution basis and
confidence. Competing candidates remain available; unresolved damage remains
Unknown. A name, nearby entity or interaction pointer does not establish a
unique physical attacker or hidden server action.

Internal launch/impact associations and cycle estimates may support reconstruction
of recorded hits. They retain their evidence and availability separately from
observed timestamps and do not become live instructions or future observations.
Observed definition/form changes likewise remain distinct from reference-based
interpretations.

## Partial data

A failed field, an observed null and an observed zero are different states.
Capture issues and availability flags must preserve those distinctions. Failure
in one actor, object or channel must not erase independently readable neighbors.
Export checkpoints/deltas must preserve partial observations and recovery without
turning a metadata failure into false membership removal.

Unknown IDs remain useful raw observations even when no bundled lookup matches.
Client-bound reference rules are applied only within their verified scope;
unqualified interpretations remain unavailable while raw capture continues.

## Scene census and lifecycle events

Object callbacks maintain scoped lifetime identities and preserve their original event
payloads. Settled membership still reconciles the currently exposed scene: a missing
callback cannot hide an object or item, and a callback alone cannot override an object
that remains in the scene. Object definitions, renderables, configuration and item
quantities are read as current fields rather than carried forward from a spawn event.

Plane discovery, object capture and ground-item capture share one native-view census
within a capture call. The census records array occupancy, bridge links, tile planes,
object planes and read failures once. Plane discovery consumes that evidence instead
of performing a second full array/bridge/object-slot scan. The census is discarded at
the end of the capture; no prior tick or different view supplies current membership.

A general event-only replacement would change the recording contract. The available
lifecycle callbacks do not establish every in-place paint/model/configuration change,
and the recorder also preserves observations when a lifecycle notification was missed.
Necessary terrain and current-field polling therefore remains. Failed enumeration,
failed metadata and a readable empty scene remain distinct, with explicit coverage.
Synthetic comparisons measure the cost of these reads; they do not establish client
frame time or prove that all client behavior has been exercised.

## Storage and export

Deaths and saved recordings stay in the RuneLite data directory. Export completion
preserves duplicate journal actions/hits and does not rewrite the saved capture.
Unchanged channels stream through the current exporter without a full JSON tree.
Large current clipboard copies use a lossless gzip/Base64 JSON transport; the
plain export API remains ordinary JSON. Heavy copy preparation runs off the UI
thread, with clipboard ownership and result messages returned to the EDT.

The distribution artifact includes the factual lookup resources and generated
capture metadata. Compile-target provenance and runtime verification are labelled
separately; a compile dependency checksum is not a measured runtime-JAR checksum.

## Release verification

Use the exact release candidate and Hub build route. Check packaged resources,
qualified and unavailable reference paths, field-failure isolation, damage ownership,
export reconstruction and the existing replay/snapshot flows. The local runnable
client is a test artifact, separate from the plugin JAR distributed by the Hub.

Limited owner feedback supplements these checks. A new client launch or restart
requires explicit owner instruction. Exhaustive encounter testing and video
collection are not release requirements.
