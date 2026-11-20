/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
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
import com.tickreplaylogger.model.world.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Resource wire aliases are constructed on a frozen view, never by editing the captured callback. */
public class RawObservationEnrichmentTest
{
    private static ObservedEvent event(int tick, long sequence)
    {
        Map<String, Object> data = new LinkedHashMap<>(); data.put("amount", 3); data.put("hitsplatType", 16);
        return ObservedEvent.builder().tick(tick).sequence(sequence).gameCycle(tick * 30).type("HITSPLAT")
            .actorRef("player").actorName("Recorded recipient").relatedActorRef("original-related").sceneKey("scene")
            .data(Collections.unmodifiableMap(data)).build();
    }

    private static DamageRecord context(int tick, long sequence)
    {
        return DamageRecord.builder().impactTick(tick).observedCycle(tick * 30).observationSequence(sequence)
            .amount(3).hitsplatType(16).hitsplatTypeKnown(true).direction("RESOURCE_IMPACT").defenderRef("player")
            .attributionCandidates(Collections.singletonList(Collections.singletonMap("projectileObservationId", 99L)))
            .observedHazardContacts(Collections.singletonList(Collections.singletonMap("graphicObservationId", 7L))).build();
    }

    @SuppressWarnings("unchecked")
    private static void assertOriginalFacts(DamageRecord original, ObservedEvent wire)
    {
        Gson gson = new Gson();
        assertEquals(Collections.singletonList(Collections.singletonMap("projectileObservationId", 99L)), original.getAttributionCandidates());
        assertEquals(Collections.singletonList(Collections.singletonMap("graphicObservationId", 7L)), original.getObservedHazardContacts());
        Object originalFacts = gson.toJsonTree(original);
        Map<String, Object> data = wire.getData();
        DamageRecord nested = (DamageRecord) data.get("attributionContext");
        DamageRecord reconstructed = nested.toBuilder()
            .attributionCandidates((List<Map<String, Object>>) data.get("attributionCandidates"))
            .observedHazardContacts((List<Map<String, Object>>) data.get("observedHazardContacts")).build();
        assertEquals(originalFacts, gson.toJsonTree(reconstructed));
        assertNull(nested.getAttributionCandidates());
        assertNull(nested.getObservedHazardContacts());
        assertEquals(originalFacts, gson.toJsonTree(original));
    }

    @Test
    public void frozenContextLeavesHistoricalCallbacksAndOriginalReceiptUntouched()
    {
        InspectableWorldSnapshotRecorder recorder = new InspectableWorldSnapshotRecorder(100, new Gson());
        ObservedEvent historical = event(1, 1), current = event(2, 2);
        recorder.addObservation(historical); recorder.addObservation(current);
        DamageRecord context = context(2, 2); recorder.addDamage(context);
        List<ObservedEvent> events = recorder.observationsForSourceResolution();
        assertEquals(2, events.size()); assertSame(historical, events.get(0)); assertNotSame(current, events.get(1));
        ObservedEvent frozen = events.get(1);
        assertEquals(current.getSequence(), frozen.getSequence()); assertEquals(current.getTick(), frozen.getTick());
        assertEquals(current.getGameCycle(), frozen.getGameCycle()); assertEquals(current.getActorRef(), frozen.getActorRef());
        assertEquals(current.getActorName(), frozen.getActorName()); assertEquals(current.getRelatedActorRef(), frozen.getRelatedActorRef());
        assertEquals(current.getSceneKey(), frozen.getSceneKey()); assertEquals(3, frozen.getData().get("amount"));
        assertEquals("UNRESOLVED", frozen.getData().get("resourceOutcomeStatus"));
        assertOriginalFacts(context, frozen);
        assertFalse(current.getData().containsKey("resourceOutcomeStatus"));
        assertFalse(current.getData().containsKey("attributionContext"));
        assertOriginalFacts(context, recorder.observationsForSourceResolution().get(1));
        try { frozen.getData().put("amount", 99); fail("Frozen wire metadata must be immutable"); }
        catch (UnsupportedOperationException expected) { }
        assertTrue(recorder.freezeExport(null, null, 1, 2).getEvents().getDamageEvents().isEmpty());
    }

    @Test
    public void unorderedTicksKeepOriginalCaptureOrderAndExactContextIds()
    {
        InspectableWorldSnapshotRecorder recorder = new InspectableWorldSnapshotRecorder(100, new Gson());
        for (ObservedEvent event : Arrays.asList(event(2, 1), event(1, 2), event(2, 3))) recorder.addObservation(event);
        recorder.addDamage(context(2, 1)); recorder.addDamage(context(2, 3));
        List<ObservedEvent> events = recorder.observationsForSourceResolution();
        assertEquals(3, events.size());
        assertEquals(1L, events.get(0).getSequence()); assertEquals(2L, events.get(1).getSequence()); assertEquals(3L, events.get(2).getSequence());
        assertEquals("UNRESOLVED", events.get(0).getData().get("resourceOutcomeStatus"));
        assertFalse(events.get(1).getData().containsKey("attributionContext"));
        assertEquals("UNRESOLVED", events.get(2).getData().get("resourceOutcomeStatus"));
        assertEquals(Long.valueOf(1), ((DamageRecord) events.get(0).getData().get("attributionContext")).getObservationSequence());
        assertEquals(Long.valueOf(3), ((DamageRecord) events.get(2).getData().get("attributionContext")).getObservationSequence());
    }

    @Test
    public void failedDuplicateContextCannotRemoveOrRewriteAnOriginalCallback()
    {
        InspectableWorldSnapshotRecorder recorder = new InspectableWorldSnapshotRecorder(100, new Gson());
        for (ObservedEvent event : Arrays.asList(event(1, 1), event(2, 2), event(2, 3))) recorder.addObservation(event);
        DamageRecord original = context(2, 2);
        try { recorder.addDamage(original.toBuilder().amount(999).build()); fail("Context cannot rewrite the observed amount"); }
        catch (IllegalArgumentException expected) { }
        recorder.addDamage(original);
        try { recorder.addDamage(original.toBuilder().amount(999).build()); fail("An ID cannot be replaced"); }
        catch (IllegalArgumentException expected) { }
        List<ObservedEvent> events = recorder.observationsForSourceResolution();
        assertEquals(3, events.size());
        assertEquals(1L, events.get(0).getSequence()); assertEquals(2L, events.get(1).getSequence()); assertEquals(3L, events.get(2).getSequence());
        assertTrue(events.stream().allMatch(event -> Integer.valueOf(3).equals(event.getData().get("amount"))));
        assertOriginalFacts(original, events.get(1));
        assertFalse(events.get(0).getData().containsKey("attributionContext"));
        assertFalse(events.get(2).getData().containsKey("attributionContext"));
    }
}
