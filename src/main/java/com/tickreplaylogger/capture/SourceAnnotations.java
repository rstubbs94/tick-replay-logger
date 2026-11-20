/* Copyright (c) 2026, rstubbs94. All rights reserved.
 * Redistribution and use are permitted under the BSD 2-Clause license distributed with this project.
 * THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
 */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import java.util.*;
import static com.tickreplaylogger.capture.ResolverEvidence.*;

/** Folds already-admitted annotation effects; never matches a reference or qualifies a source. */
final class SourceAnnotations
{
	private SourceAnnotations()
	{
	}

	static JsonObject fold(List<JsonObject> rules, ResolutionCandidate row, boolean referenceHints)
	{
		Map<String, List<JsonObject>> groups = new LinkedHashMap<>();
		for (JsonObject rule : rules)
		{
			groups.computeIfAbsent(text(object(rule.get("merge")), "group"), key -> new ArrayList<>()).add(rule);
		}
		List<List<JsonObject>> ordered = new ArrayList<>(groups.values());
		ordered.sort(Comparator.comparingInt(group -> order(group.get(0), "order")));
		JsonObject result = new JsonObject();
		for (List<JsonObject> group : ordered)
		{
			int priority = group.stream().mapToInt(rule -> order(rule, "priority")).max().orElse(0);
			List<JsonObject> winners = new ArrayList<>();
			for (JsonObject rule : group)
			{
				if (order(rule, "priority") == priority)
				{
					winners.add(object(object(rule.get("effects")).get("annotations")));
				}
			}
			if ("append_unique".equals(text(object(group.get(0).get("merge")), "mode")))
			{
				for (JsonObject annotations : winners)
				{
					for (Map.Entry<String, JsonElement> field : annotations.entrySet())
					{
						append(result, field.getKey(), array(field.getValue()));
					}
				}
			}
			else if (!winners.isEmpty() && winners.stream().allMatch(winners.get(0)::equals))
			{
				for (Map.Entry<String, JsonElement> field : winners.get(0).entrySet())
				{
					result.add(field.getKey(), field.getValue());
				}
			}
		}
		JsonObject reference = row == null ? new JsonObject() : object(row.referenceAnnotations);
		if (row != null && Boolean.TRUE.equals(row.componentQualified))
		{
			if (text(reference, "damageType") != null)
			{
				result.addProperty("damageType", text(reference, "damageType"));
				result.addProperty("damageTypeEvidence", "SOURCE_REFERENCE");
			}
			for (String field : Arrays.asList("mapRef", "attackStyleName"))
			{
				if (text(reference, field) != null)
				{
					result.add(field, reference.get(field));
				}
			}
		}
		JsonArray hints = new JsonArray();
		for (String field : referenceHints && row != null && Boolean.TRUE.equals(row.componentQualified)
			? Arrays.asList("styles", "attackStyles")
			: Collections.<String>emptyList())
		{
			for (JsonElement value : array(reference.get(field)))
			{
				if (value.isJsonPrimitive())
				{
					hints.add(value.getAsString().toLowerCase(Locale.ROOT));
				}
			}
		}
		append(result, "candidateStyles", hints);
		Integer level = row == null ? null
			: (row.identityOverride == null ? row.source.identity() : row.identityOverride).combatLevel;
		if (level != null)
		{
			result.addProperty("attackerCombatLevel", level);
		}
		String type = text(result, "damageType");
		if (type != null && type.contains("|"))
		{
			result.remove("damageType");
		}
		if ("typeless".equalsIgnoreCase(type) || "NONE_REVIEWED".equals(text(result, "prayerEffect")))
		{
			result.remove("determination");
		}
		if (text(result, "determination") == null || "UNKNOWN".equals(text(result, "determination")))
		{
			result.remove("determination");
			result.remove("determinationRuleKey");
		}
		return result;
	}

	private static int order(JsonObject rule, String key)
	{
		Integer value = integer(object(rule.get("merge")).get(key));
		return value == null ? 0 : value;
	}

	private static void append(JsonObject result, String field, JsonArray incoming)
	{
		if (incoming.size() == 0)
		{
			return;
		}
		JsonArray values = new JsonArray();
		Set<JsonElement> seen = new LinkedHashSet<>();
		for (JsonElement value : array(result.get(field)))
		{
			if (seen.add(value))
			{
				values.add(value);
			}
		}
		for (JsonElement value : incoming)
		{
			if ("candidateStyles".equals(field)
				&& (textValue(value) == null || "unknown".equalsIgnoreCase(textValue(value))))
			{
				continue;
			}
			if (seen.add(value))
			{
				values.add(value);
			}
		}
		result.add(field, values);
	}

	private static String textValue(JsonElement value)
	{
		return value instanceof JsonPrimitive && value.getAsJsonPrimitive().isString()
			&& !value.getAsString().trim().isEmpty() ? value.getAsString() : null;
	}
}
