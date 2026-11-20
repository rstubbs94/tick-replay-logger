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
import com.tickreplaylogger.model.TickRecording;
import com.tickreplaylogger.model.DamageEvent;
import com.tickreplaylogger.model.PlayerAction;
import com.tickreplaylogger.model.TickData;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingExportTest
{
	@Test
	public void streamingCompletionPreservesUnknownChannelsNullsNumbersAndEscapedStrings()
	{
		Gson gson = new Gson();
		String raw = "{\"schemaVersion\":\"1.3\",\"unknownRoot\":{\"big\":900719925474099312345,\"tiny\":1e-1000,\"negativeZero\":-0.0,\"presentNull\":null,\"values\":[null,0,false,\"quoted\\\" text\\n火 😀\"]},\"ticks\":[{\"tick\":1,\"extra\":null}],\"events\":{\"unknownEvents\":[null,{\"value\":900719925474099312345}]}}";
		TickRecording recording = TickRecording.builder().ticks(Collections.emptyList()).richExportJson(raw).build();
		String result = RecordingExport.forViewer(recording, gson);
		JsonObject before = gson.fromJson(raw, JsonObject.class), after = gson.fromJson(result, JsonObject.class);
		assertEquals(before.get("unknownRoot"), after.get("unknownRoot"));
		assertEquals(before.get("ticks"), after.get("ticks"));
		assertEquals(before.getAsJsonObject("events").get("unknownEvents"),
			after.getAsJsonObject("events").get("unknownEvents"));
		assertTrue(after.getAsJsonObject("unknownRoot").has("presentNull"));
		assertTrue(after.getAsJsonObject("unknownRoot").get("presentNull").isJsonNull());
		assertTrue(result.contains("900719925474099312345"));
		assertTrue(result.contains("1e-1000"));
		assertTrue(result.contains("-0.0"));
		assertEquals(raw, recording.getRichExportJson());
	}

	@Test
	public void typedExportPreservesEveryDuplicateReceiptWithoutReopeningOrRepairingJson()
	{
		Gson gson = new Gson();
		com.tickreplaylogger.model.world.DeathRecordingV2.Events.PlayerActionRecord action =
			com.tickreplaylogger.model.world.DeathRecordingV2.Events.PlayerActionRecord.builder().tick(1).type("EAT").detail("Shark").build();
		com.tickreplaylogger.model.world.DamageRecord hit = com.tickreplaylogger.model.world.DamageRecord.builder()
			.direction("DEALT").impactTick(1).amount(7).defenderName("<col=ffffff>Target</col>").build();
		TickData tick = TickData.builder().tickNumber(1).playerActions(Arrays.asList(PlayerAction.ate("Shark"), PlayerAction.ate("Shark")))
			.damageEvents(Arrays.asList(DamageEvent.dealt(7, DamageEvent.DamageType.UNKNOWN, "Target"), DamageEvent.dealt(7, DamageEvent.DamageType.UNKNOWN, "Target"))).build();
		com.tickreplaylogger.model.world.DeathRecordingV2 payload = com.tickreplaylogger.model.world.DeathRecordingV2.builder()
			.journalTicks(Collections.singletonList(tick)).events(com.tickreplaylogger.model.world.DeathRecordingV2.Events.builder()
				.playerActions(Arrays.asList(action, action)).damageEvents(Arrays.asList(hit, hit)).build()).build();
		TickRecording recording = TickRecording.fromPrepared(payload, 1, "Live Snapshot", false);
		String before = gson.toJson(payload);
		String exported = RecordingExport.forViewer(recording, gson);
		assertEquals(before, exported);
		com.tickreplaylogger.model.world.DeathRecordingV2 decoded = gson.fromJson(exported, com.tickreplaylogger.model.world.DeathRecordingV2.class);
		assertEquals(2, decoded.getEvents().getPlayerActions().size());
		assertEquals(2, decoded.getEvents().getDamageEvents().size());
		assertEquals(14, decoded.getEvents().getDamageEvents().stream().mapToInt(d -> d.getAmount()).sum());
		assertEquals("<col=ffffff>Target</col>", decoded.getEvents().getDamageEvents().get(0).getDefenderName());
		assertEquals(2, decoded.getJournalTicks().get(0).getDamageEvents().size());
		assertSame(payload, recording.getPreparedExport());
		assertEquals(before, RecordingExport.forViewer(recording, gson));
	}

	@Test
	public void unsupportedFutureVersionsArePreservedInsteadOfDowngradedOrReinterpreted()
	{
		String original = "{\"schemaVersion\":\"9.0\",\"futureData\":{\"value\":7}}";
		TickRecording recording = TickRecording.builder().ticks(java.util.Collections.emptyList())
			.richExportJson(original).build();
		assertEquals(original, RecordingExport.forViewer(recording, new Gson()));
		assertEquals(original, recording.getRichExportJson());
	}

	@Test
	public void historicalIntermediatePayloadIsKeptOpaqueRatherThanRepairedFromAnotherJournal()
	{
		String original = "{\"schemaVersion\":\"1.3\",\"events\":{\"damageEvents\":[{\"amount\":12}]}}";
		TickRecording recording = TickRecording.builder().richExportJson(original)
			.ticks(Collections.singletonList(TickData.builder().tickNumber(1)
				.damageEvents(Collections.singletonList(DamageEvent.dealt(7, DamageEvent.DamageType.UNKNOWN, "Target"))).build())).build();
		assertEquals(original, RecordingExport.forViewer(recording, new Gson()));
	}
}
