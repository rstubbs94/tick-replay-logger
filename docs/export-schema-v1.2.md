# Replay export 1.2 - shared complete NPC snapshots

This revision changes the wire representation of repeated NPC observations. All other fields
retain the [1.1 contract](export-schema-v1.1.md). Ordinary small exports remain 1.1. The current
exporter considers sharing when serialized NPC arrays reach 1 MiB, and emits 1.2 only when the
actual NPC table and references are smaller. This threshold never removes observations.

## Envelope and full-state references

An envelope with `schemaVersion: "1.2"` has one `npcStateTable`, shared by primary ticks and all
additional native-view ticks. Its fields are:

- `format`: exactly `shared-blocks-v1`.
- `blockWidth`: exactly `8`.
- `fields`: distinct ordered property names, between 1 and 128. Reserved prototype names are
  invalid. The producer discovers the complete DTO field order, including initially absent fields.
- `values`: JSON values with their original types, nested arrays/objects and strings preserved.
- `blocks`: arrays of 1 to 8 indexes into `values`. The sentinel `-1` means that field is absent.
- `rows`: arrays of block indexes. Each row has `ceil(fields.length / 8)` entries; each referenced
  block has exactly the length required by its column, including the short final block.

Each encoded native tick replaces `npcs` with `npcStateRefs`, a complete ordered array of row
indexes. The sentinel `-1` in this array represents a null array member. An empty array means an
empty recorded population; an absent channel remains absent. Supplying both arrays is invalid.
Unencoded arrays remain readable for compatibility, but may not be mixed with references on
the same tick. Unknown table formats, indexes, lengths or missing required tables are rejected.

For field index `i`, resolve `rows[rowId][floor(i / 8)]`, then that block's entry `i % 8`.
An entry of `-1` omits the property; otherwise the property is exactly `values[entry]`.
A JSON null in `values` remains an explicit null, distinct from an absent property. The current
DTO export's normal Gson omission policy remains in effect when producing these values.

## Fidelity and boundaries

Every row is a complete snapshot. There is no preceding-state lookup, actor-key delta, lifetime
assumption or field carry. Membership order, repeated entries, field failures/recovery, transforms,
index reuse, gaps, trimmed windows, native areas and planes resolve independently. Sharing equal
JSON values does not assert that two actors are the same or that an event caused damage.

The original rolling-buffer snapshots are not mutated. Capture coverage, journals, callbacks,
damage directions/amounts, other players and scene/world channels retain their existing meaning.
The viewer preserves the original encoded envelope separately from its derived NPC records.

## Admission and compatibility

The viewer accepts legacy journals and schema 1.0, 1.1 and 1.2. Saved normalized recordings are
decoded without replacing observed facts with reference labels.
An older viewer that does not support 1.2 must reject the version instead of displaying no NPCs.

The 64 MiB input, 5,000,000 JSON-node and 20,000,000 logical playback-entry limits remain
unchanged. Table shape/index validation precedes reconstruction. Decoded storage/indexes use the existing JSON-node budget; only present fields are counted.
Before allocation, referenced processing is checked against the playback budget. Inline and
shared NPCs then use the same max(64, nested-value/text work) model during frame expansion,
so ordinary NPC processing is counted once. Native streams share budgets and the decode cache.
Existing actor/world expansion accounting still applies. Wire sharing is not permission for
unbounded expanded state, and logical work is not a peak-heap or every-raid performance claim.

An independent Java decoder checks every NPC field against the original capture-seeded DTOs.
The dense fixture is synthetic, not a measured live raid population. Source/build hashes and
validation evidence are recorded in the research audit; current work is not an all-game certificate.

The actual clipboard/file export helper preserves1.2 and only upgrades known1.0 payloads to1.1.
Unknown future payloads remain unchanged. Plugin replay labels use a lazy complete-row adapter;
current display interpretations remain separate from original capture facts.
