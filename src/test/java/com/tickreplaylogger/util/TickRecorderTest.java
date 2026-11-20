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
package com.tickreplaylogger.util;

import com.google.gson.Gson;
import com.tickreplaylogger.capture.WorldSnapshotRecorder;
import com.tickreplaylogger.model.*;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class TickRecorderTest
{
    @Test
    public void persistedInvalidCapacityValuesCannotAllocateUnboundedBuffersOrDivideByZero()
    {
        TickRecorder recorder = new TickRecorder(Integer.MAX_VALUE); recorder.resize(0);
        for (int i = 0; i < 3; i++) { recorder.startNewTick(); recorder.finalizeTick(); }
        assertEquals(1, recorder.getTickHistory().size()); assertEquals(3, recorder.getTickHistory().get(0).getTickNumber());
    }

    private static WorldSnapshotRecorder recorder() { return new WorldSnapshotRecorder(10, new Gson()); }

    private static void action(WorldSnapshotRecorder recorder, int tick, String item)
    {
        recorder.addPlayerAction(DeathRecordingV2.Events.PlayerActionRecord.builder().tick(tick).type("EAT_FOOD")
            .detail("Ate " + item).itemName(item).build());
    }

    private static void hit(WorldSnapshotRecorder recorder, int tick, long sequence, int amount, String type)
    {
        recorder.addDamage(DamageRecord.builder().impactTick(tick).observationSequence(sequence).direction("RECEIVED")
            .defenderRef("player").amount(amount).damageType(type).build());
    }

    private static void effect(WorldSnapshotRecorder recorder, int tick, String note)
    {
        recorder.addEffect(EffectEvent.builder().startTick(tick).journalNote(note).build());
    }

    private static void resource(WorldSnapshotRecorder recorder, int tick, long sequence)
    {
        Map<String, Object> raw = new LinkedHashMap<>(); raw.put("amount", 3); raw.put("hitsplatType", 16);
        raw.put("possibleOutcomeSurfaces", Arrays.asList("HP", "SKILL"));
        recorder.addObservation(ObservedEvent.builder().tick(tick).sequence(sequence).type("HITSPLAT").actorRef("player").data(raw).build());
        recorder.addDamage(DamageRecord.builder().impactTick(tick).observationSequence(sequence).amount(3).hitsplatType(16)
            .direction("RESOURCE_IMPACT").defenderRef("player").build());
    }

    @Test
    public void callbacksBeforeFirstTickAreIncludedAndLaterCallbacksCannotMutateHistory()
    {
        WorldSnapshotRecorder recorder = recorder();
        action(recorder, 1, "Shark"); hit(recorder, 1, 1, 5, "poison"); recorder.startNewTick();
        effect(recorder, 1, "Poisoned"); recorder.finalizeTick(); TickData first = recorder.getTickHistory().get(0);
        action(recorder, 2, "Manta ray"); hit(recorder, 2, 2, 7, "unknown");
        assertEquals(1, first.getPlayerActions().size()); assertEquals(1, first.getDamageEvents().size());
        recorder.startNewTick(); recorder.finalizeTick();
        assertEquals(7, recorder.getTickHistory().get(1).getDamageEvents().get(0).getAmount());
        assertEquals("Manta ray", recorder.getTickHistory().get(1).getPlayerActions().get(0).getItemName());
        assertEquals("Poisoned", first.getEffectNotes().get(0));
        assertEquals(DamageEvent.DamageType.POISON, first.getDamageEvents().get(0).getDamageType());
        assertEquals("Unknown", first.getDamageEvents().get(0).getSource());
    }

    @Test
    public void resetDropsOriginalEventsWaitingForNextTick()
    {
        WorldSnapshotRecorder recorder = recorder(); action(recorder, 1, "Shark"); hit(recorder, 1, 1, 5, "poison");
        effect(recorder, 1, "Poisoned"); recorder.resetTickCounter(); recorder.startNewTick(); recorder.finalizeTick();
        TickData first = recorder.getTickHistory().get(0); assertEquals(1, first.getTickNumber());
        assertTrue(first.getPlayerActions().isEmpty()); assertTrue(first.getDamageEvents().isEmpty()); assertTrue(first.getEffectNotes().isEmpty());
        assertTrue(recorder.freezeExport(null, null, Integer.MIN_VALUE, Integer.MAX_VALUE).getEvents().getObservations().isEmpty());
    }

    @Test
    public void resourceMirrorLinksUseOriginalIdsAndCannotJoinAnIdenticallyNamedOrdinaryNote()
    {
        WorldSnapshotRecorder recorder = recorder();
        effect(recorder, 1, "Impact 3 (HP/stat) from Unknown"); resource(recorder, 1, 71);
        recorder.startNewTick(); effect(recorder, 1, "Identical display text is not proof of a mirror"); resource(recorder, 1, 72);
        recorder.finalizeTick(); TickData first = recorder.getTickHistory().get(0);
        assertEquals(Long.valueOf(71), first.getEffectNoteSourceSequences().get(2));
        assertEquals(Long.valueOf(72), first.getEffectNoteSourceSequences().get(3));
        assertFalse(first.getEffectNoteSourceSequences().containsKey(0)); assertFalse(first.getEffectNoteSourceSequences().containsKey(1));
        resource(recorder, 2, 73); recorder.startNewTick(); recorder.finalizeTick();
        assertEquals(2, first.getEffectNoteSourceSequences().size());
        assertEquals(Long.valueOf(73), recorder.getTickHistory().get(1).getEffectNoteSourceSequences().get(0));
        assertTrue(first.getDamageEvents().isEmpty());
    }

    @Test
    public void rawFrameAndWorldChannelsShareOneRetentionBoundary()
    {
        WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(2, new Gson());
        for (int tick = 1; tick <= 3; tick++)
        {
            hit(recorder, tick, tick, tick, "unknown"); recorder.startNewTick();
            if (tick != 2) recorder.addSnapshot(WorldSnapshot.builder().tick(tick).build());
            recorder.finalizeTick();
        }
        DeathRecordingV2 frozen = recorder.freezeExport(null, null, Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertEquals(2, frozen.getJournalTicks().size()); assertEquals(2, frozen.getJournalTicks().get(0).getTickNumber());
        assertEquals(1, frozen.getTicks().size()); assertEquals(3, frozen.getTicks().get(0).getTick());
        assertEquals(2, frozen.getEvents().getDamageEvents().size()); assertEquals(2, frozen.getEvents().getDamageEvents().get(0).getImpactTick());
        recorder.clear(); assertEquals(2, frozen.getJournalTicks().size()); assertEquals(1, frozen.getTicks().size());
    }
}
