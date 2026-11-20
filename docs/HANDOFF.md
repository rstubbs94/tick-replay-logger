# Development handoff

## Current state - 20 November 2026

The continuous-capture refactor works locally and has passed the checks below.
It is **not ready to resubmit**: readable production source is still above the
maintainer's 200,000-token limit. No new source or PR update has been pushed.
No client launch is part of this refactor.

PR #20986 still points to public source `bf19b988b53d3ec861d732671da35e1e7cdb76aa`.
That submission was measured by the maintainer at 302,079 tokens. The calibrated
local counter measures the same submission at 302,075. The current local source
measures 214,137 tokens in 107 production Java files, with 24,450 nonblank,
comment-stripped code lines. The verified checkpoint before this refactor was
222,270 tokens in 108 production files. Tests, resources and documentation are
excluded from these token counts. A new official count has not run.

This refactor removed 8,133 tokens and 1,037 code lines from the preceding
verified checkpoint. Total reduction from the public submission is 87,938 tokens
(29.1%). Those are separate comparisons; neither passes the remaining size gate.

## Recording flow

1. RuneLite callbacks and settled tick sampling continuously collect facts.
   Actor, projectile, scene, collision, terrain, action, effect and resource
   observations remain available according to capture settings.
2. One bounded frame window owns minimal tick state and optional world samples.
   Original callbacks and their impact contexts share event IDs. Journal rows
   are generated views, rather than another mutable recording store.
3. Live View displays collected facts. It does not run damage attribution.
4. Death and manual capture select a detached window. Live Pause and Save use
   the same atomically selected window, including raw events and scene data;
   later callbacks or eviction cannot change that selection.
5. The storage worker runs the existing data-driven damage resolver, derives
   the journal and fatal summary, prepares the encounter title, applies lossless
   encoding, and saves the result. Current Copy serializes the prepared typed
   payload directly. It does not parse and repair the producer's own JSON.

Actor sampling and terrain/object/item delta encoding share their common paths.
One ordered projectile-retention map replaces the pending list, link map and
processed set while preserving original samples, expiry and late-callback guards.

Sampling and identity/lifetime bookkeeping remain on the client thread because
short-lived observations cannot be recovered later. Source, hitsplat, input and
settled observations retain separate clocks and native scopes. Missing evidence
must stay unknown. Resolver output is never fed back as original evidence.

Deaths remain distinct from immediate revivals. Callback order selects a hit
for inspection, not a proven hidden lethal roll. Omitted observations and later
unclassified HP-capable hits prevent an unsupported killer assignment.

## Data and compatibility

- Runtime mechanics remain in `runtime-mechanics.json`. Java implements generic
  matching; encounter facts and declarations remain data.
- All 4,650 resolver declarations and retained factual sections are unchanged
  by the packaging cleanup. Historical authoring tables remain under tests;
  the shipped catalog shrank from 28,934,398 to 22,998,788 bytes.
- Preserve old plain captures from the published 1.0.0 plugin. They still load,
  replay, copy and save. Intermediate development exports are best effort.
- Current captures resolve raw data before compression. There is no production
  decoder for reinterpreting already-compressed development captures. The test
  decoder remains to verify encoder losslessness.
- Current journal order follows original callback IDs. Old development journals
  sometimes ordered received/dealt hits differently; those legacy mirrors are
  not an authoritative source for current reconstruction.

## Validation and remaining release gate

- Java 11 / RuneLite 1.13.7: final full suite, 1,200 checks, zero failures.
- Official RuneLite standard build: passed. Thin JAR API scan: zero findings;
  no test or RuneLite core classes packaged.
- Official formatter: syntax-tree equivalence and idempotence checks passed.
- Published-version compatibility: independent fixture harness passed 1,255
  assertions, including old actions/damage types, replay, Copy and save/reopen.
- Three retained recordings: all 146 damage records, their resolved source
  results, original callbacks, world frames and scene facts match the earlier
  verified resolver output. Rebuilt journal rows have exact callback IDs and
  amounts; whole-output legacy-journal differences remain documented.
- Independent review found and verified fixes for quadratic tile lookup and
  the non-atomic initial Live selection. Mutable map/object/item values remain
  sampled; structural scene membership is reused and lifecycle-invalidated.

The remaining publication blocker is source size: at least 14,138 locally
measured tokens must be removed to get strictly below 200,000, with headroom
preferred. Do not claim this gate is passed. Small getter and extra source-wrapper
prototypes were measured and rejected because they did not provide useful savings.

Keep standalone features and source facts. No gameplay automation, forecasts,
prayer/position advice, automatic upload or player-mistake coaching. Continue to
use RuneLite's standard build and allowed APIs. Maintainer approval remains a
separate gate from local validation. Launching a client requires an explicit
owner instruction, and its process/account must be verified when requested.
