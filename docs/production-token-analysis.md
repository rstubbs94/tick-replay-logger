# Production source token analysis

Later cleanup: the current production total is **212,967**, after wildcard
imports, shorter qualified type references and four unused-helper removals.
The tables below intentionally retain the measured `a640c64` baseline of
214,137 for comparison. The listed unused helpers were removed after this audit.

Measured 20 November 2026 against commit `a640c644a0d93297f7f674e320dda996872062bd`.

**214,137 locally measured review tokens across 107 production Java files.** The strict below-200,000 gate needs at least **14,138** more tokens removed.

## What is counted

Only `src/main/java/**/*.java` is read and counted. Comments and blank lines are removed; string literals and escapes remain. Trailing line whitespace is trimmed, then each complete file is independently counted with `o200k_base`. Imports, declarations, annotations, model source and Java string constants count.

**Not counted:** all tests, production JSON/resources, documentation/README, build scripts, compiled classes, generated Lombok code and other build output. The large mechanics JSON therefore contributes zero to this total.

The maintainer explicitly confirmed these exclusions in [the PR comment](https://github.com/runelite/plugin-hub/pull/20986#issuecomment-7017142413). Readable source is still required; [minification and obfuscation are not acceptable](https://github.com/runelite/plugin-hub/pull/20986#issuecomment-7017065819).

This is a local estimate, not a new official CI result. On the same old public submission, the counter measured 302,075 against the official 302,079: a difference of four tokens. All figures in this report use the same counter and exclusions.

## By area

| Area | Files | Tokens | Share |
| --- | ---: | ---: | ---: |
| Capture and reference lookup | 44 | 80,934 | 37.80% |
| Resolver and completed-recording projection | 11 | 51,340 | 23.98% |
| Plugin lifecycle and callbacks | 2 | 40,518 | 18.92% |
| User interface | 8 | 22,976 | 10.73% |
| Data models | 36 | 9,899 | 4.62% |
| Storage and display utilities | 6 | 8,470 | 3.96% |
| **Total** | **107** | **214,137** | **100%** |

The resolver group includes `ResolverV1`, `OriginalSourceProof`, `RawEvidenceWindow`, `ResolverEvidence`, `ResolutionCandidate`, `ResolutionResult`, `SourceAnnotations`, `SourceEvidenceResolver`, `ResolverV1Recording`, `RecordingProjection` and `RuntimeCatalog`. The capture group contains the remaining capture-package files, including factual reference lookups. Each file belongs to exactly one area.

## What the totals tell us

- Capture/reference lookup plus the main plugin/config total **121,452 tokens (56.7%)**. This is the largest place to investigate ownership, repeated field construction and repeated bookkeeping.
- The core matcher, `ResolverV1.java`, is **19,740 tokens (9.2%)**. The whole resolver/index/guard/projection group is **51,340**; its surrounding code is **31,600**. Treating all of that as only the matcher hides where the size goes.
- The largest three files total **76,565 tokens (35.8%)**. The largest six total **101,898 (47.6%)**.
- The **36 model files total only 9,899 tokens (4.6%)**. Their file count looks large, but merging them would barely affect the problem. Even deleting every model would not provide the required reduction.
- Tests and the large research JSON cannot solve the source-size gate because they are excluded.

## Largest method sections

These are source-location measurements from the JDK parser, including package-private and nested-class methods and constructors. Each method is tokenized in isolation. **Do not add these figures to the file totals**: they are already inside those files, and token boundaries differ between isolated methods and whole files. Anonymous/nested methods inside another method are not counted again.

| File | Section | Tokens in isolation |
| --- | --- | ---: |
| [ResolverV1.java](../src/main/java/com/tickreplaylogger/capture/ResolverV1.java#L1648) | `value` (line 1648) | 2,660 |
| [TickReplayLoggerPlugin.java](../src/main/java/com/tickreplaylogger/TickReplayLoggerPlugin.java#L1883) | `observeHazardsOnTile` (line 1883) | 2,406 |
| [WorldCaptureService.java](../src/main/java/com/tickreplaylogger/capture/WorldCaptureService.java#L1277) | `captureNpcs` (line 1277) | 2,177 |
| [TickReplayLoggerPlugin.java](../src/main/java/com/tickreplaylogger/TickReplayLoggerPlugin.java#L1094) | `onHitsplatApplied` (line 1094) | 2,157 |
| [ProjectileObservation.java](../src/main/java/com/tickreplaylogger/capture/ProjectileObservation.java#L54) | `<init>` (line 54) | 2,122 |
| [EncounterContextCapture.java](../src/main/java/com/tickreplaylogger/capture/EncounterContextCapture.java#L37) | `capture` (line 37) | 2,084 |
| [WorldCaptureService.java](../src/main/java/com/tickreplaylogger/capture/WorldCaptureService.java#L734) | `captureNativeTick` (line 734) | 2,083 |
| [OriginalSourceProof.java](../src/main/java/com/tickreplaylogger/capture/OriginalSourceProof.java#L499) | `geometry` (line 499) | 2,074 |
| [RawEvidenceWindow.java](../src/main/java/com/tickreplaylogger/capture/RawEvidenceWindow.java#L375) | `<init>` (line 375) | 1,981 |
| [ResolverV1.java](../src/main/java/com/tickreplaylogger/capture/ResolverV1.java#L380) | `component` (line 380) | 1,913 |
| [TickReplayLoggerPlugin.java](../src/main/java/com/tickreplaylogger/TickReplayLoggerPlugin.java#L3927) | `observe` (line 3927) | 1,792 |
| [SourceEvidenceResolver.java](../src/main/java/com/tickreplaylogger/capture/SourceEvidenceResolver.java#L46) | `project` (line 46) | 1,681 |
| [ObjectStateTracker.java](../src/main/java/com/tickreplaylogger/capture/ObjectStateTracker.java#L285) | `read` (line 285) | 1,629 |
| [WorldCaptureService.java](../src/main/java/com/tickreplaylogger/capture/WorldCaptureService.java#L467) | `captureTick` (line 467) | 1,594 |
| [PlaybackPanel.java](../src/main/java/com/tickreplaylogger/ui/playback/PlaybackPanel.java#L132) | `buildUI` (line 132) | 1,590 |
| [WorldCaptureService.java](../src/main/java/com/tickreplaylogger/capture/WorldCaptureService.java#L1600) | `captureHazards` (line 1600) | 1,570 |
| [RecordingEntryPanel.java](../src/main/java/com/tickreplaylogger/ui/RecordingEntryPanel.java#L117) | `createHeaderPanel` (line 117) | 1,525 |
| [TickReplayLoggerPlugin.java](../src/main/java/com/tickreplaylogger/TickReplayLoggerPlugin.java#L2363) | `captureReadyGameTick` (line 2363) | 1,505 |
| [ResolverV1.java](../src/main/java/com/tickreplaylogger/capture/ResolverV1.java#L144) | `evaluate` (line 144) | 1,442 |
| [TickReplayLoggerPanel.java](../src/main/java/com/tickreplaylogger/ui/TickReplayLoggerPanel.java#L104) | `<init>` (line 104) | 1,408 |

## Inspection priorities

These are candidates for a code review, **not proven removable code or promised savings**.

1. **Capture and callback construction first.** Compare hazard/contact field construction in `TickReplayLoggerPlugin.observeHazardsOnTile` (2,406) with world hazard capture (1,570), and inspect NPC/native-frame construction (2,177 / 2,083). Share factual projection or bookkeeping where it is actually duplicated. An impact-time observation and an end-of-tick observation are different facts; one must not replace the other merely to reduce code.
2. **Resolver field access and output projection next.** The `RawFacts.value` accessor alone is 2,660 tokens. The main damage-output projection is 1,681, with further candidate-grouping code in the same file. Check for unnecessary aliases and repeated conversion between original facts, annotations and display fields. The previous scoped review found most aliases currently used; this is not a known 14k deletion.
3. **UI construction is a secondary target.** It totals 22,976 tokens; playback construction alone is 1,590 and recording headers 1,525. However, labels, icons and several row builders are already shared. A new generic UI framework may cost more than it saves.
4. **Retain the real work.** Source geometry (2,074), contact checks (986), projectile history, scene identity and timing distinguish valid attribution from guesses. Their size identifies review work; it does not establish that they can be removed without losing behavior.

Moving a method into another file, merging model files, deleting tests/comments, or removing JSON does not meaningfully reduce this measured production total. A useful reduction must remove a responsibility or implement it once where it is currently duplicated. No functionality was changed for this analysis.

## Unused-file check

No whole production Java file was confirmed unused. A separate JDK-parser
reference inventory found references reachable from the manifest's plugin
entry for all **107/107** files; there were no files with zero incoming
production references, excluding the entry itself. Unused ordinary imports,
comments and string literals were excluded from the code-reference search.
Nested-type imports and static imports were accounted for. No file depended
solely on conservative static-import edges. All seven production resource files
also have loaders or icon uses.

This is a source dependency check, not proof that every method/branch executes.
It establishes **zero confirmed removable whole files**, rather than proving
that all code inside every file is necessary. RuneLite subscriber/provider
methods and Gson/Lombok model fields cannot be classified as dead merely because
they have no ordinary call expression.

Small cleanup candidates inside active files were confirmed separately:

- `PrayerObservation.prayerForIcon`: no main/test caller found; the live code
  calls `prayerForIconName` directly.
- `MovementData.none`: no qualified invocation or method reference found.
- `ResolverV1.matchedRule`: no caller found. Its `declarations` lookup map is
  populated only for this unused accessor; the matcher uses a separate index.
- `ObjectHazards.Resolved.isDamageCandidate`: used by tests, with no production
  caller found.

These are small helpers, not the missing 14k-token subsystem. Historical
implementations under `src/test` are outside production and the review count.
No source was deleted or modified during this check.

## Complete file ranking

Downloadable tables: [all production files](production-token-files.csv) and [all method sections](production-token-methods.csv).

| Rank | File | Tokens | Share |
| ---: | --- | ---: | ---: |
| 1 | [TickReplayLoggerPlugin.java](../src/main/java/com/tickreplaylogger/TickReplayLoggerPlugin.java) | 39,160 | 18.29% |
| 2 | [capture/ResolverV1.java](../src/main/java/com/tickreplaylogger/capture/ResolverV1.java) | 19,740 | 9.22% |
| 3 | [capture/WorldCaptureService.java](../src/main/java/com/tickreplaylogger/capture/WorldCaptureService.java) | 17,665 | 8.25% |
| 4 | [capture/OriginalSourceProof.java](../src/main/java/com/tickreplaylogger/capture/OriginalSourceProof.java) | 8,660 | 4.04% |
| 5 | [ui/playback/PlaybackPanel.java](../src/main/java/com/tickreplaylogger/ui/playback/PlaybackPanel.java) | 8,613 | 4.02% |
| 6 | [capture/RawEvidenceWindow.java](../src/main/java/com/tickreplaylogger/capture/RawEvidenceWindow.java) | 8,060 | 3.76% |
| 7 | [capture/CombatCatalog.java](../src/main/java/com/tickreplaylogger/capture/CombatCatalog.java) | 6,747 | 3.15% |
| 8 | [capture/SourceEvidenceResolver.java](../src/main/java/com/tickreplaylogger/capture/SourceEvidenceResolver.java) | 6,185 | 2.89% |
| 9 | [ui/TickReplayLoggerPanel.java](../src/main/java/com/tickreplaylogger/ui/TickReplayLoggerPanel.java) | 5,804 | 2.71% |
| 10 | [capture/WorldSnapshotRecorder.java](../src/main/java/com/tickreplaylogger/capture/WorldSnapshotRecorder.java) | 4,805 | 2.24% |
| 11 | [capture/RecordingProjection.java](../src/main/java/com/tickreplaylogger/capture/RecordingProjection.java) | 3,930 | 1.84% |
| 12 | [capture/TerrainCapture.java](../src/main/java/com/tickreplaylogger/capture/TerrainCapture.java) | 3,673 | 1.72% |
| 13 | [ui/RecordingEntryPanel.java](../src/main/java/com/tickreplaylogger/ui/RecordingEntryPanel.java) | 3,560 | 1.66% |
| 14 | [util/RecordingStorage.java](../src/main/java/com/tickreplaylogger/util/RecordingStorage.java) | 3,378 | 1.58% |
| 15 | [capture/ObjectStateTracker.java](../src/main/java/com/tickreplaylogger/capture/ObjectStateTracker.java) | 3,299 | 1.54% |
| 16 | [capture/SceneCaptureTraversal.java](../src/main/java/com/tickreplaylogger/capture/SceneCaptureTraversal.java) | 3,248 | 1.52% |
| 17 | [capture/ObservedInventorySourceHints.java](../src/main/java/com/tickreplaylogger/capture/ObservedInventorySourceHints.java) | 2,855 | 1.33% |
| 18 | [capture/EncounterContextCapture.java](../src/main/java/com/tickreplaylogger/capture/EncounterContextCapture.java) | 2,633 | 1.23% |
| 19 | [capture/WorldViews.java](../src/main/java/com/tickreplaylogger/capture/WorldViews.java) | 2,555 | 1.19% |
| 20 | [capture/ProjectileObservation.java](../src/main/java/com/tickreplaylogger/capture/ProjectileObservation.java) | 2,495 | 1.17% |
| 21 | [ui/LiveTickPanel.java](../src/main/java/com/tickreplaylogger/ui/LiveTickPanel.java) | 2,343 | 1.09% |
| 22 | [capture/WorldStateExport.java](../src/main/java/com/tickreplaylogger/capture/WorldStateExport.java) | 2,258 | 1.05% |
| 23 | [util/DisplayFormat.java](../src/main/java/com/tickreplaylogger/util/DisplayFormat.java) | 2,131 | 1.00% |
| 24 | [capture/CaptureBuild.java](../src/main/java/com/tickreplaylogger/capture/CaptureBuild.java) | 2,033 | 0.95% |
| 25 | [capture/ActorObservation.java](../src/main/java/com/tickreplaylogger/capture/ActorObservation.java) | 1,875 | 0.88% |
| 26 | [util/TickRecorder.java](../src/main/java/com/tickreplaylogger/util/TickRecorder.java) | 1,709 | 0.80% |
| 27 | [capture/PrayerObservation.java](../src/main/java/com/tickreplaylogger/capture/PrayerObservation.java) | 1,525 | 0.71% |
| 28 | [capture/ResolverV1Recording.java](../src/main/java/com/tickreplaylogger/capture/ResolverV1Recording.java) | 1,367 | 0.64% |
| 29 | [TickReplayLoggerConfig.java](../src/main/java/com/tickreplaylogger/TickReplayLoggerConfig.java) | 1,358 | 0.63% |
| 30 | [capture/GroundItemTracker.java](../src/main/java/com/tickreplaylogger/capture/GroundItemTracker.java) | 1,338 | 0.62% |
| 31 | [capture/ProjectileSourceTracker.java](../src/main/java/com/tickreplaylogger/capture/ProjectileSourceTracker.java) | 1,338 | 0.62% |
| 32 | [capture/ActorCallbackObservation.java](../src/main/java/com/tickreplaylogger/capture/ActorCallbackObservation.java) | 1,331 | 0.62% |
| 33 | [capture/ObservedMechanics.java](../src/main/java/com/tickreplaylogger/capture/ObservedMechanics.java) | 1,301 | 0.61% |
| 34 | [capture/EncounterWaveCueTracker.java](../src/main/java/com/tickreplaylogger/capture/EncounterWaveCueTracker.java) | 1,269 | 0.59% |
| 35 | [capture/EncounterModeCapture.java](../src/main/java/com/tickreplaylogger/capture/EncounterModeCapture.java) | 1,250 | 0.58% |
| 36 | [capture/NpcStateEncoding.java](../src/main/java/com/tickreplaylogger/capture/NpcStateEncoding.java) | 1,203 | 0.56% |
| 37 | [ui/playback/StatBoxPanel.java](../src/main/java/com/tickreplaylogger/ui/playback/StatBoxPanel.java) | 1,201 | 0.56% |
| 38 | [capture/ObjectHazards.java](../src/main/java/com/tickreplaylogger/capture/ObjectHazards.java) | 1,160 | 0.54% |
| 39 | [capture/ResolverEvidence.java](../src/main/java/com/tickreplaylogger/capture/ResolverEvidence.java) | 1,130 | 0.53% |
| 40 | [capture/SourceAnnotations.java](../src/main/java/com/tickreplaylogger/capture/SourceAnnotations.java) | 1,110 | 0.52% |
| 41 | [model/TickRecording.java](../src/main/java/com/tickreplaylogger/model/TickRecording.java) | 1,100 | 0.51% |
| 42 | [capture/SoundCallbackObservation.java](../src/main/java/com/tickreplaylogger/capture/SoundCallbackObservation.java) | 1,069 | 0.50% |
| 43 | [capture/RecordingExport.java](../src/main/java/com/tickreplaylogger/capture/RecordingExport.java) | 1,064 | 0.50% |
| 44 | [capture/SelfDamageInputs.java](../src/main/java/com/tickreplaylogger/capture/SelfDamageInputs.java) | 994 | 0.46% |
| 45 | [capture/ActorInteractions.java](../src/main/java/com/tickreplaylogger/capture/ActorInteractions.java) | 973 | 0.45% |
| 46 | [model/world/DeathRecordingV2.java](../src/main/java/com/tickreplaylogger/model/world/DeathRecordingV2.java) | 939 | 0.44% |
| 47 | [util/TickDisplay.java](../src/main/java/com/tickreplaylogger/util/TickDisplay.java) | 884 | 0.41% |
| 48 | [ui/CombatIcons.java](../src/main/java/com/tickreplaylogger/ui/CombatIcons.java) | 874 | 0.41% |
| 49 | [capture/DamageMap.java](../src/main/java/com/tickreplaylogger/capture/DamageMap.java) | 846 | 0.40% |
| 50 | [capture/SceneFieldObservation.java](../src/main/java/com/tickreplaylogger/capture/SceneFieldObservation.java) | 834 | 0.39% |
| 51 | [capture/ClientStatusCapture.java](../src/main/java/com/tickreplaylogger/capture/ClientStatusCapture.java) | 738 | 0.34% |
| 52 | [capture/ResolutionCandidate.java](../src/main/java/com/tickreplaylogger/capture/ResolutionCandidate.java) | 735 | 0.34% |
| 53 | [capture/ActorGraphicsObservation.java](../src/main/java/com/tickreplaylogger/capture/ActorGraphicsObservation.java) | 731 | 0.34% |
| 54 | [capture/PlayerAppearance.java](../src/main/java/com/tickreplaylogger/capture/PlayerAppearance.java) | 721 | 0.34% |
| 55 | [model/world/DamageRecord.java](../src/main/java/com/tickreplaylogger/model/world/DamageRecord.java) | 717 | 0.33% |
| 56 | [capture/NpcSnapshotObservation.java](../src/main/java/com/tickreplaylogger/capture/NpcSnapshotObservation.java) | 638 | 0.30% |
| 57 | [capture/ActorIdentity.java](../src/main/java/com/tickreplaylogger/capture/ActorIdentity.java) | 591 | 0.28% |
| 58 | [model/DamageEvent.java](../src/main/java/com/tickreplaylogger/model/DamageEvent.java) | 581 | 0.27% |
| 59 | [model/world/WorldSnapshot.java](../src/main/java/com/tickreplaylogger/model/world/WorldSnapshot.java) | 565 | 0.26% |
| 60 | [capture/NpcDefinitionObservation.java](../src/main/java/com/tickreplaylogger/capture/NpcDefinitionObservation.java) | 535 | 0.25% |
| 61 | [capture/RecordingEncounterLabels.java](../src/main/java/com/tickreplaylogger/capture/RecordingEncounterLabels.java) | 533 | 0.25% |
| 62 | [model/PlayerAction.java](../src/main/java/com/tickreplaylogger/model/PlayerAction.java) | 532 | 0.25% |
| 63 | [model/TickData.java](../src/main/java/com/tickreplaylogger/model/TickData.java) | 529 | 0.25% |
| 64 | [model/world/ProjectileState.java](../src/main/java/com/tickreplaylogger/model/world/ProjectileState.java) | 499 | 0.23% |
| 65 | [capture/VarbitLayoutCapture.java](../src/main/java/com/tickreplaylogger/capture/VarbitLayoutCapture.java) | 490 | 0.23% |
| 66 | [ui/ReplayIcons.java](../src/main/java/com/tickreplaylogger/ui/ReplayIcons.java) | 485 | 0.23% |
| 67 | [capture/CallbackFieldObservation.java](../src/main/java/com/tickreplaylogger/capture/CallbackFieldObservation.java) | 415 | 0.19% |
| 68 | [capture/ObservedHpCallbackContext.java](../src/main/java/com/tickreplaylogger/capture/ObservedHpCallbackContext.java) | 398 | 0.19% |
| 69 | [capture/SourceAssociationNpcIndex.java](../src/main/java/com/tickreplaylogger/capture/SourceAssociationNpcIndex.java) | 368 | 0.17% |
| 70 | [capture/RuntimeCatalog.java](../src/main/java/com/tickreplaylogger/capture/RuntimeCatalog.java) | 357 | 0.17% |
| 71 | [model/world/ContextBlock.java](../src/main/java/com/tickreplaylogger/model/world/ContextBlock.java) | 355 | 0.17% |
| 72 | [capture/WeakIdentityIds.java](../src/main/java/com/tickreplaylogger/capture/WeakIdentityIds.java) | 347 | 0.16% |
| 73 | [model/world/ProjectileSourceHistory.java](../src/main/java/com/tickreplaylogger/model/world/ProjectileSourceHistory.java) | 347 | 0.16% |
| 74 | [model/world/PlayerState.java](../src/main/java/com/tickreplaylogger/model/world/PlayerState.java) | 340 | 0.16% |
| 75 | [model/world/NpcState.java](../src/main/java/com/tickreplaylogger/model/world/NpcState.java) | 321 | 0.15% |
| 76 | [model/MovementData.java](../src/main/java/com/tickreplaylogger/model/MovementData.java) | 308 | 0.14% |
| 77 | [util/SourceResolutionLabels.java](../src/main/java/com/tickreplaylogger/util/SourceResolutionLabels.java) | 306 | 0.14% |
| 78 | [capture/NpcIdentityContext.java](../src/main/java/com/tickreplaylogger/capture/NpcIdentityContext.java) | 302 | 0.14% |
| 79 | [model/world/HazardState.java](../src/main/java/com/tickreplaylogger/model/world/HazardState.java) | 263 | 0.12% |
| 80 | [capture/SnapshotPrayerObservation.java](../src/main/java/com/tickreplaylogger/capture/SnapshotPrayerObservation.java) | 245 | 0.11% |
| 81 | [model/world/ObservedActorState.java](../src/main/java/com/tickreplaylogger/model/world/ObservedActorState.java) | 208 | 0.10% |
| 82 | [model/world/SceneData.java](../src/main/java/com/tickreplaylogger/model/world/SceneData.java) | 206 | 0.10% |
| 83 | [model/world/ObservedObjectState.java](../src/main/java/com/tickreplaylogger/model/world/ObservedObjectState.java) | 183 | 0.09% |
| 84 | [model/world/SourceResolution.java](../src/main/java/com/tickreplaylogger/model/world/SourceResolution.java) | 146 | 0.07% |
| 85 | [model/world/ActorGraphicsState.java](../src/main/java/com/tickreplaylogger/model/world/ActorGraphicsState.java) | 144 | 0.07% |
| 86 | [capture/PlayerSnapshotObservation.java](../src/main/java/com/tickreplaylogger/capture/PlayerSnapshotObservation.java) | 142 | 0.07% |
| 87 | [model/world/TileSurface.java](../src/main/java/com/tickreplaylogger/model/world/TileSurface.java) | 142 | 0.07% |
| 88 | [model/world/EffectEvent.java](../src/main/java/com/tickreplaylogger/model/world/EffectEvent.java) | 140 | 0.07% |
| 89 | [model/world/OtherPlayerState.java](../src/main/java/com/tickreplaylogger/model/world/OtherPlayerState.java) | 134 | 0.06% |
| 90 | [model/world/PrayerState.java](../src/main/java/com/tickreplaylogger/model/world/PrayerState.java) | 125 | 0.06% |
| 91 | [model/world/ObservedStatusState.java](../src/main/java/com/tickreplaylogger/model/world/ObservedStatusState.java) | 109 | 0.05% |
| 92 | [capture/ObservationReads.java](../src/main/java/com/tickreplaylogger/capture/ObservationReads.java) | 104 | 0.05% |
| 93 | [ui/SingleLineLabel.java](../src/main/java/com/tickreplaylogger/ui/SingleLineLabel.java) | 96 | 0.04% |
| 94 | [model/world/ProjectileSourceCandidate.java](../src/main/java/com/tickreplaylogger/model/world/ProjectileSourceCandidate.java) | 94 | 0.04% |
| 95 | [model/world/EncounterWaveCue.java](../src/main/java/com/tickreplaylogger/model/world/EncounterWaveCue.java) | 93 | 0.04% |
| 96 | [model/world/GroundItemState.java](../src/main/java/com/tickreplaylogger/model/world/GroundItemState.java) | 86 | 0.04% |
| 97 | [model/world/ObservedEvent.java](../src/main/java/com/tickreplaylogger/model/world/ObservedEvent.java) | 84 | 0.04% |
| 98 | [model/world/ActorInteractionState.java](../src/main/java/com/tickreplaylogger/model/world/ActorInteractionState.java) | 83 | 0.04% |
| 99 | [model/world/NpcDefinitionStateReference.java](../src/main/java/com/tickreplaylogger/model/world/NpcDefinitionStateReference.java) | 82 | 0.04% |
| 100 | [model/world/ObservedVarbitLayout.java](../src/main/java/com/tickreplaylogger/model/world/ObservedVarbitLayout.java) | 82 | 0.04% |
| 101 | [model/world/WorldEntityState.java](../src/main/java/com/tickreplaylogger/model/world/WorldEntityState.java) | 82 | 0.04% |
| 102 | [model/world/PlayerAppearanceState.java](../src/main/java/com/tickreplaylogger/model/world/PlayerAppearanceState.java) | 79 | 0.04% |
| 103 | [model/world/NpcStateTable.java](../src/main/java/com/tickreplaylogger/model/world/NpcStateTable.java) | 77 | 0.04% |
| 104 | [capture/ResolutionResult.java](../src/main/java/com/tickreplaylogger/capture/ResolutionResult.java) | 66 | 0.03% |
| 105 | [model/world/NumericVariableState.java](../src/main/java/com/tickreplaylogger/model/world/NumericVariableState.java) | 65 | 0.03% |
| 106 | [util/RecordingLimits.java](../src/main/java/com/tickreplaylogger/util/RecordingLimits.java) | 62 | 0.03% |
| 107 | [model/world/ObservedEventLink.java](../src/main/java/com/tickreplaylogger/model/world/ObservedEventLink.java) | 59 | 0.03% |
