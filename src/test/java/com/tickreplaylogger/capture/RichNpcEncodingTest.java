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

import com.google.gson.*;
import com.tickreplaylogger.model.world.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic rich populations seeded from actual capture; never a live raid population claim. */
public class RichNpcEncodingTest
{
	private final Gson gson = new Gson();

	@Test
	public void injectedAdaptersAreUsedForSharedRowsAndTheirDisplayReader() throws Exception
	{
		// Apply the same adapter to map keys; Gson's default map writer otherwise bypasses it.
		Gson injected = gson.newBuilder().enableComplexMapKeySerialization()
			.registerTypeAdapter(String.class, new TypeAdapter<String>()
			{
				@Override
				public void write(com.google.gson.stream.JsonWriter writer, String value) throws IOException
				{
					writer.value("injected:" + value);
				}

				@Override
				public String read(com.google.gson.stream.JsonReader reader) throws IOException
				{
					String value = reader.nextString();
					if (!value.startsWith("injected:"))
						throw new IOException("Missing injected adapter");
					return value.substring("injected:".length());
				}
			}.nullSafe()).create();
		List<WorldSnapshot> original = population(40, 100);
		JsonElement before = gson.toJsonTree(original);
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(40, injected);
		for (WorldSnapshot frame : original)
			recorder.addSnapshot(frame);
		DeathRecordingV2 export = recorder.buildExport(null, null);
		assertNotNull("Use the actual shared encoding path", export.getNpcStateTable());
		assertTrue(export.getNpcStateTable().getValues().stream().anyMatch(value -> value.isJsonPrimitive()
			&& value.getAsJsonPrimitive().isString() && value.getAsString().startsWith("injected:")));
		NpcStateEncoding.Reader reader = new NpcStateEncoding.Reader(export.getNpcStateTable(), injected);
		for (int index = 0; index < original.size(); index++)
			assertEquals(gson.toJsonTree(original.get(index).getNpcs()),
				gson.toJsonTree(reader.read(export.getTicks().get(index))));
		assertEquals("Encoding must not change captured values", before, gson.toJsonTree(original));
	}

	@Test
	public void richCapturedNpcSnapshotsFitTheUnchangedWireLimitWithoutDroppingFields() throws Exception
	{
		List<WorldSnapshot> originals = population(1000, 100);
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(1000, gson);
		for (WorldSnapshot frame : originals)
			recorder.addSnapshot(frame);
		DeathRecordingV2 export = recorder.buildExport(
			DeathRecordingV2.Meta.builder().recordingId("synthetic-rich-npcs").snapshotTick(1000).build(),
			DeathRecordingV2.Summary.builder().build());
		Path path = Paths.get("build/test-fixtures/rich-npcs.json");
		Files.createDirectories(path.getParent());
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8))
		{
			gson.toJson(export, writer);
		}
		long bytes = Files.size(path);
		Files.write(Paths.get("build/test-fixtures/rich-npc-size.json"),
			("{\"ticks\":1000,\"npcsPerTick\":100,\"bytes\":" + bytes
				+ ",\"byteLimit\":67108864,\"livePopulation\":false}").getBytes(StandardCharsets.UTF_8));
		assertTrue("Actual rich export bytes=" + bytes + " exceeds unchanged64MiB limit", bytes < 67108864L);
		JsonObject root = gson.toJsonTree(export).getAsJsonObject();
		assertEquals("1.3", root.get("schemaVersion").getAsString());
		verifyAllOriginalNpcFields(originals, root);
	}

	@Test
	public void ordinarySmallExportsUseLatestVersionAndRetainTheirOriginalArrays()
	{
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(3, gson);
		List<WorldSnapshot> original = population(3, 1);
		for (WorldSnapshot frame : original)
			recorder.addSnapshot(frame);
		JsonObject root = gson.toJsonTree(recorder.buildExport(null, null)).getAsJsonObject();
		assertEquals("1.3", root.get("schemaVersion").getAsString());
		assertFalse(root.has("npcStateTable"));
		for (int i = 0; i < 3; i++)
			assertEquals(gson.toJsonTree(original.get(i).getNpcs()),
				root.getAsJsonArray("ticks").get(i).getAsJsonObject().get("npcs"));
	}

	@Test
	public void archivedTobReferenceDataAlsoSharesWithoutDroppingAnyOriginalFields() throws Exception
	{
		JsonObject seed;
		try (Reader reader = new InputStreamReader(
			Objects.requireNonNull(getClass().getResourceAsStream("/rich-tob-npc-seed.json")), StandardCharsets.UTF_8))
		{
			seed = gson.fromJson(reader, JsonObject.class);
		}
		assertEquals(seed, gson.toJsonTree(gson.fromJson(seed, NpcState.class)));
		List<WorldSnapshot> originals = population(1000, 100, seed);
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(1000, gson);
		for (WorldSnapshot frame : originals)
			recorder.addSnapshot(frame);
		DeathRecordingV2 export = recorder.buildExport(
			DeathRecordingV2.Meta.builder().recordingId("synthetic-rich-tob-npcs").snapshotTick(1000).build(), null);
		assertEquals("1.3", export.getSchemaVersion());
		Path path = Paths.get("build/test-fixtures/rich-tob-npcs.json");
		Files.createDirectories(path.getParent());
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8))
		{
			gson.toJson(export, writer);
		}
		long[] originalBytes =
		{ 0 };
		try (
			OutputStream zip = new GZIPOutputStream(
				Files.newOutputStream(Paths.get("build/test-fixtures/rich-tob-original.json.gz")));
			Writer writer = new OutputStreamWriter(new FilterOutputStream(zip)
			{
				@Override
				public void write(int value) throws IOException
				{
					out.write(value);
					originalBytes[0]++;
				}

				@Override
				public void write(byte[] b, int offset, int length) throws IOException
				{
					out.write(b, offset, length);
					originalBytes[0] += length;
				}
			}, StandardCharsets.UTF_8))
		{
			gson.toJson(export.toBuilder().npcStateTable(null).ticks(originals).build(), writer);
		}
		Files.write(Paths.get("build/test-fixtures/rich-tob-size.json"),
			("{\"ticks\":1000,\"npcsPerTick\":100,\"inlineBytes\":" + originalBytes[0] + ",\"sharedBytes\":"
				+ Files.size(path) + ",\"byteLimit\":67108864,\"livePopulation\":false}")
				.getBytes(StandardCharsets.UTF_8));
		assertTrue(originalBytes[0] > 67108864L);
		assertTrue(Files.size(path) < 67108864L);
		verifyAllOriginalNpcFields(originals, gson.toJsonTree(export).getAsJsonObject());
	}

	@Test
	public void trimmedNativeFramesRetainFullFieldUnsetsMembershipOrderingAndScope()
	{
		List<WorldSnapshot> frames = population(24, 100);
		for (int index = 0; index < frames.size(); index++)
		{
			WorldSnapshot source = frames.get(index);
			List<NpcState> npcs = new ArrayList<>(source.getNpcs());
			JsonObject changed = gson.toJsonTree(npcs.get(0)).getAsJsonObject();
			for (String field : Arrays.asList("actorRef", "npcId", "animationId", "animationFrame", "healthRatio",
				"definitionStateReference", "normalWorldContextObserved", "modelOverridesPresent", "overheadText",
				"graphicIds"))
				if (index % 2 == 0)
					changed.remove(field);
			if (index % 2 != 0)
			{
				changed.addProperty("actorRef", "replacement:" + index);
				changed.addProperty("overheadText", "Observed <text> \u0000 \ud83d\ude00");
			}
			npcs.set(0, gson.fromJson(changed, NpcState.class));
			Collections.rotate(npcs, index % 7);
			if (index == 12)
				npcs.clear();
			WorldSnapshot child = source.toBuilder().sceneKey("child").worldViewId(8).plane(1).npcs(npcs).build();
			frames.set(index, source.toBuilder().sceneKey(index < 10 ? "first" : "second").plane(index < 10 ? 0 : 1)
				.npcs(npcs).additionalViews(Collections.singletonList(child)).build());
		}
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(24, gson);
		for (WorldSnapshot frame : frames)
			recorder.addSnapshot(frame);
		JsonObject export = gson.toJsonTree(recorder.buildExport(null, null, 8, 17)).getAsJsonObject();
		assertEquals("1.3", export.get("schemaVersion").getAsString());
		verifyAllOriginalNpcFields(frames.subList(7, 17), export);
	}

	private List<WorldSnapshot> population(int ticks, int count)
	{
		NpcState captured = new NpcSnapshotCaptureTest.Fixture().frame(1).getNpcs().get(0);
		return population(ticks, count, gson.toJsonTree(captured).getAsJsonObject());
	}

	private List<WorldSnapshot> population(int ticks, int count, JsonObject seed)
	{
		List<WorldSnapshot> frames = new ArrayList<>();
		for (int tick = 1; tick <= ticks; tick++)
		{
			List<NpcState> npcs = new ArrayList<>();
			for (int index = 0; index < count; index++)
			{
				JsonObject state = seed.deepCopy();
				state.addProperty("actorRef", "synthetic-npc:" + index);
				state.addProperty("sceneId", index);
				state.addProperty("localX", 6400 + (index % 10) * 128 + (tick % 3) * 16);
				state.addProperty("localY", 6400 + (index / 10) * 128);
				state.addProperty("animationFrame", tick % 9);
				state.addProperty("healthRatio", tick % 11);
				if (tick % 13 == 0)
				{
					state.remove("animationId");
					state.remove("healthRatio");
					JsonObject issues = new JsonObject();
					issues.addProperty("animationId", "Synthetic unavailable");
					state.add("observationIssues", issues);
				}
				if (tick % 17 == 0)
					state.add("overheadArchiveIds", new JsonArray());
				npcs.add(gson.fromJson(state, NpcState.class));
			}
			frames.add(WorldSnapshot.builder().tick(tick).sceneKey("synthetic-rich-room").worldViewId(-1).plane(0)
				.player(PlayerState.builder().localX(6400).localY(6400).hp(99).maxHp(99).build()).npcs(npcs)
				.captureCoverage(Collections.singletonMap("npcs", tick % 13 == 0 ? "partial" : "available")).build());
		}
		return frames;
	}

	// Independent contract reconstruction, not an encoder round-trip method.
	private void verifyAllOriginalNpcFields(List<WorldSnapshot> originals, JsonObject export)
	{
		JsonObject table = export.getAsJsonObject("npcStateTable");
		JsonArray fields = table.getAsJsonArray("fields"), values = table.getAsJsonArray("values"),
			blocks = table.getAsJsonArray("blocks"), rows = table.getAsJsonArray("rows");
		assertEquals("shared-blocks-v1", table.get("format").getAsString());
		assertEquals(8, table.get("blockWidth").getAsInt());
		for (int tick = 0; tick < originals.size(); tick++)
		{
			verifyFrame(originals.get(tick), export.getAsJsonArray("ticks").get(tick).getAsJsonObject(), fields, values,
				blocks, rows);
		}
	}

	private void verifyFrame(WorldSnapshot original, JsonObject encoded, JsonArray fields, JsonArray values,
		JsonArray blocks, JsonArray rows)
	{
		JsonArray refs = encoded.getAsJsonArray("npcStateRefs"),
			expected = gson.toJsonTree(original.getNpcs()).getAsJsonArray();
		assertEquals(expected.size(), refs.size());
		for (int actor = 0; actor < refs.size(); actor++)
		{
			JsonArray row = rows.get(refs.get(actor).getAsInt()).getAsJsonArray();
			JsonObject state = new JsonObject();
			for (int field = 0; field < fields.size(); field++)
			{
				JsonArray block = blocks.get(row.get(field / 8).getAsInt()).getAsJsonArray();
				int value = block.get(field % 8).getAsInt();
				if (value != -1)
					state.add(fields.get(field).getAsString(), values.get(value));
			}
			assertEquals("tick " + original.getTick() + " actor " + actor, expected.get(actor), state);
		}
		if (original.getAdditionalViews() != null)
		{
			assertEquals(original.getAdditionalViews().size(), encoded.getAsJsonArray("additionalViews").size());
			for (int i = 0; i < original.getAdditionalViews().size(); i++)
				verifyFrame(original.getAdditionalViews().get(i),
					encoded.getAsJsonArray("additionalViews").get(i).getAsJsonObject(), fields, values, blocks, rows);
		}
	}
}
