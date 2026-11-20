# Release notes draft

Tick Replay Logger now does a better job of matching recorded damage to its
source. It uses recorded attacks, projectiles, hit types, positions and known
attack rules. When several sources fit, their names can appear together; when
the source cannot be identified, the hit shows Unknown.

- Added recorded tick intervals and sampled world ping for context during review.
- Expanded JSON exports with more NPC, position, animation, projectile, object
  and floor-tile information.
- Made large JSON copies smaller with lossless compression.
- Damage matching runs when a death or snapshot is captured. Matching, recording saves and large copy work run in the background to reduce pauses.
- Improved replay icons, source labels and tick-list layout.

The plugin remains a record of events that have already happened. Death replays,
Live View, manual snapshots and local playback remain available. Recordings stay
on your computer and can be copied manually.

Ping is sampled periodically; each recorded tick contains the latest available
sample. Tick intervals measure when ticks reached the client.
