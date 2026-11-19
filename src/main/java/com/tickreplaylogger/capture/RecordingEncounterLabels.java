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
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.tickreplaylogger.model.TickRecording;
import com.tickreplaylogger.model.world.*;
import java.io.StringReader;
import java.util.*;
import java.util.List;

/** Recorded encounter context for titles. This never changes the fatal source or saved data. */
public final class RecordingEncounterLabels
{
	private RecordingEncounterLabels()
	{
	}

	/** UI callers never parse rich data; background callers prepare and reuse the same cache. */
	public static String title(TickRecording recording, DamageMap map, Gson gson)
	{
		return javax.swing.SwingUtilities.isEventDispatchThread() ? preparedTitle(recording, map)
			: prepareTitle(recording, map, gson);
	}

	private static boolean encounterTitleNeeded(TickRecording recording)
	{
		String original = recording.getKillerName();
		return original != null && !Boolean.TRUE.equals(recording.getCustomLabel())
			&& (original.equals("Unknown") || original.startsWith("Unknown ("))
			&& !"Live Snapshot".equals(recording.getLocation()) && recording.getRichExportJson() != null;
	}

	private static String catalogId(DamageMap map)
	{
		return map == null || map.catalog() == null ? null : map.catalog().id();
	}

	public static boolean needsPreparation(TickRecording recording, DamageMap map)
	{
		if (!encounterTitleNeeded(recording))
		{
			return false;
		}
		String scope = catalogId(map);
		TickRecording.PreparedEncounterTitle cached = recording.preparedEncounterTitle();
		return scope != null && (cached == null || !cached.matches(recording, scope));
	}

	/** Cheap lookup even for an unprepared direct panel caller. */
	public static String preparedTitle(TickRecording recording, DamageMap map)
	{
		TickRecording.PreparedEncounterTitle cached = recording.preparedEncounterTitle();
		return encounterTitleNeeded(recording) && cached != null && cached.matches(recording, catalogId(map))
			? cached.title()
			: recording.getKillerName();
	}

	public static String prepareTitle(TickRecording recording, DamageMap map, Gson gson)
	{
		if (gson == null || !needsPreparation(recording, map))
		{
			return preparedTitle(recording, map);
		}
		if (javax.swing.SwingUtilities.isEventDispatchThread())
		{
			throw new IllegalStateException("Encounter title preparation must run off the EDT");
		}
		TickRecording frozen = recording.toBuilder().build();
		String scope = catalogId(map);
		String encounter = observedEncounter(frozen, map, gson);
		TickRecording.PreparedEncounterTitle cached = new TickRecording.PreparedEncounterTitle(
			frozen.getRichExportJson(), frozen.getKillerName(), frozen.getCustomLabel(), scope,
			encounter == null ? frozen.getKillerName() : encounter);
		if (cached.matches(recording, catalogId(map)))
		{
			recording.rememberEncounterTitle(cached);
		}
		return preparedTitle(recording, map);
	}

	public static String observedEncounter(TickRecording recording, DamageMap map, Gson gson)
	{
		if (javax.swing.SwingUtilities.isEventDispatchThread())
		{
			throw new IllegalStateException("Rich encounter observations must be read off the EDT");
		}
		if (recording == null || gson == null || map == null || map.catalog() == null
			|| recording.getRichExportJson() == null)
		{
			return null;
		}
		List<WorldSnapshot> frames = new ArrayList<>();
		NpcStateTable table = null;
		String version = null, catalogId = null;
		boolean deathObserved = false;
		// Skip geometry and event payloads rather than rehydrating a full rich recording per title.
		try (JsonReader reader = new JsonReader(new StringReader(recording.getRichExportJson())))
		{
			reader.beginObject();
			while (reader.hasNext())
			{
				switch (reader.nextName())
				{
				case "schemaVersion":
					version = reader.nextString();
					break;
				case "meta":
					JsonObject meta = gson.fromJson(reader, JsonObject.class);
					catalogId = meta != null && meta.has("referenceCatalogId")
						? meta.get("referenceCatalogId").getAsString()
						: null;
					deathObserved = meta != null && meta.has("deathTick") && meta.get("deathTick").isJsonPrimitive()
						&& meta.get("deathTick").getAsJsonPrimitive().isNumber()
						&& meta.get("deathTick").getAsString().matches("[0-9]+");
					break;
				case "npcStateTable":
					table = gson.fromJson(reader, NpcStateTable.class);
					break;
				case "ticks":
					readFrames(reader, gson, frames, 0);
					break;
				default:
					reader.skipValue();
				}
			}
			reader.endObject();
			if (reader.peek() != JsonToken.END_DOCUMENT || !deathObserved || !"1.3".equals(version)
				|| !map.catalog().id().equals(catalogId))
			{
				return null;
			}
			NpcStateEncoding.Reader rows = table == null ? null : new NpcStateEncoding.Reader(table, gson);
			Set<String> bosses = new LinkedHashSet<>();
			for (WorldSnapshot frame : frames)
			{
				List<NpcState> npcs = frame.getNpcs();
				if (npcs == null && frame.getNpcStateRefs() != null)
				{
					if (rows == null)
					{
						return null;
					}
					npcs = rows.read(frame);
				}
				if (npcs == null)
				{
					continue;
				}
				for (NpcState npc : npcs)
				{
					String name = map.catalog().observedBossName(npc);
					if (name != null)
					{
						bosses.add(name);
					}
					if (bosses.size() > 1)
					{
						return null;
					}
				}
			}
			return bosses.size() == 1 ? bosses.iterator().next() : null;
		}
		catch (Exception unavailable)
		{
			return null;
		}
	}

	private static void readFrames(JsonReader reader, Gson gson, List<WorldSnapshot> frames, int depth)
		throws java.io.IOException
	{
		if (depth > 8)
		{
			throw new IllegalArgumentException("Nested native frames");
		}
		if (reader.peek() == JsonToken.NULL)
		{
			reader.nextNull();
			return;
		}
		reader.beginArray();
		while (reader.hasNext())
		{
			List<NpcState> npcs = null;
			List<Integer> refs = null;
			reader.beginObject();
			while (reader.hasNext())
			{
				switch (reader.nextName())
				{
				case "npcs":
					if (reader.peek() == JsonToken.NULL)
					{
						reader.nextNull();
						break;
					}
					npcs = new ArrayList<>();
					reader.beginArray();
					while (reader.hasNext())
					{
						npcs.add(gson.fromJson(reader, NpcState.class));
					}
					reader.endArray();
					break;
				case "npcStateRefs":
					if (reader.peek() == JsonToken.NULL)
					{
						reader.nextNull();
						break;
					}
					refs = new ArrayList<>();
					reader.beginArray();
					while (reader.hasNext())
					{
						refs.add(gson.fromJson(reader, Integer.class));
					}
					reader.endArray();
					break;
				case "additionalViews":
					readFrames(reader, gson, frames, depth + 1);
					break;
				default:
					reader.skipValue();
				}
			}
			reader.endObject();
			frames.add(WorldSnapshot.builder().npcs(npcs).npcStateRefs(refs).build());
		}
		reader.endArray();
	}
}
