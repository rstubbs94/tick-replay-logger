# Changelog

## Unreleased

- **Damage tracking:** better matching of damage to NPCs and environmental sources using recorded attacks, projectiles, hit types, positions and known attack rules. Unclear hits retain the possible sources or show Unknown.
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
