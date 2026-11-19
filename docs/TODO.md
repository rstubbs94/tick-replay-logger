# Phase 1 release checklist

## Current maintainer corrections - in progress

PR #20986 passed compilation but is not review-ready: the maintainer requested
standard build mode and the review input is 302,079 tokens against a 200,000 limit.
Follow the [single-resolver consolidation plan](resolver-consolidation-plan.md).
The refactor must preserve existing standalone features and exported game facts.

- [ ] Freeze the behaviour/metadata inventory and establish a measured reduction budget.
- [ ] Support the actual standard package without disabling existing reference-backed interpretation.
- [ ] Consolidate mechanics data, evidence normalization and all attribution entry points.
- [ ] Remove replaced code only after behaviour and data parity are demonstrated.
- [ ] Pass final functionality, package/API and official review-size gates.
- [ ] Update the existing PR and obtain RuneLite approval.

Implementation is active in `C:/Dev/tick-replay-refactor` on
`refactor/standard-single-resolver-20261119`. Earlier local checks below describe
previous candidates; the refactor still needs its behaviour and review-size gates.

## Previous candidate validation - 19 November 2026

- [x] Rerun the complete current runtime suite: 1,049 tests in 103 suites, with no failures, errors or skips.
- [x] Build the Java 11 production JAR against the retrieved Hub target, RuneLite 1.13.6; scan it for restricted APIs and unintended client/test classes.
- [x] Review the retained test log for plugin errors and capture-time warnings.
- [x] Review the update against the published source revision and prepare plain-language release notes focused on retrospective recording and analysis.
- [x] Document tick intervals and periodic ping sampling accurately.
- [x] Recheck current Hub rules and developer conventions, correct formatting and telemetry wording, then rerun all 1,049 tests and scan the cleaned production JAR against current official API rules.
- [x] Push the reviewed release revision and submit the Plugin Hub commit-marker update (PR #20986; corrections requested).
- [ ] Confirm the accepted update is available through the Plugin Hub.

Older counts and checkpoints below describe earlier candidates.

## Previous implementation milestones

- [x] Preserve the rolling journal, death recordings, Live View, manual snapshots, saved recordings and local playback.
- [x] Capture rich actor, damage, object, graphic, terrain, collision and resource observations with explicit unknowns and source evidence.
- [x] Keep incoming, local-owned and other observed NPC damage separate.
- [x] Improve replay labels/icons and retain single-line rows without horizontal panel scrolling.
- [x] Reduce repeated scene capture and copy preparation costs; use lossless compact JSON for large current clipboard exports.
- [x] Preserve independently readable object/graphic fields after optional getter failures, without false object-removal deltas or stale field reuse.
- [x] Validate the earlier custom Gradle package; this approach is superseded by the required standard-build migration above.
- [x] Validate the earlier RuneLite 1.13.6 target and supported references. Current release checks target RuneLite 1.13.7.
- [x] Rewrite the README, draft release notes and capture documentation around the plugin's shipped local functionality.

## Plugin Hub review corrections

- [x] Wrap custom Gradle lines and pass the injected Gson through NPC export/reading.
- [x] Move recording files to Filepath, retaining existing data through RuneLite's directory migration.
- [x] Serialize storage operations in the background, protect failed loads, and recover incomplete writes from a valid backup.
- [x] Freeze selected export windows before background compaction/JSON work; keep game and UI threads free of recording file writes.
- [x] Dispatch UI refreshes on Swing's thread and dispose playback/copy work safely on disable.
- [x] Replace prayer-mechanic tooltips with neutral recorded facts and reuse the current scene census for plane discovery.
- [x] Pass the combined behavior checks: 644 tests, no failures/errors/skips, including storage recovery, migration, selected-window retention and concurrent capture.
- [x] Apply RuneLite source formatting and copyright headers in a separate change; preserve all 147 Java file bodies.
- [x] Complete the final clean build (659 passing checks), match all 206 packaged plugin entries, and verify no forbidden API calls or bundled client/test classes.

## Death-processing performance

- [x] Confirm the old client-thread pause from the retained capture/log and measure the actual replay's allocation costs offline.
- [x] Buffer file writes and retain strict Unicode/default-charset recovery without changing recorded values.
- [x] Reuse indexes only for exact-equal complete scene samples; preserve changed, missing, reordered and removed rows.
- [x] Prepare/cache replay labels off the UI thread and avoid full rich-recording decoding for playback captions.
- [x] Verify death and immediate revival callbacks continue while export/storage is blocked; finish the clean 659-test build and packaged API check.

## Generic evidence work - 8 November 2026

Final local validation: **700 passing checks**, 220 matching packaged entries,
216 classes and zero forbidden API findings. Fresh native feedback is next;
see [the current handoff](HANDOFF.md).

Work branch: `feature/generic-evidence-20261108`. The previous system is retained
on `backup/pre-generic-evidence-20261108-0036` and in a verified local Git bundle.

- [x] Retain structured candidate identity separately from observed projectile source fields.
- [x] Resolve source actor/family independently of an exact attack, with named alternatives and relevant missing-data flags.
- [x] Apply typed, scoped research constraints through generic operators; include status and environmental candidates together.
- [x] Move remaining content-specific context/item interpretation branches into declarative bindings.
- [x] Verify arbitrary identities, missing references, simultaneous hits, variant/mode limits and incomplete observations without content-specific implementation branches.
- [x] Preserve amount/type/raw callbacks, current export transport, Live View and asynchronous recording performance.
- [x] Complete source/API/combat-guidance review, independent data inspection and final packaged validation.

No coaching, forecasts, automatic gameplay, new product features or publication
are authorized by this work. Keep all interpretation retrospective and preserve
unknowns where supporting evidence is genuinely missing.

## Earlier publication checklist (historical)

- [ ] Publish the approved plugin source commit.
- [ ] Update the existing Plugin Hub marker to that commit and prepare the update pull request.
- [ ] Resolve any actual Hub CI or reviewer feedback.

These older notes do not replace the owner's current instructions or the
maintainer-correction gates above. A client launch/restart requires an explicit
owner instruction; verify process state before acting.
Further reproducible defects are handled as ordinary fixes, not an open-ended
encounter certification project. Video collection and exhaustive owner testing
are not release requirements.

## Observed hitsplat eligibility - 8 November 2026

- [x] Apply data-backed mechanism signatures before accepting competing sources.
- [x] Preserve observed type availability separately from unknown attack style.
- [x] Retain mismatches as exclusions and prevent numeric fallback from reviving them.
- [x] Replay the reported live tick: blocked zero from the attack source; venom damage from the contact source.
- [x] Finish pinned packaging: 712 checks, 223 matching entries, 219 classes and zero forbidden API findings.
