# Replay export 1.3 - current capture contract

All new rich exports use the stable top-level discriminator `schemaVersion: "1.3"`. This applies to small inline NPC populations and large shared NPC populations alike. The current producer, schema checks and synthetic contracts qualify this latest format. Older formats are best-effort imports with a missing-details warning; they are not release blockers and are never relabelled as 1.3.

The envelope contains `meta`, recorded `scenes`, ordered `ticks`, `events`, `summary`, available `idToName` references and `journalTicks`. Metadata retains the capture build, API/client artifact identities, catalog epoch, channel settings and clock boundaries. Missing, failed and disabled observations remain unavailable rather than becoming zero, false or observed absence.

Current capture continuously retains original observations. Death or manual Save
selects one frozen window; background processing adds source annotations and
derives `journalTicks` from those events and the minimal tick state. Journal hits
follow original callback order and retain their `observationSequence` IDs. The
summary includes `totalDamageDealtInBuffer`, summed from recorded `DEALT` hits.
These derived views do not replace the original events or world observations.
Plain recordings from the published plugin remain supported for local playback;
intermediate development rich exports have best-effort compatibility.

## NPC representation

An ordinary current frame carries its full `npcs` array. Large current exports may instead carry top-level `npcStateTable` with `format: "shared-blocks-v1"`, `blockWidth: 8`, ordered fields, values, blocks and complete rows; frames then carry ordered `npcStateRefs`. The table format and table presence identify sharing. Sharing does not change `schemaVersion` and is not an event delta or a replacement for missing observations. Native additional views retain their own frame, plane, membership and scope.

Current density controls reconstruct every original NPC field, unsets, order and view membership, and retain the existing 64 MiB wire limit. They compare current inline versus shared representations, without upgrading historical recordings or requiring old-format compatibility certification.

NPCs can include `worldArea` with inclusive `minX`, `minY`, `maxX`, `maxY` and
`plane`, alongside `worldAreaKnown`, `worldAreaWorldViewKnown`,
`worldAreaWorldViewId` and `worldAreaEvidence`. These preserve the direct public
actor API's occupied area independently of rendered coordinates and definition
size. A missing point read does not erase a separately observed area. Neither the
area nor its presence establishes a damage footprint, attack or activation.

`nativeContextKnown: true` on a frame means its actual native view, view ID,
plane and scene identity were available and stayed consistent across sampling.
A failed or changed scope cannot become a positive witness. Unavailable scope
does not erase independently captured actor or damage facts.

## Optional compact JSON transport

Large current clipboard exports may use a lossless transport envelope. The capture
schema remains 1.3; the decoded document is the complete ordinary export, not a
reduced recording. Saved rich recordings and the plain export API remain unchanged.

```json
{
  "schemaVersion": "1.3",
  "transportEncoding": "gzip-base64-v1",
  "uncompressedBytes": 1234567,
  "payload": "base64-encoded gzip bytes"
}
```

`payload` is standard Base64 of gzip-compressed UTF-8 JSON. `uncompressedBytes`
is the exact decoded UTF-8 byte length, not a Java character count. Compression
is used only for large current exports and only when the full envelope is smaller.
It preserves every field, clock, ordering, observation and unavailable value in
the completed plain payload. Older captures are not relabelled as current.

Readers validate the encoding and current schema, bound decoded bytes while
decompressing, require the declared length to match, and parse the inner JSON.
The existing 64 MiB decoded-data limit still applies. The envelope must not bypass
record count, complexity or playback-expansion limits. Plain current JSON remains
valid input. Compression does not establish attribution or change recording facts.

## Recorded timing

Tick-log entries can include `tickDurationMs`, `pingMs` and `packetLossPercent`.
`tickDurationMs` measures the time between game-tick callbacks reaching the
client. It is not a measurement of server processing time. An unavailable or
discarded interval does not establish that a tick took zero milliseconds.

Ping is sampled periodically while logged in. `pingMs` is the latest available
successful world-ping sample, rather than a new measurement on every tick. A
failed attempt can leave the previous sample in place. Capture settings determine
whether timing and ping are recorded.

The legacy field name `packetLossPercent` describes the percentage of failed
attempts in the recent ping window. It does not measure loss of game packets.

## Callback and damage facts

Raw callbacks retain original actor/effective-definition samples, field availability, callback clocks, actor/view identity and placement only where observed. Source references, settled observations and later readings cannot silently replace missing callback facts. Damage records preserve recipient ownership, amounts, known and unknown attribution, position availability and evidence. A source candidate does not prove a mechanic, successful input or causal attacker.

## Optional resolved sources

Current damage records can add `sourceResolution` without changing schema 1.3.
It describes where the hit most likely came from, worked out after capture. It
never replaces the recorded amount, hitsplat type or observations.

| Field | Meaning |
| --- | --- |
| `version` | `1` for this source-resolution contract. |
| `status` | `IDENTIFIED` (one source remains), `ALTERNATIVES` (several remain, or one remains with only weak evidence) or `UNIDENTIFIED`. |
| `identityLevel` | `ACTOR` when every candidate carries an actor reference with its scene, world view and plane; `FAMILY` otherwise; `NONE` with no candidates. |
| `candidates` | Remaining sources, each with a unique `key`, usable `name`, `familyKey`, `STRONG` or `POSSIBLE` strength and an `evidence` code. |
| `excludedCandidates` | Optional. Not produced by `RESOLVER_V2`; older recordings may contain it. |
| `unidentifiedPossible` | False only for `IDENTIFIED`. |
| `catalogId` / `method` | The bundled resolver catalog version and `RESOLVER_V2`. |

`IDENTIFIED` has exactly one candidate. It means one supported source remains in
the recorded evidence; it is not server-confirmed causality. Labels join candidate
names, such as `Zulrah` or `Snakeling or Zulrah`; with no candidate they show
`Unknown`.

Evidence codes, from strongest to weakest:

| Code | Recorded evidence |
| --- | --- |
| `STATUS` | Poison, venom, disease, burn or bleed hitsplat while that status is recorded. |
| `STATUS_APPLIED:<tick>` | As `STATUS`, naming whatever hit the player on the tick the poison counter turned on ("Poison from Kalphite Soldier"). |
| `PROJECTILE:<id>` | A projectile aimed at the player whose end cycle matches the hit. Projectiles landing on one cycle are matched jointly with splats, under max hits. |
| `SURFACE:<id>`, `CONTACT_NPC` | A reviewed damaging object or contact NPC on the player's tile in that frame or the previous one. |
| `GRAPHIC_ON_TILE:<id>` | A cache-named hazard graphic on the player's tile; `STRONG` when it ends on or just after the hit. |
| `TILE_PROJECTILE:<id>` | A projectile aimed at a tile landing on, or next to, where the player stood. |
| `ATTACK_TIMING:<animation>` | An NPC targeting the player whose attack animation lands on this tick by the hit-delay rules. |
| `SELF_ITEM` | A listed self-damage item used on the hit tick or the tick before. |
| `NEARBY_ATTACK` | An attack-named animation from a nearby NPC not targeting the player. |
| `OWN_ACTION:<option>` | The player's own scenery or NPC click, used when nothing attacked them (pickpocket, nettles). |
| `MESSAGE` | A game message at the hit, used last; its text is the candidate name. |

A source is removed only by a positive fact: an amount above the source's max hit
for that attack style (unscaled content only), a different plane, or an attack
that already landed on an earlier tick. Scaled content (raids and modes) never
uses max-hit exclusion. Missing facts weaken strength; they never exclude.

Additional supporting fields are optional:

- Incoming journal `DamageEvent.observationSequence` links to the same received
  rich callback by tick and sequence, without matching merely on amount.
- Rich damage `impactSceneKey`, `impactWorldViewId` and `impactPlane` retain the
  original callback scope where available.
- Rich damage `impactContextEpoch` records the capture-context generation at the
  original hit callback. `finalizationContextEpoch` records the generation when
  that hit was finalized. Observation `data.causalSceneEpoch` and projectile
  source-history `contextEpoch` use the same counter. Missing values remain unknown.
- Projectile `proximitySourceCandidate` records a structured hypothesis. It is
  separate from the API's `sourceRef` and includes its own scope and availability.
- Rich `summary.sourceResolution` and wrapper `fatalSourceResolution` describe
  the hits in the death tick without changing the original fatal-hit facts.
- Hits whose HP effect is unknown keep their resolution on the hitsplat
  observation (`data.sourceResolution`) beside `data.attributionContext`.

Prayer and status explanations are also written after capture: a prayer switched
off without the player's click is explained by the projectile that landed that
tick, and a `Poisoned`/`Venomed` effect names what hit the player as it began. A
derived switch-off duplicating the player's own click is dropped.

All resolution uses already recorded observations. There are no future gameplay
instructions or automatically generated attacks, damage or player actions.

## Observed hitsplat compatibility

`hitsplatTypeKnown` is an optional damage-record availability flag. True records
an original type read, including a real type zero. False records an unavailable
read. Older records without the flag can use a known nonzero type as best-effort
information; missing/default zero does not prove a corruption hitsplat.

A poison- or venom-coloured hitsplat while the recorded poison counter shows no
such status (including anti-venom immunity) is a one-off hit in that colour and
is resolved from the other evidence; with nothing else it remains the status.
Ordinary hitsplats do not distinguish melee, ranged and magic; a damage type
comes from the projectile's or attack animation's cache name when known. Raw
amounts, type IDs and observations remain unchanged.

## Partial object and graphic samples

An optional metadata failure does not remove an otherwise observed scene object
or graphic. Partial rows retain independently readable current fields and may add:

- `fieldAvailability`: flags for attempted API reads; false means the read was unavailable.
- `observationIssues`: field-specific failure reasons.
- `nullFields`: reads that succeeded and returned null, kept separate from failures.

These diagnostics are omitted on ordinary successful rows. Object coverage keeps
`membershipStatus` separate from metadata completeness and reports
`currentMetadataFailures` (partial object-record count) after metadata failures
have been observed. A metadata
failure can make coverage partial while the exposed membership scan is complete.
It does not by itself produce an object-removal delta. Recovery updates the same
observed object key without borrowing old field values for the failed tick.

Graphic lifetime fields such as `startCycle` remain unavailable if their getter
fails; zero is retained only when it was actually read as zero.

Object hazard samples can also retain `objectNativeScopeKnown`,
`objectWorldViewId` and `objectPlane`. These are independent reads of the object's
own API scope. Recorded scene bounds may be interpreted only in their positively
matching native scene; an unknown or foreign owner cannot borrow the collection's
coordinates. Multi-tile conditional contacts use actual game-object bounds or
reported server-tile membership, without deriving a centre, activation clock or
damage area from the model.

## Build and runtime reference provenance

The Gradle Hub build generates capture metadata and bundles it in the plugin JAR.
Full artifact checksums identify compile dependencies, not measured loaded JARs.
The additive metadata fields make that distinction explicit:

- `runeLiteArtifactIdentityScope`: `COMPILE_CLASSPATH_REFERENCE` for generated metadata.
- `runeLiteCompiledApiVersion`, `runeLiteCompiledApiArtifactSha256` and `runeLiteCompiledClientArtifactSha256`: the resolved compile target.
- `runeLiteRuntimeVersion`: the loaded client version when readable.
- `runeLiteRuntimeReferenceVerification`: the result of the once-cached version and selected class-resource checks.

The existing qualified `runeLiteApiVersion`, `runeLiteApiArtifactSha256` and
`runeLiteClientArtifactSha256` fields are supplied only when the runtime reference
check matches. Their checksums still identify the compile reference artifacts.
They are not full runtime-JAR checksums. A missing or conflicting reference leaves
client-bound interpretations unavailable while preserving raw observations and
the readable compile-target provenance.

## Factual runtime references

Current captures retain factual identity, effective-definition/form, native context,
status and guarded damage-source references. The compact runtime catalog carries
`runtimeProjection` metadata and the source catalog identity. It omits the research
encyclopedia and supplemental annotation corpus. New captures do not emit
`supplementalReferenceCandidates` or `actorSupplementalReferenceCandidates`.
Recorded animation/frame/speech and observed definition changes remain raw facts.
Supporting internal reconstruction fields are also retained: action/onset evidence,
phase-cue provenance and cycle estimates. These interpretations remain separate
from the observed fields; a cycle estimate is not an observed future event.

## Qualified retrospective source hints

The resolver catalog (`resolver-catalog.json`) is generated from wiki and cache
data and contains facts only: NPC max hits overall and per combat style, attack
speeds, a scaled-content flag, cache-named attack animations and projectile
styles, prayer-attack projectiles, labelled hazard graphics, reviewed damaging
surfaces and contact NPCs, and self-damage items. Missing entries leave the
generic rules in place; they never exclude a source.

## Export completion and old files

The copy/export helper completes journal facts only for an actual 1.3 payload, preserving duplicate actions and hits and leaving the saved capture unchanged. Older and unknown rich payloads pass through without a version upgrade or migration. Their missing channels/details remain unknown. Historical format documents and recordings are retained as history, not current validation targets.

Current synthetic exports must identify 1.3 and satisfy the current capture/schema constraints. Passing those controls does not certify live or all-game correctness, active encounter settings, source eligibility or observed damage causality.
