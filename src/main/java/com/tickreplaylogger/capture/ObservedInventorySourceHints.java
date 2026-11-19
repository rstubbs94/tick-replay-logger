/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.tickreplaylogger.capture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import net.runelite.api.gameval.VarbitID;

/**
 * Bounded original-callback item facts. The bundled catalog is validated when compiled;
 * this index checks capture fields and never decides causality or a damage limit.
 */
public final class ObservedInventorySourceHints
{
	private static final int MAX_IDS = 4096, MAX_RULES = 4096, SLOTS = 28;
	private static final Set<Integer> POSITIVE_HP = new HashSet<>(
		Arrays.asList(HitsplatID.DAMAGE_ME, HitsplatID.DAMAGE_OTHER, HitsplatID.DAMAGE_MAX_ME));
	private final Map<Integer, List<Rule>> byItem = new HashMap<>();
	private final Set<Integer> itemIds = new HashSet<>(), types = new HashSet<>();
	private boolean unavailable;

	@RequiredArgsConstructor
	private static final class Rule
	{
		final String key, name, sourceRef, pageHash;
		final boolean needsNumericContext;
	}

	/** The authoring compiler validates provenance and reference-policy shape before packaging. */
	public ObservedInventorySourceHints(JsonObject namespace)
	{
		if (namespace == null || namespace.size() == 0)
		{
			return;
		}
		try
		{
			if (!Integer.valueOf(1).equals(number(namespace.get("schemaVersion"))) || namespace.get("rules") == null
				|| !namespace.get("rules").isJsonArray() || namespace.getAsJsonArray("rules").size() > MAX_RULES)
			{
				throw new IllegalArgumentException("Invalid inventory capture bindings");
			}
			Set<String> keys = new HashSet<>();
			for (JsonElement value : namespace.getAsJsonArray("rules"))
			{
				JsonObject row = object(value);
				String key = string(row, "key"), name = string(row, "name"), source = string(row, "sourceRef"),
					hash = string(row, "pageHash");
				List<Integer> ids = numbers(row.get("ids")), outcomes = numbers(row.get("hitsplatTypes"));
				JsonElement numeric = row.get("needsNumericContext");
				if (key == null || name == null || source == null || hash == null || !keys.add(key) || ids == null
					|| ids.isEmpty() || outcomes == null || outcomes.isEmpty() || !POSITIVE_HP.containsAll(outcomes)
					|| !Collections.disjoint(itemIds, ids) || itemIds.size() + ids.size() > MAX_IDS || numeric == null
					|| !numeric.isJsonPrimitive() || !numeric.getAsJsonPrimitive().isBoolean())
				{
					throw new IllegalArgumentException("Invalid inventory capture selector");
				}
				Rule rule = new Rule(key, name, source, hash, numeric.getAsBoolean());
				for (Integer id : ids)
				{
					byItem.put(id, Collections.singletonList(rule));
				}
				itemIds.addAll(ids);
				types.addAll(outcomes);
			}
		}
		catch (RuntimeException invalid)
		{
			unavailable = true;
			byItem.clear();
			itemIds.clear();
			types.clear();
		}
	}

	public boolean accepts(int type, int amount)
	{
		return amount > 0 && POSITIVE_HP.contains(type) && (types.contains(type) || unavailable);
	}

	/** Called only after actual callback receiver==local-player and received/rich settings have been checked. */
	public Map<String, Object> capture(Client client, int tick, Integer cycle)
	{
		Map<String, Object> observed = new LinkedHashMap<>();
		Map<String, String> issues = new LinkedHashMap<>();
		List<Map<String, Object>> slots = new ArrayList<>();
		boolean known = false, needsNumericContext = false;
		try
		{
			ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
			if (container == null)
			{
				issues.put("container", "UNAVAILABLE");
			}
			else
			{
				Item[] items = container.getItems();
				if (items == null)
				{
					issues.put("items", "UNAVAILABLE");
				}
				else
				{
					known = true;
					if (items.length > SLOTS)
					{
						issues.put("items", "TRUNCATED_TO_INVENTORY_CAPACITY");
					}
					for (int slot = 0; slot < Math.min(items.length, SLOTS); slot++)
					{
						Item item = items[slot];
						if (item == null)
						{
							continue;
						} // Public API empty slot.
						Integer id = null, quantity = null;
						try
						{
							id = item.getId();
						}
						catch (RuntimeException failed)
						{
							issues.put("slot." + slot + ".itemId", failed.getClass().getSimpleName());
						}
						try
						{
							quantity = item.getQuantity();
						}
						catch (RuntimeException failed)
						{
							issues.put("slot." + slot + ".quantity", failed.getClass().getSimpleName());
						}
						if (id != null && !itemIds.contains(id))
						{
							continue;
						}
						if (id != null && byItem.getOrDefault(id, Collections.emptyList()).stream()
							.anyMatch(rule -> rule.needsNumericContext))
						{
							needsNumericContext = true;
						}
						Map<String, Object> entry = new LinkedHashMap<>();
						entry.put("slot", slot);
						entry.put("itemId", id);
						entry.put("quantity", quantity);
						entry.put("itemIdKnown", id != null);
						entry.put("quantityKnown", quantity != null);
						slots.add(Collections.unmodifiableMap(entry));
					}
				}
			}
		}
		catch (RuntimeException failed)
		{
			issues.put("container", failed.getClass().getSimpleName());
		}
		observed.put("version", 1);
		observed.put("observationKind", "LOCAL_INVENTORY_PRESENCE_AT_HP_CALLBACK");
		observed.put("status", !known ? "UNAVAILABLE" : issues.isEmpty() ? "OBSERVED" : "PARTIAL");
		observed.put("containerId", InventoryID.INVENTORY.getId());
		observed.put("containerKnown", known);
		observed.put("localRecipientKnown", true);
		observed.put("observedTick", tick);
		observed.put("observedCycle", cycle);
		observed.put("slotPolicy", "REVIEWED_SOURCE_IDS_AND_FAILED_READS");
		observed.put("slotCapacity", SLOTS);
		observed.put("slots", Collections.unmodifiableList(slots));
		observed.put("fieldIssues", Collections.unmodifiableMap(issues));
		if (needsNumericContext)
		{
			observed.put("numericContext", captureNumericContext(client, tick, cycle));
		}
		return Collections.unmodifiableMap(observed);
	}

	/** Copies derived links only; no action, consumption, timer, or HP amount is fabricated. */
	public List<Map<String, Object>> witnesses(Map<String, Object> observation, long sequence)
	{
		if (observation == null)
		{
			return Collections.emptyList();
		}
		List<Map<String, Object>> result = new ArrayList<>();
		if (unavailable || !"OBSERVED".equals(observation.get("status")))
		{
			result.add(Collections.singletonMap("kind", "INVENTORY_SOURCE_CONTEXT_UNAVAILABLE"));
		}
		Object rawSlots = observation.get("slots");
		if (!(rawSlots instanceof List))
		{
			return result;
		}
		Set<String> seen = new HashSet<>();
		int work = 0;
		for (Object rawSlot : (List<?>) rawSlots)
		{
			if (++work > SLOTS)
			{
				result.add(Collections.singletonMap("kind", "INVENTORY_SOURCE_CONTEXT_UNAVAILABLE"));
				break;
			}
			if (!(rawSlot instanceof Map))
			{
				continue;
			}
			Map<?, ?> slot = (Map<?, ?>) rawSlot;
			Integer id = integer(slot.get("itemId")), quantity = integer(slot.get("quantity"));
			if (!Boolean.TRUE.equals(slot.get("itemIdKnown")) || !Boolean.TRUE.equals(slot.get("quantityKnown"))
				|| id == null || quantity == null || quantity <= 0)
			{
				continue;
			}
			for (Rule rule : byItem.getOrDefault(id, Collections.emptyList()))
			{
				if (seen.add(rule.key))
				{
					if (result.size() >= SourceEvidenceResolver.MAX_HYPOTHESES - 1)
					{
						result.add(Collections.singletonMap("kind", "INVENTORY_SOURCE_CONTEXT_UNAVAILABLE"));
						return Collections.unmodifiableList(result);
					}
					Map<String, Object> witness = new LinkedHashMap<>();
					witness.put("kind", "ITEM_POSSESSION_CANDIDATE");
					witness.put("ruleKey", rule.key);
					witness.put("name", rule.name);
					witness.put("sourceRef", rule.sourceRef);
					witness.put("pageHash", rule.pageHash);
					witness.put("itemId", id);
					witness.put("quantity", quantity);
					witness.put("slot", slot.get("slot"));
					witness.put("observedTick", observation.get("observedTick"));
					witness.put("observedCycle", observation.get("observedCycle"));
					witness.put("observationSequence", sequence);
					witness.put("containerKnown", observation.get("containerKnown"));
					witness.put("localRecipientKnown", observation.get("localRecipientKnown"));
					if (observation.containsKey("numericContext"))
					{
						witness.put("numericContext", observation.get("numericContext"));
					}
					result.add(Collections.unmodifiableMap(witness));
				}
			}
		}
		return Collections.unmodifiableList(result);
	}

	/** Minimal static facts; the caller must have established this actual local HP callback. */
	public static Map<String, Object> captureNumericContext(Client client, int tick, Integer cycle)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		Map<String, String> evidence = new LinkedHashMap<>();
		List<String> worldTypes = null;
		Integer league = null;
		try
		{
			Set<WorldType> observed = client.getWorldType();
			if (observed != null)
			{
				worldTypes = new ArrayList<>();
				for (WorldType type : observed)
				{
					worldTypes.add(type.name());
				}
				evidence.put("worldTypes", "OBSERVED_CLIENT_WORLD_TYPES");
			}
		}
		catch (RuntimeException missing)
		{
			evidence.put("worldTypes", "UNAVAILABLE");
		}
		try
		{
			league = client.getVarbitValue(VarbitID.LEAGUE_TYPE);
			evidence.put("LEAGUE_TYPE", "OBSERVED_CLIENT_VARBIT");
		}
		catch (RuntimeException missing)
		{
			evidence.put("LEAGUE_TYPE", "UNAVAILABLE");
		}
		result.put("observationKind", "STATIC_WORLD_AT_HP_CALLBACK");
		result.put("observedTick", tick);
		result.put("observedCycle", cycle);
		result.put("localRecipientKnown", true);
		result.put("worldTypes", worldTypes == null ? null : Collections.unmodifiableList(worldTypes));
		result.put("leagueType", league);
		result.put("evidence", Collections.unmodifiableMap(evidence));
		return Collections.unmodifiableMap(result);
	}

	@SuppressWarnings("unchecked")

	private static Set<String> set(String... values)
	{
		return new HashSet<>(Arrays.asList(values));
	}

	private static String text(Object value)
	{
		return value instanceof String && !((String) value).trim().isEmpty() ? ((String) value).trim() : null;
	}

	private static JsonObject object(JsonElement value)
	{
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
	}

	private static String string(JsonObject value, String key)
	{
		JsonElement element = value.get(key);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
			? text(element.getAsString())
			: null;
	}

	private static Integer number(JsonElement value)
	{
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
			|| !value.getAsString().matches("0|[1-9][0-9]*"))
		{
			return null;
		}
		try
		{
			return Integer.valueOf(value.getAsString());
		}
		catch (NumberFormatException invalid)
		{
			return null;
		}
	}

	private static Integer integer(Object value)
	{
		if (!(value instanceof Number))
		{
			return null;
		}
		try
		{
			return new BigDecimal(value.toString()).intValueExact();
		}
		catch (NumberFormatException | ArithmeticException invalid)
		{
			return null;
		}
	}

	private static List<Integer> numbers(JsonElement value)
	{
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > MAX_IDS)
		{
			return null;
		}
		List<Integer> result = new ArrayList<>();
		for (JsonElement element : value.getAsJsonArray())
		{
			Integer id = number(element);
			if (id == null || result.contains(id))
			{
				return null;
			}
			result.add(id);
		}
		return result;
	}

}
