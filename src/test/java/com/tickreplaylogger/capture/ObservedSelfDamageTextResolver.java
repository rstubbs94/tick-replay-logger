/*
 * Copyright (c) 2026, rstubbs94
 * All rights reserved.
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
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.SourceResolution.Candidate;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact recorded system responses, never player speech, an accepted-action proof or a generated hit. */
public final class ObservedSelfDamageTextResolver
{
	private static final int MAX_INPUT = 65536, MAX_RULES = 4096;
	private static final Set<String> FIELDS = new HashSet<>(
		Arrays.asList("key", "name", "familyKey", "itemIds", "observationKind", "channels", "literalTexts",
			"temporalRelation", "qualification", "unidentifiedPossible", "recipients", "observedOutcomeDomain",
			"observedHitsplatTypes", "admissionMaximum", "contextPredicate", "provenance"));
	private final Map<String, Rule> literals = new HashMap<>();
	private final Map<Integer, List<Cue>> ticks = new HashMap<>();
	private final LegacyObservedHpCallbackContext hpCallbackContexts;
	private boolean unavailable;

	private static final class Rule
	{
		final String key, name, family;
		final int maximum;
		final List<String> channels, zeroVariables, excludedTypes;
		final List<Integer> hitsplatTypes;

		Rule(JsonObject row)
		{
			key = string(row.get("key"));
			name = string(row.get("name"));
			family = string(row.get("familyKey"));
			maximum = number(row.get("admissionMaximum"));
			channels = strings(row.get("channels"));
			hitsplatTypes = numbers(row.get("observedHitsplatTypes"));
			JsonObject context = row.getAsJsonObject("contextPredicate");
			zeroVariables = strings(context.get("requiredZeroVariables"));
			excludedTypes = strings(context.get("excludedWorldTypeFragments"));
		}
	}

	private static final class Cue
	{
		final Rule rule;
		final ObservedEvent event;

		Cue(Rule rule, ObservedEvent event)
		{
			this.rule = rule;
			this.event = event;
		}
	}

	public ObservedSelfDamageTextResolver(JsonObject namespace, List<ObservedEvent> events)
	{
		this(namespace, events, new LegacyObservedHpCallbackContext(events));
	}

	public ObservedSelfDamageTextResolver(JsonObject namespace, List<ObservedEvent> events,
		LegacyObservedHpCallbackContext hpCallbackContexts)
	{
		this.hpCallbackContexts = hpCallbackContexts;
		if (namespace == null || namespace.size() == 0)
			return;
		if (!namespace.keySet().equals(set("version", "rules"))
			|| !Integer.valueOf(1).equals(number(namespace.get("version"))) || !namespace.has("rules")
			|| !namespace.get("rules").isJsonArray() || namespace.getAsJsonArray("rules").size() > MAX_RULES)
		{
			unavailable = true;
			return;
		}
		Set<String> keys = new HashSet<>();
		for (JsonElement element : namespace.getAsJsonArray("rules"))
		{
			if (!element.isJsonObject())
			{
				unavailable = true;
				continue;
			}
			JsonObject row = element.getAsJsonObject();
			JsonObject predicate = row.has("contextPredicate") && row.get("contextPredicate").isJsonObject()
				? row.getAsJsonObject("contextPredicate")
				: new JsonObject();
			List<String> texts = strings(row.get("literalTexts")), channels = strings(row.get("channels"));
			List<Integer> types = numbers(row.get("observedHitsplatTypes"));
			Integer maximum = number(row.get("admissionMaximum"));
			String key = string(row.get("key"));
			boolean valid = row.keySet().equals(FIELDS) && key != null && keys.add(key)
				&& string(row.get("name")) != null && string(row.get("familyKey")) != null && maximum != null
				&& maximum >= 0 && maximum <= 1000000 && texts != null && channels != null
				&& new HashSet<>(channels).equals(set("GAMEMESSAGE", "SPAM")) && types != null
				&& new HashSet<>(Arrays.asList(12, 13, 16, 17, 43)).containsAll(types)
				&& "SYSTEM_MESSAGE".equals(string(row.get("observationKind")))
				&& "SAME_RECORDED_TICK_CO_OCCURRENCE".equals(string(row.get("temporalRelation")))
				&& "POSSIBLE_SOURCE_HINT".equals(string(row.get("qualification")))
				&& "OBSERVED_LOCAL_PLAYER".equals(string(row.get("recipients")))
				&& "ORDINARY_HP_OR_BLOCK_CALLBACK_ONLY".equals(string(row.get("observedOutcomeDomain")))
				&& Boolean.TRUE.equals(bool(row.get("unidentifiedPossible")))
				&& predicate.keySet()
					.equals(set("requiredZeroVariables", "excludedWorldTypeFragments", "requireObservedWorldTypes"))
				&& Boolean.TRUE.equals(bool(predicate.get("requireObservedWorldTypes")))
				&& strings(predicate.get("requiredZeroVariables")) != null
				&& strings(predicate.get("excludedWorldTypeFragments")) != null && numbers(row.get("itemIds")) != null
				&& row.has("provenance") && row.get("provenance").isJsonObject();
			if (!valid)
			{
				unavailable = true;
				continue;
			}
			Rule rule = new Rule(row);
			for (String text : texts)
			{
				if (literals.containsKey(text))
				{
					unavailable = true;
					literals.remove(text);
				}
				else
					literals.put(text, rule);
			}
		}
		if (literals.isEmpty())
			return;
		if (events == null)
		{
			unavailable = true;
			return;
		}
		int count = 0;
		for (ObservedEvent event : events)
		{
			if (++count > MAX_INPUT)
			{
				unavailable = true;
				break;
			}
			if (event == null || !"SYSTEM_MESSAGE".equals(event.getType()) || event.getData() == null)
				continue;
			Object message = event.getData().get("message");
			if (!(message instanceof String))
				continue;
			// Only display tags are removed. Case, punctuation and spacing remain exact.
			String text = ((String) message).replaceAll("<[^>]*>", "");
			Rule rule = literals.get(text);
			if (rule != null && rule.channels.contains(event.getData().get("channel")))
				ticks.computeIfAbsent(event.getTick(), ignored -> new ArrayList<>()).add(new Cue(rule, event));
		}
	}

	public ObservedActorActivityResolver.Result resolve(DamageRecord hit, WorldSnapshot impact, Object catalog)
	{
		List<Candidate> output = new ArrayList<>();
		if (!ordinaryLocalOutcome(hit, catalog))
			return new ObservedActorActivityResolver.Result(output, false);
		boolean incomplete = unavailable;
		Set<String> families = new HashSet<>();
		for (Cue cue : ticks.getOrDefault(hit.getImpactTick(), Collections.emptyList()))
		{
			Rule rule = cue.rule;
			if (!rule.hitsplatTypes.contains(hit.getHitsplatType()) || hit.getAmount() < 0
				|| hit.getAmount() > rule.maximum)
				continue;
			if (cue.event.getSceneKey() != null && hit.getImpactSceneKey() != null
				&& !cue.event.getSceneKey().equals(hit.getImpactSceneKey()))
				continue;
			if (!qualifiedCue(cue, hit, impact))
			{
				incomplete = true;
				continue;
			}
			if (!families.add(rule.family))
				continue;
			// The compiler enforces name == canonical page. Match SelfDamageInputs' existing
			// wiki:page sourceRef namespace so message and menu evidence describe one source.
			output.add(Candidate.builder().key("self-text:" + rule.key + ":" + cue.event.getSequence()).name(rule.name)
				.familyKey("input-rule:wiki:" + rule.name).sceneKey(impact.getSceneKey())
				.worldViewId(impact.getWorldViewId()).plane(impact.getPlane()).strength("POSSIBLE")
				.evidence(Arrays.asList("SELF_SYSTEM_RESPONSE_RULE:" + rule.key,
					"SAME_RECORDED_TICK_CO_OCCURRENCE;sequence=" + cue.event.getSequence() + ";tick="
						+ hit.getImpactTick(),
					"QUALIFIED_PER_CALLBACK_ADMISSION_MAXIMUM:" + rule.maximum,
					"Exact public system response and HP callback; item possession, accepted action, ordering and cause are not established"))
				.build());
			incomplete = true;
			if (output.size() >= LegacyConstraintOracle.MAX_HYPOTHESES)
				break;
		}
		return new ObservedActorActivityResolver.Result(output, incomplete);
	}

	/** A qualified response can contradict only its matching retained input source, never other causes. */
	public List<String> eligibilityExclusions(DamageRecord hit, WorldSnapshot impact, Candidate candidate,
		Object catalog)
	{
		if (candidate == null || !ordinaryLocalOutcome(hit, catalog) || hit.getAmount() < 0 || impact == null
			|| !Objects.equals(candidate.getSceneKey(), impact.getSceneKey())
			|| !Objects.equals(candidate.getWorldViewId(), impact.getWorldViewId())
			|| !Objects.equals(candidate.getPlane(), impact.getPlane()))
			return Collections.emptyList();
		for (Cue cue : ticks.getOrDefault(hit.getImpactTick(), Collections.emptyList()))
		{
			Rule rule = cue.rule;
			if (Objects.equals(candidate.getFamilyKey(), "input-rule:wiki:" + rule.name)
				&& rule.hitsplatTypes.contains(hit.getHitsplatType()) && hit.getAmount() > rule.maximum
				&& qualifiedCue(cue, hit, impact))
				return Collections.singletonList("SELF_RESPONSE_AMOUNT_EXCEEDED:" + rule.key + ";amount="
					+ hit.getAmount() + ";maximum=" + rule.maximum + ";responseSequence=" + cue.event.getSequence());
		}
		return Collections.emptyList();
	}

	private static boolean ordinaryLocalOutcome(DamageRecord hit, Object referenceData)
	{
		LegacyCombatCatalog catalog=LegacyCombatCatalog.from(referenceData);
		return hit != null && catalog != null && "player".equals(hit.getDefenderRef())
			&& Arrays.asList("RECEIVED", "RESOURCE_IMPACT").contains(hit.getDirection())
			&& Boolean.TRUE.equals(hit.getHitsplatTypeKnown())
			&& Arrays.asList("HP", "BLOCK").contains(catalog.hitsplatSurface(hit.getHitsplatType()))
			&& catalog.hitsplatDamageType(hit.getHitsplatType()) == null;
	}

	private boolean qualifiedCue(Cue cue, DamageRecord hit, WorldSnapshot impact)
	{
		Map<String, Object> data = cue.event.getData();
		return impact != null && Boolean.TRUE.equals(impact.getNativeContextKnown())
			&& impact.getTick() == hit.getImpactTick() && Objects.equals(impact.getSceneKey(), hit.getImpactSceneKey())
			&& Objects.equals(impact.getWorldViewId(), hit.getImpactWorldViewId()) && hit.getImpactPlane() != null
			&& impact.getPlane() == hit.getImpactPlane() && hit.getImpactSceneKey() != null
			&& hit.getImpactSceneKey().equals(cue.event.getSceneKey()) && cue.event.getSequence() > 0
			&& hit.getObservationSequence() != null && hit.getObservationSequence() > 0
			&& Boolean.TRUE.equals(data.get("messagePresent")) && "LOGGED_IN".equals(data.get("callbackGameState"))
			&& data.get("captureIssues") == null && hpCallbackContexts != null && LegacyObservedHpCallbackContext
				.normal(hpCallbackContexts.forHit(hit), cue.rule.zeroVariables, cue.rule.excludedTypes);
	}

	private static Set<String> set(String... values)
	{
		return new HashSet<>(Arrays.asList(values));
	}

	private static String string(JsonElement value)
	{
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
			&& !value.getAsString().isEmpty() ? value.getAsString() : null;
	}

	private static Boolean bool(JsonElement value)
	{
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean()
			: null;
	}

	private static Integer number(JsonElement value)
	{
		try
		{
			return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
				? new BigDecimal(value.getAsString()).intValueExact()
				: null;
		}
		catch (ArithmeticException | NumberFormatException invalid)
		{
			return null;
		}
	}

	private static List<String> strings(JsonElement value)
	{
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() == 0
			|| value.getAsJsonArray().size() > MAX_RULES)
			return null;
		List<String> result = new ArrayList<>();
		for (JsonElement element : value.getAsJsonArray())
		{
			String text = string(element);
			if (text == null || result.contains(text))
				return null;
			result.add(text);
		}
		return Collections.unmodifiableList(result);
	}

	private static List<Integer> numbers(JsonElement value)
	{
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() == 0
			|| value.getAsJsonArray().size() > MAX_RULES)
			return null;
		List<Integer> result = new ArrayList<>();
		for (JsonElement element : value.getAsJsonArray())
		{
			Integer number = number(element);
			if (number == null || number < 0 || result.contains(number))
				return null;
			result.add(number);
		}
		return Collections.unmodifiableList(result);
	}
}
