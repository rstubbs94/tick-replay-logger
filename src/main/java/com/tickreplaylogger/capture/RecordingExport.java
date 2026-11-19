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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.DamageEvent;
import com.tickreplaylogger.model.PlayerAction;
import com.tickreplaylogger.model.TickData;
import com.tickreplaylogger.model.TickRecording;
import com.tickreplaylogger.model.world.DeathRecordingV2;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.google.gson.stream.JsonToken;
import lombok.extern.slf4j.Slf4j;

/** Completes the viewer payload from the original saved journal without rewriting old captures. */
@Slf4j
public final class RecordingExport
{
	private RecordingExport()
	{
	}

	private static final int COMPRESS_AT_BYTES = 1024 * 1024;
	private static final int MAX_EXPANDED_BYTES = 64 * 1024 * 1024;

	/** Lossless clipboard transport; {@link #forViewer} remains ordinary JSON. */
	public static String forClipboard(TickRecording recording, Gson gson)
	{
		String rich = recording.getRichExportJson();
		if (rich != null && rich.length() >= COMPRESS_AT_BYTES / 3 && latestPayload(rich))
			try
			{
				Completion completed = prepare(recording, gson);
				String encoded = compress(out -> writeCompleted(recording, gson, completed, out), gson, rich.length());
				if (encoded != null)
					return encoded;
			}
			catch (IOException | RuntimeException invalid)
			{
				/* Preserve the ordinary export fallback below. */ }
		return clipboardTransport(forViewer(recording, gson), gson);
	}

	static String clipboardTransport(String plain, Gson gson)
	{
		if (plain.length() < COMPRESS_AT_BYTES / 3 || !latestPayload(plain))
			return plain;
		String encoded = compress(out -> out.write(plain), gson, plain.length());
		return encoded == null ? plain : encoded;
	}

	@FunctionalInterface
	private interface WritePayload
	{
		void write(Writer out) throws IOException;
	}

	private static String compress(WritePayload payload, Gson gson, int sizeHint)
	{
		try
		{
			ByteArrayOutputStream compressed = new ByteArrayOutputStream(Math.min(1024 * 1024, sizeHint / 16));
			CountingOutputStream counted;
			try (GZIPOutputStream gzip = new GZIPOutputStream(compressed))
			{
				counted = new CountingOutputStream(gzip);
				try (Writer writer = new BufferedWriter(
					new OutputStreamWriter(counted, StandardCharsets.UTF_8.newEncoder()
						.onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)),
					32768))
				{
					payload.write(writer);
				}
			}
			if (counted.bytes < COMPRESS_AT_BYTES)
				return null;
			JsonObject envelope = new JsonObject();
			envelope.addProperty("schemaVersion", DeathRecordingV2.LATEST_SCHEMA_VERSION);
			envelope.addProperty("transportEncoding", "gzip-base64-v1");
			envelope.addProperty("uncompressedBytes", counted.bytes);
			envelope.addProperty("payload", Base64.getEncoder().encodeToString(compressed.toByteArray()));
			String result = gson.toJson(envelope);
			return result.length() < counted.bytes ? result : null; // Envelope is entirely ASCII.
		}
		catch (IOException unavailable)
		{
			return null;
		}
	}

	private static boolean latestPayload(String plain)
	{
		try (JsonReader reader = new JsonReader(new StringReader(plain)))
		{
			reader.beginObject();
			while (reader.hasNext())
			{
				if ("schemaVersion".equals(reader.nextName()))
					return DeathRecordingV2.LATEST_SCHEMA_VERSION.equals(reader.nextString());
				reader.skipValue();
			}
		}
		catch (IOException | RuntimeException invalid)
		{
			return false;
		}
		return false;
	}

	private static final class CountingOutputStream extends FilterOutputStream
	{
		long bytes;

		CountingOutputStream(OutputStream out)
		{
			super(out);
		}

		@Override
		public void write(int value) throws IOException
		{
			if (++bytes > MAX_EXPANDED_BYTES)
				throw new IOException("Expanded recording limit");
			out.write(value);
		}

		@Override
		public void write(byte[] values, int offset, int length) throws IOException
		{
			bytes += length;
			if (bytes > MAX_EXPANDED_BYTES)
				throw new IOException("Expanded recording limit");
			out.write(values, offset, length);
		}
	}

	public static String forViewer(TickRecording recording, Gson gson)
	{
		String rich = recording.getRichExportJson();
		if (rich == null || rich.isEmpty())
			return gson.toJson(recording);
		try
		{
			if (!latestPayload(rich))
			{
				try (JsonReader reader = new JsonReader(new StringReader(rich)))
				{
					if (reader.peek() != JsonToken.BEGIN_OBJECT)
						throw new IOException("Invalid recording object");
					reader.skipValue();
					if (reader.peek() != JsonToken.END_DOCUMENT)
						throw new IOException("Trailing export data");
				}
				return rich;
			}
			Completion completed = prepare(recording, gson);
			StringWriter out = new StringWriter(rich.length());
			writeCompleted(recording, gson, completed, out);
			return out.toString();
		}
		catch (IOException | RuntimeException invalid)
		{
			log.warn("Cannot complete rich export; returning the original journal", invalid);
			return gson.toJson(recording);
		}
	}

	private static final class Completion
	{
		JsonArray damage = new JsonArray(), actions = new JsonArray();
		JsonObject summary = new JsonObject();
		JsonElement journal;
		JsonObject enrichment;
		boolean recovered = true, observations;
	}

	private static Completion prepare(TickRecording recording, Gson gson) throws IOException
	{
		Completion completed = new Completion();
		try (JsonReader reader = new JsonReader(new StringReader(recording.getRichExportJson())))
		{
			reader.beginObject();
			while (reader.hasNext())
				switch (reader.nextName())
				{
				case "journalTicks":
					completed.recovered = false;
					reader.skipValue();
					break;
				case "summary":
					completed.summary = gson.fromJson(reader, JsonObject.class);
					if (completed.summary == null)
						throw new IOException("Null summary");
					break;
				case "events":
					reader.beginObject();
					while (reader.hasNext())
						switch (reader.nextName())
						{
						case "damageEvents":
							completed.damage = gson.fromJson(reader, JsonArray.class);
							if (completed.damage == null)
								completed.damage = new JsonArray();
							break;
						case "playerActions":
							completed.actions = gson.fromJson(reader, JsonArray.class);
							if (completed.actions == null)
								completed.actions = new JsonArray();
							break;
						case "observations":
							completed.observations = true;
							reader.skipValue();
							break;
						default:
							reader.skipValue();
						}
					reader.endObject();
					break;
				default:
					reader.skipValue();
				}
			reader.endObject();
			if (reader.peek() != JsonToken.END_DOCUMENT)
				throw new IOException("Trailing export data");
		}
		JsonArray damage = completed.damage, actions = completed.actions;
		Map<String, Integer> knownActions = new HashMap<>(), knownHits = new HashMap<>();
		for (JsonElement value : actions)
			count(knownActions, actionKey(value.getAsJsonObject()));
		for (JsonElement value : damage)
		{
			JsonObject hit = value.getAsJsonObject();
			if ("DEALT".equals(text(hit, "direction")))
				count(knownHits, hitKey(hit));
		}
		Map<String, Integer> seenActions = new HashMap<>(), seenHits = new HashMap<>();
		int totalDealt = 0;
		for (TickData tick : recording.getTicks())
		{
			for (PlayerAction action : tick.getPlayerActions())
			{
				JsonObject row = new JsonObject();
				row.addProperty("tick", tick.getTickNumber());
				row.addProperty("type", action.getType().name());
				row.addProperty("detail", action.getDescription());
				if (action.getTarget() != null)
					row.addProperty("target", action.getTarget());
				if (action.getItemName() != null)
					row.addProperty("itemName", action.getItemName());
				row.addProperty("observationKind", "LEGACY_JOURNAL");
				String key = actionKey(row);
				if (count(seenActions, key) > knownActions.getOrDefault(key, 0))
					actions.add(row);
			}
			for (DamageEvent hit : tick.getDamageEvents())
			{
				if (hit.getDirection() != DamageEvent.DamageDirection.DEALT)
					continue;
				totalDealt += hit.getAmount();
				JsonObject row = new JsonObject();
				row.addProperty("direction", "DEALT");
				row.addProperty("ownership", "LOCAL_PLAYER");
				row.addProperty("impactTick", tick.getTickNumber());
				row.addProperty("launchTick", tick.getTickNumber());
				row.addProperty("launchTickEstimated", true);
				row.addProperty("launchStateEvidence", "UNAVAILABLE");
				row.addProperty("attackerRef", "player");
				row.addProperty("defenderName", hit.getSource());
				row.addProperty("amount", hit.getAmount());
				row.addProperty("damageType",
					hit.getDamageType() == null ? "unknown" : hit.getDamageType().name().toLowerCase(Locale.ROOT));
				row.addProperty("damageTypeEvidence", "LEGACY_JOURNAL");
				row.addProperty("attribution", "UNKNOWN");
				row.addProperty("attributionEvidence", "LEGACY_JOURNAL");
				row.addProperty("determination", "UNKNOWN");
				String key = hitKey(row);
				if (count(seenHits, key) > knownHits.getOrDefault(key, 0))
					damage.add(row);
			}
		}
		completed.journal = gson.toJsonTree(recording.getTicks());
		completed.summary.addProperty("totalDamageDealtInBuffer", totalDealt);
		if (completed.recovered)
		{
			completed.enrichment = new JsonObject();
			completed.enrichment.addProperty("sourceSchemaVersion", DeathRecordingV2.LATEST_SCHEMA_VERSION);
			completed.enrichment.addProperty("journalRecovered", true);
			completed.enrichment.addProperty("historicalObservationsUnavailable", !completed.observations);
		}
		return completed;
	}

	private static void writeCompleted(TickRecording recording, Gson gson, Completion completed, Writer output)
		throws IOException
	{
		try (JsonReader reader = new JsonReader(new StringReader(recording.getRichExportJson())))
		{
			JsonWriter writer = gson.newJsonWriter(output);
			writer.setHtmlSafe(gson.htmlSafe());
			writer.setSerializeNulls(true);
			boolean events = false, journal = false, summary = false, enrichment = false;
			reader.beginObject();
			writer.beginObject();
			while (reader.hasNext())
			{
				String name = reader.nextName();
				writer.name(name);
				switch (name)
				{
				case "events":
					events = true;
					writeEvents(reader, writer, gson, completed);
					break;
				case "journalTicks":
					journal = true;
					reader.skipValue();
					writeValue(writer, gson, completed.journal);
					break;
				case "summary":
					summary = true;
					reader.skipValue();
					writeValue(writer, gson, completed.summary);
					break;
				case "exportEnrichment":
					enrichment = true;
					if (completed.recovered)
					{
						reader.skipValue();
						writeValue(writer, gson, completed.enrichment);
					}
					else
						copy(reader, writer);
					break;
				default:
					copy(reader, writer);
				}
			}
			reader.endObject();
			if (!events)
			{
				writer.name("events").beginObject();
				writer.name("damageEvents");
				writeValue(writer, gson, completed.damage);
				writer.name("playerActions");
				writeValue(writer, gson, completed.actions);
				writer.endObject();
			}
			if (!journal)
			{
				writer.name("journalTicks");
				writeValue(writer, gson, completed.journal);
			}
			if (!summary)
			{
				writer.name("summary");
				writeValue(writer, gson, completed.summary);
			}
			if (completed.recovered && !enrichment)
			{
				writer.name("exportEnrichment");
				writeValue(writer, gson, completed.enrichment);
			}
			writer.endObject();
			writer.flush();
		}
	}

	private static void writeEvents(JsonReader reader, JsonWriter writer, Gson gson, Completion completed)
		throws IOException
	{
		boolean damage = false, actions = false;
		reader.beginObject();
		writer.beginObject();
		while (reader.hasNext())
		{
			String name = reader.nextName();
			writer.name(name);
			if (name.equals("damageEvents"))
			{
				damage = true;
				reader.skipValue();
				writeValue(writer, gson, completed.damage);
			}
			else if (name.equals("playerActions"))
			{
				actions = true;
				reader.skipValue();
				writeValue(writer, gson, completed.actions);
			}
			else
				copy(reader, writer);
		}
		reader.endObject();
		if (!damage)
		{
			writer.name("damageEvents");
			writeValue(writer, gson, completed.damage);
		}
		if (!actions)
		{
			writer.name("playerActions");
			writeValue(writer, gson, completed.actions);
		}
		writer.endObject();
	}

	private static void writeValue(JsonWriter writer, Gson gson, JsonElement value) throws IOException
	{
		gson.getAdapter(JsonElement.class).write(writer, value);
	}

	/** Standard Gson token forwarding keeps number lexemes, nulls and unknown channels. */
	private static void copy(JsonReader reader, JsonWriter writer) throws IOException
	{
		switch (reader.peek())
		{
		case BEGIN_OBJECT:
			reader.beginObject();
			writer.beginObject();
			while (reader.hasNext())
			{
				writer.name(reader.nextName());
				copy(reader, writer);
			}
			reader.endObject();
			writer.endObject();
			break;
		case BEGIN_ARRAY:
			reader.beginArray();
			writer.beginArray();
			while (reader.hasNext())
				copy(reader, writer);
			reader.endArray();
			writer.endArray();
			break;
		case STRING:
			writer.value(reader.nextString());
			break;
		case NUMBER:
			writer.jsonValue(reader.nextString());
			break;
		case BOOLEAN:
			writer.value(reader.nextBoolean());
			break;
		case NULL:
			reader.nextNull();
			writer.nullValue();
			break;
		default:
			throw new IOException("Invalid export token");
		}
	}

	private static String text(JsonObject row, String key)
	{
		return !row.has(key) || row.get(key).isJsonNull() ? "" : row.get(key).getAsString();
	}

	private static String actionKey(JsonObject row)
	{
		return text(row, "tick") + "\t" + text(row, "type") + "\t" + text(row, "detail");
	}

	private static String hitKey(JsonObject row)
	{
		return text(row, "impactTick") + "\t" + text(row, "amount") + "\t"
			+ text(row, "defenderName").replaceAll("<[^>]*>", "").trim();
	}

	private static int count(Map<String, Integer> counts, String key)
	{
		return counts.merge(key, 1, Integer::sum);
	}
}
