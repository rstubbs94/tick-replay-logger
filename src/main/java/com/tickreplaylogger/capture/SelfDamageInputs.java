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
import java.util.*;

/** Input candidates only: no synthetic hits, amount guesses or claims that a click executed. */
public final class SelfDamageInputs
{
	private final List<Input> inputs = new ArrayList<>();

	private static final class Input
	{
		final JsonObject rule;
		final int itemId, tick;
		final long sequence;
		final String option;

		Input(JsonObject rule, int itemId, int tick, long sequence, String option)
		{
			this.rule = rule;
			this.itemId = itemId;
			this.tick = tick;
			this.sequence = sequence;
			this.option = option;
		}
	}

	public void clear()
	{
		inputs.clear();
	}

	public void observeResourceDecrease(CombatCatalog catalog, int weaponId, int tick, long sequence)
	{
		prune(tick);
		if (catalog == null || weaponId <= 0)
		{
			return;
		}
		for (JsonElement element : catalog.selfDamageCombatCandidates())
		{
			JsonObject rule = element.getAsJsonObject();
			boolean matches = false;
			for (JsonElement item : rule.getAsJsonArray("itemIds"))
			{
				if (item.getAsInt() == weaponId)
				{
					matches = true;
				}
			}
			if (matches)
			{
				if (inputs.size() >= 32)
				{
					inputs.remove(0);
				}
				inputs.add(new Input(rule, weaponId, tick, sequence, "Special energy decreased"));
			}
		}
	}

	public void observe(CombatCatalog catalog, int itemId, String option, int tick, long sequence)
	{
		prune(tick);
		if (catalog == null || itemId <= 0)
		{
			return;
		}
		for (JsonElement element : catalog.selfDamageInputs())
		{
			JsonObject rule = element.getAsJsonObject();
			boolean id = false, operation = false;
			JsonElement candidateIds = rule.has("candidateItemIds") ? rule.get("candidateItemIds")
				: rule.get("itemIds");
			if (candidateIds == null || !candidateIds.isJsonArray())
			{
				continue;
			}
			for (JsonElement value : candidateIds.getAsJsonArray())
			{
				if (value.getAsInt() == itemId)
				{
					id = true;
				}
			}
			for (JsonElement value : rule.getAsJsonArray("candidateOperations"))
			{
				if (value.getAsString().equalsIgnoreCase(option))
				{
					operation = true;
				}
			}
			if (id && operation)
			{
				if (inputs.size() >= 32)
				{
					inputs.remove(0);
				}
				inputs.add(new Input(rule, itemId, tick, sequence, option));
			}
		}
	}

	private void prune(int tick)
	{
		inputs.removeIf(i -> tick < i.tick || tick - i.tick > i.rule.get("candidateWindowTicks").getAsInt());
	}

	public List<Map<String, Object>> candidates(int tick)
	{
		prune(tick);
		List<Map<String, Object>> result = new ArrayList<>();
		Set<List<Object>> seen = new HashSet<>();
		for (Input input : inputs)
		{
			String page = input.rule.get("page").getAsString();
			// Different operations can have different source constraints. Preserve each
			// bounded witness; source families merge only after per-witness filtering.
			if (!seen.add(Arrays.asList(page, input.itemId, input.tick, input.sequence, input.option,
				input.rule.get("pageHash").getAsString(), input.rule.has("observationKind"))))
			{
				continue;
			}
			boolean resource = input.rule.has("observationKind");
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("kind", resource ? "SELF_DAMAGE_RESOURCE_CANDIDATE" : "SELF_DAMAGE_INPUT_CANDIDATE");
			row.put("name", input.rule.has("name") ? input.rule.get("name").getAsString() : page);
			row.put("itemId", input.itemId);
			row.put("inputTick", input.tick);
			row.put("inputSequence", input.sequence);
			row.put("option", input.option);
			row.put("evidence", resource ? "SPECIAL_ENERGY_AND_WEAPON_CORRELATION_NOT_EXECUTION_PROOF"
				: "INPUT_CORRELATION_NOT_EXECUTION_PROOF");
			row.put("sourceRef", "wiki:" + page);
			row.put("pageHash", input.rule.get("pageHash").getAsString());
			result.add(row);
		}
		return result;
	}
}
