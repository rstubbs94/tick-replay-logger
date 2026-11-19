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
