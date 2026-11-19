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
import com.tickreplaylogger.model.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClipboardTransportTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void smallLatestPayloadRemainsOrdinaryJson()
	{
		String plain = "{\"schemaVersion\":\"1.3\",\"ticks\":[{\"tick\":1}]}";
		assertSame(plain, RecordingExport.clipboardTransport(plain, GSON));
	}

	@Test
	public void compressedTransportRestoresExactUtf8AndPreservesUnicode() throws Exception
	{
		String plain = "{\"schemaVersion\":\"1.3\",\"ticks\":[{\"tick\":1}],\"recordedText\":\""
			+ "Observed 火 😀; ".repeat(90000) + "\"}";
		String encoded = RecordingExport.clipboardTransport(plain, GSON);
		JsonObject envelope = GSON.fromJson(encoded, JsonObject.class);
		assertEquals("1.3", envelope.get("schemaVersion").getAsString());
		assertEquals("gzip-base64-v1", envelope.get("transportEncoding").getAsString());
		assertEquals(plain.getBytes(StandardCharsets.UTF_8).length, envelope.get("uncompressedBytes").getAsLong());
		assertEquals(plain, decode(envelope));
		assertTrue(encoded.length() < plain.getBytes(StandardCharsets.UTF_8).length);
		Path fixture = Paths.get("build/clipboard-transport-fixture.json");
		Files.createDirectories(fixture.getParent());
		Files.write(fixture, encoded.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void compressionIsNotUsedWhenTheEnvelopeWouldBeLarger()
	{
		byte[] random = new byte[1100000];
		new Random(781).nextBytes(random);
		String plain = "{\"schemaVersion\":\"1.3\",\"recordedBytes\":\"" + Base64.getEncoder().encodeToString(random)
			+ "\"}";
		assertSame(plain, RecordingExport.clipboardTransport(plain, GSON));
	}

	@Test
	public void anUnsupportedOrMalformedSchemaIsNotWrappedAsCurrent()
	{
		String future = "{\"schemaVersion\":\"9.0\",\"future\":\"" + "future ".repeat(200000) + "\"}";
		assertSame(future, RecordingExport.clipboardTransport(future, GSON));
		String malformed = "not-json " + "data ".repeat(250000);
		assertSame(malformed, RecordingExport.clipboardTransport(malformed, GSON));
	}

	@Test
	public void clipboardTransportCompletesTheSamePlainJournalWithoutChangingSavedData() throws Exception
	{
		TickData tick = TickData.builder().tickNumber(1)
			.playerActions(Arrays.asList(PlayerAction.ate("Shark"), PlayerAction.ate("Shark")))
			.damageEvents(Arrays.asList(DamageEvent.dealt(7, DamageEvent.DamageType.UNKNOWN, "Recorded target"),
				DamageEvent.dealt(7, DamageEvent.DamageType.UNKNOWN, "Recorded target")))
			.build();
		String rich = "{\"schemaVersion\":\"1.3\",\"ticks\":[{\"tick\":1}],\"recordedText\":\""
			+ "synthetic observation; ".repeat(60000) + "\"}";
		TickRecording recording = TickRecording.builder().ticks(Collections.singletonList(tick)).richExportJson(rich)
			.build();
		String before = GSON.toJson(recording);
		String expected = RecordingExport.forViewer(recording, GSON),
			encoded = RecordingExport.forClipboard(recording, GSON);
		JsonObject envelope = GSON.fromJson(encoded, JsonObject.class);
		assertEquals(expected, decode(envelope));
		JsonObject restored = GSON.fromJson(decode(envelope), JsonObject.class);
		assertEquals(2, restored.getAsJsonObject("events").getAsJsonArray("playerActions").size());
		assertEquals(2, restored.getAsJsonObject("events").getAsJsonArray("damageEvents").size());
		assertEquals(14, restored.getAsJsonObject("summary").get("totalDamageDealtInBuffer").getAsInt());
		assertEquals(before, GSON.toJson(recording));
	}

	private static String decode(JsonObject envelope) throws Exception
	{
		byte[] compressed = Base64.getDecoder().decode(envelope.get("payload").getAsString());
		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed)))
		{
			return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
