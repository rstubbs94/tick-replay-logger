# Changelog

## Unreleased

- **Damage tracking:** each hit is matched to its source from what was recorded: projectiles landing on you, attacks timed to the hit, floors and hazards under you, falling and splash effects, max hits per attack style, and your own actions. Poison and venom name what applied them, self-damage items and failed actions name the item or object, and environmental damage shows the game's message. When several sources fit, all are named; when none does, the hit shows Unknown.
- **Prayer:** prayers switched off by an attack (such as Vorkath's or the Hunllef's prayer attack) name that attack in the tick log.
- **Recorded timing:** tick intervals and sampled world ping are included in the tick log and recordings for later review.
- **JSON exports:** more recorded NPC, position, animation, projectile, object and floor-tile information, with smaller lossless exports for large recordings.
- **Saving and copying:** damage matching runs when a death or snapshot is captured. Matching, recording saves and large JSON copies run in the background to reduce pauses.
- **Replay UI:** clearer damage and action icons, source names and telemetry wording, plus a tidier tick list without horizontal scrolling.

Live View shows recorded observations as they arrive. Completed deaths and
snapshots include damage-source matching for later playback.

## 1.0.0 - 2026-01-27

- Initial Plugin Hub release as Tick Replay Logger.
- Added optional Live View toggle for strict post-death review.
- Improved live snapshot saving behavior and documentation.
