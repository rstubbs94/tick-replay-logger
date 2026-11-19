# Phase 1 Export Schema — v1 (DeathRecording)

> Current runtime formats: [1.1](export-schema-v1.1.md) and optional [1.2 shared NPC snapshots](export-schema-v1.2.md).
> The examples below describe the original 1.0 design. New 1.1 captures add observed callbacks,
> cycles/order, journal ticks, both damage directions and per-tick projectile samples. The earlier
> proposal to reserve v1.1 for visual objects/dynamic collision is deferred to a later revision.

The earlier versioned local JSON design for captured scene, actor, damage,
effect and context observations. The implemented contract is documented in
`export-schema-v1.1.md`. `schemaVersion` identifies the format; breaking changes
require an explicit version change and compatibility handling.

## Envelope
```jsonc
{
  "schemaVersion": "1.0",
  "meta": {
    "recordingId": "uuid",
    "pluginVersion": "x.y.z",
    "dataMapVersion": "2026-10-27",        // bundled damage/effect map version used for labels
    "exportedAtMs": 0,
    "rsn": "string",                        // the recording player
    "accountType": "normal|ironman|hcim|gim|...",
    "world": 0, "worldType": ["members","pvp","..."],
    "deathTick": 0,                         // tick index of the fatal hit in ticks[]
    "bufferTicks": 0, "captureRadius": 0
  },
  "context": { /* see §Context — the active variance context at death (and per-tick deltas) */ },
  "idToName": {                             // self-describing: self-contained recorded scene
    "npc": {"id":"name"}, "item": {}, "anim": {}, "spotanim": {}, "object": {}, "projectile": {}
  },
  "scenes": [ /* §Scene — one per distinct loaded scene the buffer touched */ ],
  "ticks":  [ /* §WorldSnapshot — one per game tick, oldest→newest */ ],
  "events": {
    "damageEvents": [ /* §DamageEvent */ ],
    "effectEvents": [ /* §EffectEvent */ ],
    "playerActions": [ /* eat/drink/prayer-toggle/gear-swap/attack/spec/move-click */ ],
    "deaths": [ {"tick":0,"actorRef":"player|npc:<sceneId>"} ],
    "interactingChanges": [ {"tick":0,"actorRef":"","targetRef":""} ]
  },
  "summary": { /* §Summary */ }
}
```

## Scene (collision/map — captured once per scene load, §4b)
```jsonc
{
  "sceneKey": "regionHash:loadSeq",
  "regionIds": [0], "plane": 0,
  "baseX": 0, "baseY": 0, "width": 104, "height": 104,
  "collision": { "encoding": "sparse", "tiles": [ {"x":0,"y":0,"flag":2097408} ] }, // non-zero CollisionDataFlag tiles only
  "objects": [ {"id":0,"x":0,"y":0,"plane":0,"sizeX":1,"sizeY":1,"orientation":0,"type":"game|wall|ground|decor"} ] // [default] on, for visual obstacles
}
```
Dynamic collision changes ride in `events` as object spawn/despawn (so recorded geometry can be updated
over time). A snapshot references its scene by `sceneKey`.

## WorldSnapshot (one per tick)
```jsonc
{
  "tick": 0, "wallClockMs": 0, "sceneKey": "", "plane": 0,
  "player": {
    "worldX":0,"worldY":0,"localX":0,"localY":0,"orientation":0,
    "animationId":-1,"poseAnimationId":-1,"graphicIds":[],
    "hp":0,"maxHp":0,"prayer":0,"maxPrayer":0,"activePrayers":[],"overheadPrayer":null,
    "boostedLevels":{"attack":0,"strength":0,"defence":0,"ranged":0,"magic":0,"hitpoints":0,"prayer":0}, // real vs base → drains/boosts
    "specEnergy":0,"runEnergy":0,"interactingRef":null,
    "equipment":[{"slot":0,"itemId":0}],
    "movement":{"fromX":0,"fromY":0,"toX":0,"toY":0,"clickX":null,"clickY":null} // intent vs actual → knockback/stun/freeze
  },
  "npcs": [ {
    "sceneId":0,"npcId":0,"name":"","combatLevel":0,
    "worldX":0,"worldY":0,"localX":0,"localY":0,"orientation":0,
    "animationId":-1,"poseAnimationId":-1,"graphicIds":[],
    "healthRatio":-1,"healthScale":-1,"overheadText":null,"overheadPrayer":null,
    "interactingRef":null,
    "derivedAction":"IDLE|MOVING|ATTACKING|MOVING_AND_ATTACKING|COOLDOWN",
    "attackCooldownTicksRemaining":0
  } ],
  "players": [ {   // other players (PvP + bystanders) — [default] capture always; equipment [default] ON (needed for PvP attribution)
    "name":"","worldX":0,"worldY":0,"localX":0,"localY":0,"orientation":0,
    "animationId":-1,"graphicIds":[],"overheadPrayer":null,"interactingRef":null,
    "equipment":[{"slot":0,"itemId":0}],"team":null
  } ],
  "projectiles": [ {
    "projectileId":0,"sourceRef":null,"targetRef":null,
    "startWorldX":0,"startWorldY":0,"endWorldX":0,"endWorldY":0,
    "spawnTick":0,"startCycle":0,"endCycle":0,"estimatedImpactTick":0,
    "slope":0,"startHeight":0,"endHeight":0
  } ],
  "hazards": [ {   // environmental — spot anims / objects / aoe on or near the player
    "kind":"graphicsObject|gameObject|groundObject|projectileAoe",
    "id":0,"worldX":0,"worldY":0,"mappedName":"Acid pool","startCycle":0
  } ]
}
```
`*Ref` = `"player" | "npc:<sceneId>" | "otherplayer:<name>"` so events/interactions resolve to the
exact actor captured this tick.

## DamageEvent (§3, §5)
```jsonc
{
  "launchTick":0,"impactTick":0,
  "attackerRef":null,"attribution":"CONFIRMED|TIMED|PROXIMITY|ENVIRONMENTAL|UNKNOWN",
  "amount":0,"hitsplatType":0,"damageType":"melee|ranged|magic|typeless|poison|venom|dragonfire|...",
  "attackStyleName":null,                    // from idToName/gameval semantics where known
  "determination":"LAUNCH|IMPACT|UNKNOWN",   // reactability (from the map; default UNKNOWN)
  "pvp":false,                               // ⇒ protection prayers only ~40% (map rule)
  "defenderAtLaunch":{"overhead":null,"activePrayers":[],"worldX":0,"worldY":0},
  "defenderAtImpact":{"overhead":null,"activePrayers":[],"worldX":0,"worldY":0},
  "mapRef":"sources.json key / specials.json spec / generic rule"   // which map entry labelled it
}
```

## EffectEvent (effects.json — non-damaging / status)
```jsonc
{
  "type":"freeze_bind|teleblock|stun|knockback|prayer_disable|prayer_pierce|disarm|stat_drain|prayer_drain|run_energy_drain|spec_drain|dot|lifesteal|guaranteed_hit|multi_hit",
  "sourceRef":null,"attribution":"CONFIRMED|TIMED|PROXIMITY|ENVIRONMENTAL|UNKNOWN",
  "startTick":0,"durationTicks":null,"magnitude":null,
  "observedVia":"spotanim:<id>|varbit:<id>|skillDelta|equipmentDiff|movementDelta",
  "mapRef":"effects.json key"
}
```

## Context (variance — §3b)
```jsonc
{
  "activity":"colosseum|toa|cox|tob|inferno|wilderness|...|null",
  "raidLevel":null,"invocations":[],"challengeMode":null,"teamSize":null,
  "bossPhase":null,"bossHpRatio":null
}
```
[default] captured at death + whenever a value changes (delta list), so attribution/replay know the
variant in effect. Read from varbits/varplayers where available.

## Summary
```jsonc
{
  "killerRef":null,"killerName":null,"killerConfidence":"CONFIRMED|TIMED|PROXIMITY|ENVIRONMENTAL|UNKNOWN",
  "fatalHit":{ /* the DamageEvent of the killing blow + its launch/impact prayer state */ },
  "reactable":null,                    // was the fatal hit reactable (map determination + lifecycle)
  "activity":null,"bossName":null,
  "totalDamageTakenInBuffer":0,"ticksCaptured":0,"otherPlayersPresent":false
}
```

## Proposed capture defaults [default — change if you disagree]
- Buffer: **50 ticks** (~30s) rolling; configurable. Capture **always-on while logged in**.
- Capture radius: **full loaded scene** for NPCs/projectiles/hazards; other players same.
- Other players: **captured incl. equipment** (needed for PvP). Capture/export stays local in
  the plugin. Recorded combat data stays on the user's device.
- `boostedLevels` every tick (cheap; powers drain/boost detection).
- Compact objects in memory; all JSON/string work deferred to export (§6).

## Decisions (resolved)
- **`schemaVersion` starts at `"1.0"`.** (Shipped; asserted in tests.)
- **Buffer default: 100 ticks (~60s), configurable** (`ticksToRecord`). Chosen over the 50-tick floor
  for a more generous pre-death window; per-tick capture is light and gated by the Death
  Reconstruction toggles. Scene grids are retained per referenced scene, not per tick.
- **`objects[]` (purely-visual static obstacles) + dynamic object spawn/despawn deltas → v1.1.**
  Collision is the functional must-have and ships in v1. Object footprints add visual richness but
  do not change what is walkable/blocked (collision already encodes that), and their exact shape is
  best pinned alongside the Phase-2 renderer to avoid rework. **Damage-relevant** game/ground objects
  are NOT deferred — they are captured now through `hazards[]` (`kind: gameObject|groundObject`,
  mapped-name only), so every damage source is still recorded in v1.

## Journal storage timing (2026-10-28)
The local TickRecording journal envelope (deaths.json / saved.json, not this rich export)
adds eventsBeforeSnapshot=true for new death/live-snapshot recordings. Events and resulting
state now share the settled tick; missing/false retains the old journal HP-delta interpretation.
This optional local-journal marker does not change the rich schemaVersion 1.0 structure.

## Manual live snapshot exports (2026-10-28)
Save Snapshot now includes the same schemaVersion 1.0 world/scenes/events envelope as death saves.
For a live snapshot, meta.snapshotTick is the final selected absolute tick number; meta.deathTick,
summary.fatalHit, killer and reactable are absent. Death exports keep meta.deathTick and omit
snapshotTick. These optional fields distinguish a manual capture from a death without inventing a
fatal hit. Older death exports remain compatible, and older journal-only live saves remain readable.
Events and world frames are limited to the selected journal tick range. Pausing freezes both the
journal and its serialized rich payload; saving later does not use newer frames or events.
