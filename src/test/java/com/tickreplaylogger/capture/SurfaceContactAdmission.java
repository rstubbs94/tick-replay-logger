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
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
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
import com.tickreplaylogger.model.world.DamageRecord;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Optional hint admission, not a claim that this source exclusively uses ordinary HP outcomes. */
final class SurfaceContactAdmission
{
	private static final Set<Integer> ORDINARY = new HashSet<>(Arrays.asList(12, 13, 16, 17, 43));
	private static final List<String> EXCLUDED_WORLDS = Arrays.asList("SEASONAL", "DEADMAN", "TOURNAMENT", "BETA");

	private SurfaceContactAdmission()
	{
	}

	static boolean declared(DamageMap.ObjectHazardRule rule)
	{
		return rule != null && rule.getContactAdmission() != null;
	}

	static boolean accepts(DamageMap.ObjectHazardRule rule, DamageRecord hit, LegacyCombatCatalog catalog,
		ObservedHpCallbackContext original)
	{
		if (!declared(rule))
			return true;
		JsonObject data = rule.getContactAdmission();
		return Arrays.asList("CONDITIONAL_HP_TILE", "CONDITIONAL_HP_OBJECT_FOOTPRINT")
			.contains(rule.getSurfaceOperator()) && valid(data) && catalog != null && hit != null
			&& "RECEIVED".equals(hit.getDirection()) && "player".equals(hit.getDefenderRef()) && hit.getAmount() >= 0
			&& Boolean.TRUE.equals(hit.getHitsplatTypeKnown()) && ORDINARY.contains(hit.getHitsplatType())
			&& Arrays.asList("HP", "BLOCK").contains(catalog.hitsplatSurface(hit.getHitsplatType()))
			&& catalog.hitsplatDamageType(hit.getHitsplatType()) == null && original.positionMatches(hit);
	}

	static LegacyConstraintOracle.Hypothesis constrain(LegacyConstraintOracle.Hypothesis hypothesis,
		DamageMap.ObjectHazardRule rule, DamageRecord hit, ObservedHpCallbackContext original,
		boolean definitionQualified)
	{
		if (!declared(rule) || !valid(rule.getContactAdmission()) || !definitionQualified
			|| !rule.getContactAdmission().has("perHitMaximum") || !original.positionMatches(hit)
			|| !ObservedHpCallbackContext.normal(original.forHit(hit), Collections.singletonList("LEAGUE_TYPE"),
				EXCLUDED_WORLDS))
			return hypothesis;
		String component = rule.getKey(), family = hypothesis.getCandidate().getFamilyKey();
		if (component == null || family == null)
			return hypothesis;
		List<String> required = Arrays.asList("QUALIFIED_OBJECT_NATIVE_CONTACT", "ORIGINAL_LOCAL_ORDINARY_HP_CALLBACK",
			"NORMAL_WORLD_CONTEXT");
		Map<String, Boolean> observed = new LinkedHashMap<>();
		for (String fact : required)
			observed.put(fact, true);
		return hypothesis.toBuilder().mechanism("ENVIRONMENT_CONTACT").damageComponent(component).worldMode("NORMAL")
			.observations(Collections.unmodifiableMap(observed))
			.constraints(Collections.singletonList(AttributionConstraint.builder()
				.key(component + ":contact-hp-maximum").familyKey(family)
				.maximum(integer(rule.getContactAdmission().get("perHitMaximum"))).qualification("HARD_BOUND")
				.composition("ALTERNATIVE_CASE").numericScope("PER_CALLBACK_MAXIMUM").mechanism("ENVIRONMENT_CONTACT")
				.damageComponent(component).worldMode("NORMAL").requiredObservations(required).build()))
			.build();
	}

	static boolean valid(JsonObject data)
	{
		if (data == null
			|| !data.keySet()
				.equals(data.has("perHitMaximum")
					? set("outcomeDomain", "observedHitsplatTypes", "perHitMaximum", "maximumWitness")
					: set("outcomeDomain", "observedHitsplatTypes"))
			|| !"ORDINARY_HP_OR_BLOCK_CALLBACK_ONLY".equals(string(data.get("outcomeDomain")))
			|| !data.has("observedHitsplatTypes") || !data.get("observedHitsplatTypes").isJsonArray()
			|| data.getAsJsonArray("observedHitsplatTypes").size() != ORDINARY.size())
			return false;
		Set<Integer> types = new HashSet<>();
		for (JsonElement type : data.getAsJsonArray("observedHitsplatTypes"))
			if (!types.add(integer(type)))
				return false;
		if (!types.equals(ORDINARY))
			return false;
		if (!data.has("perHitMaximum"))
			return true;
		Integer maximum = integer(data.get("perHitMaximum"));
		if (maximum == null || maximum < 0 || maximum > 1000000 || !data.get("maximumWitness").isJsonObject())
			return false;
		JsonObject witness = data.getAsJsonObject("maximumWitness");
		Integer start = integer(witness.get("start")), end = integer(witness.get("end"));
		String hash = string(witness.get("pageHash")), page = string(witness.get("page"));
		return witness.keySet().equals(set("page", "pageHash", "start", "end", "text", "quantity")) && page != null
			&& !page.trim().isEmpty() && hash != null && hash.matches("[a-f0-9]{64}") && start != null && start >= 0
			&& end != null && end > start && String.valueOf(maximum).equals(string(witness.get("text")))
			&& "OBJECT_CONTACT_HP_PER_CALLBACK_MAXIMUM".equals(string(witness.get("quantity")));
	}

	private static Set<String> set(String... names)
	{
		return new HashSet<>(Arrays.asList(names));
	}

	private static String string(JsonElement value)
	{
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString()
			: null;
	}

	private static Integer integer(JsonElement value)
	{
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
			return null;
		try
		{
			return value.getAsBigDecimal().intValueExact();
		}
		catch (ArithmeticException | NumberFormatException invalid)
		{
			return null;
		}
	}
}
