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
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;
import com.tickreplaylogger.model.world.DeathRecordingV2;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class DetachedExportTest
{
	@Test
	public void selectedWindowSurvivesFurtherTicksAndClearingTheRecorder()
	{
		Gson gson = new Gson();
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(2, gson);
		recorder.addSnapshot(frame(1));
		DeathRecordingV2 frozen = recorder.freezeExport(null, null, 1, 1);
		String expected = gson.toJson(recorder.completeExport(frozen));
		recorder.addSnapshot(frame(2));
		recorder.addSnapshot(frame(3));
		recorder.clear();
		assertEquals(expected, gson.toJson(recorder.completeExport(frozen)));
		assertEquals(1, frozen.getTicks().size());
		assertEquals(1, frozen.getTicks().get(0).getTick());
	}

	@Test
	public void slowCompactionDoesNotHoldTheLiveRecorderLock() throws Exception
	{
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Gson gson = new GsonBuilder()
			.registerTypeAdapter(NpcState.class, (JsonSerializer<NpcState>) (npc, type, context) ->
			{
				entered.countDown();
				try
				{
					if (!release.await(5, TimeUnit.SECONDS))
						throw new AssertionError("Worker was not released");
				}
				catch (InterruptedException error)
				{
					throw new AssertionError(error);
				}
				JsonObject value = new JsonObject();
				value.addProperty("npcId", npc.getNpcId());
				return value;
			}).create();
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(2, gson);
		recorder.addSnapshot(frame(1));
		DeathRecordingV2 frozen = recorder.freezeExport(null, null, 1, 1);
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try
		{
			Future<DeathRecordingV2> export = workers.submit(() -> recorder.completeExport(frozen));
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			workers.submit(() -> recorder.addSnapshot(frame(2))).get(2, TimeUnit.SECONDS);
			release.countDown();
			assertEquals(1, export.get(5, TimeUnit.SECONDS).getTicks().get(0).getTick());
		}
		finally
		{
			release.countDown();
			workers.shutdownNow();
		}
	}

	private static WorldSnapshot frame(int tick)
	{
		return WorldSnapshot.builder().tick(tick)
			.npcs(Collections.singletonList(NpcState.builder().npcId(1).name("Captured NPC").build())).build();
	}
}
