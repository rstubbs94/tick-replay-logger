# Factual tick recording plan

The plugin captures client observations, stores recordings locally and provides
Live View, manual snapshots, playback and versioned JSON export. The current
contract is [export schema 1.3](export-schema-v1.3.md); [TODO](TODO.md) lists active
work and [HANDOFF](HANDOFF.md) identifies the validated candidate.

## Capture

Record actors and effective NPC definitions/forms, positions, animations, prayers,
equipment, resources, damage, projectiles, graphics, objects, ground items and
available callbacks. Preserve clocks, callback order, stable lifetime identities,
native scene/view/plane scope and explicit unavailable fields. Keep the original
callback sample separate from later settled snapshots.

Preserve local incoming, local-owned outgoing and other-owned damage, including
observed zeroes. Retain simultaneous source alternatives and unknown causes.
Names, nearby actors, ownership or source references alone cannot establish a
unique attacker or hidden server action.

## Local use and export

Keep Live View, paused-buffer snapshots, saved recordings and export usable.
Render recorded facts without combat advice or user-facing forecasts. Supporting
internal reconstruction retains the existing onset, movement, phase-cue and cycle
estimates with evidence, separate from original observations. Actual observed NPC
definition/form changes remain available. Keep recordings and export on the user's device.

Bundle compact factual identity/context/status and damage-source lookup rules.
The full research corpus and mechanic prose remain outside the runtime product.
Source matching may label an observation only within its existing evidence guards.

## Validation

Run checks appropriate to changes: actual callback/handler controls, missing-field
and native-scope cases, damage ownership/totals, export reconstruction and package
reproducibility. Validate the current format and candidate identity. Report concrete
limitations without turning source-description completeness or all-content testing
into release requirements. Any client launch or restart needs explicit owner
instruction. Capture remains read-only; there is no game automation.
