# Plugin Hub update

## Title

tick-replay-logger: improve damage tracking, exports and replay

## Description

This update improves damage-source matching. After a death or snapshot is
captured, each recorded hit is matched to its source from the recorded
observations: projectiles landing on the player, attacks timed to the hit,
floors and hazards under the player, max hits per attack style and the player's
own actions. Poison and venom name what applied them. When several sources fit,
all are named; when none does, the hit shows Unknown. Attribution describes
events after they happen.

It also adds recorded tick intervals and sampled world ping, expands JSON
exports, improves replay icons and source labels, and moves saving and large
copy work into the background to reduce pauses. The existing Live View, manual
snapshots and local playback remain available. Recordings stay local and can be
copied manually.

## Changes since review

- Switched to `build=standard`; the plugin builds with the Hub's standard
  `build.gradle`.
- Reduced readable source from about 302,000 to about 169,900 tokens (local
  count; comments, resources, tests and the README excluded) by replacing the
  attribution engine with a smaller data-driven one. No minification or
  obfuscation. Reference data is in resource files, and `src/main` contains no
  test code.

## Validation

- Java 11, RuneLite 1.13.7: 1,038 tests, zero failures.
- Built with the Hub's standard build template. Local restricted-API preflight
  of the production JAR: zero findings, with no packaged client or test classes.
  Official Hub review remains pending.
- Damage matching was checked hit by hit against recorded play, including
  bosses, poison, self-damage and environmental damage.

## Reviewer notes

- Damage reconstruction uses recorded observations and bundled reference data.
- Recording storage and large export preparation run on background workers;
  presentation updates use Swing's event thread.
- Storage uses RuneLite's plugin data directory with migration and backup recovery.
- Optional world ping uses RuneLite's existing WorldService and Ping utility.
  The most recent successful sample is recorded alongside each tick.
- The Hub update changes the commit marker for the existing plugin.

## Guidance checked

- [Plugin Hub build and review guidance](https://github.com/runelite/plugin-hub/blob/master/README.md)
- [Rejected and restricted features](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features)
- [Code conventions](https://github.com/runelite/runelite/wiki/Code-Conventions)
