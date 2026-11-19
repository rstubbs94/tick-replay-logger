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

import com.tickreplaylogger.model.world.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class TickWindowTest
{
	@Test
	public void journalBoundariesExpireEventsAndScenesEvenWhenNoSnapshotsArrive()
	{
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(2, new com.google.gson.Gson());
		recorder.putScene(SceneData.builder().sceneKey("old").build());
		recorder.addSnapshot(WorldSnapshot.builder().tick(1).sceneKey("old").build());
		recorder.addDamage(DamageRecord.builder().impactTick(1).amount(7).build());
		recorder.addObservation(ObservedEvent.builder().tick(1).sequence(1).type("HITSPLAT").build());
		recorder.advanceTick(2);
		assertNotNull(recorder.snapshotAtTick(1));
		recorder.advanceTick(3);
		assertNull(recorder.snapshotAtTick(1));
		assertFalse(recorder.hasScene("old"));
		assertTrue(recorder.buildExport(null, null).getEvents().getDamageEvents().isEmpty());
		assertTrue(recorder.buildExport(null, null).getEvents().getObservations().isEmpty());
		recorder.addDamage(DamageRecord.builder().impactTick(3).amount(4).build());
		assertEquals(1, recorder.buildExport(null, null, 2, 3).getEvents().getDamageEvents().size());
		recorder.clear();
		recorder.addSnapshot(WorldSnapshot.builder().tick(1).build());
		assertNotNull(recorder.snapshotAtTick(1));
	}

	@Test
	public void explicitJournalWindowRetainsEventsAtBothMissingSnapshotEdges()
	{
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(10, new com.google.gson.Gson());
		recorder.addSnapshot(WorldSnapshot.builder().tick(2).build());
		for (int tick : new int[]
		{ 0, 1, 3, 4 })
			recorder.addDamage(DamageRecord.builder().impactTick(tick).amount(tick).build());
		DeathRecordingV2 export = recorder.buildExport(null, null, 1, 3);
		assertEquals(2, export.getEvents().getDamageEvents().size());
		assertEquals(1, export.getEvents().getDamageEvents().get(0).getImpactTick());
		assertEquals(3, export.getEvents().getDamageEvents().get(1).getImpactTick());
		assertEquals(1, export.getTicks().size());
	}
}
