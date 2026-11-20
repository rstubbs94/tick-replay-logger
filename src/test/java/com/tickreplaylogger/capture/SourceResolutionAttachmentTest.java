/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.tickreplaylogger.capture;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.*;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Completed outcomes annotate frozen views only; the raw rolling capture has no result-application API. */
public class SourceResolutionAttachmentTest
{
    private static final Gson GSON = new Gson();

    private static SourceResolution resolution()
    {
        return SourceResolution.builder().version(1).status("IDENTIFIED").identityLevel("ACTOR")
            .candidates(Collections.singletonList(SourceResolution.Candidate.builder().key("actor:unfamiliar")
                .name("Unfamiliar entity").actorRef("npc:unfamiliar:v0:g1").strength("STRONG")
                .evidence(Collections.singletonList("RECORDED_RELATIONSHIP")).build()))
            .unidentifiedPossible(false).build();
    }

    private static DamageRecord hit(int tick, long sequence, int amount, String direction)
    {
        return DamageRecord.builder().impactTick(tick).observationSequence(sequence).observedCycle(tick * 30)
            .defenderRef("player").direction(direction).amount(amount).hitsplatType(16).hitsplatTypeKnown(true)
            .damageType("unknown").attackerName("Unknown").attribution("UNKNOWN").determination("UNKNOWN").build();
    }

    private static DamageRecord projected(DamageRecord original)
    {
        return original.toBuilder().damageType("magic").attackerName("Unfamiliar entity").attribution("TIMED")
            .determination("IMPACT").sourceResolution(resolution()).build();
    }

    private static WorldSnapshotRecorder recorder()
    {
        return new WorldSnapshotRecorder(5, GSON);
    }

    private static void resource(WorldSnapshotRecorder recorder, DamageRecord context)
    {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("amount", context.getAmount()); raw.put("hitsplatType", context.getHitsplatType());
        raw.put("possibleOutcomeSurfaces", Arrays.asList("HP", "SKILL"));
        recorder.addObservation(ObservedEvent.builder().tick(context.getImpactTick()).sequence(context.getObservationSequence())
            .gameCycle(context.getObservedCycle()).type("HITSPLAT").actorRef("player").data(raw).build());
        recorder.addDamage(context);
    }

    @Test
    public void projectedJournalResultsStayWithTheirOriginalTicksAndDoNotInventResourceHpHits()
    {
        WorldSnapshotRecorder recorder = recorder();
        recorder.startNewTick();
        DamageRecord hp = hit(1, 11, 7, "RECEIVED");
        DamageRecord dealt = hit(1, 12, 3, "DEALT").toBuilder().defenderRef("npc:target").defenderName("Target").build();
        DamageRecord resource = hit(1, 21, 5, "RESOURCE_IMPACT");
        recorder.addDamage(hp); recorder.addDamage(dealt); resource(recorder, resource); recorder.finalizeTick();
        List<TickData> paused = recorder.getTickHistory();
        DeathRecordingV2 frozen = recorder.freezeExport(null, null, 1, 1);
        recorder.startNewTick();
        recorder.addEffect(EffectEvent.builder().startTick(2).journalNote("Current tick must remain separate").build());
        Map<Long, DamageRecord> results = new LinkedHashMap<>();
        results.put(11L, projected(hp)); results.put(21L, projected(resource));
        DeathRecordingV2 completed = RecordingProjection.apply(frozen, results, "test");
        TickData resolved = completed.getJournalTicks().get(0);
        assertEquals(2, resolved.getDamageEvents().size());
        assertEquals(7, resolved.getDamageEvents().get(0).getAmount());
        assertEquals(DamageEvent.DamageType.MAGIC, resolved.getDamageEvents().get(0).getDamageType());
        assertEquals("Unfamiliar entity", resolved.getDamageEvents().get(0).getSource());
        assertEquals("Target", resolved.getDamageEvents().get(1).getSource());
        assertEquals("Impact 5 (HP/stat) from Unfamiliar entity", resolved.getEffectNotes().get(0));
        assertTrue(frozen.getJournalTicks().get(0).getDamageEvents().isEmpty());
        assertTrue(frozen.getJournalTicks().get(0).getEffectNotes().isEmpty());
        assertEquals("Unknown", paused.get(0).getDamageEvents().get(0).getSource());
        assertEquals("Impact 5 (HP/stat) from Unknown", paused.get(0).getEffectNotes().get(0));
        assertEquals("Unknown", recorder.getTickHistory().get(0).getDamageEvents().get(0).getSource());
        assertEquals(1, completed.getJournalTicks().size());
    }

    @Test
    public void resourceOnlyResultUpdatesItsFrozenNoteWithoutAddingHpDamage()
    {
        WorldSnapshotRecorder recorder = recorder(); recorder.startNewTick();
        DamageRecord resource = hit(1, 21, 5, "RESOURCE_IMPACT");
        JsonObject originalResourceFacts = GSON.toJsonTree(resource).getAsJsonObject();
        resource(recorder, resource); recorder.finalizeTick();
        List<TickData> paused = recorder.getTickHistory();
        DeathRecordingV2 frozen = recorder.freezeExport(null, null, 1, 1);
        DeathRecordingV2 completed = RecordingProjection.apply(frozen, Collections.singletonMap(21L, projected(resource)), "test");
        assertTrue(completed.getEvents().getDamageEvents().isEmpty());
        assertTrue(completed.getJournalTicks().get(0).getDamageEvents().isEmpty());
        assertEquals("Impact 5 (HP/stat) from Unfamiliar entity", completed.getJournalTicks().get(0).getEffectNotes().get(0));
        assertTrue(frozen.getJournalTicks().get(0).getEffectNotes().isEmpty());
        assertEquals("Impact 5 (HP/stat) from Unknown", paused.get(0).getEffectNotes().get(0));
        assertEquals("Impact 5 (HP/stat) from Unknown", recorder.getTickHistory().get(0).getEffectNotes().get(0));
        DamageRecord raw = (DamageRecord) frozen.getEvents().getObservations().get(0).getData().get("attributionContext");
        Map<String, Object> wire = frozen.getEvents().getObservations().get(0).getData();
        assertNull(raw.getAttributionCandidates());
        assertNull(raw.getObservedHazardContacts());
        assertEquals(GSON.toJsonTree(resource), GSON.toJsonTree(raw.toBuilder()
            .attributionCandidates(resource.getAttributionCandidates()).observedHazardContacts(resource.getObservedHazardContacts()).build()));
        assertEquals(resource.getAttributionCandidates(), wire.get("attributionCandidates"));
        assertEquals(resource.getObservedHazardContacts(), wire.get("observedHazardContacts"));
        assertNull(raw.getSourceResolution());
        assertNull(resource.getSourceResolution());
        assertEquals(originalResourceFacts, GSON.toJsonTree(resource));
    }

    @Test
    public void frozenProjectionRejectsAnotherTickDirectionAmountOrSequence()
    {
        WorldSnapshotRecorder recorder = recorder(); recorder.startNewTick();
        DamageRecord hp = hit(1, 11, 7, "RECEIVED"); recorder.addDamage(hp); recorder.finalizeTick();
        DeathRecordingV2 frozen = recorder.freezeExport(null, null, 1, 1);
        for (DamageRecord wrong : Arrays.asList(projected(hp).toBuilder().impactTick(2).build(),
            projected(hp).toBuilder().direction("DEALT").build(), projected(hp).toBuilder().amount(8).build(),
            projected(hp).toBuilder().observationSequence(12L).build()))
        {
            DeathRecordingV2 completed = RecordingProjection.apply(frozen, Collections.singletonMap(11L, wrong), "test");
            assertNull(completed.getEvents().getDamageEvents().get(0).getSourceResolution());
            assertEquals("Unknown", completed.getJournalTicks().get(0).getDamageEvents().get(0).getSource());
            assertEquals(7, completed.getJournalTicks().get(0).getDamageEvents().get(0).getAmount());
        }
    }

    @Test
    public void projectedRecordsCannotReplaceAnotherNativeHitOrMutateRawExports()
    {
        WorldSnapshotRecorder recorder = recorder();
        DamageRecord original = hit(2, 11, 7, "RECEIVED").toBuilder().impactContextEpoch(3L).finalizationContextEpoch(4L)
            .impactSceneKey("room-a").impactWorldViewId(0).impactPlane(0).build();
        recorder.addDamage(original);
        DeathRecordingV2 frozen = recorder.freezeExport(null, null, 0, 2);
        DamageRecord projected = original.toBuilder().attackerName("Unfamiliar entity").sourceResolution(resolution()).build();
        for (DamageRecord wrong : Arrays.asList(projected.toBuilder().impactSceneKey("another-room").build(),
            projected.toBuilder().impactWorldViewId(1).build(), projected.toBuilder().impactPlane(1).build(),
            projected.toBuilder().impactContextEpoch(4L).build(), projected.toBuilder().finalizationContextEpoch(5L).build(),
            projected.toBuilder().hitsplatTypeKnown(null).build()))
        {
            assertNull(RecordingProjection.apply(frozen, Collections.singletonMap(11L, wrong), "test")
                .getEvents().getDamageEvents().get(0).getSourceResolution());
        }
        DamageRecord completed = RecordingProjection.apply(frozen, Collections.singletonMap(11L, projected), "test")
            .getEvents().getDamageEvents().get(0);
        assertNotNull(completed.getSourceResolution()); assertEquals(7, completed.getAmount());
        assertNull(frozen.getEvents().getDamageEvents().get(0).getSourceResolution());
        assertNull(recorder.freezeExport(null, null, 0, 2).getEvents().getDamageEvents().get(0).getSourceResolution());
        JsonObject raw = GSON.toJsonTree(original).getAsJsonObject(), annotated = GSON.toJsonTree(completed).getAsJsonObject();
        annotated.remove("sourceResolution"); annotated.add("attackerName", raw.get("attackerName"));
        assertEquals(raw, annotated);
    }

    @Test
    public void frozenMembershipExcludesLaterCallbacksAndSurvivesClear()
    {
        WorldSnapshotRecorder recorder = recorder(); recorder.advanceTick(4);
        ObservedEvent before = ObservedEvent.builder().tick(3).sequence(11).type("HITSPLAT").actorRef("player").build();
        recorder.addObservation(before);
        recorder.addObservation(ObservedEvent.builder().tick(4).sequence(12).type("HITSPLAT").actorRef("player").build());
        recorder.addDamage(hit(3, 11, 5, "RECEIVED"));
        DeathRecordingV2 frozen = recorder.freezeExport(null, null, 3, 3);
        recorder.addObservation(ObservedEvent.builder().tick(3).sequence(13).type("HITSPLAT").actorRef("player").build());
        recorder.clear();
        assertTrue(frozen.getTicks().isEmpty());
        assertEquals(1, frozen.getEvents().getObservations().size());
        assertSame(before, frozen.getEvents().getObservations().get(0));
        assertEquals(1, frozen.getEvents().getDamageEvents().size());
        assertEquals(Long.valueOf(11), frozen.getEvents().getDamageEvents().get(0).getObservationSequence());
    }

    @Test
    public void frozenResultsOnlyAnnotateTheMatchingLinkedReceivedRecord()
    {
        WorldSnapshotRecorder recorder = recorder();
        DamageRecord previous = hit(1, 10, 9, "RECEIVED"), received = hit(2, 11, 0, "RECEIVED");
        DamageRecord dealt = hit(2, 12, 7, "DEALT").toBuilder().defenderRef("npc:target").build();
        DamageRecord unlinked = hit(2, 13, 5, "RECEIVED").toBuilder().observationSequence(null).build();
        for (DamageRecord raw : Arrays.asList(previous, received, dealt, unlinked)) recorder.addDamage(raw);
        recorder.addSnapshot(WorldSnapshot.builder().tick(2).build());
        DeathRecordingV2 frozen = recorder.freezeExport(null, null, 1, 2);
        DeathRecordingV2 completed = RecordingProjection.apply(frozen, Collections.singletonMap(11L, projected(received)), "test");
        List<DamageRecord> results = completed.getEvents().getDamageEvents();
        assertNull(results.get(0).getSourceResolution()); assertNotNull(results.get(1).getSourceResolution());
        assertNull(results.get(2).getSourceResolution()); assertNull(results.get(3).getSourceResolution());
        assertEquals(0, results.get(1).getAmount()); assertEquals(7, results.get(2).getAmount());
        assertTrue(frozen.getEvents().getDamageEvents().stream().allMatch(raw -> raw.getSourceResolution() == null));
        recorder.clear(); assertEquals(1, frozen.getTicks().size()); assertEquals(2, frozen.getTicks().get(0).getTick());
    }

    @Test
    public void pausedJournalCannotBeMutatedOrJoinedToAnOutgoingEvent()
    {
        WorldSnapshotRecorder recorder = recorder(); recorder.startNewTick();
        DamageRecord received = hit(1, 11, 0, "RECEIVED"), dealt = hit(1, 12, 7, "DEALT");
        recorder.addDamage(received); recorder.addDamage(dealt); recorder.finalizeTick();
        List<TickData> paused = recorder.getTickHistory();
        DeathRecordingV2 frozen = recorder.freezeExport(null, null, 1, 1);
        for (DamageRecord wrong : Arrays.asList(projected(received).toBuilder().impactTick(2).build(),
            projected(received).toBuilder().amount(1).build(), projected(received).toBuilder().observationSequence(12L).build()))
        {
            assertNull(RecordingProjection.apply(frozen, Collections.singletonMap(11L, wrong), "test")
                .getJournalTicks().get(0).getDamageEvents().get(0).getSourceResolution());
        }
        List<DamageEvent> complete = RecordingProjection.apply(frozen, Collections.singletonMap(11L, projected(received)), "test")
            .getJournalTicks().get(0).getDamageEvents();
        assertNotNull(complete.get(0).getSourceResolution()); assertNull(complete.get(1).getSourceResolution());
        assertEquals("Unfamiliar entity", complete.get(0).getSource());
        assertNull(paused.get(0).getDamageEvents().get(0).getSourceResolution());
        assertEquals(0, complete.get(0).getAmount()); assertEquals(7, complete.get(1).getAmount());
    }
}
