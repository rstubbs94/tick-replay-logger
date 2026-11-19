/*
 * Copyright (c) 2026, rstubbs94
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.tickreplaylogger.capture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSerializer;
import com.tickreplaylogger.model.TickData;
import com.tickreplaylogger.model.world.*;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExportFailureRetentionTest
{
	@Test
	public void interpretationFailureRetainsSerializableRawDeathAndJournalWithoutMutatingFrozenInput()
	{
		Gson plain = new Gson();
		AtomicInteger contextReads = new AtomicInteger();
		Gson gson = new GsonBuilder().registerTypeAdapter(ContextBlock.class,
			(JsonSerializer<ContextBlock>) (context, type, serialization) ->
			{
				if (contextReads.getAndIncrement() == 0)
					throw new IllegalStateException("Synthetic interpretation failure");
				return plain.toJsonTree(context);
			}).create();
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(5, gson);
		DamageRecord hit = DamageRecord.builder().impactTick(1).observationSequence(7L).observedCycle(100)
			.direction("RECEIVED").defenderRef("player").amount(12).hitsplatType(16).hitsplatTypeKnown(true)
			.impactSceneKey("scene").impactWorldViewId(0).impactPlane(0).attackerName("Unknown").build();
		recorder.addSnapshot(WorldSnapshot.builder().tick(1).sceneKey("scene").worldViewId(0).plane(0)
			.nativeContextKnown(true).player(PlayerState.builder().build())
			.context(ContextBlock.builder().activity("Synthetic activity").build()).build());
		recorder.addDamage(hit);
		recorder.addDeath(DeathRecordingV2.Events.DeathMark.builder().tick(1).build());
		DeathRecordingV2 frozen = recorder.freezeExport(DeathRecordingV2.Meta.builder().deathTick(1)
			.captureIssues(Collections.singletonList("Existing capture issue")).build(), null, 1, 1)
			.toBuilder().journalTicks(Collections.singletonList(TickData.builder().tickNumber(1).build())).build();
		String original = plain.toJson(frozen);

		DeathRecordingV2 retained = recorder.completeExport(frozen);
		assertEquals(1, contextReads.get());
		assertSame(frozen.getEvents(), retained.getEvents());
		assertSame(frozen.getTicks(), retained.getTicks());
		assertSame(frozen.getJournalTicks(), retained.getJournalTicks());
		assertEquals(original, plain.toJson(frozen));
		assertEquals(2, retained.getMeta().getCaptureIssues().size());
		assertEquals("Existing capture issue", retained.getMeta().getCaptureIssues().get(0));
		assertEquals("POST_EVENT_PROCESSING_UNAVAILABLE: IllegalStateException",
			retained.getMeta().getCaptureIssues().get(1));
		DeathRecordingV2 reread = plain.fromJson(gson.toJson(retained), DeathRecordingV2.class);
		assertEquals(12, reread.getEvents().getDamageEvents().get(0).getAmount());
		assertEquals(1, reread.getEvents().getDeaths().size());
		assertEquals(1, reread.getJournalTicks().size());
	}
}
