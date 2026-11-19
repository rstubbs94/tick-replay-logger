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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.ContextBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Classify an observed client level under a pinned reference, never active modifiers or damage. */
final class EncounterModeCapture
{
	static ContextBlock.ModeReference interpret(ContextBlock context, CombatCatalog catalog)
	{
		if (context == null || context.getActivity() == null || catalog == null)
			return null;
		List<JsonObject> rules = new ArrayList<>();
		JsonElement rows = catalog.contextBindings().get("encounterModeRules");
		if (rows != null && rows.isJsonArray())
			for (JsonElement value : rows.getAsJsonArray())
				if (value.isJsonObject() && context.getActivity().equals(str(value.getAsJsonObject(), "activity")))
					rules.add(value.getAsJsonObject());
		if (rules.size() != 1)
			return null;
		JsonObject rule = rules.get(0);
		String symbol = str(rule, "symbol");
		Map<String, Integer> values = context.getObservedVariables();
		Map<String, String> evidence = context.getEvidence();
		Integer raw = values == null ? null : values.get(symbol);
		ContextBlock.ModeReference.ModeReferenceBuilder out = ContextBlock.ModeReference.builder()
			.ruleKey(str(rule, "key")).namespace(str(rule, "namespace")).variableId(rule.get("id").getAsInt())
			.variableSymbol(symbol).sourceHash(str(rule, "sourceHash")).referenceCatalogId(catalog.id()).rawValue(raw)
			.known(false).state("unknown").label("Unavailable");
		if (!CaptureBuild.supportsClientReference(str(rule, "clientApiVersion"), str(rule, "clientArtifactSha256")))
			return out.evidence("UNSUPPORTED_CLIENT_MODE_REFERENCE").build();
		Integer region = context.getTemplateRegionId();
		JsonObject regions = catalog.contextBindings().getAsJsonObject("regionActivities");
		JsonElement room = region == null || regions == null ? null : regions.get(String.valueOf(region));
		if (room == null || !room.isJsonObject()
			|| !context.getActivity().equals(str(room.getAsJsonObject(), "activity")) || evidence == null
			|| !("OBSERVED_TEMPLATE_REGION:" + region).equals(evidence.get("activity")))
			return out.evidence(str(rule, "unverifiedRegionEvidence")).build();
		Integer observed = sample(context, str(rule, "sampleField"));
		if ("UNAVAILABLE".equals(evidence.get(symbol)) || raw == null || observed == null)
			return out.evidence("LEVEL_SAMPLE_UNAVAILABLE").build();
		if (!Objects.equals(raw, observed))
			return out.evidence("CONFLICTING_RAID_LEVEL_SAMPLES").build();
		if (!rule.has("minimumRawValue"))
			return out.evidence("MODE_GUARDS_UNAVAILABLE").build();
		if (raw < rule.get("minimumRawValue").getAsInt())
			return out.evidence("INVALID_RAID_LEVEL").build();
		if (context.getWorldTypes() == null || "UNAVAILABLE".equals(evidence.get("worldTypes")) || values == null
			|| !rule.has("requiredZeroVariables") || !rule.get("requiredZeroVariables").isJsonArray()
			|| !rule.has("excludedWorldTypeFragments") || !rule.get("excludedWorldTypeFragments").isJsonArray())
			return out.evidence("WORLD_CONTEXT_UNAVAILABLE").build();
		for (JsonElement variable : rule.getAsJsonArray("requiredZeroVariables"))
		{
			if (!variable.isJsonPrimitive())
				return out.evidence("WORLD_CONTEXT_UNAVAILABLE").build();
			String name = variable.getAsString();
			Integer value = values.get(name);
			if (value == null || "UNAVAILABLE".equals(evidence.get(name)))
				return out.evidence("WORLD_CONTEXT_UNAVAILABLE").build();
			if (value != 0)
				return out.evidence("SPECIAL_WORLD_CONTEXT").build();
		}
		for (JsonElement excluded : rule.getAsJsonArray("excludedWorldTypeFragments"))
		{
			if (!excluded.isJsonPrimitive())
				return out.evidence("WORLD_CONTEXT_UNAVAILABLE").build();
			if (context.getWorldTypes().stream().anyMatch(name -> name.contains(excluded.getAsString())))
				return out.evidence("SPECIAL_WORLD_CONTEXT").build();
		}
		for (JsonElement value : rule.getAsJsonArray("cases"))
		{
			JsonObject c = value.getAsJsonObject();
			if (raw >= c.get("min").getAsInt() && raw <= c.get("max").getAsInt())
				return out.known(true).state(str(c, "state")).label(str(c, "label")).evidence(str(rule, "evidence"))
					.build();
		}
		return out.evidence("UNCLASSIFIED_RAID_LEVEL").build();
	}

	private static String str(JsonObject row, String key)
	{
		JsonElement value = row.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	private static Integer sample(ContextBlock context, String field)
	{
		if ("raidLevel".equals(field))
			return context.getRaidLevel();
		if ("teamSize".equals(field))
			return context.getTeamSize();
		if ("scaledTeamSize".equals(field))
			return context.getScaledTeamSize();
		return null;
	}
}
