# Direct observation resolver: review-size implementation contract

Status: implementation work, not ready for submission.

The direct-path checkpoints reduced the targeted failures from 91 of 530 to
39 of 541 checks. Checkpoint 15 compiled on Java 11/RuneLite 1.13.7 and retained
stable source inputs; it did not pass. The last measured production size was
221,239 review tokens before the latest UI consolidation and final formatting.
Correctness, the 200,000-token limit, full regression and standard packaging
are still required. The public submission has not been updated.

The readable consolidation checkpoint is preserved locally at
`backup/typed-consolidation-10b-20261119` (`cecbacb6187ebc408e4affefbefcc6d86f0fbead`).
Work continues on `refactor/direct-observation-resolver-20261119` in the same worktree.
The public candidate and Hub PR remain unchanged.

## Why this changes the implementation approach

Formatting and measurement establish 252,885 production review tokens. About
178,859 belong to capture, storage, UI and other retained responsibilities;
74,026 belong to the resolver, adapter, proof, lookup and presentation path.
The 200,000 limit leaves approximately 21,141 for that entire replacement path.
Moving functions between classes does not meet this budget.

The target is at most 20,000 readable tokens for the replacement path, with a
working target of 19,000. Existing non-resolver functionality stays intact.
Do not minify code, hide application algorithms in resources, drop observations,
or move active runtime work into tests or precompiled dependencies.

## One runtime path

`EvidenceWindow -> raw receipt/source views -> one rule/effect fold -> grouped result -> UI/export`

The runtime consumes the recorded window directly. It does not build a second
normalized JSON document and then reconstruct the same source facts from it.
Normalized fixture adapters and their JSON renderers belong under `src/test`.

A raw source view references its original captured actor, projectile, surface,
item or status payload and its own receipt. It must keep the actual source
receipt separate from a related HP, input, response or outgoing-hit receipt.
Serialization of a detached DTO may be cached once where useful; derived aliases
must not create a parallel evidence representation.

The same finite fact interface serves matching, qualifications and annotations.
Schema field-path bindings are allowed; native-scope, identity, clock, confidence
and reconciliation algorithms remain shared Java code. Encounter identities,
mechanic selectors, signatures, geometry parameters and qualified limits remain
reviewed data.

## Responsibility budgets

The figures below are design targets, not achieved sizes. The current formatted
implementation is still **222,270 production tokens**, above the review limit
by **22,270**. The existing public submission has not been updated.

| Responsibility | Working budget |
| --- | ---: |
| Raw-window indexes and receipt/source access | 5,500 |
| Shared identity, native, clock and geometry proofs | 4,000 |
| Rule selection, three-state predicates and one effect fold | 6,000 |
| Grouped presentation, annotations and small DTOs | 2,500 |
| Runtime catalog/index loading | 1,000 |
| Total | 19,000 |

These are measured code budgets, not permission to omit behavior. Check the
actual implementation against them before expanding another parallel layer.

## Preservation requirements

- Preserve all original capture channels, amounts, zeroes, types, recipients,
  raw witnesses, ordering, actor generations and nullable availability facts.
- Preserve Live View, saved/death snapshots, replay, telemetry and schema 1.3
  export meanings, background processing and save recovery.
- Preserve generic handling of content without a reference rule. Actual source
  observations remain useful; unresolved damage keeps alternatives or Unknown.
- Use original source ownership, native context and clocks. A related HP receipt
  cannot supply missing source metadata. Later samples cannot rewrite an earlier
  hit. Known foreign context differs from unknown context.
- Keep source identity, component/attack identity, numerical exclusion and scalar
  confidence separate. A weak candidate cannot inherit a stronger row's bounds.
- Apply kind/ID/name/level selection consistently to every global lookup.
- Retain complete-signature outcome exclusions, grouped alternative versus
  independent bounds, conditional surfaces and original physical identities.
- Reconcile shared projectiles across actual HP peers without greedy consumption.
- One grouped result supplies names, alternatives, confidence and annotation
  consensus. Diagnostic changes must preserve the underlying recorded facts.

## Validation and completion

The complete regression run now passes **1,189 tests** on Java 11 and RuneLite
1.13.7. Removing an unused test constructor from production then passed all
104 affected checks. The actual standard-built JAR also passes the local
restricted-API scan. These checks do not replace the Hub's review, and the size
gate still prevents resubmission.

The backup checkpoint compiled on Java 11/RuneLite 1.13.7. Its targeted run had
513 tests and 26 failures; it is a diagnostic reference, not a passed release.
Its sealed artifacts and failing cases remain outside the source repository.

Use the existing raw-capture integration checks, qualification/conformance cases,
projection checks and retained recordings to verify the replacement. Do not
weaken expected source, type, confidence, cardinality or evidence requirements
to meet the budget. Preserve reference implementations only as test oracles.

Completion requires the full regression suite, actual readable review size below
200,000, a fresh standard-built JAR, restricted-API checks, retained-recording
raw-fact comparisons and clean public documentation. Update the existing Hub PR
only after those gates pass. No client launch is part of this work.
