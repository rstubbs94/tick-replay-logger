# Replay export 1.3 - current capture contract

All new rich exports use the stable top-level discriminator `schemaVersion: "1.3"`. This applies to small inline NPC populations and large shared NPC populations alike. The current producer, schema checks and synthetic contracts qualify this latest format. Older formats are best-effort imports with a missing-details warning; they are not release blockers and are never relabelled as 1.3.

The envelope contains `meta`, recorded `scenes`, ordered `ticks`, `events`, `summary`, available `idToName` references and the original `journalTicks`. Metadata retains the capture build, API/client artifact identities, catalog epoch, channel settings and clock boundaries. Missing, failed and disabled observations remain unavailable rather than becoming zero, false or observed absence.

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
It describes the best supported source after the hit. It never replaces the
recorded amount, hitsplat type, original source fields or candidate observations.

Optional source hints can also retain a source-qualified body association,
graphic creation or exact public system response. These remain `POSSIBLE` and
leave an unidentified alternative. A system response does not establish an item
instance or accepted input; its reference family is separate from an actor ref.

| Field | Meaning |
| --- | --- |
| `version` | `1` for this source-resolution contract. |
| `status` | `IDENTIFIED`, `ALTERNATIVES` or `UNIDENTIFIED`. |
| `identityLevel` | `ACTOR`, `FAMILY` or `NONE`. Source identity is separate from identifying the exact attack. |
| `candidates` | Remaining source hypotheses, each with a stable key, usable name, evidence and `STRONG` or `POSSIBLE` strength. |
| `excludedCandidates` | Hypotheses ruled out by an applicable constraint, with the rule and comparison retained. |
| `unidentifiedPossible` | An observation gap or unqualified correlation leaves room for another source. |
| `catalogId` / `method` | The reference catalog and resolver used. |

Candidate identity can include `actorRef`, `npcId`, `familyKey`, `sceneKey`,
`worldViewId` and `plane`. An actor match requires its recorded lifetime and
native scope; identical names never establish identical actors. A family match
can establish the source family without selecting an individual minion, object
or tile. `IDENTIFIED` means one supported source hypothesis remains; it is not
server-confirmed causality. No numerical probability is invented.

Primary labels use the source names, such as `Entity A`, `Entity A or Entity B`,
or `Entity A or unidentified source`. With no named candidate they use `Unknown`.
Evidence and excluded alternatives remain available separately.

Maximum-hit rules use their own declared applicability. An ordinary base profile
requires the observed variant, style and modifier facts; it cannot cap an unknown
special attack. Independently reviewed component or whole-NPC ceilings have their
own exact source, context and callback requirements. Missing facts never default
to normal values. A component limit cannot erase a separately supported contact
or area hypothesis. Every observed amount and zero-damage callback remains intact.

An actual projectile source can occupy a different native view from its target.
Its original actor reference and source scope remain separate from impact scope.
Later model, level, form or animation readings cannot replace unavailable or
contradictory original facts for numerical exclusions. Shared or reused projectile
witnesses retain uncertainty about their association with individual hits.

Additional supporting fields are optional:

- Incoming journal `DamageEvent.observationSequence` links to the same received
  rich callback by tick and sequence, without matching merely on amount.
- Rich damage `impactSceneKey`, `impactWorldViewId` and `impactPlane` retain the
  original callback scope where available.
- Projectile `proximitySourceCandidate` records a structured hypothesis. It is
  separate from the API's `sourceRef` and includes its own scope and availability.
- `sourceHistory.firstObservedSource.proximitySampleRetained` records that the
  first proximity-sample outcome was retained, including a null or failed read.
  Its optional `proximityCandidate` is an immutable original hypothesis; later
  current samples cannot replace it. Observation issues still distinguish a
  failed read from a successful null. First observed is not proof of server launch time.
- Rich `summary.sourceResolution` and wrapper `fatalSourceResolution` mirror the
  chosen fatal record's result without changing the original fatal-hit facts.

All resolution uses already recorded observations. There are no future gameplay
instructions or automatically generated attacks, damage or player actions.

## Observed hitsplat compatibility

`hitsplatTypeKnown` is an optional damage-record availability flag. True records
an original type read, including a real type zero. False records an unavailable
read. Older records without the flag can use a known nonzero type as best-effort
information; missing/default zero does not prove a corruption hitsplat.

The resolver checks complete, source-qualified outcome signatures before numeric
bounds or source consensus. Signatures and observed-cue mechanism bindings come
from the reference data. A mismatch adds `HITSPLAT_TYPE_MISMATCH` to excluded
hypotheses; numerical-reference recovery cannot revive that contradiction.
Unknown types, incomplete rules and unqualified mechanisms stay unestablished.
Ordinary hitsplats do not distinguish melee, ranged and magic attack styles.
Raw amounts, type IDs, attribution fields and observations remain unchanged.

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

The bundled catalog can declare item-operation bounds, observed outcome types,
system-response hints and projectile endpoint clues. The resolver applies the
same operators to each qualified declaration. A canonical item ID, operation,
source reference and source-page hash must match before an item rule applies.
An observed input is not proof that the action was accepted or executed.

Source qualifications use facts observed at the relevant callback. A later NPC
form, combat level, definition or native view must not supply missing earlier
facts. Source-wide ceilings are distinct from ordinary-attack ceilings: an
ordinary attack profile cannot exclude a separate contact or area component.
Overlapping viable source models remain alternatives until the recorded evidence
rules each one out.

Projectile endpoint clues retain the original observed API source, its native
scope and the latest pre-hit destination and client end cycle. Their timing
window is an analysis policy, not a measured server impact clock. They remain
`POSSIBLE`, retain an unidentified alternative and do not establish attack style,
an ordinary-attack damage ceiling or a unique hit assignment. Original raw
attacker and damage fields remain unchanged.

## Export completion and old files

The copy/export helper completes journal facts only for an actual 1.3 payload, preserving duplicate actions and hits and leaving the saved capture unchanged. Older and unknown rich payloads pass through without a version upgrade or migration. Their missing channels/details remain unknown. Historical format documents and recordings are retained as history, not current validation targets.

Current synthetic exports must identify 1.3 and satisfy the current capture/schema constraints. Passing those controls does not certify live or all-game correctness, active encounter settings, source eligibility or observed damage causality.
