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
	public void streamingCompletionCountsExistingCollidingActionsAndHitsBeforeAddingMissingCopies()
	{
		Gson gson = new Gson();
		PlayerAction action = PlayerAction.ate("Shark");
		TickData tick = TickData.builder().tickNumber(1).playerActions(Arrays.asList(action, action))
			.damageEvents(Arrays.asList(DamageEvent.dealt(7, DamageEvent.DamageType.UNKNOWN, "Target"),
				DamageEvent.dealt(7, DamageEvent.DamageType.UNKNOWN, "Target")))
			.build();
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", "1.3");
		JsonObject events = new JsonObject();
		root.add("events", events);
		com.google.gson.JsonArray actions = new com.google.gson.JsonArray();
		JsonObject existing = new JsonObject();
		existing.addProperty("tick", 1);
		existing.addProperty("type", action.getType().name());
		existing.addProperty("detail", action.getDescription());
		actions.add(existing);
		events.add("playerActions", actions);
		com.google.gson.JsonArray hits = new com.google.gson.JsonArray();
		JsonObject hit = new JsonObject();
		hit.addProperty("direction", "DEALT");
		hit.addProperty("impactTick", 1);
		hit.addProperty("amount", 7);
		hit.addProperty("defenderName", "<col=ffffff>Target</col>");
		hits.add(hit);
		events.add("damageEvents", hits);
		TickRecording recording = TickRecording.builder().ticks(Collections.singletonList(tick))
			.richExportJson(gson.toJson(root)).build();
		JsonObject result = gson.fromJson(RecordingExport.forViewer(recording, gson), JsonObject.class);
		assertEquals(2, result.getAsJsonObject("events").getAsJsonArray("playerActions").size());
		assertEquals(2, result.getAsJsonObject("events").getAsJsonArray("damageEvents").size());
		assertEquals("<col=ffffff>Target</col>", result.getAsJsonObject("events").getAsJsonArray("damageEvents").get(0)
			.getAsJsonObject().get("defenderName").getAsString());
		assertEquals(14, result.getAsJsonObject("summary").get("totalDamageDealtInBuffer").getAsInt());
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
	public void latestExportIncludesJournalMultiplicityWithoutChangingTheSavedCapture()
	{
		Gson gson = new Gson();
		TickData tick = TickData.builder().tickNumber(1)
			.playerActions(Arrays.asList(PlayerAction.ate("Shark"), PlayerAction.ate("Shark")))
			.damageEvents(Arrays.asList(
				DamageEvent.builder().direction(DamageEvent.DamageDirection.DEALT).amount(7).source("Observed target")
					.build(),
				DamageEvent.builder().direction(DamageEvent.DamageDirection.DEALT).amount(7).source("Observed target")
					.build()))
			.build();
		TickRecording recording = TickRecording.builder().ticks(Collections.singletonList(tick)).richExportJson(
			"{\"schemaVersion\":\"1.3\",\"events\":{\"damageEvents\":[{\"direction\":\"RECEIVED\",\"amount\":12}]},\"summary\":{}}")
			.build();
		String original = gson.toJson(recording);
		String copied = RecordingExport.forViewer(recording, gson);
		JsonObject exported = gson.fromJson(copied, JsonObject.class);
		assertEquals("1.3", exported.get("schemaVersion").getAsString());
		assertEquals(1, exported.getAsJsonArray("journalTicks").size());
		assertEquals(2, exported.getAsJsonObject("events").getAsJsonArray("playerActions").size());
		com.tickreplaylogger.model.world.DeathRecordingV2 rich = gson.fromJson(copied,
			com.tickreplaylogger.model.world.DeathRecordingV2.class);
		assertEquals(2,
			rich.getEvents().getDamageEvents().stream().filter(d -> "DEALT".equals(d.getDirection())).count());
		assertEquals(14, rich.getEvents().getDamageEvents().stream().filter(d -> "DEALT".equals(d.getDirection()))
			.mapToInt(d -> d.getAmount()).sum());
		assertEquals(12, rich.getEvents().getDamageEvents().stream().filter(d -> "RECEIVED".equals(d.getDirection()))
			.mapToInt(d -> d.getAmount()).sum());
		assertEquals(original, gson.toJson(recording));
		// Copying the completed payload again must preserve duplicate click multiplicity without adding duplicates.
		TickRecording again = gson.fromJson(original, TickRecording.class);
		again.setRichExportJson(copied);
		assertEquals(exported, gson.fromJson(RecordingExport.forViewer(again, gson), JsonObject.class));
	}
}
