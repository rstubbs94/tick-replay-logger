# Current handoff

## Current status - consolidation in progress, 19 November 2026

PR #20986 is open for public source `bf19b988b53d3ec861d732671da35e1e7cdb76aa`,
whose tree matches local validated candidate `1505ed1`. The build passed, but the
maintainer requested `build=standard` and flagged 302,079 review tokens against a
200,000 limit. Passing local checks did not establish review readiness.

The [consolidation plan](resolver-consolidation-plan.md) defines the target,
no-loss contract, staged migration and release gates. It replaces overlapping
attribution paths with one data-driven interpreter while preserving standalone
capture, Live View, snapshots, replay and exports. Merely changing the build flag
would disable supported interpretations and is not an acceptable correction.
Implementation is active in the isolated worktree `C:/Dev/tick-replay-refactor`,
branch `refactor/standard-single-resolver-20261119`. The frozen public candidate
and backup remain unchanged. The standard-build migration passed its isolated
package checks. Production now uses the shared resolver path; unused legacy
evaluators and reference resources remain in the test suite as comparison tools.
Behaviour regressions and the review-size gate remain open. Older readiness
notes below describe the previous candidate and do not validate this refactor.

## Release candidate - 19 November 2026

The Phase 1 update is prepared locally for Plugin Hub submission. The public
README, changelog and release notes describe improved damage-source matching,
richer JSON exports, recorded tick intervals, sampled world ping, background
saving/copying and replay UI improvements. Interpretation remains retrospective;
the existing Live View and manual snapshots remain available.

Runtime source revision: `80fd4aa98e8b60c1e82ff2313e74b9887b2d1f0a`.
The Java 11 build against the retrieved Hub target, RuneLite 1.13.6, passed the
complete rerun: **1,049 tests in 103 suites, zero failures, errors or skips**.
This includes the two previously corrected menu-reference regressions, 109 shared
resolver cases and 528 qualification cases for 106 source bounds. The local
production-JAR API scan found zero issues across 273 Java 11 classes. These are
local checks, not official Hub approval or a percentage claim for attribution.

The follow-up developer-guidance review checked the current official restricted
API rules, production behaviour, storage, networking, lifecycle and packaging.
Nineteen production files received parser-verified formatting cleanup. Telemetry
changes were limited to nine labels/tooltips and two comments, accurately
describing client tick intervals, sampled ping and failed ping attempts. Capture
and attribution logic were preserved. The complete suite and current-rule API
scan passed again after cleanup; no known blocking issue was found.

Plugin-only JAR SHA-256:
`ee5b1cacc80a9c2565a2033985a6887fd4cf5ee3761775f5211e3b437a94630d`.
Bundled resolver SHA-256:
`f43591a2acc45d726a58fcd9c7d8e170ab2fd5e30e015116d9707c2df7dc2769`.

The retained test log contained 66,686 recorded-tick messages and no plugin
errors. Capture-time warnings peaked at 205 ms; two exceeded 100 ms, none
exceeded 600 ms. This is evidence from one test session, not a general guarantee
against client pauses. Detailed logs and receipts remain outside this repository.

See [release notes](release-notes.md) and the
[submission draft](plugin-hub-submission.md). No remote push or Hub submission
has been performed. Entries below are historical checkpoints.

## Resolver v1 local candidate - 10 November 2026

The storage worker now applies the generic resolver to frozen recorded hits before
compaction and serialization. Its bundled data contains 570 ordinary profiles and
445 component rules. The rule resource SHA-256 is
`db755dbe898ce144a032694ec156e326011bae0f96abbe368e75d9a2c3452599`.
New source resolutions identify this exact resource; earlier catalog references
remain in their evidence.

The matcher supports actor activity, projectiles, area effects, contact, status
hits, interactions, retaliation and lifecycle events. Missing facts stay unknown.
Known contradictions can rule out their matching component. Verified signatures,
geometry and limits require the applicable original facts; an ordinary maximum
is not a special-attack or whole-actor maximum. Formulas use
original observed inputs, never reconstructed pre-hit HP. Reference-only limits
and timing remain references. Shared observations are not consumed or converted
into extra hits.

Original callbacks, amounts, hit types, actor identity, positions, prayers and
NPC-received ownership remain intact. Existing live capture and inference remain.
Death and saved-record journals receive the derived source evidence on the worker;
raw killer confidence and mixed-hit selection stay separate from possible names.
Idle/reset callbacks cannot revive an excluded attack. Original proximity clues
can restrict an ordinary outcome type but cannot acquire numeric privileges.
Retaliation uses a single actual locally owned outgoing hit, with a declared
trigger-target relationship for a separate recorded source. Either packet order
within the recorded tick remains an association; later source facts gain no cap.

The focused checks include 45 cross-language cases and actual animation/hit
handlers retaining a compatible Snakeling hit while excluding an over-limit hit
and an incompatible typed outcome. Independent replay of three original saved
recordings preserved all 146 damage rows, including 86 incoming rows. The reported
red-zero/cloud and venom/minion contradictions both pass. These checks establish
the shared recorded-evidence paths, not certification of every encounter or every
conditional component. The pinned RuneLite 1.13.6 offline build passes all
**1,025 checks in 103 suites**, with no failures, errors or skips. Both `jar` and
`shadowJar` completed successfully. The built source tree hash is
`7caa8677d0b49d3765ab8c1881c99ad4a63543de827745bf1fcaf60b5cb4199b`.

Artifacts in `build/libs/`:

- `tick-replay-logger-1.0.0.jar` SHA-256:
  `6e130094e796bb27edf9036e404bf71a66e0afb9e4691259bf7a50eb174de3f7`.
- `tick-replay-logger-1.0.0-all.jar` SHA-256:
  `29cfe9ecf95e7b0b0794f0ebed31edf498dfaa6079e0153d4ea70b7a51d0faa2`.

No client was launched. All work remains local, without future attack guidance,
coaching or automation.

---

## Latest validated local build ? 9 November 2026

Branch: `fix/dataset-resolver-20261109`. The pinned RuneLite 1.13.6 build passes
**934 checks**, with no failures, errors or skips. Plugin-only JAR SHA-256:
`346427ce33f539ae2a9b27c990cfd180cffbb10535ff79942e0c1755fc67b331`.
Catalog: `71b131fc3e49d8195faa11b672ce8959746728e7f7b3f19d7e4a1ce314b89e9e`.

Shared fixes preserve the original projectile source across native views and
form changes, retain pre-hit NPC activity when the NPC is idle by the settled
sample, and capture actual API occupied bounds. Source-wide damage ceilings use
the original callback facts; missing or different earlier models remain separate
possibilities. Later NPC levels or definitions cannot fill earlier gaps. Ordinary
attack limits do not become limits on unrelated contact or area components.

Source-specific facts stay in bundled data. Current declarations include 68 NPC
guards (60 source-wide direct-HP ceilings), the original 3,155 reference rules,
34 object definitions, 18 single-tile contact rules, four graphic hint groups,
15 occupied-body hint groups, 20 item-operation ceilings, two system-response
hints and two projectile endpoint hints. These overlapping counts are not a
count of supported encounters. The three outcome signatures apply only to their
qualified mechanisms. Unobserved activation, execution time and cause stay
uncertain; weak hints retain an unidentified alternative.

The local restricted-API preflight found zero flagged calls across 261 plugin
classes. All 265 plugin entries match the runnable package byte for byte. This
is a local check, not official approval. Current immutable packages and receipts
are under `C:/Dev/tick-replay-test/whole-dataset-audit-20261109/` in
`release-candidate-callback-scope-final/`. The source/component binding audit
still has explicit unresolved entries. Do not claim all-game certification or
release readiness from passing checks.

Keep the approved Live View, snapshots and manual exports. Reconstruction stays
retrospective: no coaching, future attack guidance or automation. All changes
are local. No client was launched or restarted, and the existing launch helper
was not changed.

---

## Previous validated local checkpoint — 9 November 2026

Branch: `fix/dataset-resolver-20261109`. The pinned RuneLite 1.13.6 build passes
827 checks with no failures or skips. The plugin-only JAR is
`59a4c75ea6d2b8d169c47dec62fb3b67a2c93b382f5379814a9b2176a9bf0584`.
Its catalog is `0b9c63c7d237ccf9b4a293c3b38a412c8a9ec11d62656587c9cd7a18585b6fbf`.

This checkpoint preserves graphic lifetimes, original projectile/player source
identities, callback scope and conditional contact evidence. Ten qualified
numeric guards are separate from ordinary reference profiles; a profile cannot
exclude an actor without the applicable attack and modifier observations.

Potential HP/stat/resource hits now keep their original callback number and
derived source evidence. They remain outside confirmed HP damage totals. Journal
notes link to their original callback, without inventing a second impact.
Current-tick enrichment avoids copying the full observation history.

The area/contact-history evaluators are implemented, but their data bindings are
not complete: there are 17 active single-tile spatial rules and no activated
actor-area or historical-delay rules. Do not describe this as support for every
mechanic or a completed release audit. Preserve raw facts when a rule or required
observation is unavailable.

No client was started or restarted. The launcher still points to the previous
validated runnable archive; this thin JAR does not replace that archive.

---

## In progress — dataset-wide resolution

Work branch: `fix/dataset-resolver-20261109`. Previous committed versions are on
`backup/pre-dataset-resolver-20261109`. All work is local and unpublished.

This branch is not yet a completed replacement test build. Shared graphics contact
evidence, native scope handling and data-defined spatial/lifetime matching are
being corrected. Canonical declarations must distinguish active, conditional and
resource surfaces and preserve competing sources. A reference name or maximum is
not evidence that a complete mechanic is supported.

Keep Live View, snapshots and manual exports. Preserve observed facts separately
from reconstruction. Do not expose future attack guidance, positioning/prayer
advice, player-mistake coaching or automation. No client was launched or restarted
for this work; the external helper still selects the previous validated archive.

---

## Current correction - hitsplat eligibility

Work branch: `fix/hitsplat-eligibility-20261108`.
Validated source: `d57695177f479cd6fa976a6da18848750ec6f396`.

Known observed outcome types now eliminate incompatible source mechanisms before
numeric bounds or source consensus. The generic code reads source-qualified
signatures and cue bindings from the compiled reference data. Unknown facts stay
unknown. No named boss or NPC implementation branches were added.

The reported live tick2016 was replayed from its original saved facts: blocked
zero type12 resolves to the attack source; venom type5 resolves to the contact
source. Exclusions preserve the rule and observed type. A numeric fallback cannot
revive an outcome-type contradiction. Original files and observed values remain
unchanged. This was offline reanalysis, not a fresh native capture.

The clean pinned build passes **712 checks**. All **223 packaged plugin entries**
match. The local API preflight checks **219 classes**, with **zero findings**.

Current artifacts:
`C:/Dev/tick-replay-test/hitsplat-eligibility-20261108/release-candidate/`.

- `plugin/tick-replay-logger.jar`: `effd851c525cd2a0d94dbc31e3bde6cd6e6474e52a7c49419ce55c7978b61c2d`.
- `runtime/TickReplay-test.jar`: `b98efef222905d2c10bba2a6c15dd6e54d139093d09a763246f7114ecadbd185`.
- Capture source: `f1f0486ba2ab1bd14f5f6cddf5e2fff7602b68dcbd15e6d124f0cf344b767291`.
- Catalog: `45d8b2c537d38d411eec199973c6c5c8cf35a1a58856e78f7ac7be590b3bb5bc`.

The previous archive remains intact. The external launch helper points to this
new archive; it was not executed during the correction. A restart requires an
explicit owner instruction and verification of the actual target process.

## Previous local candidate - 8 November 2026

The generic source changes are ready for fresh capture testing on
`feature/generic-evidence-20261108`. The implementation was built from
`34cd4cdd482175a5112cdd3d129bfa86cdafe1cc`. Nothing has been pushed or submitted.

The previous system remains on `backup/pre-generic-evidence-20261108-0036`.
Verified local bundles and their manifest are under
`C:/Dev/backups/generic-evidence-20261108-0036/`.

## What changed

- One retrospective resolver combines recorded projectiles, actor activity,
  status and environmental contacts. Actor or family agreement can identify
  a source even when the exact attack is unclear.
- Source names stay plain. Competing names remain alternatives; relevant missing
  observations stay explicit. An unknown attack does not erase a known actor.
- Typed numeric references use observed variant, attack and context conditions.
  Missing conditions keep the reference from excluding a source. A last strong
  API-linked source survives a conflicting reference as a possibility.
- Structured proximity identity remains separate from the API's source actor.
  The first observed proximity sample survives rolling-window trimming; later
  changes or recovered reads cannot replace an original null or failure.
  Original scope, in-flight form changes, later source-history changes and
  independently failed reads remain distinguishable.
- Named projectile witnesses survive a missing observation ID. Inputs without
  an observed identity stay separate, rather than merging by a missing key.
- Context, mode and item interpretation use declarative bindings rather than
  named encounter or item branches. Raw amounts, hitsplat types, source fields,
  recipient ownership and observations remain intact.

The schema remains **1.3**. Optional `sourceResolution` fields describe derived
sources; see [the export contract](export-schema-v1.3.md). Live View, snapshots,
manual exports, compact transport and background storage remain available.

## Validated artifacts

The clean RuneLite **1.13.6** build passed **700 checks**, with no failures,
errors or skipped checks. All **220 plugin entries** match between the two
archives. The distribution excludes client and test-launcher classes. The local
bytecode preflight checked **216 classes** with **zero forbidden API findings**.

Artifacts are under
`C:/Dev/tick-replay-test/generic-evidence-20261108/release-candidate/`:

- Plugin: `plugin/tick-replay-logger.jar`.
  SHA256 `34c1546d02cb27e6a0782cb47e18d8e06ec0b845354e9255a457e22d49705762`.
- Test archive: `runtime/TickReplay-test.jar`.
  SHA256 `e571e35ee4a4521731c077adcea0a356c812b98f6c2d4df572ab3656be5b7ad8`.
- Capture source hash: `c6e4dfa06deb6ecebf4a7c08514a520b34d9635c67f2e4160b7b24bda0f57ab5`.
- Exact receipts: `summary.json` and `artifact-check.json`.

The source formatting receipts verify AST equality and formatter idempotence.
No named encounter predicates remain in the production interpretation code;
content identities and qualified facts belong in the bundled data.

## Performance and evidence

The resolver runs only for received-hit batches and reads finished DTOs. It does
not scan native scenes, serialize JSON or write files. Its typed reference index
is prepared while the catalog loads, rather than on the first combat callback.
Existing detached export, serial background storage, atomic writes, strict text
recovery and cached UI labels are preserved.

The existing saved death and live datasets were replayed offline. Their original
facts stayed unchanged. Actor consensus, variant bounds, simultaneous source
alternatives, cloud contacts and family-only source identity were checked.
These are offline checks, not a claim that this build captured new live gameplay
or that every hidden server cause can be known.

## RuneLite rules and operating limits

Keep this a factual recording tool. Preserve supporting internal reconstruction
fields, but do not add gameplay automation, future attack instructions, prayer
or position advice, or assessments of player mistakes. The approved Live View
is preserved; no new delay was added.

The local checks used the published
[RuneLite restrictions](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features),
[Hub review expectations](https://github.com/runelite/runelite/wiki/Plugin-Hub-Review)
and [Jagex guidelines](https://secure.runescape.com/m=news/third-party-client-guidelines?oldschool=1).
They are not official Hub CI or maintainer approval.

This work did not launch or restart a game client. The external launch helper points to this
validated archive for a later explicit owner instruction. Verify the actual
process and selected character before any later restart. Do not infer that an
older client is running or stopped.

Keep work local until publication is authorized. Limited owner/friend feedback
on fresh captures is the next practical check; video and exhaustive owner
encounter testing are not requirements.
