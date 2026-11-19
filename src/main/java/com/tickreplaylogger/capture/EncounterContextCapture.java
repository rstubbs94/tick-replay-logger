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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.ContextBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

/** Named observed values and source-backed activity gating; selected settings are not presence. */
final class EncounterContextCapture
{
	static ContextBlock capture(Client client, Player player, CombatCatalog catalog, Integer bossHp)
	{
		Map<String, Integer> values = new LinkedHashMap<>();
		Map<String, String> evidence = new LinkedHashMap<>();
		Boolean inInstance = null;
		try
		{
			net.runelite.api.WorldView active = WorldViews.active(client);
			inInstance = active == null ? client.isInInstancedRegion() : active.isInstance();
			evidence.put("inInstance",
				active == null ? "OBSERVED_CLIENT_INSTANCE_FLAG" : "OBSERVED_NATIVE_VIEW_INSTANCE_FLAG");
		}
		catch (RuntimeException unavailable)
		{
			evidence.put("inInstance", "UNAVAILABLE");
		}
		List<String> worldTypes = null;
		try
		{
			java.util.EnumSet<net.runelite.api.WorldType> types = client.getWorldType();
			if (types != null)
			{
				worldTypes = new ArrayList<>();
				for (net.runelite.api.WorldType type : types)
					worldTypes.add(type.name());
				evidence.put("worldTypes", "OBSERVED_CLIENT_WORLD_TYPES");
			}
			else
				evidence.put("worldTypes", "UNAVAILABLE");
		}
		catch (RuntimeException unavailable)
		{
			evidence.put("worldTypes", "UNAVAILABLE");
		}
		JsonObject bindings = catalog == null ? new JsonObject() : catalog.contextBindings();
		JsonObject variables = bindings.has("observedVarbits") ? bindings.getAsJsonObject("observedVarbits")
			: new JsonObject();
		for (Map.Entry<String, JsonElement> entry : variables.entrySet())
		{
			try
			{
				values.put(entry.getKey(), client.getVarbitValue(entry.getValue().getAsInt()));
			}
			catch (RuntimeException unavailable)
			{
				evidence.put(entry.getKey(), "UNAVAILABLE");
			}
		}
		Integer region = null;
		WorldPoint template = null;
		try
		{
			template = WorldViews.template(player);
			if (template != null)
				evidence.put("templateRegionId", "OBSERVED_NATIVE_TEMPLATE_POSITION");
		}
		catch (RuntimeException unavailable)
		{
			evidence.put("templatePosition", "UNAVAILABLE");
		}
		// Rendered coordinates are a template fallback only when non-instanced state was read.
		if (template == null && Boolean.FALSE.equals(inInstance))
		{
			try
			{
				template = player.getWorldLocation();
				if (template != null)
					evidence.put("templateRegionId", "OBSERVED_NONINSTANCED_WORLD_POSITION");
			}
			catch (RuntimeException unavailable)
			{
				evidence.put("worldPosition", "UNAVAILABLE");
			}
		}
		if (template != null)
			region = template.getRegionID();
		else
			evidence.put("templateRegionId", "UNAVAILABLE");
		String activity = null, room = null, mode = null;
		Integer raidLevel = null, teamSize = null, scaledSize = null;
		JsonObject regions = bindings.has("regionActivities") ? bindings.getAsJsonObject("regionActivities")
			: new JsonObject();
		JsonElement regionEntry = region == null ? null : regions.get(String.valueOf(region));
		if (regionEntry != null)
		{
			JsonObject observed = regionEntry.getAsJsonObject();
			if (!observed.has("requireInstance") || !observed.get("requireInstance").getAsBoolean()
				|| Boolean.TRUE.equals(inInstance))
			{
				activity = observed.get("activity").getAsString();
				room = observed.get("room").getAsString();
				evidence.put("activity", "OBSERVED_TEMPLATE_REGION:" + region);
			}
		}
		JsonObject captureRules = object(bindings, "captureRules");
		if (!Integer.valueOf(1).equals(integer(captureRules.get("version"))))
			captureRules = new JsonObject();
		Boolean wilderness = observedBoolean(captureRules, "wilderness", values, evidence);
		if (activity == null)
			for (JsonElement value : rows(captureRules, "activityFallbackRules"))
			{
				if (!value.isJsonObject())
					continue;
				JsonObject rule = value.getAsJsonObject();
				Integer raw = values.get(text(rule, "variable"));
				for (JsonElement c : rows(rule, "cases"))
				{
					if (!c.isJsonObject())
						continue;
					JsonObject observed = c.getAsJsonObject();
					if (contains(rows(observed, "values"), raw))
					{
						activity = text(rule, "activity");
						evidence.put("activity", text(observed, "evidence"));
						break;
					}
				}
				if (activity != null)
					break;
			}
		Map<String, Integer> interpreted = new LinkedHashMap<>();
		for (JsonElement value : rows(captureRules, "scalarRules"))
		{
			if (!value.isJsonObject())
				continue;
			JsonObject rule = value.getAsJsonObject();
			if (!Objects.equals(activity, text(rule, "activity")))
				continue;
			Integer raw = values.get(text(rule, "variable")), minimum = integer(rule.get("minimum"));
			if (raw != null && minimum != null && raw >= minimum)
				interpreted.put(text(rule, "target"), raw);
		}
		for (JsonElement value : rows(captureRules, "partyRules"))
		{
			if (!value.isJsonObject())
				continue;
			JsonObject rule = value.getAsJsonObject();
			if (!Objects.equals(activity, text(rule, "activity")))
				continue;
			Integer base = integer(rule.get("baseCount")), minimum = integer(rule.get("minimum"));
			if (base == null || minimum == null || rows(rule, "variables").size() == 0)
				continue;
			int count = base;
			boolean complete = true;
			for (JsonElement variable : rows(rule, "variables"))
			{
				String symbol = variable.isJsonPrimitive() ? variable.getAsString() : null;
				Integer slot = values.get(symbol);
				if (slot == null)
					complete = false;
				else if (slot != 0)
					count++;
			}
			if (complete && count >= minimum)
			{
				String target = text(rule, "target");
				interpreted.put(target, count);
				evidence.put(target, text(rule, "evidence"));
			}
		}
		raidLevel = interpreted.get("raidLevel");
		teamSize = interpreted.get("teamSize");
		scaledSize = interpreted.get("scaledTeamSize");
		List<ContextBlock.ObservedResource> resources = new ArrayList<>();
		if (bindings.has("resources"))
			for (JsonElement element : bindings.getAsJsonArray("resources"))
			{
				JsonObject rule = element.getAsJsonObject();
				if (!Objects.equals(activity, rule.get("activity").getAsString()))
					continue;
				int id = rule.get("id").getAsInt();
				String variable = catalog.symbol("varbit", id);
				Integer value = values.get(variable);
				if (value == null)
					continue;
				resources.add(ContextBlock.ObservedResource.builder().key(rule.get("key").getAsString())
					.label(rule.get("label").getAsString()).rawValue(value).divisor(rule.get("divisor").getAsInt())
					.maxRaw(rule.has("maxRaw") ? rule.get("maxRaw").getAsInt() : null)
					.unit(rule.get("unit").getAsString()).evidence("OBSERVED_VARBIT:" + id + ":" + variable).build());
			}
		Integer world = null, revision = null;
		try
		{
			world = client.getWorld();
		}
		catch (RuntimeException unavailable)
		{
			evidence.put("worldId", "UNAVAILABLE");
		}
		try
		{
			revision = client.getRevision();
		}
		catch (RuntimeException unavailable)
		{
			evidence.put("gameRevision", "UNAVAILABLE");
		}
		for (JsonElement value : rows(captureRules, "unknownEvidence"))
		{
			if (!value.isJsonObject())
				continue;
			JsonObject rule = value.getAsJsonObject();
			for (JsonElement candidate : rows(rule, "activities"))
				if (candidate.isJsonPrimitive() && Objects.equals(activity, candidate.getAsString()))
					evidence.put(text(rule, "field"), text(rule, "evidence"));
		}
		ContextBlock context = ContextBlock.builder().activity(activity).worldId(world).gameRevision(revision)
			.worldTypes(worldTypes).room(room).templateRegionId(region).raidLevel(raidLevel).invocations(null)
			.challengeMode(mode).teamSize(teamSize).scaledTeamSize(scaledSize).bossPhase(null).bossHpRatio(bossHp)
			.observedVariables(values).evidence(evidence).resources(resources).inInstance(inInstance)
			.inWilderness(wilderness).build();
		return context.toBuilder().modeByRaidLevel(EncounterModeCapture.interpret(context, catalog)).build();
	}

	private static JsonObject object(JsonObject row, String key)
	{
		JsonElement value = row.get(key);
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
	}

	private static JsonArray rows(JsonObject row, String key)
	{
		JsonElement value = row.get(key);
		return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
	}

	private static String text(JsonObject row, String key)
	{
		JsonElement value = row.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString()
			: null;
	}

	private static Integer integer(JsonElement value)
	{
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
			return null;
		try
		{
			return Integer.valueOf(value.getAsString());
		}
		catch (NumberFormatException invalid)
		{
			return null;
		}
	}

	private static boolean contains(JsonArray values, Integer sought)
	{
		if (sought == null)
			return false;
		for (JsonElement value : values)
			if (sought.equals(integer(value)))
				return true;
		return false;
	}

	private static Boolean observedBoolean(JsonObject rules, String target, Map<String, Integer> values,
		Map<String, String> evidence)
	{
		for (JsonElement value : rows(rules, "booleanRules"))
		{
			if (!value.isJsonObject())
				continue;
			JsonObject rule = value.getAsJsonObject();
			if (!Objects.equals(target, text(rule, "target")))
				continue;
			Integer raw = values.get(text(rule, "variable"));
			if (contains(rows(rule, "falseValues"), raw))
				return false;
			if (contains(rows(rule, "trueValues"), raw))
				return true;
			evidence.put(target, raw == null ? "UNAVAILABLE" : "UNRECOGNIZED_VALUE");
		}
		return null;
	}

}
