# Plugin Hub update draft

## Title

Tick Replay Logger: improve recorded damage sources, exports and replay

## Description

This update improves damage-source matching using recorded attacks, projectiles,
hit types, positions and known attack rules. Ambiguous hits retain alternatives
or show Unknown. Attribution describes events after they happen.

It also adds recorded tick intervals and sampled world ping, expands JSON
exports, improves replay icons and source labels, and moves saving and large
copy work into the background to reduce pauses. The existing Live View, manual
snapshots and local playback remain available. Recordings stay local and can be
copied manually.

## Validation

- Java 11, RuneLite 1.13.6: complete rerun of 1,049 tests, zero failures, errors
  or skips, including storage recovery, callback consistency, capture, replay
  presentation and source attribution.
- Includes 109 shared resolver cases and 528 qualification cases covering all
  106 bundled source bounds. These verify rule interpretation, not an encounter
  accuracy percentage.
- Production JAR preflight against the current official restricted-API rules:
  zero findings, with no packaged client or test classes. This is a local
  preflight; official Hub review remains pending.

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
