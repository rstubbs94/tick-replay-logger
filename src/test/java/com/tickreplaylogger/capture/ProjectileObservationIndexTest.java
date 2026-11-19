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
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.ProjectileState;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** The live endpoint path reads only indexed projectile instances and the supplied current tail. */
public class ProjectileObservationIndexTest
{
	private static ObservedEvent projectile(Integer definition, long instance, int tick, long sequence)
	{
		return ObservedEvent.builder().type("PROJECTILE_TARGET_CHANGED").tick(tick).sequence(sequence).gameCycle(tick * 30)
			.data(Collections.singletonMap("projectile", ProjectileState.builder().projectileId(definition).observationId(instance).build())).build();
	}
	private static ObservedEvent other(int tick, long sequence)
	{ return ObservedEvent.builder().type("HITSPLAT").tick(tick).sequence(sequence).data(Collections.emptyMap()).build(); }
	private static LegacyWorldSnapshotRecorder recorder() { return new LegacyWorldSnapshotRecorder(100, new Gson()); }
	private static LegacyWorldSnapshotRecorder.ProjectileObservationBatch query(LegacyWorldSnapshotRecorder recorder, int tick, List<ObservedEvent> tail)
	{ return recorder.projectileObservationsForSourceResolution(Collections.singleton(42), tick, tail); }

	@Test public void queryDoesNotIterateTheFullRawJournalAndMergesCurrentTailByOriginalSequence() throws Exception
	{
		LegacyWorldSnapshotRecorder recorder = recorder(); ObservedEvent first = projectile(42, 1, 1, 1), latest = projectile(42, 1, 2, 1002);
		recorder.addObservation(first); for (int index = 2; index < 1002; index++) recorder.addObservation(other(1, index)); recorder.addObservation(latest);
		Field raw = LegacyWorldSnapshotRecorder.class.getDeclaredField("observations"); raw.setAccessible(true);
		@SuppressWarnings("unchecked") java.util.Deque<ObservedEvent> original = (java.util.Deque<ObservedEvent>) raw.get(recorder);
		ArrayDeque<ObservedEvent> noScan = new ArrayDeque<ObservedEvent>(original)
		{
			@Override public Iterator<ObservedEvent> iterator() { throw new AssertionError("Full raw iterator used by indexed query"); }
			@Override public Iterator<ObservedEvent> descendingIterator() { throw new AssertionError("Full raw reverse iterator used by indexed query"); }
		};
		raw.set(recorder, noScan);
		ObservedEvent hp = other(2, 1003);
		LegacyWorldSnapshotRecorder.ProjectileObservationBatch batch = query(recorder, 2, Arrays.asList(latest, hp));
		assertTrue(batch.isComplete()); assertEquals(Arrays.asList(first, latest, hp), batch.getEvents());
		assertSame(first, batch.getEvents().get(0)); assertSame(latest, batch.getEvents().get(1));
	}

	@Test public void knownDefinitionAndUnknownDefinitionRetargetsRemainInTheTrackedInstanceTimeline()
	{
		LegacyWorldSnapshotRecorder recorder = recorder(); ObservedEvent first = projectile(42, 1, 1, 1);
		ObservedEvent changed = projectile(99, 1, 2, 2), unavailable = projectile(null, 1, 2, 3);
		recorder.addObservation(first); recorder.addObservation(changed); recorder.addObservation(unavailable);
		LegacyWorldSnapshotRecorder.ProjectileObservationBatch batch = query(recorder, 2, Collections.emptyList());
		assertTrue(batch.isComplete()); assertEquals(Arrays.asList(first, changed, unavailable), batch.getEvents());
	}

	@Test public void trimmingResizingAndClearingRemoveEveryIndexedReference() throws Exception
	{
		LegacyWorldSnapshotRecorder recorder = recorder(); recorder.addObservation(projectile(42, 1, 1, 1));
		recorder.resize(2); recorder.advanceTick(3);
		assertTrue(query(recorder, 3, Collections.emptyList()).getEvents().isEmpty());
		for (String fieldName : Arrays.asList("projectileDefinitionObservations", "projectileInstanceObservations"))
		{
			Field field = LegacyWorldSnapshotRecorder.class.getDeclaredField(fieldName); field.setAccessible(true);
			assertTrue(((Map<?, ?>) field.get(recorder)).isEmpty());
		}
		recorder.addObservation(projectile(42, 2, 3, 2)); recorder.clear();
		assertTrue(query(recorder, 3, Collections.emptyList()).getEvents().isEmpty());
	}

	@Test public void rawCapacityEvictionAlsoRemovesItsProjectileIndexEntry()
	{
		LegacyWorldSnapshotRecorder recorder = recorder(); recorder.addObservation(projectile(42, 1, 1, 1));
		// Repeated immutable noise is enough to exercise the recorder's actual100000-observation cap.
		ObservedEvent noise = other(1, 2);
		for (int index = 0; index < LegacyWorldSnapshotRecorder.MAX_OBSERVATIONS; index++) recorder.addObservation(noise);
		LegacyWorldSnapshotRecorder.ProjectileObservationBatch batch = query(recorder, 1, Collections.emptyList());
		assertTrue(batch.getEvents().isEmpty()); assertFalse(batch.isComplete());
	}

	@Test public void queryWorkCapReportsIncompleteInsteadOfPretendingAnOlderTimelineWasComplete()
	{
		LegacyWorldSnapshotRecorder recorder = recorder();
		for (int index = 1; index <= 4097; index++) recorder.addObservation(projectile(42, 1, 1, index));
		LegacyWorldSnapshotRecorder.ProjectileObservationBatch batch = query(recorder, 1, Collections.emptyList());
		assertFalse(batch.isComplete()); assertTrue(batch.getEvents().size() <= 4096);
		assertTrue(batch.getEvents().stream().allMatch(event -> event.getSequence() > 0));
	}

	@Test public void originalProjectileReferencesSurviveSameTickHitsplatEnrichment()
	{
		LegacyWorldSnapshotRecorder recorder = recorder(); ObservedEvent projectile = projectile(42, 1, 5, 1);
		recorder.addObservation(projectile); recorder.addObservation(other(5, 2));
		recorder.enrichHitsplatObservations(5, Collections.singletonMap(2L, Collections.singletonMap("resourceOutcomeStatus", "UNRESOLVED")));
		LegacyWorldSnapshotRecorder.ProjectileObservationBatch batch = query(recorder, 5, recorder.observationsAtTickForSourceResolution(5));
		assertEquals(2, batch.getEvents().size()); assertSame(projectile, batch.getEvents().get(0)); assertTrue(batch.isComplete());
	}
}
