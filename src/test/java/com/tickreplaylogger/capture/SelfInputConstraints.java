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
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

/** Converts proved per-callback item bounds into existing source constraints; inputs remain hypotheses. */
public final class SelfInputConstraints
{
	private static final int LIMIT = 4096;
	private final Map<Integer, List<Rule>> byItem = new HashMap<>();
	private final LegacyCombatCatalog catalog;
	private boolean unavailable;

	private static final class Rule
	{
		final String key, sourceRef, pageHash;
		final int maximum;
		final List<String> operations, zeros, excludedTypes;

		Rule(JsonObject row)
		{
			JsonObject selector = row.getAsJsonObject("selector"), context = row.getAsJsonObject("contextPredicate");
			key = text(row.get("key"));
			sourceRef = text(selector.get("inputSourceRef"));
			pageHash = text(selector.get("pageHash"));
			maximum = number(row.get("maximum"));
			operations = strings(selector.get("operations"));
			zeros = strings(context.get("requiredZeroVariables"));
			excludedTypes = strings(context.get("excludedWorldTypeFragments"));
		}
	}

	public SelfInputConstraints(JsonObject namespace, Object catalog)
	{
		this.catalog = LegacyCombatCatalog.from(catalog);
		if (namespace == null || namespace.size() == 0)
			return;
		if (!namespace.keySet().equals(set("version", "rules"))
			|| !Integer.valueOf(1).equals(number(namespace.get("version"))) || !namespace.has("rules")
			|| !namespace.get("rules").isJsonArray() || namespace.getAsJsonArray("rules").size() > LIMIT)
		{
			unavailable = true;
			return;
		}
		Set<String> keys = new HashSet<>();
		int indexed = 0;
		for (JsonElement value : namespace.getAsJsonArray("rules"))
		{
			JsonObject row = object(value), selector = object(row.get("selector")),
				context = object(row.get("contextPredicate"));
			List<Integer> ids = numbers(selector.get("ids"));
			Integer maximum = number(row.get("maximum"));
			String key = text(row.get("key")), sourceRef = text(selector.get("inputSourceRef")),
				hash = text(selector.get("pageHash"));
			boolean valid = row.keySet()
				.equals(set("key", "selector", "maximum", "numericScope", "contextPredicate", "provenance"))
				&& selector.keySet().equals(set("kind", "ids", "inputSourceRef", "operations", "pageHash"))
				&& "ITEM".equals(text(selector.get("kind"))) && ids != null && sourceRef != null
				&& sourceRef.startsWith("wiki:") && sourceRef.length() > 5 && hash != null
				&& hash.matches("[0-9a-f]{64}") && strings(selector.get("operations")) != null && key != null
				&& keys.add(key) && maximum != null && maximum >= 0 && maximum <= 1000000
				&& "PER_CALLBACK_MAXIMUM".equals(text(row.get("numericScope")))
				&& context.keySet()
					.equals(set("requiredZeroVariables", "excludedWorldTypeFragments", "requireObservedWorldTypes"))
				&& context.get("requireObservedWorldTypes") != null
				&& context.get("requireObservedWorldTypes").isJsonPrimitive()
				&& context.get("requireObservedWorldTypes").getAsJsonPrimitive().isBoolean()
				&& context.get("requireObservedWorldTypes").getAsBoolean()
				&& strings(context.get("requiredZeroVariables")) != null
				&& strings(context.get("excludedWorldTypeFragments")) != null && row.has("provenance")
				&& row.get("provenance").isJsonObject() && row.getAsJsonObject("provenance").size() > 0
				&& indexed + (ids == null ? 0 : ids.size()) <= LIMIT;
			if (!valid)
			{
				unavailable = true;
				continue;
			}
			Rule rule = new Rule(row);
			indexed += ids.size();
			for (Integer id : ids)
				byItem.computeIfAbsent(id, ignored -> new ArrayList<>()).add(rule);
		}
		byItem.replaceAll((id, rules) -> Collections.unmodifiableList(rules));
	}

	public LegacyConstraintOracle.Hypothesis apply(LegacyConstraintOracle.Hypothesis hypothesis, DamageRecord hit,
		WorldSnapshot impact, Map<String, Object> input, BiPredicate<String, List<String>> qualifiedInput)
	{
		return apply(hypothesis, hit, impact, input, qualifiedInput, Collections.emptyMap());
	}

	public LegacyConstraintOracle.Hypothesis apply(LegacyConstraintOracle.Hypothesis hypothesis, DamageRecord hit,
		WorldSnapshot impact, Map<String, Object> input, BiPredicate<String, List<String>> qualifiedInput,
		Map<String, Object> originalCallbackContext)
	{
		if (unavailable || hypothesis == null || hypothesis.getCandidate() == null || hit == null || input == null
			|| catalog == null || !"SELF_DAMAGE_INPUT_CANDIDATE".equals(input.get("kind"))
			|| !"player".equals(hit.getDefenderRef())
			|| !Arrays.asList("RECEIVED", "RESOURCE_IMPACT").contains(hit.getDirection())
			|| !Boolean.TRUE.equals(hit.getHitsplatTypeKnown()) || hit.getAmount() < 0
			|| hit.getObservationSequence() == null || hit.getObservationSequence() <= 0
			|| !Arrays.asList("HP", "BLOCK").contains(catalog.hitsplatSurface(hit.getHitsplatType()))
			|| qualifiedInput == null || !sameNativeImpact(hit, impact))
			return hypothesis;
		if (hit.getCaptureIssues() != null)
			for (String issue : hit.getCaptureIssues())
				if (issue != null && (issue.startsWith("hitsplatType") || issue.startsWith("hitsplat.type")
					|| issue.startsWith("impact.hitsplatType")))
					return hypothesis;
		Integer id = integer(input.get("itemId"));
		if (id == null)
			return hypothesis;
		String sourceRef = input.get("sourceRef") instanceof String ? (String) input.get("sourceRef") : null;
		String operation = input.get("option") instanceof String ? (String) input.get("option") : null;
		if (sourceRef == null || operation == null
			|| !Objects.equals("input-rule:" + sourceRef, hypothesis.getCandidate().getFamilyKey()))
			return hypothesis;
		List<AttributionConstraint> constraints = hypothesis.getConstraints() == null ? new ArrayList<>()
			: new ArrayList<>(hypothesis.getConstraints());
		boolean added = false;
		for (Rule rule : byItem.getOrDefault(id, Collections.emptyList()))
		{
			if (!rule.sourceRef.equals(sourceRef)
				|| rule.operations.stream().noneMatch(value -> value.equalsIgnoreCase(operation)))
				continue;
			boolean qualified;
			try
			{
				qualified = rule.pageHash.equals(input.get("pageHash"))
					&& qualifiedInput.test(rule.sourceRef, rule.operations)
					&& ObservedHpCallbackContext.normal(originalCallbackContext, rule.zeros, rule.excludedTypes);
			}
			catch (RuntimeException missing)
			{
				qualified = false;
			}
			if (!qualified)
				return hypothesis; // An unestablished alternative cannot become a smaller maximum.
			constraints.add(
				AttributionConstraint.builder().key(rule.key).familyKey("input-rule:" + sourceRef).maximum(rule.maximum)
					.qualification("HARD_BOUND").composition("ALTERNATIVE_CASE").numericScope("PER_CALLBACK_MAXIMUM")
					.mechanism("SELF_INPUT").damageComponent("INPUT_RESPONSE").worldMode("NORMAL")
					.requiredObservations(
						Arrays.asList("QUALIFIED_SELF_INPUT", "NORMAL_WORLD_CONTEXT", "KNOWN_HP_OR_BLOCK_CALLBACK"))
					.build());
			added = true;
		}
		if (!added)
			return hypothesis;
		Map<String, Boolean> observed = hypothesis.getObservations() == null ? new LinkedHashMap<>()
			: new LinkedHashMap<>(hypothesis.getObservations());
		observed.put("QUALIFIED_SELF_INPUT", true);
		observed.put("NORMAL_WORLD_CONTEXT", true);
		observed.put("KNOWN_HP_OR_BLOCK_CALLBACK", true);
		return hypothesis.toBuilder().constraints(Collections.unmodifiableList(constraints))
			.observations(Collections.unmodifiableMap(observed)).mechanism("SELF_INPUT")
			.damageComponent("INPUT_RESPONSE").worldMode("NORMAL").build();
	}

	private static boolean sameNativeImpact(DamageRecord hit, WorldSnapshot frame)
	{
		return frame != null && Boolean.TRUE.equals(frame.getNativeContextKnown()) && frame.getPlayer() != null
			&& frame.getTick() == hit.getImpactTick() && hit.getImpactSceneKey() != null
			&& hit.getImpactWorldViewId() != null && hit.getImpactPlane() != null
			&& Objects.equals(frame.getSceneKey(), hit.getImpactSceneKey())
			&& Objects.equals(frame.getWorldViewId(), hit.getImpactWorldViewId())
			&& frame.getPlane() == hit.getImpactPlane();
	}

	private static Set<String> set(String... values)
	{
		return new HashSet<>(Arrays.asList(values));
	}

	private static JsonObject object(JsonElement value)
	{
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
	}

	private static String text(JsonElement value)
	{
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
			&& !value.getAsString().trim().isEmpty() ? value.getAsString() : null;
	}

	private static Integer number(JsonElement value)
	{
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
			return null;
		try
		{
			return new BigDecimal(value.getAsString()).intValueExact();
		}
		catch (ArithmeticException | NumberFormatException invalid)
		{
			return null;
		}
	}

	private static Integer integer(Object value)
	{
		if (!(value instanceof Number))
			return null;
		try
		{
			return new BigDecimal(value.toString()).intValueExact();
		}
		catch (ArithmeticException | NumberFormatException invalid)
		{
			return null;
		}
	}

	private static List<Integer> numbers(JsonElement value)
	{
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() == 0
			|| value.getAsJsonArray().size() > LIMIT)
			return null;
		List<Integer> values = new ArrayList<>();
		for (JsonElement item : value.getAsJsonArray())
		{
			Integer id = number(item);
			if (id == null || id <= 0 || values.contains(id))
				return null;
			values.add(id);
		}
		return Collections.unmodifiableList(values);
	}

	private static List<String> strings(JsonElement value)
	{
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() == 0
			|| value.getAsJsonArray().size() > LIMIT)
			return null;
		List<String> values = new ArrayList<>();
		for (JsonElement item : value.getAsJsonArray())
		{
			String text = text(item);
			if (text == null || values.contains(text))
				return null;
			values.add(text);
		}
		return Collections.unmodifiableList(values);
	}
}
