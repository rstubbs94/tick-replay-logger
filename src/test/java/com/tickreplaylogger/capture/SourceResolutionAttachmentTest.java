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
import com.tickreplaylogger.model.DamageEvent;
import com.tickreplaylogger.model.TickData;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import com.tickreplaylogger.util.TickRecorder;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

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

	@Test
	public void projectedJournalResultsStayWithTheirOriginalTicksAndDoNotInventResourceHpHits()
	{
		TickRecorder journal = new TickRecorder(5);
		journal.startNewTick();
		journal.addDamageEvent(DamageEvent.received(7, DamageEvent.DamageType.UNKNOWN, "Unknown")
			.toBuilder().observationSequence(11L).build());
		journal.addDamageEvent(DamageEvent.dealt(3, DamageEvent.DamageType.MELEE, "Target")
			.toBuilder().observationSequence(12L).build());
		journal.addEffectNote("Impact 5 (HP/stat) from Unknown", 21L);
		journal.finalizeTick();
		List<TickData> frozen = journal.getTickHistory();
		journal.startNewTick();
		journal.addEffectNote("Current tick must remain separate");
		DamageRecord hp = DamageRecord.builder().impactTick(1).observationSequence(11L).defenderRef("player")
			.direction("RECEIVED").amount(7).hitsplatType(16).damageType("magic")
			.attackerName("Unfamiliar entity").attribution("TIMED").determination("IMPACT")
			.sourceResolution(resolution()).build();
		DamageRecord resource = hp.toBuilder().observationSequence(21L).amount(5).build();
		java.util.Map<Long, DamageRecord> results = new java.util.LinkedHashMap<>();
		results.put(11L, hp);
		results.put(21L, resource);
		journal.applyResolvedDamageRecords(1, results);
		TickData resolved = journal.getTickHistory().get(0);
		assertEquals(2, resolved.getDamageEvents().size());
		assertEquals(7, resolved.getDamageEvents().get(0).getAmount());
		assertEquals(DamageEvent.DamageType.MAGIC, resolved.getDamageEvents().get(0).getDamageType());
		assertEquals("Unfamiliar entity", resolved.getDamageEvents().get(0).getSource());
		assertEquals("Target", resolved.getDamageEvents().get(1).getSource());
		assertEquals("Impact 5 (HP/stat) from Unfamiliar entity", resolved.getEffectNotes().get(0));
		assertEquals("Unknown", frozen.get(0).getDamageEvents().get(0).getSource());
		assertEquals("Impact 5 (HP/stat) from Unknown", frozen.get(0).getEffectNotes().get(0));
	}

	@Test
	public void resourceOnlyResultUpdatesItsNoteWithoutAddingHpDamage()
	{
		TickRecorder journal = new TickRecorder(5);
		journal.startNewTick();
		journal.addEffectNote("Impact 5 (HP/stat) from Unknown", 21L);
		journal.finalizeTick();
		List<TickData> frozen = journal.getTickHistory();
		DamageRecord resource = DamageRecord.builder().impactTick(1).observationSequence(21L)
			.amount(5).direction("RESOURCE_IMPACT").attackerName("Unfamiliar entity")
			.sourceResolution(resolution()).build();
		journal.applyResolvedDamageRecords(1, Collections.singletonMap(21L, resource));
		assertTrue(journal.getTickHistory().get(0).getDamageEvents().isEmpty());
		assertEquals("Impact 5 (HP/stat) from Unfamiliar entity", journal.getTickHistory().get(0).getEffectNotes().get(0));
		assertEquals("Impact 5 (HP/stat) from Unknown", frozen.get(0).getEffectNotes().get(0));
	}

	@Test
	public void journalRejectsResultsForAnotherTickDirectionOrAmount()
	{
		TickRecorder journal = new TickRecorder(5);
		journal.startNewTick();
		journal.addDamageEvent(DamageEvent.received(7, DamageEvent.DamageType.UNKNOWN, "Unknown")
			.toBuilder().observationSequence(11L).build());
		journal.addEffectNote("Impact 5 (HP/stat) from Unknown", 21L);
		journal.finalizeTick();
		DamageRecord differentTick = DamageRecord.builder().impactTick(2).observationSequence(11L)
			.amount(7).direction("RECEIVED").sourceResolution(resolution()).build();
		journal.applyResolvedDamageRecords(1, Collections.singletonMap(11L, differentTick));
		journal.applyResolvedDamageRecords(1, Collections.singletonMap(11L,
			differentTick.toBuilder().impactTick(1).amount(8).build()));
		journal.applyResolvedDamageRecords(1, Collections.singletonMap(11L,
			differentTick.toBuilder().impactTick(1).direction("DEALT").build()));
		journal.applyResolvedDamageRecords(1, Collections.singletonMap(21L,
			differentTick.toBuilder().amount(5).observationSequence(21L).attackerName("Wrong tick").build()));
		TickData unchanged = journal.getTickHistory().get(0);
		assertNull(unchanged.getDamageEvents().get(0).getSourceResolution());
		assertEquals("Unknown", unchanged.getDamageEvents().get(0).getSource());
		assertEquals("Impact 5 (HP/stat) from Unknown", unchanged.getEffectNotes().get(0));
	}

	@Test
	public void projectedRecordsCannotReplaceAnotherNativeHitOrMutateFrozenExports()
	{
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(5, GSON);
		recorder.advanceTick(2);
		DamageRecord original = DamageRecord.builder().impactTick(2).observationSequence(11L).amount(7)
			.hitsplatType(16).hitsplatTypeKnown(true).direction("RECEIVED").defenderRef("player").observedCycle(60)
			.impactContextEpoch(3L).finalizationContextEpoch(4L)
			.impactSceneKey("room-a").impactWorldViewId(0).impactPlane(0).build();
		recorder.addDamage(original);
		com.tickreplaylogger.model.world.DeathRecordingV2 frozen = recorder.freezeExport(null, null, 0, 2);
		DamageRecord projected = original.toBuilder().attackerName("Unfamiliar entity")
			.sourceResolution(resolution()).build();
		recorder.applyResolvedDamageRecords(2, Collections.singletonMap(11L,
			projected.toBuilder().impactSceneKey("another-room").build()));
		assertNull(recorder.receivedAtTick(2).get(0).getSourceResolution());
		for (DamageRecord anotherCallback : java.util.Arrays.asList(
			projected.toBuilder().impactContextEpoch(4L).build(),
			projected.toBuilder().finalizationContextEpoch(5L).build(),
			projected.toBuilder().hitsplatTypeKnown(null).build()))
		{
			recorder.applyResolvedDamageRecords(2, Collections.singletonMap(11L, anotherCallback));
			assertNull(recorder.receivedAtTick(2).get(0).getSourceResolution());
		}
		recorder.applyResolvedDamageRecords(2, Collections.singletonMap(11L, projected));
		assertNotNull(recorder.receivedAtTick(2).get(0).getSourceResolution());
		assertEquals(7, recorder.receivedAtTick(2).get(0).getAmount());
		assertNull(frozen.getEvents().getDamageEvents().get(0).getSourceResolution());
	}

	@Test
	public void lightweightSourceWindowsExcludeLaterCallbacksAndKeepFrozenMembership()
	{
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(5, GSON);
		recorder.advanceTick(4);
		com.tickreplaylogger.model.world.ObservedEvent before = com.tickreplaylogger.model.world.ObservedEvent.builder()
			.tick(3).sequence(11).type("HITSPLAT").actorRef("player").build();
		com.tickreplaylogger.model.world.ObservedEvent later = com.tickreplaylogger.model.world.ObservedEvent.builder()
			.tick(4).sequence(12).type("HITSPLAT").actorRef("player").build();
		recorder.addObservation(before);
		recorder.addObservation(later);
		DamageRecord hit = DamageRecord.builder().impactTick(3).observationSequence(11L).amount(5).build();
		recorder.addDamage(hit);
		ResolverV1Recording.EvidenceWindow window = recorder.freezeSourceEvidence(4, 11, 1, Collections.emptyList());
		recorder.addObservation(com.tickreplaylogger.model.world.ObservedEvent.builder()
			.tick(4).sequence(13).type("HITSPLAT").actorRef("player").build());
		assertTrue(window.getTicks().isEmpty());
		assertEquals(1, window.getObservations().size());
		assertEquals(11, window.getObservations().get(0).getSequence());
		assertEquals(1, window.getHits().size());
	}

	@Test
	public void richAttachmentUsesTickDirectionAndSequenceWithoutChangingFrozenOriginals()
	{
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(5, GSON);
		DamageRecord received = DamageRecord.builder().impactTick(2).amount(0).hitsplatType(16).direction("RECEIVED")
			.observationSequence(11L).attackerName("Unknown").build();
		DamageRecord dealt = received.toBuilder().direction("DEALT").amount(7).build();
		DamageRecord previous = received.toBuilder().impactTick(1).amount(9).build();
		DamageRecord unlinked = received.toBuilder().observationSequence(null).amount(5).build();
		recorder.addDamage(previous);
		recorder.addDamage(received);
		recorder.addDamage(dealt);
		recorder.addDamage(unlinked);
		List<DamageRecord> frozen = recorder.receivedAtTick(2);
		assertEquals(2, frozen.size());
		recorder.applySourceResolutions(2, Collections.singletonMap(11L, resolution()));
		List<DamageRecord> current = recorder.receivedAtTick(2);
		assertNotNull(current.get(0).getSourceResolution());
		assertNull(current.get(1).getSourceResolution());
		assertNull(frozen.get(0).getSourceResolution());
		assertNull(previous.getSourceResolution());
		assertNull(dealt.getSourceResolution());
		JsonObject original = GSON.toJsonTree(received).getAsJsonObject();
		JsonObject derived = GSON.toJsonTree(current.get(0)).getAsJsonObject();
		derived.remove("sourceResolution");
		assertEquals(original, derived);
		recorder.addSnapshot(WorldSnapshot.builder().tick(2).build());
		List<WorldSnapshot> snapshots = recorder.snapshotsForSourceResolution();
		recorder.clear();
		assertEquals(1, snapshots.size());
		assertEquals(2, snapshots.get(0).getTick());
	}

	@Test
	public void journalAttachmentDoesNotMutatePausedHistoryOrJoinAnUnrelatedDirection()
	{
		TickRecorder recorder = new TickRecorder(5);
		DamageEvent received = DamageEvent.builder().amount(0).direction(DamageEvent.DamageDirection.RECEIVED)
			.damageType(DamageEvent.DamageType.UNKNOWN).source("Unknown").observationSequence(11L).build();
		DamageEvent dealt = received.toBuilder().direction(DamageEvent.DamageDirection.DEALT).amount(7).build();
		recorder.startNewTick();
		recorder.addDamageEvent(received);
		recorder.addDamageEvent(dealt);
		recorder.finalizeTick();
		List<TickData> frozen = recorder.getTickHistory();
		recorder.applyReceivedSourceResolutions(2, Collections.singletonMap(11L, resolution()));
		assertNull(recorder.getTickHistory().get(0).getDamageEvents().get(0).getSourceResolution());
		recorder.applyReceivedSourceResolutions(1, Collections.singletonMap(11L, resolution()));
		List<DamageEvent> current = recorder.getTickHistory().get(0).getDamageEvents();
		assertNotNull(current.get(0).getSourceResolution());
		assertNull(current.get(1).getSourceResolution());
		assertNull(frozen.get(0).getDamageEvents().get(0).getSourceResolution());
		assertEquals(0, current.get(0).getAmount());
		assertEquals("Unknown", current.get(0).getSource());
		assertEquals(7, current.get(1).getAmount());
	}
}
