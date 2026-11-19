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
import com.tickreplaylogger.model.world.ObservedEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class RawObservationEnrichmentTest
{
	private static ObservedEvent event(int tick, long sequence)
	{
		return ObservedEvent.builder().tick(tick).sequence(sequence).gameCycle(tick * 30).type("HITSPLAT")
			.actorRef("player").actorName("Recorded recipient").relatedActorRef("original-related").sceneKey("scene")
			.data(Collections.singletonMap("amount", 3)).build();
	}

	@Test
	public void currentTailReplacementLeavesHistoryAndOriginalCallbackIdentityUntouched()
	{
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(100, new Gson());
		ObservedEvent historical = event(1, 1), current = event(2, 2);
		recorder.addObservation(historical); recorder.addObservation(current);
		Map<String, Object> derived = new HashMap<>(); derived.put("resourceOutcomeStatus", "UNRESOLVED"); derived.put("amount", 999);
		recorder.enrichHitsplatObservations(2, Collections.singletonMap(2L, derived));
		List<ObservedEvent> events = recorder.observationsForSourceResolution();
		assertEquals(2, events.size()); assertSame(historical, events.get(0)); assertNotSame(current, events.get(1));
		ObservedEvent enriched = events.get(1);
		assertEquals(current.getSequence(), enriched.getSequence()); assertEquals(current.getTick(), enriched.getTick());
		assertEquals(current.getGameCycle(), enriched.getGameCycle()); assertEquals(current.getActorRef(), enriched.getActorRef());
		assertEquals(current.getActorName(), enriched.getActorName()); assertEquals(current.getRelatedActorRef(), enriched.getRelatedActorRef());
		assertEquals(current.getSceneKey(), enriched.getSceneKey()); assertEquals(3, enriched.getData().get("amount"));
		assertEquals("COMPLETE_TICK_TAIL", enriched.getData().get("sourceEnrichmentCoverage"));
		assertFalse(current.getData().containsKey("resourceOutcomeStatus"));
		try { enriched.getData().put("amount", 99); fail("Enriched metadata must be immutable"); }
		catch (UnsupportedOperationException expected) { }
	}

	@Test
	public void unorderedInputUsesCompleteBoundedFallbackAndPreservesOriginalSequenceOrder()
	{
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(100, new Gson());
		for (ObservedEvent event : Arrays.asList(event(2, 1), event(1, 2), event(2, 3))) recorder.addObservation(event);
		Map<Long, Map<String, Object>> derived = new HashMap<>();
		derived.put(1L, Collections.singletonMap("resourceOutcomeStatus", "UNRESOLVED"));
		derived.put(3L, Collections.singletonMap("resourceOutcomeStatus", "UNRESOLVED"));
		recorder.enrichHitsplatObservations(2, derived);
		List<ObservedEvent> events = recorder.observationsForSourceResolution();
		assertEquals(3, events.size());
		assertEquals(1L, events.get(0).getSequence()); assertEquals(2L, events.get(1).getSequence()); assertEquals(3L, events.get(2).getSequence());
		assertEquals("COMPLETE_BUFFER_SCAN", events.get(0).getData().get("sourceEnrichmentCoverage"));
		assertFalse(events.get(1).getData().containsKey("resourceOutcomeStatus"));
		assertEquals("COMPLETE_BUFFER_SCAN", events.get(2).getData().get("sourceEnrichmentCoverage"));
	}

	@Test
	public void optionalEnrichmentFailureCannotRemovePreviouslyCapturedCallbacks()
	{
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(100, new Gson());
		for (ObservedEvent event : Arrays.asList(event(1, 1), event(2, 2), event(2, 3))) recorder.addObservation(event);
		Map<Long, Map<String, Object>> derived = new HashMap<Long, Map<String, Object>>()
		{
			@Override public Map<String, Object> get(Object key)
			{
				if (Long.valueOf(2).equals(key)) throw new IllegalStateException("injected optional enrichment failure");
				return Collections.singletonMap("resourceOutcomeStatus", "UNRESOLVED");
			}
			@Override public boolean isEmpty() { return false; }
		};
		try { recorder.enrichHitsplatObservations(2, derived); fail("Injected failure must be visible"); }
		catch (IllegalStateException expected) { }
		List<ObservedEvent> events = recorder.observationsForSourceResolution();
		assertEquals(3, events.size());
		assertEquals(1L, events.get(0).getSequence()); assertEquals(2L, events.get(1).getSequence()); assertEquals(3L, events.get(2).getSequence());
		assertTrue(events.stream().allMatch(event -> Integer.valueOf(3).equals(event.getData().get("amount"))));
	}
}
