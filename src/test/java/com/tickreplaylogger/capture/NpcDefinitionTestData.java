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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Independently archived primitive cache facts for synthetic API fixtures; never selects a live transform. */
public final class NpcDefinitionTestData
{
	private static final Map<Integer, JsonObject> IDENTITIES = load("npc-definition-identities.json");
	private static final Map<Integer, JsonObject> TRANSFORMS = load("npc-transform-definitions.json");

	private NpcDefinitionTestData()
	{
	}

	private static Map<Integer, JsonObject> load(String name)
	{
		try (InputStreamReader stream = new InputStreamReader(
			Objects.requireNonNull(NpcDefinitionTestData.class.getResourceAsStream("/" + name)),
			StandardCharsets.UTF_8))
		{
			Map<Integer, JsonObject> rows = new LinkedHashMap<>();
			for (JsonElement entry : new Gson().fromJson(stream, JsonObject.class).getAsJsonArray("definitions"))
			{
				JsonObject row = entry.getAsJsonObject();
				rows.put(row.get("npcId").getAsInt(), row);
			}
			return Collections.unmodifiableMap(rows);
		}
		catch (Exception unavailable)
		{
			throw new AssertionError("Source-backed NPC fixture unavailable: " + name, unavailable);
		}
	}

	/** Callers must not mutate the retained reference row. */
	public static JsonObject identity(int id)
	{
		return IDENTITIES.get(id);
	}

	public static Collection<JsonObject> transforms()
	{
		return Collections.unmodifiableCollection(TRANSFORMS.values());
	}

	public static int[] configs(int id)
	{
		JsonObject row = TRANSFORMS.get(id);
		if (row == null)
			return null;
		return java.util.stream.StreamSupport.stream(row.getAsJsonArray("configs").spliterator(), false)
			.mapToInt(JsonElement::getAsInt).toArray();
	}
}
