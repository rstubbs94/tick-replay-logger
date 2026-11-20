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
import java.nio.charset.*;
import java.util.*;

/** Export-time wire sharing only. Every reference resolves to one complete recorded NPC value. */
final class NpcStateEncoding
{
	private final Gson gson;
	private static final int WIDTH = 8;
	private static final long MINIMUM_BYTES = 1024 * 1024;
	private final List<String> fields;
	private final Set<String> fieldNames;
	private final List<JsonElement> values = new ArrayList<>();
	private final List<int[]> blocks = new ArrayList<>(), rows = new ArrayList<>();
	private final Map<String, Integer> valueIds = new HashMap<>(), blockIds = new HashMap<>(), rowIds = new HashMap<>();
	private long referenceBytes;

	private NpcStateEncoding(NpcState exemplar, Gson gson)
	{
		this.gson = Objects.requireNonNull(gson, "gson");
		// Serialize-null inspection supplies the full DTO field order, including fields absent
		// from early samples. Actual values below use the ordinary export's omission policy.
		fields = new ArrayList<>(
			gson.newBuilder().serializeNulls().create().toJsonTree(exemplar).getAsJsonObject().keySet());
		fieldNames = new HashSet<>(fields);
	}

	static DeathRecordingV2 compact(DeathRecordingV2 original, Gson gson)
	{
		Objects.requireNonNull(gson, "gson");
		List<WorldSnapshot> all = new ArrayList<>();
		collect(original.getTicks(), all);
		long rawBytes = 0;
		NpcState exemplar = null;
		for (WorldSnapshot frame : all)
		{
			if (frame.getNpcs() != null)
			{
				rawBytes += bytes(gson.toJson(frame.getNpcs())) + 8;
				if (exemplar == null)
				{
					for (NpcState npc : frame.getNpcs())
					{
						if (npc != null)
						{
							exemplar = npc;
							break;
						}
					}
				}
			}
		}
		if (exemplar == null || rawBytes < MINIMUM_BYTES)
		{
			return original;
		}
		NpcStateEncoding encoding = new NpcStateEncoding(exemplar, gson);
		if (encoding.fields.size() > 128)
		{
			return original;
		} // Do not emit a table outside the versioned reader contract.
		List<WorldSnapshot> frames = encoding.encode(original.getTicks());
		NpcStateTable table = NpcStateTable.builder().format("shared-blocks-v1").blockWidth(WIDTH)
			.fields(encoding.fields).values(encoding.values).blocks(encoding.blocks).rows(encoding.rows).build();
		// Expensive sharing is admitted only when it reduces this actual wire payload.
		if (bytes(gson.toJson(table)) + 17 + encoding.referenceBytes >= rawBytes)
		{
			return original;
		}
		return original.toBuilder().npcStateTable(table).ticks(frames).build();
	}

	private List<WorldSnapshot> encode(List<WorldSnapshot> input)
	{
		List<WorldSnapshot> output = new ArrayList<>();
		for (WorldSnapshot frame : input)
		{
			WorldSnapshot.WorldSnapshotBuilder copy = frame.toBuilder();
			if (frame.getNpcs() != null)
			{
				List<Integer> refs = new ArrayList<>();
				for (NpcState npc : frame.getNpcs())
				{
					// A null array member is represented explicitly, distinct from an absent channel.
					if (npc == null)
					{
						refs.add(-1);
						continue;
					}
					JsonObject state = gson.toJsonTree(npc).getAsJsonObject();
					if (!fieldNames.containsAll(state.keySet()))
					{
						throw new IllegalStateException("Unexpected NPC serialization fields");
					}
					int[] row = new int[(fields.size() + WIDTH - 1) / WIDTH];
					for (int offset = 0; offset < fields.size(); offset += WIDTH)
					{
						int[] block = new int[Math.min(WIDTH, fields.size() - offset)];
						for (int i = 0; i < block.length; i++)
						{
							JsonElement value = state.get(fields.get(offset + i));
							if (value == null)
							{
								block[i] = -1;
							}
							else
							{
								String key = value.toString();
								Integer id = valueIds.get(key);
								if (id == null)
								{
									id = values.size();
									valueIds.put(key, id);
									values.add(value);
								}
								block[i] = id;
							}
						}
						row[offset / WIDTH] = intern(block, blocks, blockIds);
					}
					refs.add(intern(row, rows, rowIds));
				}
				referenceBytes += bytes(gson.toJson(refs)) + 16;
				copy.npcs(null).npcStateRefs(refs);
			}
			if (frame.getAdditionalViews() != null)
			{
				copy.additionalViews(encode(frame.getAdditionalViews()));
			}
			output.add(copy.build());
		}
		return output;
	}

	private static int intern(int[] value, List<int[]> pool, Map<String, Integer> ids)
	{
		String key = Arrays.toString(value);
		Integer id = ids.get(key);
		if (id == null)
		{
			id = pool.size();
			ids.put(key, id);
			pool.add(value);
		}
		return id;
	}

	private static void collect(List<WorldSnapshot> frames, List<WorldSnapshot> all)
	{
		for (WorldSnapshot frame : frames)
		{
			all.add(frame);
			if (frame.getAdditionalViews() != null)
			{
				collect(frame.getAdditionalViews(), all);
			}
		}
	}

	private static int bytes(String value)
	{
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

}
