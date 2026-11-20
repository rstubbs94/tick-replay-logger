/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.Gson;
import com.tickreplaylogger.model.*;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AtomicCaptureWindowTest
{
    private static final class CountingRecorder extends WorldSnapshotRecorder
    {
        int completions;

        CountingRecorder(int capacity)
        {
            super(capacity, new Gson());
        }

        @Override
        public DeathRecordingV2 completeExport(DeathRecordingV2 frozen)
        {
            completions++;
            return super.completeExport(frozen);
        }
    }

    private static void hit(WorldSnapshotRecorder recorder, int tick, long sequence, int amount)
    {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("amount", amount);
        fields.put("hitsplatType", 16);
        fields.put("actorState", Collections.singletonMap("actorKind", "LOCAL_PLAYER"));
        recorder.addObservation(ObservedEvent.builder().tick(tick).gameCycle(tick * 30).sequence(sequence)
            .type("HITSPLAT").actorRef("player").data(Collections.unmodifiableMap(fields)).build());
        recorder.addDamage(DamageRecord.builder().impactTick(tick).observedCycle(tick * 30).observationSequence(sequence)
            .amount(amount).hitsplatType(16).hitsplatTypeKnown(true).direction("RECEIVED").defenderRef("player")
            .attackerName("Unknown").damageType("unknown").attribution("UNKNOWN").determination("UNKNOWN").build());
    }

    @Test
    public void displayAndCompletionKeepOneWindowAcrossLateReceiptsEvictionAndRevival()
    {
        CountingRecorder recorder = new CountingRecorder(1);
        recorder.startNewTick();
        recorder.updateState(20, 99, 10, 99, Collections.emptyList());
        hit(recorder, 1, 11, 7);
        recorder.finalizeTick();

        TickRecording.CapturedWindow paused = recorder.captureLiveWindow(DeathRecordingV2.Meta.builder().build());
        assertEquals(0, recorder.completions);
        assertEquals(1, paused.getTicks().size());
        assertEquals(1, paused.getTicks().get(0).getTickNumber());
        assertEquals(7, paused.getTicks().get(0).getDamageEvents().get(0).getAmount());
        assertNull(paused.getTicks().get(0).getDamageEvents().get(0).getSourceResolution());

        // A later callback for the same recorded tick must not enter the already selected window.
        hit(recorder, 1, 12, 9);
        recorder.startNewTick();
        hit(recorder, 2, 13, 3);
        recorder.addObservation(ObservedEvent.builder().tick(2).gameCycle(60).sequence(14)
            .type("ACTOR_DEATH").actorRef("player").data(Collections.emptyMap()).build());
        recorder.addDeath(DeathRecordingV2.Events.DeathMark.builder().tick(2).sequence(14L).actorRef("player").build());
        recorder.updateState(99, 99, 99, 99, Collections.emptyList());
        recorder.finalizeTick();
        assertEquals(2, recorder.getTickHistory().get(0).getTickNumber());

        DeathRecordingV2 completed = paused.getCompletion().get();
        assertEquals(1, recorder.completions);
        assertEquals(Integer.valueOf(1), completed.getMeta().getSnapshotTick());
        assertNull(completed.getMeta().getDeathTick());
        assertEquals(1, completed.getJournalTicks().size());
        assertEquals(7, completed.getJournalTicks().get(0).getDamageEvents().get(0).getAmount());
        assertEquals(1, completed.getEvents().getDamageEvents().size());
        assertEquals(Long.valueOf(11), completed.getEvents().getDamageEvents().get(0).getObservationSequence());
        assertEquals(1, completed.getEvents().getObservations().size());
        assertEquals(11, completed.getEvents().getObservations().get(0).getSequence());
        assertEquals(7, completed.getSummary().getTotalDamageTakenInBuffer());
        assertNull(paused.getTicks().get(0).getDamageEvents().get(0).getSourceResolution());

        TickRecording.CapturedWindow revived = recorder.captureLiveWindow(DeathRecordingV2.Meta.builder().build());
        DeathRecordingV2 revivedCompleted = revived.getCompletion().get();
        assertEquals(Integer.valueOf(2), revivedCompleted.getMeta().getSnapshotTick());
        assertNull("Historical death evidence does not turn a manual snapshot into a death capture", revivedCompleted.getMeta().getDeathTick());
        assertEquals(1, revivedCompleted.getEvents().getDeaths().size());
        TickRecording saved = TickRecording.fromPrepared(revivedCompleted, 1L, "Live Snapshot", false);
        assertEquals(99, saved.getFinalHp());
        assertEquals(Boolean.TRUE, saved.getFinalHpKnown());
        assertEquals(3, revivedCompleted.getEvents().getDamageEvents().get(0).getAmount());
    }

    @Test
    public void anUnfinishedBoundaryDoesNotInventACompletedLiveRow()
    {
        CountingRecorder recorder = new CountingRecorder(2);
        recorder.startNewTick();
        hit(recorder, 1, 1, 5);
        recorder.addSnapshot(WorldSnapshot.builder().tick(1).build());
        TickRecording.CapturedWindow captured = recorder.captureLiveWindow(DeathRecordingV2.Meta.builder().build());
        assertTrue(captured.getTicks().isEmpty());
        assertNull(captured.getCompletion().get());
        assertEquals(0, recorder.completions);
        recorder.finalizeTick();
        assertEquals(1, recorder.captureLiveWindow(DeathRecordingV2.Meta.builder().build()).getTicks().size());
    }
}
