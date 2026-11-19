# Replay export 1.1 - historical capture contract

This document preserves the earlier capture format. The current contract is
[schema 1.3](export-schema-v1.3.md); historical validation notes below are not
active release requirements.

## Independent prayer observations (5 November)

Local `HITSPLAT` observations include `data.localPrayerState` when callback identity
establishes the recipient as the local player. Received damage retains the same
immutable sample as `defenderPrayerStateAtImpact`. Version1 includes `flags`
(prayer name to observed boolean), `expectedFlags`, `flagsComplete`,
`reportedPrayers`, `activePrayers`, `overheadPrayer`, `overheadPrayerKnown`,
`overheadIcon`, `overheadIconKnown`, `overheadEvidence`, `overheadConflict`,
`iconSource` and independent `observationIssues`.

Missing flags are unavailable; false flags were read as inactive. Known null icons
mean no icon was displayed when read, distinct from an unread icon. A failed flag
does not erase other flags or the original actor icon. Callback actor state is
reused without another local-player/icon lookup. Snapshot reconciliation uses the
same rules but remains a separate, later observation. Unmapped raw icons and
competing overhead flags remain unresolved; enum order does not select an answer.

Legacy `impactPrayerStateKnown` is true only for complete flags and a resolved
overhead. Partial positive flags remain inspectable with it false. Snapshot
`prayerStateKnown` has the same completeness/resolution requirement. Raw flags and
icon availability are independent of that aggregate flag. Unidentified recipients
do not borrow local flags or later tick state; NPC/other-player recipients never
receive local prayer flags. An explicit unavailable structured state preserves
this distinction after same-boundary recipient resolution.

These are client observations, not server damage-roll or mitigation proof. The
additive contract and controlled handler regressions do not establish live
encounter conformance or exact prayer timing for every attack.

## Passive sound callbacks (5 November)

`events.observations[]` also retains `SOUND_EFFECT` and `AREA_SOUND_EFFECT` callbacks.
`data.soundId`, `reportedDelay`, `consumedWhenObserved` are copied independently at
observation time. Area events add `sceneX`, `sceneY`, `reportedRange`. These are raw
API values; no delay-to-tick conversion or audible-playback assertion is made.
`playbackConfirmed` is false. Subsequent plugins/processing can modify or suppress
the event; volume and listener state are not established by it.

`sourcePresent` distinguishes no supplied actor from a failed source read. Optional
`reportedSource` stores callback-time actor name, reported NPC ID and source actor
coordinates/view independently, not a damage cause. Its `actorRef` uses an already
registered session identity only; queued/retired sources cannot create lifetimes.
Other-player privacy and unavailable ready context withhold actor details while
retaining the primitive sound facts. `observationIssues` retains independent read
failures. Existing bounded observation retention and omission accounting apply.

Neither sound event exposes its own native world view or plane. `scopeKnown` is
false and the observation has no scene key; area `positionScopeKnown` is false.
Source actor coordinates do not establish the sound's coordinate frame or location.
No hazard, attack, phase, audible playback or individual hit is inferred from these
callbacks. Focused synthetic controls are separate from live/API conformance.

This format dates from 2026-11-02. Large repeated NPC data could use the
[lossless 1.2 representation](export-schema-v1.2.md). It records client-observable
fight state and events at captured ticks; hidden server decisions remain unknown.

## Additive systemic-capture fields (2026-11-02)

`meta.referenceCatalogId` identifies the bundled factual reference snapshot.
Reference-based labels remain distinct from raw observations. See the current
[export contract](export-schema-v1.3.md) for the active format.

- `ticks[].objectBaseline`: clear object state before applying `objectChanges[]` full replacement
  states keyed by session `objectKey`, then `objectRemovals[]` keys. Each selected window,
  scene/plane/gap boundary and return after a failed channel receives a full checkpoint.
  Unchanged objects are not repeated; `observedObjects` is in-memory only.
- `collisionBaseline`, `collisionWidth`, `collisionHeight`, `collisionChanges[]` reconstruct
  current collision state per tick. Checkpoints contain nonzero flags; deltas include zero
  flags to remove walls/barriers. Every selected window, scene/plane/gap boundary and recovery
  after unavailable collision starts with a checkpoint. Failed/disabled capture is unknown,
  not a reuse of the old scene baseline.
- Scene and tick `worldViewId` identify the active native frame. Scenes retain actual width/
  height, instance flag and cloned `instanceTemplateChunks`; scene epochs change on view/
  plane/base/instance-layout changes. Additional discovered native views and available plane
  channels are recorded separately; see the native-frame contract below.
- `context.observedVariables` contains named raw encounter values and `evidence` states
  their interpretation/availability. `teamSize` is distinct from `scaledTeamSize`; configured
  ToA level is not raid presence and level0 is valid. Unverified CM/invocation enums stay null.
  `resources[]` carries key/label/rawValue/divisor/maxRaw/unit/evidence: e.g. observed Wintertodt
  warmth11434 divided by10 is a resource percentage, not HP damage.
- Objects preserve all four kinds, `baseId`, nullable effective `id`/name/definitionEvidence,
  plane/worldViewId, nullable local/world positions, exposed inclusive scene bounds,
  controlling varbit/varp/value, orientation/config, offsets and model animation IDs/frames.
  No name whitelist controls capture; unavailable transforms are not guessed.
- `varpBaseline[]` is full indexed state; `varpChanges` maps changed indexes to observed values.
  Selected windows/scene/gap boundaries include checkpoints. GAME_VARIABLE_CHANGED callbacks
  retain varp/varbit ID, value and long value as a decimal string. Names do not prove a phase.
- `captureCoverage` reports available/unavailable/failed/partial/disabled channels;
  `captureIssues[]` records partial failures. `objectCoverage` reports scope/count/drops/failures/
  completeness. Missing channels are not empty scenes and must not carry stale state.
- NPCs include base/effective definition identity/evidence, reference candidates/keys and
  action evidence; optional `observedPhase`, `phaseRule`, `phaseEvidence`, `phaseObservedTick`
  come from reviewed observable predicates. Physical forms remain definition observations.
- Actor `localPositionKnown`/`worldPositionKnown` distinguish unavailable positions from real
  zero coordinates. Player `baseSkillLevels`/`boostedSkillLevels` cover all skills, including
  disease/noncombat drains already active at the selected window boundary.
- Ground graphics cover the loaded current plane, retaining worldViewId, height, animation/
  frame, start cycle and finished flag independently of the old near-player graphics radius.
- New raw callbacks cover all four object lifecycle families, GRAPHICS_OBJECT_CREATED,
  ACTOR_GRAPHICS_CHANGED, OVERHEAD_TEXT_CHANGED and SKILL_STATE_CHANGED. Callback-only
  transient observations survive without invented settled entities.
- SYSTEM_MESSAGE preserves GAMEMESSAGE/SPAM channel and message, not user/private chat.
  PLAYER_SPAWNED/PLAYER_DESPAWNED retain optional other-player lifecycle evidence.
- HITSPLAT data includes resourceSurface, actor state, local HP at callback and independent
  ownership flags. HP/BLOCK, prayer, sanity, poise, stacks, healing and contextual surfaces
  remain distinct. Ambiguous disease/coloured hits do not automatically become HP damage;
  actual HP/skill changes remain visible and uncertain late hits can invalidate an old fatal
  attribution. Old exports without a surface are not retroactively reclassified.
- Optional referenceCandidates/actorResearchKeys link possible mechanics, not confirmed
  effects or causes. Cache-named freeze/teleblock graphics have candidate evidence; they do
  not establish that an action disable succeeded.

Scope is exposed loaded native scene/plane state plus callbacks, not server-global state.
Dynamic collision, native floor appearance and additional discovered frames are recorded;
live conformance, unavailable channels, exact composition and causal/mode predicates remain
[audit gates](../../../osrs-pvm-research/REPLAY_AUDIT_ACTIONS.md).
Old captures cannot acquire uncaptured facts.

## Tick and callback ordering

- `ticks[]` contains settled client state after packet processing for each GameTick.
- `tick` is the plugin session tick counter, not a globally unique server tick ID.
- Each snapshot has `gameCycle` as well as `wallClockMs`. Each raw callback has `gameCycle` and
  a strictly increasing session `sequence`, retaining order even when callbacks share a tick/cycle.
- Callbacks before the settled snapshot are grouped into that snapshot's tick. Input clicks happen
  independently of server ticks; their assigned tick does not prove when the server executed them.
- Current actor refs are `player`, `npc:<slot>:v<viewId>:g<lifetime>`, or
  `otherplayer:<playerId>:v<viewId>:g<lifetime>`. Actor snapshots and callbacks share the same
  recording-local reference; a name is not identity. Unknown view is explicit. Old files may
  use `npc:<slot>` / `otherplayer:<name>` and retain their older lifetime ambiguity.
- Projectile `observationId` is a session-local identity, not a projectile type ID. Repeated samples
  of the same projectile retain it. A projectile's `projectileId` identifies its graphic/type.
- Projectile `sourceWorldViewId`, `targetWorldViewId`, `sourcePlane`, `targetPlane` retain
  independent endpoint context; `worldViewId`/`coordinateEvidence` qualify sampled coordinates.
  Unresolved coordinates remain raw and are not overlaid on an unrelated native map.
- Attack/hit prayer timing requires reviewed individual NPC/projectile predicates. A boss name
  alone returns UNKNOWN; its melee/special attacks cannot inherit a generic timing default.
  `damageEvents[].prayerEffect` may be `NONE_REVIEWED` for a scoped source rule; null does not
  imply full protection. Damage/accuracy terminology and prayer effectiveness are independent.

RuneLite documents [GameTick after packet processing](https://static.runelite.net/runelite-api/apidocs/net/runelite/api/events/GameTick.html)
and the distinction between client input and server ticks.

## Envelope

`schemaVersion: "1.1"`; existing meta, scenes, world snapshots, events and summary remain present.

- `journalTicks[]`: the original journal ticks, including every captured action and both damage
  directions. No UI-time relabeling or deduplication is applied to this data.
- `meta.captureSettings`: worldSnapshots, sceneCollision, otherPlayers, effects, damageReceived,
  damageDealt, hazardRadiusTiles (currently 12), projectileMode (`PER_TICK_OBSERVED`), and
  tickConvention (`CALLBACKS_BEFORE_SETTLED_SNAPSHOT`). These describe capture coverage; disabled
  or unavailable capture must never be presented as proof that nothing happened.
- Death exports have `meta.deathTick`; manual live exports instead have `meta.snapshotTick`.
- The export covers the selected rolling window, currently 100 ticks by default. Earlier parts
  of a fight cannot be reconstructed unless they were recorded. No new upload/account behavior.

## Raw observations

### Additive object-hazard fields (2026-11-02)

`ticks[].hazards[]` can include `baseId` (original scene object), with `id` set to the current
resolved object definition. `state` is `inactive`, `warning`, `active` or `unknown` for verified
floor definitions; `damaging` is nullable (missing/null means unclassified, not safe).
`stateEvidence` records `OBJECT_DEFINITION`, `TRANSFORMED_OBJECT_DEFINITION` or
`TRANSFORM_UNAVAILABLE`, and `mapRef` identifies the classification source.
`minSceneX/minSceneY/maxSceneX/maxSceneY` are optional inclusive observed object tile bounds;
ground objects occupy the recorded tile, game objects use their actual scene min/max API bounds.
An object's footprint does not independently prove the damage area of its mechanic.

Object and graphic IDs are separate namespaces. Missing historical floor observations cannot
be recovered from an environmental damage event. Normal/CG Hunllef floor definitions distinguish
inactive/warning/active states; no pattern or future activation tick is predicted. These optional
fields remain compatible with existing 1.1 readers. No new object lifecycle callbacks are claimed.

### Callback envelope

Actor snapshots retain `currentOrientation` separately from the existing target `orientation`.
The tactical map uses sampled client facing when available; both raw angles remain in the export.
Optional `animationFrame`/`poseAnimationFrame` retain sampled frame positions, and other-player
snapshots also retain `poseAnimationId`, observed health-bar ratio/scale and overhead text/cycle.
Health-bar fractions are client observations, not exact other-player HP. Animation IDs/frames do
not expose a server attack roll or cooldown.

`interaction` baselines apply to the local player, NPCs and other players. They retain the API
interaction flag, allowed partner reference/name/kind/view and explicit evidence. An unavailable
partner is distinct from `NO_INTERACTION_REPORTED`; disabled other-player capture redacts its
identity. Interaction can mean following or trading and is not proof that damage was dealt.

`appearance` archives copied encoded composition IDs, the API's item/kit offsets, API-decoded
visible item slots, a per-slot known mask, NPC transformation and body colours. Local actual
equipment still comes from its inventory container. Other-player `equipmentItemIds` are now
decoded appearance IDs with `equipmentEvidence`; they are not proof of server inventory.
Ring/ammo and non-inventory kit slots remain unknown. A transformed player's equipment is not
established from its cached composition. Older other-player gear without this encoding evidence
is not decoded or linked to weapon mechanics by the viewer. `PLAYER_CHANGED` archives appearance
and actor state between settled snapshots, subject to the existing other-player setting.

Primary API contracts: [Actor](https://static.runelite.net/runelite-api/apidocs/net/runelite/api/Actor.html),
[Angle](https://static.runelite.net/runelite-api/apidocs/net/runelite/api/coords/Angle.html) and
[PlayerComposition](https://static.runelite.net/runelite-api/apidocs/net/runelite/api/PlayerComposition.html).
The getters were also checked in the resolved1.13.4 API before implementation.

`events.observations[]` entries contain:

```jsonc
{
  "tick": 123, "gameCycle": 456, "sequence": 78,
  "type": "HITSPLAT", "actorRef": "player", "actorName": "...",
  "relatedActorRef": "npc:42", "sceneKey": "...",
  "data": { "amount": 10, "hitsplatType": 16, "isMine": true, "isOthers": false,
            "disappearsOnGameCycle": 526, "renderingKnown": false }
}
```

Observed callback types:

| Type | Data |
| --- | --- |
| HITSPLAT | Amount, raw type, ownership flag, expiry cycle; includes unclassified/zero/other-actor callbacks |
| MENU_OPTION | Option, target text, menu action, identifier, item ID and raw parameters |
| ITEM_CONTAINER_CHANGED | Container ID plus actual item IDs and quantities, for inventory/equipment |
| ANIMATION_CHANGED | Actor animation ID, position and NPC ID where applicable |
| NPC_SPAWNED / NPC_DESPAWNED / NPC_CHANGED | Actor/NPC identity and observed state |
| INTERACTING_CHANGED | Actor and related actor ref, including cleared interaction |
| ACTOR_DEATH | Actor death callback; also appears in `events.deaths[]` with cycle/sequence |
| PROJECTILE_FIRST_SEEN / PROJECTILE_TARGET_CHANGED | Observed projectile state and target height |
| SPECIAL_ENERGY_OBSERVED / SPECIAL_ENERGY_CHANGED | Initial observed value, or observed previous/current resource values |

The first resource/status sample is a baseline, not proof of a new attack/status application.
An observed callback is not always a rendered effect: RuneLite explicitly says
[HitsplatApplied can fire for hitsplats that were not rendered](https://static.runelite.net/runelite-api/apidocs/net/runelite/api/events/HitsplatApplied.html).
Do not claim exact on-screen hitsplat visibility from the callback alone.

## Player actions and damage

`events.playerActions[]` retains tick, type, full detail, target, targetRef, itemName/itemId,
gameCycle, sequence and `observationKind`:

- `INPUT`: an observed menu request. An Eat/Drink/Attack click is not confirmation of execution.
- `RESOURCE_CHANGE`: observed resource decrease; not independent proof of a particular attack.
- `DERIVED`: an interpreted journal event, such as the existing prayer-drain heuristic.
- `LEGACY_JOURNAL`: recovered from an older journal; finer observation evidence is unavailable.

`events.damageEvents[]` contains RECEIVED (local player received damage), DEALT (local player's
owned NPC hits), and NPC_RECEIVED (other-owned or unowned status damage on an NPC). Legacy direction
absence means RECEIVED. New outgoing entries retain the actual NPC defender ref and observed
ownership, with direct attacker identity left null unless independently established. Incoming
attacker refs are set only when a candidate actor is identified; attribution evidence qualifies it.

`observedHazardContacts[]` optionally retains concurrent NPC contact witnesses, including on
status hits without assigning the status inflictor. Each witness identifies the actual NPC/actor,
reported world tile/plane, observed size, callback cycle/stage and defender-position evidence.
`contactPredicate` distinguishes `SAME_SERVER_TILE` from `COHERENT_SERVER_FOOTPRINT`; the latter
requires the API area, server anchor and transformed definition size to agree. Footprint width/
height are recorded. Contact is a possible source of an actual received hit, never a synthesized
damage event. Delayed webs, moving waves and pre-spawn telegraphs need their own predicates;
they are not covered merely by occupying an NPC tile. Missing witnesses are not proof of safety.

Known `determination` values now include `determinationEvidence: REVIEWED_ATTACK_CONTEXT` and
`determinationRuleKey` for the specific admitted NPC/projectile/context rule. Overlapping timing
predicates abstain. Old timing claims without that evidence are displayed as unverified. A
`launchStateEvidence: SNAPSHOT` prayer sample belongs to the settled attack-tick snapshot; it
can follow a change within that tick and does not establish the exact prayer at a hidden roll.

For NPC targets, `ownership` is LOCAL_PLAYER, OTHER or UNKNOWN, based on RuneLite's hitsplat ownership
classification. OTHER does not identify a particular teammate, and unowned poison/venom does not
prove which player applied it. Other-owned hits retain an actual defender ref, amount, type, cycle,
sequence and zero-damage blocks, with attacker identity left null. They never inherit the local
player's attack style, inflate personal Dealt totals, or become the local player's fatal hit.
Healing remains a raw observation rather than damage. Unclassified types remain in raw observations.
NPC hits remain observable even when other-player snapshots are disabled; no named teammate is
fabricated from animation/proximity. Player-specific attribution and hitsplat ownership are separate.

RuneLite exposes [mine/others classifiers based on hitsplat types](https://github.com/runelite/runelite/blob/master/runelite-api/src/main/java/net/runelite/api/Hitsplat.java);
the [HitsplatApplied callback identifies the affected actor](https://static.runelite.net/runelite-api/apidocs/net/runelite/api/events/HitsplatApplied.html),
not an attacker identity. Personal journal entries remain scoped to the local player; the viewer's
shared-boss damage timeline must also consume NPC_RECEIVED entries and raw observations.

- `impactTick` is the tick of the processed hitsplat observation, not a computed projectile arrival.
- `observedCycle` / `observationSequence` link damage interpretation to its raw callback.
- `attributionEvidence` states the method: owned hitsplat, projectile timing correlation, NPC/player
  animation correlation, hazard on impact tile, engagement/history fallback, status hitsplat,
  pickpocket failure correlation, or item-consumption correlation.
- `damageTypeEvidence`: HITSPLAT_TYPE, LABELLED_FROM_MAP, UNKNOWN or LEGACY_JOURNAL.
- `launchTickEstimated` is true for the current attribution logic. `launchStateEvidence` is SNAPSHOT,
  IMPACT_FALLBACK or UNAVAILABLE. A fallback defender state is not an observed attack-launch state.
- `determination` remains the Attack tick / Hit tick mechanics label. It does not reveal the actual
  hidden server damage-roll tick. Never move an observed hit/HP change to fit an estimated flight time.
- `summary.fatalHit` considers only received positive damage; outgoing hits and zero blocks cannot
  replace the killing blow.

Potion clicks no longer inject damage or suppress a later hitsplat. A divine-potion label requires
an actual matching hit and observed slot/item change, and remains explicitly a correlation.

## World state and projectiles

Player snapshots include inventory IDs/quantities, equipment, raw poison varp, positions,
animation/graphic IDs, HP/prayer and other existing state. Missing inventory is null, not an
invented empty inventory. NPCs include observed footprint size when available and raw overhead
archive/sprite arrays. World and local coordinate fields preserve their respective API values;
do not assume they are interchangeable actor anchors.

RuneLite Actor `getWorldLocation()` reports the server tile; `getLocalLocation()` reports
the client position, which can lag during movement. Local-player surface candidates now use
the reported tile in its native scene, not the rendered previous tile. If that known tile is
outside the current view, no unrelated rendered tile is substituted. A missing reported tile
can fall back to the observed client tile. Graphics are compared by scene-tile occupancy,
not a circular distance cutoff. Co-location remains candidate evidence, not causal proof.

NPC contact-hazard candidates can include `observations[]` with actual observed `npcId`,
`baseNpcId`, `actorRef`, raw observed name, server tile/plane, observed one-tile size, native
view ID when available, client cycle and `observationStage` (HITSPLAT_CALLBACK or
DEFERRED_RESOLUTION). `defenderPositionEvidence` distinguishes a reported server tile from
the client-render fallback. The observed identity survives a hazard leaving before settlement;
it is not reconstructed from a reference name. No co-location event synthesizes HP damage.
`SCOPED_HAZARD_RULE` identifies an explicitly bound subtype; an environmental source does not
automatically become typeless. Missing subtype evidence stays unknown.

Prayer snapshots retain `reportedActivePrayers` (raw flags), `overheadIcon` (visible actor icon),
`prayerStateConflict`, and `overheadPrayerEvidence`. A known visible overhead supplies the display
label. A single flag can provide PRAYER_FLAGS evidence when no icon is available; conflicting
flags without a usable icon remain AMBIGUOUS. Raw conflicts are never discarded or resolved by
enum iteration order. Incoming damage retains the corresponding impact observations too.

`ticks[].projectiles[]` contains per-tick observations of incoming, outgoing and ground-targeted
projectiles, subject to other-player settings. Each sample retains actual local X/Y/Z, observed
tick/cycle, actor refs where exposed, real source/destination points when available, target-local
coordinates, and start/end cycles/heights/slope.

- `positionEvidence`: TICK_SAMPLE or MOVED_CALLBACK. A short-lived projectile may only have a
  callback sample; do not pretend it survived until the settled snapshot.
- `originKnown` / `destinationKnown` distinguish unavailable points from real zero coordinates.
- `spawnTickEvidence: FIRST_OBSERVED`; the first observation is not a proven server attack start.
- `estimatedImpactTick` remains an estimate. Raw cycle fields and sampled positions are separate.
- `sourceEvidence` distinguishes a real actor from proximity-based source naming or UNKNOWN.
- Ground targets use their observed destination, never a fabricated player destination.

The API documents [ProjectileMoved.position as the target location](https://static.runelite.net/runelite-api/apidocs/net/runelite/api/events/ProjectileMoved.html)
and [Projectile X/Y/Z as current position](https://static.runelite.net/runelite-api/apidocs/net/runelite/api/Projectile.html).

## Legacy Copy recovery and validation

Copy completes older rich payloads with journalTicks, missing actions and outgoing hits. It preserves
duplicate event multiplicity, never mutates saved recordings, and is idempotent. Recovered entries
carry LEGACY_JOURNAL evidence; `exportEnrichment` identifies the original schema and unavailable
historical observations. This does not backfill missing projectile samples, actor refs or cycles.

Validation includes the actual latest Zulrah journal (22 outgoing events, 163 dealt, 53 actions),
ordered mixed damage callbacks, potion click-without-hit, actual consumption, prayer conflicts,
projectile targets/retargeting/expiry, and bounded window retention. The fixture omits dense collision
tiles and the account name; captured combat data is unchanged.

The remaining acceptance check is a fresh client recording compared with video at matching tick
boundaries. Automated export tests alone do not establish literal video-identical playback.


## Overnight additive capture fields (2026-11-03)

The schema remains1.1; old files can omit these optional fields. Missing is unavailable, not zero or false.

- `meta.captureBuildId`: SHA-256 of compiled main source/resources and build definition. `meta.runeLiteApiVersion` identifies the resolved API. Together with `referenceCatalogId`, these identify the actual capture implementation; Git workspace cleanliness is not assumed.
- `captureSettings.sceneTerrain`: whether native floor appearance capture was enabled.
- Tick `terrainBaseline`, `terrainChanges[]`, `terrainRemovals[]`: independent selected-window checkpoints and deltas. Missing/failed terrain invalidates prior materialized state. Each tile retains native x/y/plane, render level, bridge flag, decoded underlay/overlay definition IDs (-1 absent, null unavailable), shape, paint RGB/texture/corners and cloned model vertex/face/colour/texture arrays. Appearance does not establish a damage cause. Raw scene colour encodings are not all RGB; only `paintRgb` is directly rendered as RGB.
- Context `worldId`, `worldTypes[]`, `gameRevision`, and observed `LEAGUE_TYPE` distinguish actual observed context from guessed modes. A missing world-type list differs from an observed empty list. NPC `normalWorldContextObserved`, `modelOverridesPresent`, `overrideModelIds[]` retain the context witness used by base-mode guards. Captured level alone does not establish an Echo/base variant.
- Received damage `attackerNpcId`, `attackerCombatLevel`, `attributionCandidates[]` preserve source-identity evidence and simultaneous alternatives. Projectile candidates include observed source/ref, first-observed/estimated arrival ticks and matching scope. TIMED/PROXIMITY are correlations, not confirmed causal assignments. Ambiguous hits retain the observed damage amount with UNKNOWN source.
- NPC-hit `ownership` is the observed mine/other/unknown rendering ownership. It does not establish a direct attacker or attack style. A currently active player weapon animation cannot label an earlier projectile/reflection/summon hit.
- Projectile `sourceNpcId`/`sourceCombatLevel`, independent source/target view IDs and planes, `targetLocalX/Y` and `coordinateEvidence` retain coordinate ambiguity. A viewer may show an explicitly labelled observed target when the sampled in-flight coordinate frame is unavailable; it must not present the destination as the flight position.
- Effect `journalNote` links the exact parallel journal text for deduplication. Unexplained prayer deactivation is `prayer_state_change`, and boosted-skill deltas are `stat_change`; neither identifies an enemy cause.
- `summary.otherPlayersPresent` is derived from the selected frames. It is null when the channel was unavailable/disabled, rather than falsely claiming nobody else was present.

Self-damage input candidates carry their item ID, input sequence/tick, source page/hash and `INPUT_CORRELATION_NOT_EXECUTION_PROOF`. They never create damage. Actual hits remain necessary; simultaneous combat/hazards preserve competing candidates. Crafting/item-use, delayed dialogue and self-damaging specials still require further bindings.

Further additive fields in the same overnight build:

- NPC `overheadIconsKnown` distinguishes a sampled empty icon array from an older missing channel.
  Player/other-player `overheadIconKnown` does the same for the raw visible actor icon. Prayer
  flags/resolved prayer names do not prove that an icon was visible. Raw archive/sprite slots are
  preserved; a reference glyph is not proof of protection, damage reduction or a captured pixel.
- `damageTypeCandidates[]` retains infobox/cache style hints without making them the hit's type.
  `damageTypeEvidence` distinguishes `HITSPLAT_TYPE`, `REVIEWED_ATTACK_BINDING`,
  `REVIEWED_PROJECTILE_BINDING`, `SCOPED_DEFINITION_RULE` and `UNKNOWN`. Source uncertainty remains
  independent of style evidence. NPC animation, floor and projectile alternatives compete without
  adjacency/history scores; an ambiguous projectile is not consumed.
- `clientIntegerBaseline` (sparse ID/value map), `clientIntegerChanges`, `clientIntegerRemovals`
  retain numeric client-variable state. Client strings/arbitrary objects are excluded.
- `serverVarpBaseline` / `serverVarpChanges` retain int values last reported to the client,
  separately from existing client varps. This does not expose hidden server state.
- `varpLongBaseline` / `varpLongChanges` and `serverVarpLongBaseline` / `serverVarpLongChanges`
  retain signed64-bit values as **decimal strings**, never JSON numbers. Baselines are arrays;
  delta keys are observed array indexes. An index must not borrow an unverified int-varp name.
  Each channel requires an independent checkpoint after a missing/failed tick, gap or scene/view
  boundary. `captureCoverage` reports `clientIntegers`, `serverVariables`, `longVariables` and
  `serverLongVariables` separately.
- Raw `CLIENT_INTEGER_CHANGED` observations retain ID/value and normal callback tick/cycle/
  sequence. `captureSettings.variableMode` names the broader numeric checkpoint/callback scope.
- Effect `linkedActionDetail` identifies its duplicate derived journal action; it never suppresses
  an independent input request. `events.captureGaps[]` retains tick/channel/callback counts and
  omission reason when the raw observation buffer reaches its bound.

Blood Infusion's `SELF_DAMAGE_RESOURCE_CANDIDATE` records a special-energy decrease with a
matching equipped weapon. It is not proof of execution, full-set eligibility, amount or a proc;
it cannot manufacture HP loss. Self-input/resource candidates reset across scene/view boundaries.

### Independent-review corrections (2026-11-03)

`meta.runeLiteApiArtifactSha256` and `meta.runeLiteClientArtifactSha256` identify the resolved
dependency bytes alongside `captureBuildId` and `runeLiteApiVersion`. The development build now
pins RuneLite1.13.6, verifies dependency checksums and records the two artifact hashes.

`summary.fatalHitBasis` states how `fatalHit` was selected. `fatalHitCandidates` retains every
positive received record in the death tick, in observed callback order. A unique record is
distinguished from choosing the last of several. Multiple candidates leave `reactable` null;
the selection is not a hidden server-side lethality observation. Raw positive HP hits still
invalidate an unsupported summary when received-damage interpretation was disabled. A death
callback without a linked same-tick hit remains unresolved; an older hit is not substituted.

`EffectEvent.linkedActionType` plus `linkedActionDetail` identifies a parallel derived journal
entry. Consumers match by occurrence; older prayer aliases share one allowance, and independent
input requests remain separate.

For status hits, `attackerName` can be the observed category (`Poison`, `Venom`, `Bleed`, `Burn`),
with `attributionEvidence: STATUS_HITSPLAT`. This does not identify the status inflictor.
Concurrent item, resource, hazard and attack hypotheses remain in `attributionCandidates`.
Disabling other-player snapshots preserves anonymous PvP hypotheses without names/actor refs;
it must not make a competing NPC become the apparent unique source.
When every admitted attack hypothesis has the same non-null actor reference and observed name,
`SAME_ACTOR_MULTIPLE_ATTACK_CANDIDATES` can retain that qualified actor while leaving specific
attack/type/timing unresolved. It never consumes an ambiguous projectile or merges anonymous names.

`NpcState.attackOnsetEvidence` distinguishes observed animation-ID changes from decreases of
the sampled animation frame. Either can start an **estimated** attack cycle; a frame decrease
can be a restart or loop and is not proof of a fresh server attack. Phase changes invalidate the
old cycle. Null NPC overhead arrays remain unknown; empty/all-unused sampled arrays establish
no observed icons. Prayer flags alone never establish a sampled visible icon.

### Additional native world views

Received-hit interpretation now preserves batch ambiguity explicitly. Several ordinary HP
hits/blocks in the same scope do not receive a one-to-one attack assignment from callback
order. Candidate maps can include `OBSERVATION_BATCH_WITHOUT_UNIQUE_ATTACK_LINK` and an
`observationCount`. Status hits remain independent categories. Arrival-window projectile
candidates include `matchBasis: ARRIVAL_WINDOW` and
`previouslyCandidateForObservationSequences`; a projectile that could already have impacted
cannot become a later unique cause. The unsupported spawn-batch/LAUNCH fallback is removed.
A reviewed projectile-wind-up animation remains an NPC hypothesis when its projectile is
missing; it cannot make an overlapping floor the sole cause by exclusion. All hit amounts,
callbacks and candidates remain recorded, with UNKNOWN source/type/timing where unresolved.

`terrainCoverage` records the selected plane, native-grid availability, actual `gridWidth`,
nullable `columnHeights` and `samplingCompleted`. Completing an API read is not a claim that
every surface or server mechanic was exposed. Disabled and failed sampling remain distinct.
Unchanged producer terrain/object lists share immutable snapshots; later changes and resets
cannot alter earlier ticks.

`ticks[].additionalViews` is a flat array of separately observed native frames at the same tick.
Each has its own `worldViewId`, reported `plane`, `sceneKey`, NPC/player/graphics/projectile samples
and object/terrain/collision checkpoints. It never duplicates the local player's stats or global
variable channels as though they belonged to another area. The local-player/other-player preference
continues to apply. Native coordinates from different views must not be overlaid directly.

Each additional stream carries `nativeScene` metadata at its selected-window first frame, scene
change, tick gap or metadata recovery. Later unchanged metadata is omitted. Object/terrain/collision
baselines and recovery are independent per stream; a missing view is not an empty scene. A bounded
export window remains self-contained. Primary scenes continue to use the top-level `scenes` array.

`worldEntities` retains carrier lifetime keys and API-observed current/target locations and
orientations, parent/child view IDs, overlap and owner-kind flags. Its locations are explicitly
top-level local coordinates. They are observations, not a composed terrain or movement prediction.

`captureCoverage.worldViews` uses `ALL_DISCOVERED_NATIVE_PLANE_CHANNELS` or
`PARTIAL_LOADED_NATIVE_PLANE_CHANNELS`. Discovery includes current planes, non-null scene/collision
array channels, bridge-linked tile/object planes, reported actor planes, graphics and projectile
endpoint planes, and callback-observed planes. This names the discovery scope, not a guarantee
that the client exposes every server entity or that every channel succeeded.
Individual view/channel failures remain explicit and cannot erase the primary frame. The pinned
API's projectile collection is global on `Client`; failed entries are isolated and scoped samples
are routed to their native frames. Unmatched observations remain retained in the primary raw data.
Player movement distances are never computed across different native scopes or planes.

`planeCoverage` retains discovery evidence and, when available, `sceneArrayNonNullTiles`.
An exposed empty array differs from an unavailable array; neither certifies that an encounter
room is safe or that the server contains no entity there. Actors with `planeKnown: false`
remain once in the view's current stream with their raw facts, but have no map placement.
NPC/player membership otherwise follows reported world-location planes, not the view's current
plane. A projectile can retain a flight sample in one plane and an explicitly labelled target
in another; a destination is never presented as its flight position.

Terrain `sampledSceneArrayPlane`, `observedViaBridge`, and `sampleEvidence` retain how a tile was
obtained. `LINKED_CANONICAL_PLANE_TILE` is a bridge-linked tile sampled as the requested plane's
floor when its direct grid/slot was absent. It has `bridge: false` for that frame's display role
and `observedViaBridge: true` for provenance. `BRIDGE_UNDERLAY` remains an under-layer sample;
render level is not substituted for reported plane. Linked-only samples with no selected grid
retain partial terrain coverage. Collision snapshots share immutable unchanged columns in memory;
exports retain independent checkpoint/delta semantics. Missing equipment containers export null,
distinct from a known empty equipment array.

Native auxiliary caches are invalidated on a logical scene-loading transition even if API
container identities are reused. The primary scene key is reserved before callbacks, and its
object/phase caches are reset before those callbacks rather than again at the settled baseline.
Unavailable metadata retries under that reserved key. Incoming hits, pending projectile
correlations, historical attackers and self-damage input scopes include a logical loading epoch;
hit/projectile comparisons also require compatible impact planes. A load cannot erase an already
observed hit or reinterpret it against a reused scene container. Pickpocket input hypotheses
expire at that boundary. Complete event-volume budgets, exact cross-plane causality and live
conformance still require independent review; conservative abstention is not complete attribution.

Phase cue lifetime follow-up: `phaseObservedTick` is the first sample of the current label/rule and `phaseLastObservedTick` is its latest observed cue sample. These are not continuous proof of a phase. The same NPC object shares cue history across primary/secondary plane caches. Logical map loading, changed native view/scene/base/template, incompatible definition/context and despawn clear it; complete discovered-view membership prunes absent actors. Timing/collision/object/terrain cache resets remain separate.

Native lifetime follow-up: `WORLD_VIEW_LOADED` and `WORLD_VIEW_UNLOADED` observations retain `worldViewId`, `causalSceneEpoch` and reset quality. Reusing API containers after an explicit lifecycle event creates a new native scene/actor lifetime. Internal scope comparisons use monotonic weak object identities and complete template arrays, not hash uniqueness. Any view lifecycle event currently advances the global causal epoch conservatively: earlier inferred hit hypotheses become unavailable even if a different loaded area changed. Observed damage/callbacks are retained; this is not proof of per-view causality or exact composed geometry.

Death evidence follow-up: rich `summary` and the journal-only wrapper now distinguish `fatalHitBasis` (record selection) from `killerBasis` (source attribution). Mixed or unlinked death-tick sources do not promote the last callback into a known killer. Rich `fatalHit` may remain the last-callback inspection record while `killerName`/`killerConfidence` remain Unknown. Both formats retain candidate records. `unresolvedImpactObservations` retain raw positive ambiguous-resource or uninterpreted HP callbacks from the death tick, including sequence/tick/cycle/type/amount/resource surface/callback HP/causal epoch. The summary stores at most256 entries and exposes `unresolvedImpactObservationCount`; a larger count is explicit truncation, not complete raw coverage. Rich raw observations remain a separate channel. These metadata do not turn disease or other ambiguous resource values into known HP loss.

NPC contact observations retain stage/lifetime/definition/position/footprint. Sole-contact attribution requires matching callback and settled-stage witnesses; disagreement stays `CONTACT_OBSERVATION_PHASE_CONFLICT`, with `observationPhaseAgreement: UNESTABLISHED` on the candidate. Agreement is a stronger correlation, not causal or packet-order proof.

`captureSettings.damageAmountMode` states that damage amounts are hitsplat callback values. Summed values, sampled HP changes, applied per-hit net HP loss and hidden server rolls are distinct quantities. No per-hit applied HP or uncapped roll is reconstructed. Nominal source damage ranges cannot safely exclude a candidate without the relevant mitigation, mode and display/clamping conditions.

Zero-valued HP-type callbacks are retained with their original hitsplat type and compete in ordinary batch attribution. They are not silently discarded or relabelled as a proven miss; their observed amount remains zero.

Per-frame `hazardCoverage` (including every `additionalViews` frame) describes the direct
graphics/mapped-object sample. `mappedObjectsScope` is `LOCAL_RADIUS` with `radiusTiles` and
`centerSceneX/Y`, or `EXPOSED_NATIVE_PLANE`. `mappedObjectsStatus` is `available`, `partial`,
`unavailable` or `skipped`; `mappedObjectsUnavailableReason` explains an unavailable/omitted scan.
`objectScanCompleted` means the stated API-array scan finished, including any reported read gaps.
It is not a claim that all game hazards were exposed or all definitions were researched.
`sampledPlane`, `tileArrayWidth`, `boundsPolicy`, `visitedTileSlots`, `exposedTiles`,
`unavailableColumns` and `mappedObjectReadFailures` retain the measured scope. Null array entries
are not evidence about unexposed server entities. All four object kinds share the object-definition
namespace, independently from graphics IDs. Unknown objects remain in the separate object channel.

`graphicsScope`, `graphicsStatus`, `graphicsReadFailures` and `unpositionedGraphics` distinguish
known empty graphics from collection/entry/coordinate failures. Graphics with a readable ID but
missing location retain the ID and explicit position-known flags. Graphics and object scans fail
independently; one broken object slot does not erase other kinds on that tile. Walls/decorations
receive no invented occupied footprint or damage-causality predicate merely from their render kind.
The player's direct mapped-object sample remains radius12; secondary native frames can scan their
exposed level. This differs from the full scene-object channel and is now explicit per frame.
Older exports without these fields have unknown direct-scan scope. Object reads that throw during
hit attribution propagate to the per-hit incomplete-context guard instead of silently making a
competing attacker the selected source.

Object-membership follow-up: the settled object channel now re-enumerates exposed scene tiles
and bridge links at every snapshot, for all four object kinds and the requested reported plane.
It is no longer seeded once and maintained solely by lifecycle callbacks. Only re-observed
objects with readable state appear in the settled list. Unavailable scene arrays return an
unavailable channel rather than replaying cached members as current. Partial scans retain their
positive observations and mark gaps; missing entries do not prove a server despawn. Raw lifecycle
callbacks remain a separate channel, including short-lived objects absent from the settled list.

`objectCoverage.reconciledEverySnapshot` identifies this behavior. `membershipStatus` is
`COMPLETE_EXPOSED_SCENE_SCAN`, `PARTIAL_EXPOSED_SCENE_SCAN` or `UNAVAILABLE`; the scope is the
API-exposed scene, not every server object. `observedObjects` is the current output count, while
`objects` is the identity-cache count. Failed scans preserve identities for later recovery but
do not display un-reobserved cached states as current. A successful complete enumeration prunes
absent cache entries before admitting new members. Surviving identities and immutable unchanged
state lists are shared; an object returning after established absence receives a new lifetime key.

`currentFailures`/`currentDropped` describe this snapshot; `failures`/`dropped` retain historical
counts within the scene key. `complete` now describes successful current sampling, qualified by
the explicit reconciliation marker. Historical gaps remain even after current reads recover.
The object-count cap and unexposed/extended scene origins remain coverage limits. Synthetic API
tests measure reconciliation cost separately; they do not establish real-client latency or full
plugin performance. The exact Java-generated recovery fixture is preserved in the review checkpoint.

Optional callback enrichment can return `data.actorState.captureStatus: PARTIAL_ACTOR_METADATA` with `errorClass`, or `data.actorResearchStatus: unavailable`. Missing display/reference detail does not erase the numeric hitsplat observation. Missing actor fields are not filled with dictionary ID or coordinate zero. This guard does not establish that every required coordinate/prayer/classification getter failure is handled.

Hit-context failure follow-up: raw callbacks can carry `data.captureStatus: PARTIAL_EVENT_CONTEXT`
and `contextErrorClass` when scene/actor enrichment fails. The original callback value/type/ownership
and sequence are retained, with unavailable actor/frame fields omitted. `damageEvents[].captureIssues`
lists failed context reads by field and exception class. Incoming ordinary hits with incomplete
callback context use `INCOMPLETE_HIT_CONTEXT`; failed deferred source classification uses
`ATTRIBUTION_CONTEXT_UNAVAILABLE`. Neither supplies source/type/timing from an older hit. Observed
status types remain intact, and one failed attribution does not abort the remaining hit batch.

`impactPositionKnown` and `launchPositionKnown` qualify the existing integer coordinate fields;
false means they are not observed positions at that lifecycle stage. Launch coordinates copied
from impact remain unknown as launch observations even when the impact position itself is known.
`impactPrayerStateKnown: false`
uses `overheadAtImpactEvidence: UNAVAILABLE` and null prayer/conflict lists rather than an observed
empty state. NPC-received hits retain amount and ownership when defender metadata is unavailable.
Journal `ticks[].prayerStateKnown: false` similarly marks a failed settled prayer read. No inferred
deactivation is emitted across that gap. An absent flag in older exports has legacy semantics.
These guards cover the tested callback-context, NPC-defender and source-classifier paths. Failure
of other required game-state/resource/tick getters, event omission and full live conformance remain
separate open requirements; the guard is not a guarantee that arbitrary client failure is recoverable.

The shared reference catalog retains historical spreadsheet rows independently from reviewed
executable attack rules. For every unreviewed spreadsheet reference, normalized `application`
and `prayer_timing` are `UNKNOWN`; `originalApplication`, `originalPrayerTiming` and
`originalConfidence` retain the exact archived cells. Original row hashes, keys and CSV lines
remain unchanged, and every original row can be reconstructed. These references do not supply
an observed hit's style, prayer check, launch tick or reactability. Verified per-attack bindings
and captured lifecycle evidence retain their separate scopes.

### Settled journal availability and tick isolation

Journal rows now include `unavailableStateFields` and `captureIssues`. The numeric fields
`currentHp`, `maxHp`, `currentPrayer` and `maxPrayer` remain integers for compatibility, but a
listed field is an unavailable placeholder and must not supply a resource meter, HP delta,
healing/damage event or death classification. A genuine observed zero is still valid. Older
rows without the marker retain their legacy interpretation. Independent rich-player readings
may still supply a known value when the separate journal read failed. `movement` marks a failed
settled movement sample; comparisons restart after the gap. Additional stage names identify
failed cleanup/effect/world-capture stages, not game-mechanic types.

Settled movement, resource and prayer reads are isolated. Optional expiry, effect and world
capture stages cannot prevent the tested hits from settling/finalizing. Failed stat/status
reads reset their comparison baselines, so recovery cannot establish an onset or delta across
the missing sample. Metadata read failures are retained as issues. Finalization occurs in a
`finally` block once a journal tick has started. This does not establish fault tolerance for
every API getter or arbitrary failure inside hit resolution.

Journal wrappers include optional `finalHpKnown`: false means a live snapshot's `finalHp` is
a placeholder, not evidence of death. An actual death wrapper remains explicitly identified
independently of a failed settled resource read. Rich `meta.deathTick` remains its own evidence.

The synthetic `settled-state-failures.json` is generated by the real Java handlers/export DTO
and retained as an exact export contract fixture. Only wall-clock fields and measured
journal tick durations are removed by its producer test for determinism. No captured resource,
hit, ownership, availability or failure values are removed. This is API fault-injection evidence;
entry game-state/local-player reads, missing callbacks and full live conformance remain open.

### Coloured HP hits identify type, not recurrence

Poison/venom/burn/bleed hits now include `damageRecurrence: UNKNOWN` and
`damageRecurrenceEvidence: HITSPLAT_TYPE_ONLY` for local-player and NPC-received observations.
These fields do not establish periodic damage, status onset, an inflicting actor or an attack.
Existing `STATUS_HITSPLAT` evidence remains the observed damage category; its displayed source
name is a mechanism label. A status-state varp transition remains separate evidence.

All same-scope pending HP observations, including typed colours and zero-valued hits, compete
for an attack association. A compatible projectile remains a candidate for a coloured hit; it
is not consumed or assigned as the cause. Its ambiguity history prevents a later ordinary hit
from reusing it as a unique link. Both callback orders and typed-only-then-ordinary sequences
are fault-injected. Captured amounts/types/order remain unchanged; no periodic hit is generated.

The shared poison/venom references now distinguish recurring schedules from one-off damage.
Poison's ordinary 1-20 examples are not a universal cap, and venom's 6-to-20 recurring schedule
does not describe the venom sac's single 4-damage hit. Primary source revisions retain original
values/hashes and literal corpus evidence; renewed independent review remains outstanding.

The legacy `pvp` boolean describes the selected candidate's attribution context, not a proven
causal player hit. False does not exclude an unresolved player candidate. Observed area/world
context remains independent, and mixed typed/player-projectile observations can stay unlinked.

An optional context failure, such as an unavailable impact-prayer sample, cannot prove that a
compatible projectile was unused. Typed-hit alternatives remain qualified under the same native
scope/time checks even when optional context failed; a later hit cannot claim that projectile
as a unique association. Unknown scope and broader conformance remain separate requirements.


### Burn/bleed properties (additive 1.1 fields)

`damageType` may be `burn` or `bleed`: these identify damage mechanisms, separately from conventional attack style. `damageReferenceKey` points to the shared typed-hit source reference. For NPC-received true burns, `npcDamageReductionStyle: ranged` describes the sourced NPC reduction category, not attack accuracy or a measured reduction. For local-player true burns, `prayerEffect: PROTECT_FROM_MISSILES_INEFFECTIVE_REVIEWED` describes the sourced prayer rule; it does not prove which prayer was active. `damagePropertyEvidence: SOURCE_REFERENCE` qualifies these properties. Bleed has neither property unless a future individually scoped rule establishes it. Null means unestablished. Recurrence remains UNKNOWN with HITSPLAT_TYPE_ONLY evidence. Ordinary red-hit mechanics named burns do not inherit these fields. Legacy captures retain their original type; consumers must qualify reference-based reinterpretation and cannot assume numeric IDs from another API epoch.


### Ground items (additive 1.1 channel)

`groundItemBaseline`, `groundItemChanges[]` and `groundItemRemovals[]` encode a separate native-view/plane item namespace. Each settled sample has a stable scoped `itemKey`, item `id`, `quantity`, raw API `ownership`, `privateItem`, local/world positions and optional-field `captureIssues`. Partial or unavailable scans have explicit `groundItemCoverage` and `captureCoverage.groundItems`; absence from a partial scan is not a server despawn. A readable tile without an exposed item layer is an absence witness for that exposed channel. Item timers `visibleAtApiTick`/`despawnAtApiTick` use `Client.getTickCount()`, whose sampled value is `apiTickCount`, rather than replay tick numbers. Timer values are API observations, not proof of future events.

`GROUND_ITEM_SPAWNED`, `GROUND_ITEM_DESPAWNED` and `GROUND_ITEM_QUANTITY_CHANGED` callbacks retain short-lived stacks and old/new quantities. A callback requires matching current tile identity before receiving a native scene and item lifetime key; otherwise scope and stable identity stay unknown. No item becomes an NPC, scene object or damaging area by sharing its numeric ID. Ownership does not name an attacker or prove a pickup. Selected-window baselines, unavailable-state recovery and independent native streams use the same export path.


### Scoped projectile categories (additive 1.1 fields)

Projectile samples may include sourceNormalWorldContextObserved, damageType, damageTypeEvidence=SCOPED_SOURCE_PROJECTILE_REFERENCE, damageTypeRuleKey and damageTypeCatalogId. These describe the sampled actor/projectile reference context; they do not establish a server accuracy roll, prayer check, phase, status application or impact. Incoming damage classified with REVIEWED_PROJECTILE_BINDING also retains its exact rule key and catalog identity. Source-hit attribution remains a separate hypothesis/evidence channel.

The catalog validates each mapping's exact NPC/projectile symbols, literal source hashes/snippets, mechanic references and non-overlapping scope. These integrity checks do not replace semantic/live review. Current category rules cover scoped Hunllef, Whisperer, Vorkath, Hydra, post-quest Muspah and Nex families. Transitions/inactive/quest/seasonal/special variants remain subject to explicit scopes. Muspah magic category does not imply late prayer prevented corruption. No new timing rule follows from style alone.

Projectile destination fallback uses the pinned API WorldView.TOPLEVEL constant. Unknown native IDs cannot acquire top-level coordinates merely by being -1. Client-exposed global projectile framing remains separately qualified and is not universally certified.


## Additive local status-state channel

WorldSnapshot.localStatuses is an independent optional list for local-player client variables.
Each sample contains key, actorRef=player, label, optional family, state/stateLabel, optional active,
valueObserved, optional rawValue, variableNamespace/id/symbol, ruleKey, catalogId and evidence.
Supported reference interpretation is qualified by the recorder's resolved client target.
A raw reading can be available while its interpretation is unsupported. Unavailable raw values
are omitted, not zero; localStatuses capture coverage can be partial/failed/disabled/unavailable.
Additional native frames disable this local-only stream. Absence never proves no effects.

PlayerState.poisonValue remains backward compatible; poisonValueKnown=false marks its numeric
value as an unavailable placeholder. Failure of this optional read does not discard pose or HP.
Observed poison/venom category-transition EffectEvents can also carry observedRawValue,
previousRawValue, referenceRuleKey and referenceCatalogId. A missing or disabled sampling
interval breaks onset continuity. These samples/events establish neither an inflictor nor a
damage occurrence, future hit/severity, immunity duration or cure reason. The initial rules
interpret poison/venom, disease, antifire, super-antifire, stamina and teleblock flags; they are not a complete status-mechanic inventory.

The variableNamespace is explicitly varp or varbit; matching numeric IDs in another namespace
are unrelated. family is condition, protection or buff and only controls presentation. The
antifire counters establish inactive at zero and active for positive values. Stamina's active
flag establishes only zero/inactive and one/active; its separate duration variable does not
establish activity. Unexpected values retain rawValue with active omitted and
UNESTABLISHED_CLIENT_STATUS_VALUE evidence. Flags do not establish remaining time, an item
consumption or actual damage mitigation. One failed varbit read cannot erase another status.

Teleblock uses varbit4163, TELEBLOCK_CYCLES. The pinned client timer reference subtracts100;
only values above100 establish its active state. Values0..100 are inactive and negative
values remain unestablished. No remaining duration, immunity, inflictor or cast/landing
prayer condition is inferred from this sample.

### Source-associated graphic sightings

`EffectEvent.type=graphic_reference` records a source-associated graphic sampled on the
local actor. `targetRef=player`, `observedGraphicId`, `referenceRuleKey`,
`referenceCatalogId` and `evidence=PINNED_LOCAL_GRAPHIC_REFERENCE` retain its scope.
`startTick` is the sighting tick; it is not a proven cast or applied-effect onset. Source,
duration and magnitude remain absent. A registered graphic need not be currently visible.
Raw actor graphics retain their independently captured state.

Seven exact pinned timer/cache associations replace the runtime keyword scan. The historical
120 keyword matches remain discovery data, including114 without a matching reference;
Bind impact181 is newly included. Blood/shadow/smoke barrage, SLICE, cast/travel and unrelated
NPC graphics cannot become a local freeze/bind sighting through that scan. These references
still do not establish an applied freeze, spell, hit, immunity or duration. Graphic read
failures, sampling gaps and scene epochs break dedup continuity. Positive reads survive an
independent graphic-channel failure with explicit partial-sample markers.

### Independent journal ticks and unavailable world snapshots

`journalTicks` and rich `ticks` are independently observed channels. A failed/null local-player
read while logged in still finalizes the journal boundary and settles already captured hits
at that tick. `unavailableStateFields` and `captureIssues` identify the local-player/world
failure. A failed scene scope, null snapshot or snapshot exception is explicitly recorded.
No world pose or empty arena is substituted. Recovery breaks movement comparison across
the unavailable actor sample.

Death/live exports window all event families to the selected journal tick interval, including
its edges when no rich snapshot exists there. They may contain journal/events with no rich
ticks at all. Retention advances on journal boundaries, so missing snapshots cannot retain
stale scenes or events indefinitely. Rich `summary.ticksCaptured` still counts rich samples;
consumers must retain independently recorded journal ticks without interpolating world state.


### Observed GameTick boundaries and independent clocks

Within an already observed gameplay session, an actually delivered GameTick retains its
journal row when the client reports LOADING, CONNECTION_LOST, HOPPING, UNKNOWN, or its
state read fails. State callbacks and elapsed wall time never create rows. Startup,
login/authentication and unestablished sessions do not become gameplay samples. Fresh
session reset clears the prior character/world identity before any partial sample.

Optional journal fields observedGameState, apiTickCount and gameCycle retain independent
read availability. The gameState availability key refers to observedGameState. Actual zero
is valid; null/failed/absent clocks remain unavailable. apiTickCount is the client API counter
sampled during GameTick delivery, before pinned RuneLite Hooks increments it after delivery.
It is independent of replay tickNumber and client gameCycle; none is a universal server
sequence. No missing boundary is reconstructed from clock differences or elapsed time.

Unready state suppresses world/resource/prayer cache reads. Previously observed hits and
deaths still settle once on their original boundary; callback coverage remains unknown.
Recovery breaks causal, attack-timing, movement and effect comparison baselines while
preserving observed actor/object lifetime identities. Unavailable world state is explicit,
not an empty room or a carried-forward pose. These tested paths do not recover missing
callbacks, certify every raw intake/clock failure, or establish upstream serverTick producer
conditions and all reconnect/hopping behavior. Independent live/video conformance remains open.


### Callback intake, missing clocks and same-boundary identity

ObservedEvent.gameCycle is independently nullable. Missing/failed cycle reads are omitted,
never exported as an observed zero or borrowed from the settled tick. Callback sequence is
observation order; it is not proof of hidden server execution order or visual rendering.
Primitive hitsplat values survive optional metadata/clock failures. State/local-player reads
have independent issues. Only already observed sessions admit unready/unknown-state intake;
these events do not sample cached world/resource/prayer state or create GameTick boundaries.

When callback local-player identity is unavailable, raw player facts remain unassigned. The
actual actor object can match the local player independently read at this same settled
boundary. DamageRecord.defenderIdentityEvidence then records SETTLED_LOCAL_REFERENCE_MATCH;
CALLBACK_LOCAL_REFERENCE_MATCH marks a direct callback match. Missing impact context remains
unavailable. Deferred incoming hits retain their original sequence order. Unassigned identity
never carries into another boundary or a fresh session. If no same-boundary match is available,
raw events remain unassigned, callbackIdentity is marked unavailable, and a local death/autosave
cannot be asserted. Disabled other-player capture suppresses their metadata; anonymous primitive
combat observations can remain when local identity is unknown.

DeathMark.actorIdentityEvidence and captureIssues retain matching/read evidence. Optional
Meta.captureIssues and worldKnown expose failed death metadata; world=0 with worldKnown=false
is an unavailable legacy numeric placeholder. A failed name/world/position read cannot cancel
an independently observed local death. Missing metadata is not filled from a later sample.
This does not establish a killer, net HP loss, all callback caller paths or live conformance.


### Shared raw status observations and timed poison protection

Status rules with the same declared sharedObservationKey use one variable read/failure per
local status sample. This does not merge independent player/variable capture channels; their
conflicting values remain observable. Namespace and numeric identity must match the declared
shared key. Poison/venom condition state and timed protection both read varp102, POISON.
The pinned timer predicates classify values below-38 as poison/venom protection, -38..-1 as
poison protection and nonnegative values as no timed protection observed. This seventh local
operator is a client timer reference, not a particular item, equipment immunity, remaining
length or proof that a special poison/venom-like hit was prevented. Damage and effect event
streams do not gain synthetic hits/onsets from this protection interpretation.


### Native local energy values and availability

PlayerState.runEnergyRaw retains Client.getEnergy() in hundredths of one percentage point;
specEnergyRaw retains the SPECIAL_ATTACK_PERCENT/SA_ENERGY varp in tenths of one percentage
point. The legacy runEnergy/specEnergy fields remain whole-percent display values. Nullable
raw fields preserve every observed signed integer without clamping. runEnergyKnown and
specEnergyKnown independently mark failed reads; failed raw and legacy numeric fields are
omitted, not zero. Earlier placeholder zeros with known=false also remain unavailable. These optional reads cannot erase the
player's independently captured pose/HP or the other resource. Partial player coverage and
captureIssues expose those failures. Other player-capture API paths are not certified here.

Net HP, prayer, run and special changes compare adjacent known local samples, including
scene transitions; they are not per-hit effects or source/cost attribution. Missing samples
break comparison. Native and coarse legacy energy samples do not establish a comparable
sub-percent delta. Native/display disagreements remain unavailable in the consumer, with
original fields retained. Older exports cannot acquire discarded precision. HEALING callback
values, net HP changes and damage totals remain separate: a healing hitsplat does not prove
its amount was the actual net gain or identify an item/source. No extra hit is reconstructed.


### Same-boundary callback recipient links

Events.observationLinks is an optional list of separately observed recipient resolutions:
`tick`, `observationSequence`, `observationType` (HITSPLAT or ACTOR_DEATH), `actorRef`
(currently player only), `resolvedTick`, and `evidence` (SETTLED_LOCAL_REFERENCE_MATCH).
The actor object retained from a callback matched the local player object read at that same
delivered GameTick boundary. Both tick fields must match. This applies to healing/prayer
and other hitsplat surfaces as well as HP damage; it never turns a non-HP surface into damage.

Original observations keep their original anonymous recipient, sequence, nullable clock and
unavailable context. Links add no impact position, prayer, attacker or server ordering. Their
list order is resolution order, not callback order. Identity queues expire on every delivered
GameTick, including rejected boundaries, and on session reset. This does not certify broader
pending-hit/raw-event handling across rejected authentication or session boundaries.

The link buffer is separately bounded at100,000 records, trimmed to the journal retention
window and reset with the session. Evicted links produce captureGaps with channel
observationLinks and reason OBSERVATION_LINK_BUFFER_LIMIT; this does not mean the raw callback
was lost. Consumers require a unique exact tick/sequence/type raw callback and one compatible
same-tick link. Orphan/duplicate/contradictory links remain untrusted and create no new event.

Compatible structured damage/death records and raw callbacks retain explicit linkage. Conflicting
linked enrichment is retained separately, excluded from damage totals, and cannot provide a
confident death cause. Typed raw HP facts remain available. Journal multiplicity is reconciled
without collapsing simultaneous equal-valued hits. Source records remain downloadable unchanged.

### Independent encounter context availability


Encounter-context follow-up: independent world/revision, instance/template/position,
wilderness and target-health reads no longer erase unrelated raid variables/resources.
ContextBlock.inInstance/inWilderness are nullable; omitted values are unavailable, not false.
Wilderness varbit5963/INSIDE_WILDERNESS retains its raw integer; only documented0/1 values
become booleans. Unexpected values are uninterpreted. Known non-instanced state is required
before rendered world coordinates can substitute for a missing template position. Independent
read failures and partial context coverage stay visible; no stale field fills a failed read.

The fifteenth actual Java capture/export contract spans seven ticks: ToA150 and0, independent
API failures, unknown instance state, an uninterpreted wilderness value, CoX party3/scaling5,
Wilderness entry and exit, and recovery/backward seeking. Readable current-tick context keeps
original fields/evidence expandable. It does not turn lobby presets, raid/path variables or
a numeric challenge-mode flag into active invocations or a decoded mode. No modifiers, damage,
effects or phases are inferred from these fields. Original source data remains unchanged.

Pinned official sources establish the wilderness0/1 contract and CoX inside-raid flag handling.
The inspected ToA invocation helper reads/caches a lobby widget: that implementation is not
an authoritative active-invocation bitset, and does not prove no other active-state API exists.
Active ToA invocations, CoX/ToB mode enums/scaling, remaining player/world API failure paths,
and independent live/video conformance remain open. Source integrity and synthetic tests do
not certify every raid mechanic. Evidence: audit/source-evidence/raid-context-source.metadata.json.

### Qualified raid-level classification


ToA raid-level reference follow-up: ContextBlock.modeByRaidLevel is separate from challengeMode
and invocations. It records ruleKey, namespace, variableId/symbol, sourceHash, referenceCatalogId,
nullable rawValue, known, state, label and evidence. The shared pinned rule classifies0..149 as
Entry,150..299 as Normal and300+ (through the signed client integer limit) as Expert. This is a
standard-mode reference applied to the observed client level, not an active modifier bitset,
server settings observation, damage/scaling formula or phase detector. Primary release prose
and the exact pinned helper enum corroborate the thresholds; the enum resolves the300 boundary.

Actual ToA template-region evidence, matching compiled client target, consistent level fields
and known non-special world context are required. Missing/negative/conflicting/unsupported
samples remain unknown with original values retained. A configured level outside ToA never
establishes presence. Explicit captured failure and contradictory interpretations remain separate from a
qualified reference. Missing fields must not inherit another tick's category, establish
active invocations, replace raw observations or create damage/effect events.

The sixteenth actual Java contract spans13 ticks:0/149/150/299/300/600, negative and failed
levels, failed/special world context, outside preset and recovery. Source/compiler tests reject
changed identity, target, source hash, ranges, labels and scope. Five ToA knowledge records link
the rule as partial reference coverage and still require reviewed mechanic predicates.

A second pinned invocation helper was inspected independently: its summary reader is explicitly
a hypothesis; unmatched entries default to false and all-inactive grid reads are rejected before
caching. Jagex explicitly permits no-invocation entry raids, so that universal nonzero premise is
not adopted. Original helper source/findings are retained without copying its active-state logic.
Primary threshold excerpts are retained primary-source observations with exact hashes; direct raw HTML
retrieval returned403. They are not an immutable source revision or original full HTML snapshot.
Active invocation identity, CoX/ToB modes/scaling, special-world conformance, remaining capture
failure paths and independent live/video fidelity remain open. Evidence:
audit/source-evidence/encounter-mode-followup.metadata.json.

### Primitive system-message observations


System-message and ToB-source follow-up: accept only GAMEMESSAGE/SPAM
before optional client reads. Preserve original text, messagePresent, raw API messageTimestamp,
nullable callbackGameState/client cycle and callback sequence. Timestamp units are not inferred;
zero is retained and it does not create a tick or determine ordering. A known capture session
can retain messages through unready/failed reads; no stale world state is filled in. Dedicated
name/sender fields and player/private channels are excluded, while game-reported text stays
original. Optional pickpocket metadata cannot erase the primitive message or create a source ID.
The pinned ChatMessage API explicitly excludes NPC dialogue: this is not dialogue coverage.

A preserved real export demonstrates the previous viewer order2,3,4,1 when callback1 had no
clock. Shared ordering now retains positive recorded callback sequence ahead of clock sorting;
unsequenced tick facts have no callback position, and their display placement is not an assertion
that they occurred later. Client callback order does not prove server execution order. Empty or
formatting-only text is explicit; absent text remains unavailable. Noncombat event details do
not show an attack-type field. The17th actual Java export spans four ticks, six messages, loading,
failed context/clock, null/empty text, shared NPC damage and fatal damage, with private-channel
spoofs absent. Original source, totals and backward-seek facts remain unchanged.

The deterministic ToB mode inventory accounts for all103 pinned recorder definitions across133
NPC IDs and reparses14 literal wiki pages. It retains58 explicit mode agreements,3 pooled-mode
ambiguities,52 IDs with no explicit wiki mode,19 with no mined variant and1 neutral reference.
Nine boss-page IDs are outside that recorder table:7 have explicit wiki mode labels (including
Bloat and spawning/death forms), and2 are quest forms with no mode claim. HP/scaling values and
internal application enum numbers are not adopted as game-variable encodings. All source hashes,
cache symbols, candidate rows and missing/ambiguous cases are retained; zero NPC mode rules are
activated by this inventory. Literal consistency is not semantic or live certification.

Evidence: audit/tob-mode-inventory.json, audit/build_tob_mode_inventory.py,
audit/source-evidence/tob-mode-source.metadata.json and system-message-followup.metadata.json.
Active ToB mode lifetime/selection, CoX flags, active ToA modifiers, NPC dialogue, other callback/
session failures and independent live/video conformance remain open. Claude stays disabled.

## Independent NPC snapshot fields (2026-11-04)

NPC observations are sampled independently. A failed name, animation, definition, icon,
interaction, facing, health-bar or position read does not erase other available NPC facts.
`observationIssues` maps affected field names to exception classes; aggregate NPC coverage is
partial. No exception message or account metadata is added. Successful zero values and the
observed animation sentinel `-1` remain distinct from unavailable values.

NPC `sceneId`, `npcId`, `combatLevel`, `orientation`, `animationId`, `poseAnimationId`,
`healthRatio`, `healthScale`, `interactingWithPlayer` and
`attackCooldownTicksRemaining` can be omitted when unavailable. Consumers must not supply
ID zero, an idle state, a facing direction, an empty health bar or a cooldown as a substitute.
This extends the existing1.1 partial-observation contract; older captures remain readable.

`definitionHasTransforms` and `definitionTransformIds` retain the reported base definition's
possible forms. They do not select a current phase or active mode. A failed transformed
composition read, or a known transform with no resolved composition, has
`definitionEvidence: TRANSFORM_UNAVAILABLE`; effective ID, size and phase are not guessed
from the base. The base API ID remains independently available. `NPC_API_ID` explicitly labels
the API-ID fallback rather than claiming a transformed-composition observation.

`graphicsKnown` qualifies completeness of the NPC's sampled graphics. Positive graphic IDs
survive a partial collection read; a fallback primary graphic does not certify the full set.
`actorRef` is an opaque session-local object-lifetime key; unavailable index/view components
remain `unknown` rather than zero. Missing identity/animation/cycle inputs invalidate timing
estimates, so recovery alone does not become an attack onset. Independent fields are read once
for the sample; optional interpretation and interaction enrichment have their own failure scope.

Missing world position leaves the plane unavailable, even if local coordinates were read.
Such an NPC is retained without assigning a map tile or copying it onto auxiliary planes.
Auxiliary NPC coverage is partial when an unlocated actor could belong there. The viewer
retains an inspectable unpositioned observation, leaves its map marker absent, and labels any
pinned prior sample as historical. Nullable facing omits an unobserved direction arrow.

The18th real Java death-export fixture spans eight ticks with failures/recovery, an unpositioned
NPC, missing effective identity,99 local damage and7 other-owned NPC damage. Source/API hashes,
positive/negative field regressions and export checks establish this scoped contract. They
do not establish live failure frequency, all player/collection/session failure paths, complete
phase semantics or hidden server decisions.


### Independent local/other-player snapshots (player follow-up)

Optional actor, skill, prayer and local-container reads are independent. Failed numeric
fields are absent, not zero/idle/false. `observationIssues` maps affected fields to exception
class names; other recorded fields and party rows survive. Base/boosted skill maps contain
only successfully read skills, each read once; HP/prayer/boosted display fields use those
same values. Actual zero HP remains zero. Local-container slots are read once, with separate
ID/quantity availability. `equipmentSlotKnown`, `inventorySlotKnown` and
`inventoryQuantityKnown` distinguish unread slots from observed empty slots. Numeric array
placeholders in unread slots establish no item/quantity facts. Literal equipment slot indexes
were already correct in pinned RuneLite1.13.6; this change adds availability, not a slot remap.
Other-player gear remains public appearance, never the local container or server inventory.

`prayerFlagValues` includes only successfully read flags (including observed false values).
`prayerStateKnown` means all flags were read; it does not certify server state or icon
availability. `reportedActivePrayers` retains known-positive flags. The compatibility
`activePrayers` list combines known non-overhead flags with the independently resolved
visible/complete-flags overhead. A partial flag list cannot resolve a single possible overhead.
`overheadIconKnown` is independent; `prayerStateConflict=false` in a partial sample means
no contradiction was positively observed, not proof of a complete consistent prayer state.
Known-positive flags remain visible in the viewer with a partial-sample label.
`launchPrayerStateKnown=false` prevents a partial launch snapshot or impact fallback from
being presented as complete attack-tick prayer knowledge; it is independent of launch position.

`graphicsKnown` qualifies completeness without removing positively observed graphic IDs.
Local `fromX/fromY` are the supplied previous reported world point; `toX/toY` are the current
reported point. They are nullable and do not represent movement intent, a path or proof of
freeze/knockback. Continuity of the supplied previous point must be assessed separately.

If the capture service's native-view resolution fails, `nativeContextKnown=false` retains
independent player observations with no `sceneKey`/world-view assignment. Other-player rows
then explicitly identify their top-level API-collection scope; they are not projected into an
old, guessed or top-level replay room. View-dependent channels are unavailable. Raw supplied
projectile observations remain retained but unplaced. `gameCycleKnown=false` qualifies a
failed clock read on this fallback. Normal root identity/session and transient failures at
other call sites remain separate acceptance work; this is not all-world failure certification.

The19th actual Java death export covers six ticks, partial skills/prayers/container slots,
unpositioned local/teammate observations, recovery,99 received and7 other-owned NPC damage.
Only recording/export IDs and wall-clock fields are normalized. The pinned API sources and
fixture hashes are in player-snapshot-followup.metadata.json. Old exports cannot recover
observations that were never captured. Source-specific mechanics, live/video fidelity and
all-game correctness remain unestablished.


### Qualified NPC definition-state references (ToB source follow-up)

`npcs[].definitionStateReference` is an optional source interpretation tied to the current
observed NPC definition. Fields are `key`, `catalogId`, `npcId`, `name`, `role`, `form`,
`modeReference`, `evidence` (NPC_DEFINITION_SOURCE_REFERENCE) and `sourceRefs`.
It requires independently observed name, combat level and effective-definition size, together
with ordinary non-override context and an exact ID match. Unavailable/conflicting fields omit
the reference while retaining the independently observed NPC. Raw fields and reference labels
remain separate. `modeReference` labels the NPC definition, not an active raid-wide setting.
Percentages in names such as70% form identify an asset/form reference, not measured current HP.
No HP, attack, cooldown, protection or damage-source predicate is added by this object.

The shared source reconciliation contains140 ToB definitions. It keeps10786 spawning,
10787 melee,10788 magic,10789 ranged and10790 support distinct; the pinned recorder's entry
range/dropping check is not adopted. Maiden entry/hard alternate and dying forms, Verzik
quick-start/transition/death forms, Athanatos, minions and tornadoes retain qualified references.
Xarpus's combat definition remains phases2/3, and Sotetseg's inactive definition remains
pre-fight/maze. Quest-only Verzik IDs8250/11178 are excluded. Pooled Blood spawn infobox
candidates stay unchanged; cache-observed name/level/size and pinned mode references are
separate, and no pooled HP value is adopted.

The Verzik enrage cue is scoped to phase3 IDs8374/10835/10852 and invalidated on incompatible
forms, including death. The viewer checks a captured phase cue against applicable rules when
using a qualified definition reference, retaining incompatible raw cue metadata on demand.
This does not complete every phase-cue, mode or live lifetime proof.

The20th actual Java handler/export fixture has23 synthetic ticks: form changes, support,
transitions, enrage/death invalidation, five rejected guard contexts, recovery and a poison
fatal hit with a concurrent tornado reference.99 received and7 other-owned NPC damage remain
independent. Only recording/export IDs and wall-clock fields are normalized. Source and fixture
provenance are separate files to avoid a catalog/build/fixture hash cycle:
`tob-definition-states.metadata.json` and `tob-definition-fixture.metadata.json`.

Primary-scope failure preserves independent player, teammate and top-level API NPC fields in
a snapshot with `nativeContextKnown=false`, no `sceneKey` and no primary `worldViewId`.
`captureCoverage.npcsScope` and `otherPlayersScope` explicitly identify
`TOP_LEVEL_API_COLLECTION_WITHOUT_REPLAY_FRAME_ASSIGNMENT`; these are not all loaded views.
Raw per-actor coordinates/IDs remain observations but must not be projected into a previous
map frame. Unscoped NPC rows have no normal-context qualification, phase, derived action,
definition reference or cooldown. Timing and phase state are invalidated before recovery.

`gameCycleKnown=false` means the primitive `gameCycle=0` is unavailable, not an observed zero.
An observed zero has `gameCycleKnown=true`. A failed clock or optional primary scene metadata
read cannot erase other independently captured world channels. Scene metadata failure is
marked in snapshot coverage/issues and the journal; projectile cleanup failure has its own
journal channel and cannot invalidate an already stored snapshot. Movement history reuses the
snapshot's reported position rather than another actor-position API read. The21st actual Java
contract covers five synthetic ticks and preserves99 received/7 other-owned damage. Missing
local-player identity, some child scope/repeated context paths and whole service failures
remain explicit gaps; this is not a universal exception or mechanic certificate.

`WorldSnapshot.varbitLayouts` retains actual `Client.getVarbit` composition fields for configured
context/status/mode IDs on a ready primary snapshot. Map keys and `varbitId` identify the same
requested variable. `varpIndex`, `leastSignificantBit` and `mostSignificantBit` are independent
nullable integer observations; the most-significant bit is inclusive. `layoutKnown` requires
all three valid fields and `evidence=CLIENT_VARBIT_COMPOSITION`. Partial observations retain
their available fields plus `observationIssues`; missing fields are not zero. Entirely unavailable
compositions are omitted from snapshot maps and counted in capture issues/coverage.

`GAME_VARIABLE_CHANGED` is a client-global callback with its original varp/varbit IDs, integer
value and lossless long string. It does not require a local actor identity or scene assignment.
For nonnegative varbit IDs, optional `data.varbitLayout` retains an actual composition sample;
unavailable metadata cannot erase the callback. `captureSettings.varbitLayoutScope` explicitly
describes configured IDs and actual callbacks.62 unique configured IDs are currently sampled;
the19,771 archived definitions are reference data, not19,771 runtime observations per tick.

The viewer's unsigned bitfield channel is calculated from recorded integer varps and usable
layouts observed on the same tick. Client and last-server-reported values remain separate.
It rejects callback/layout ID disagreement, explicit unavailable layout channels and conflicting
or partial layouts, and does not reuse earlier layouts or substitute the reference table.
Reference names require matching catalog identity and layout. Original callback values are
unchanged; unsigned arithmetic does not establish the API's full-width signed convention.
The22nd actual Java export retains six synthetic ticks and independent99 received/7 other-owned
damage. Other variable IDs without actual observations, remaining context/callback failure paths,
broader capacity and independent live conformance remain open.

### Independent projectile observations (4 November)

Projectile primitive fields are nullable when their API read fails; an observed zero remains
zero. `observationIssues` records field-specific failures. Optional source name, level, index,
view or composition failures cannot discard independently readable geometry or timing.
Non-finite coordinates are omitted with an issue so JSON export remains usable.
`sourceActorKnown` and `targetActorKnown` distinguish a successful null actor read from a failed
read; unavailable `targetsPlayer` is omitted rather than written as false.

`sourceBaseNpcId` is the actor's base ID; `sourceNpcId` is its observed effective definition.
The latter stays absent with `sourceDefinitionEvidence=TRANSFORM_UNAVAILABLE` when a known
transform cannot resolve, or `DEFINITION_CONTEXT_UNAVAILABLE` when neither effective nor
enough base context is available. A base-ID fallback requires positively observed absence of
transforms. `sourceNameKnown` separates a failed name read from a successful unnamed observation.
Raw normal-world evidence is independent from reference qualification.

`sourcePlane`, `targetPlane`, `originPlane`, `destinationPlane`, source/target actor view IDs
and target-local view ID are separate observations. API target points are labelled with
`destinationEvidence=PROJECTILE_API_TARGET_POINT`; conversions from current target-local
coordinates are explicitly labelled. Actor positions are not substituted for missing projectile
points, and current samples do not reuse older callback targets. Unknown sample plane/frame
prevents a flight marker; an independently qualified destination may still have a target marker.

`remainingCycles`, `startPos`, `orientation`, `animationId` and `animationFrame` retain available
API values. `estimatedImpactTick` is a client-lifetime estimate, not an actual hit or server
launch witness. Unavailable lifetime gives no estimate; zero does not invent a future arrival.
First observation remains `spawnTickEvidence=FIRST_OBSERVED`. `collectionEvidence` distinguishes
current client collection membership from sampling a registered object when enumeration is
unavailable. Missing lifetime does not prove expiry; coverage and cleanup gaps remain explicit.

`PROJECTILE_RELATIONSHIP_CHANGED` preserves source/target identity changes even at unchanged
callback destination coordinates. Late local-player target binding can enter the candidate
set. Unknown timing stays an explicit alternative to rival hazards and cannot prove a unique
source or prayer result. The28th actual handler/death/clipboard contract covers seven controlled
ticks and99 received/7 other-owned NPC damage. It is not a live fight or all-game certificate.
First-source provenance across trimmed windows, remaining actor callback metadata and live
conformance remain open. These additive fields also apply to schema1.2.

### Actor callbacks and original payloads (4 November)

NPC/player lifecycle, animation, graphics and overhead callbacks retain independent actor
fields with nullable unavailable values and `observationIssues`, using the same readers as
settled snapshots. Effective IDs, base IDs, possible transforms, definition evidence, position
availability, graphics and public appearance remain separate facts. Source flags or optional
interaction name/reference/view failures cannot erase independently readable partner details.
An unavailable local-player identity cannot establish that another observed player is an
OTHER_PLAYER: `actorKind=PLAYER` keeps that distinction unresolved. With player capture disabled,
`LOCAL_IDENTITY_UNAVAILABLE_PLAYER_CAPTURE_DISABLED` preserves no partner name or reference.

Basic callback `callbackMetadataEvidence=LOGGED_IN_ACTOR_FIELDS` records an observed logged-in
state, not a guarantee that every context field or local identity was available. Known-session
loading/unknown-state NPC callbacks can retain raw public fields with
`API_REPORTED_WITHOUT_READY_CONTEXT`; their header has no actor/scene assignment or ready-world
reference qualification. Disabled world-only callbacks do not read actor metadata. Existing
hitsplat/death intake retains its separate identity and privacy rules.

`OVERHEAD_TEXT_CHANGED.data.text` is the original `OverheadTextChanged` payload, labelled
`textEvidence=EVENT_PAYLOAD`. `data.actorState.overheadText` is a distinct actor API read and
may differ; neither overwrites the other. Reviewed overhead phase cues use the event payload,
and optional phase-context failure cannot erase the original callback.

`NPC_CHANGED.data.oldComposition` retains the event's actual old composition, with independent
ID/name/size/level/config fields and `evidence=NPC_CHANGED_OLD_COMPOSITION_EVENT_PAYLOAD`.
`oldCompositionProvided` distinguishes a supplied composition from an absent payload. This
composition is not transformed using current actor or varbit state and is not automatically
a previous active form or boss phase. Current effective/base IDs remain separate.

`INTERACTING_CHANGED.data.interaction.targetEvidence=EVENT_TARGET_PAYLOAD` identifies the actual
event target, including `evidence=EVENT_TARGET_CLEARED` for an explicit null. The source's later
API pointer/flag is separately retained in `data.actorState.interaction`. Targeting can be
following, trading or combat and does not establish damage causality. Optional attacker-history
enrichment cannot abort the raw callback. Older exports can retain a related actor reference
while their sampled interaction details come from a later read; they must not acquire the new
payload label retrospectively.

`actorResearchStatus=unavailable` remains compatible. `actorResearchUnavailableReason` separately
identifies `EFFECTIVE_DEFINITION_UNAVAILABLE` or `OBSERVED_NAME_NOT_QUALIFIED`. Available keys
have `actorResearchEvidence=EFFECTIVE_ID_NAME_COMPATIBLE_REFERENCE_CANDIDATES`; these are source
links, not mechanic certification. Unknown effective definitions do not inherit base-ID keys.

The29th exact handler/death/clipboard contract has seven controlled ticks, original versus
later observations, partial old/current definitions, recovery/lifecycle facts and99 received/
7 other-owned NPC damage. It is not a live fight, an actual Nex-to-other-NPC transformation
sequence, an all-callback exception certificate or independent game conformance.

### Reviewed object surfaces and observation stages (4 November)

Mapped object hazards can additionally carry `objectKey`, `surfaceRuleKey` and
`sourceEligibility`. The object key is the same view/plane tracker identity used
by the neutral scene-object channel; it is not a numeric game object definition.
Registration does not itself establish settled membership. A scene change clears
membership before hazard/object samples are associated. Missing keys stay missing.

The reviewed static-surface cohort requires matching current definition ID, name,
one-tile size and absence of transforms. `ELIGIBLE_REFERENCE_SURFACE` is a spatial
candidate, not a confirmed hit cause. `CONDITION_NOT_OBSERVED` retains conditions
such as active Colosseum waves, Glyph protection or an unestablished pool damage
window. `DEFINITION_NOT_QUALIFIED` retains unavailable/conflicting definitions.
`RESOURCE_REFERENCE_ONLY` does not establish HP damage or any applied effect.
Unknown damaging values remain unavailable, never false. The cohort supplies no blanket typeless,
poison/venom, amount, healing, mode or cooldown claim.

`observedHazardContacts` can contain `OBJECT_SURFACE` witnesses with object/base ID,
kind, recorded definition state, eligibility, contact tile, client cycle and
`HITSPLAT_CALLBACK`/`DEFERRED_RESOLUTION` stage. `objectObservationId` is a separate
recorder-local API-instance identity for comparing these two reads; it is not an
`objectKey` or server ID. Same ID/tile on a replacement object is not agreement.
Unresolved surfaces compete with other source hypotheses. A sole unqualified
surface remains unknown; differing eligible instances/states retain
`HAZARD_OBSERVATION_PHASE_CONFLICT`. Two-stage agreement is still correlation,
not proof of server ordering or unique causality.

The30th Java handler/death/clipboard contract contains11 scripted API ticks and
99 received/7 other-owned NPC damage, with qualified/conditional surfaces,
post-impact state changes and a disease-coloured resource observation. These
controls are not a live fight or all-environment conformance proof.

### Retained projectile source observations

Each newly sampled `projectiles[]` entry may include `sourceHistory.version: 1`.
Its immutable `firstObservedSource` retains the original observation ID, tick,
cycle, sample kind, recorder context epoch and independently available source
fields. `firstActorSource` is present only when an actor was first reported later;
it does not fill the originally absent or unavailable source. Neither is a hidden
attack-launch observation. Current source, target and pose fields remain current
samples and are never filled from these historical anchors.

The history records `actorChanged`, `referenceChanged`, `definitionChanged`,
`scopeChanged`, `observationGap` and `projectileDefinitionChanged` separately.
An API actor-object change differs from a recording reference being retired and
reallocated for the same reported object. Definition changes retain the original
source form; they do not manufacture a phase or attack event. Context epochs are
recorder boundaries, not server identities or complete native-scope certification.

Deferred projectile hypotheses use the first actor observation's available
identity/definition/reference context. A later form does not rewrite it. Actual
source-object conflicts, context/sample gaps and changed projectile definitions
withhold a unique attribution/reference. Candidate metadata retains the
`sourceHistory`, `latestSourceObservation`, `sourceSamplingUnavailable` and stable
`projectileObservationId`. Pending metadata updates require both the registered
observation ID and API object, so object reuse cannot overwrite an older flight.
Tick samples precede deferred hit attribution and are shared with the world export.
They still do not expose a unique server projectile-to-hitsplat association.

History entries leave the transient cache with registered projectile lifetimes.
Retained other-player names/references are redacted when that capture option is
disabled; prior snapshots are immutable. No old history or native position is
manufactured for earlier recordings. The32nd Java handler/death/clipboard contract
contains six exported ticks3..8, a source first observed at tick1, changed source
definitions/actors, retirement and a new lifetime, with99 received/7 other-owned
damage. It is a scripted API-state control, not a real encounter or all-game proof.


### Raw actor visual lifecycle observations (working follow-up, 5 November UTC)

`player.graphicsState`, NPC/other-player `graphicsState` and actor-callback
`graphicsState` retain an optional version1 sample. `primaryGraphicKnown` describes
the independent legacy primary-ID read; its recorded `-1` means no primary graphic
was reported. `collectionKnown` describes complete enumeration, not complete values
for every entry. Missing/null `entries` is unavailable; an observed empty array is
a successful empty sample. A partial enumeration retains its readable prefix.

Each entry retains its enumeration `ordinal` (not a server ID or actor slot),
`entryAvailable`, `id`, `startCycle`, `height`, `frame` and `frameCycle`, with
independent `observationIssues`. Duplicate IDs are retained as distinct entries.
A null entry remains unavailable. Legacy `graphicIds` is a separate de-duplicated
ID list; `graphicsKnown` means those IDs were completely sampled. Lifecycle getter
failures do not erase otherwise readable IDs. No frame or missing field is filled
from another sample.

`DamageRecord.defenderGraphicsAtImpact` captures the reported local defender's
original raw actor-state visual sample during the hitsplat callback, without a second
read before deferred attribution. NPC-owned
and other-owned hits capture their actual NPC defender independently. An unassigned
defender or unavailable ready context does not borrow later visual state. If exact
actor-object recipient resolution occurs within the same delivered boundary, its
original visual sample is retained with that identity evidence; no new read fills it. A later
settled tick can legitimately differ from the callback. Damage amount, ownership,
prayer sampling and attribution stay independent of visual availability.

The official API supports delayed spot-animation activation. A recorded ID, start
cycle or visual name does not by itself establish active protection, its duration,
contract effects, shared protection, damage eligibility or a hit cause. These fields
are raw visual observations, not a new mechanic predicate. Focused controls pass;
full frozen validation and the next source/build checkpoint are still pending.
### Independent generic callback fields (5 November UTC)

`MENU_OPTION`, `GRAPHICS_OBJECT_CREATED`, `CLIENT_INTEGER_CHANGED`,
`ITEM_CONTAINER_CHANGED`, `SKILL_STATE_CHANGED` and world-view loaded/unloaded
observations add `data.fieldObservationVersion: 1` and
`data.fieldAvailability: {field: boolean}`. False means that field was unavailable;
the value must not be interpreted as zero, empty or a successful null. Successful
null reads appear in optional `data.nullFields` because ordinary Gson output omits
null map values. `data.observationIssues` maps failed fields to an exception class
or unavailable-dependency reason. Partial reads carry `captureStatus`; independent
callback context issues can supply their own partial status and `captureIssues`.

Availability also describes opaque dependencies `menuEntry`, `relatedActor`,
`worldView`, `location`, `itemContainer` and `items`. Their successful nonnull reads
do not serialize native API objects or arrays of native items. All existing raw
scalar names remain compatible. Menu targets retain the existing color-tag removal;
an absent/unread target is not converted to an observed empty string. Raw menu and
structured action records share one related actor sample. Actions retain
`observationKind=INPUT`; they do not prove consumption or action success.

Graphic fields include independently available `id`, `plane`, `worldViewId`,
`nativeSceneKey`, `startCycle`, `localX`, `localY`, `localWorldViewId` and `z`.
`scopeKnown=false` prevents a current/previous scene assignment when native scope
cannot be established. `positionScopeKnown` additionally requires a location whose
reported view matches the graphic view. Local coordinates remain raw facts when
unplaced; no current plane is substituted. A successfully null `nativeSceneKey`
is valid for an established primary view, whose raw header can use the primary
scene key. View lifecycle observations retain the independent view ID, causal
epoch and reset status without assigning an unobserved native plane/scene.

Container `itemsKnown=true` includes an observed empty array, producing empty
`itemIds` and `quantities`. Missing, unread or null item arrays have
`itemsKnown=false` and unavailable/omitted arrays while retaining `containerId`.
An observed null slot keeps the existing `itemId=-1`/quantity0 slot convention.
Unavailable client integer values remain absent; an observed0 remains0. A null
skill name does not erase independently observed XP, level or boosted level.

The36th actual handler/death/export fixture `callback-fields.json` has six scripted
ticks and99 received/4 local dealt/7 other-owned damage. It is controlled API-state
evidence, not a live fight, action-success witness or all-callback certificate.
These additive fields also apply to compact schema1.2 observations.

Callback actor enrichment reuses the original actor metadata sample for the raw
header name, position, view and lifetime identity primitives. A failed or observed
null field cannot be filled by a later getter or borrowed primary scene. Unknown
actor position/view leaves the raw scene key unassigned. Existing identity
lifetimes and privacy gates still apply. Self-interaction name/view values and
issues come from that same sample; native actor/view pointers stay internal.
Raw input and action records share one related-actor reference result, including
null. Resource-change actions retain the original callback cycle/context.
Original failed header/scope reads retain their field-specific reasons in raw
`captureIssues` and `PARTIAL_EVENT_CONTEXT` status. Successful nulls do not invent
exception diagnostics. A view-ID failure marks raw placement partial without
adding a new damage-attribution guard; unrelated optional visual failures remain
actor metadata diagnostics.

The37th actual-handler export `callback-consistency.json` exercises those original
sample guarantees across five scripted ticks with99 received/4 local dealt/
7 other-owned damage. It adds no action-success, ownership or mechanic rule and
does not qualify unreviewed callback paths or independent live behavior.
