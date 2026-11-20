# Phase 1 release checklist

## Current refactor

- [x] Continuously buffer original observations, map state and projectiles.
- [x] Use one bounded recording owner and one original event identity.
- [x] Keep Live View, pause, manual snapshots, saved recordings and playback.
- [x] Run damage attribution only for a captured window, in the background.
- [x] Keep paused display and saved raw data on the same atomic selection.
- [x] Preserve death/revival boundaries and unresolved-impact safeguards.
- [x] Generate current journal, title and export from the prepared recording.
- [x] Remove the live attribution worker and current JSON repair path.
- [x] Reuse scene membership while freshly sampling mutable state.
- [x] Remove unconsumed historical tables from the runtime package without changing current rules.
- [x] Keep published 1.0.0 captures usable in the plugin.
- [x] Pass the full regression suite, retained-data checks, standard build and restricted-API scan.
- [x] Independently review capture lifetimes, atomic selection and published compatibility.
- [x] Keep README and release wording focused on damage matching, export detail, telemetry and UI.

## Generic resolver (branch rebuild/generic-resolver-20261120)

- [x] Replace the resolver path with the generic data-driven resolver; keep capture, export and UI.
- [x] Full suite green, thin-JAR API scan clean, production under 200,000 tokens (165,990).
- [ ] Owner tests the new build in game (test client in `C:\Dev	ick-replay-test\generic-resolver-test`).
- [ ] Run the official formatter on the new files.
- [ ] Phase B: remove remaining capture-time classification and the unused resolver section of `runtime-mechanics.json`.
- [ ] Website: show the plugin's resolution instead of re-resolving on import.

## Before resubmission

- [ ] Reduce readable production source below the maintainer's 200,000-token limit, with headroom.
- [ ] Validate the final reduced source and the exact standard-built artifact.
- [ ] Update the existing public source/Plugin Hub PR only after that gate passes.
- [ ] Address actual CI/reviewer feedback and obtain maintainer approval.

Current counts, behavior and validation scope are in [HANDOFF.md](HANDOFF.md).
Passing local checks does not mean the update is accepted or review-size eligible.
Do not add new mechanics research, product features, gameplay advice, uploads,
client launches or exhaustive owner/video testing as release requirements.
