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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
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

/**
 * Bounded, client-independent interpreter of the declarative resolver-v1 contract.
 * Its inputs are recorded associations, never generated attacks. No cue is consumed by a hit.
 */
public final class ResolverV1
{
	public static final int MAX_CUES = 2048;
	public static final int MAX_RESULTS = 1024;
	private static final List<String> PATTERNS = Arrays.asList("direct_attack", "projectile_impact", "area_impact",
		"contact_hazard", "status_tick", "interaction_damage", "retaliation", "delayed_effect");
	private final Map<String, List<JsonObject>> ordinary = new HashMap<>(), special = new HashMap<>(),
		activities = new HashMap<>(), sourceBounds = new HashMap<>();
	private final Map<String, List<JsonObject>> sourceAssociations = new HashMap<>();
	private final String id;
	private final boolean available;
	private static volatile ResolverV1 bundled;

	public ResolverV1(JsonObject document)
	{
		this(document, document == null ? null : digest(document.toString().getBytes(StandardCharsets.UTF_8)));
	}

	private ResolverV1(JsonObject document, String hash)
	{
		id = hash == null ? "resolver-v1:unavailable" : "resolver-v1:sha256:" + hash;
		available = document != null && Integer.valueOf(1).equals(integer(document.get("schemaVersion")))
			&& "retrospective_damage_resolution".equals(text(document, "purpose"));
		if (available)
		{
			index(document.get("ordinaryProfiles"), ordinary, true);
			index(document.get("specialRules"), special, false);
			index(document.get("activityClassifications"), activities, true);
			index(document.get("sourceBounds"), sourceBounds, true);
			index(document.get("sourceAssociations"), sourceAssociations, true);
		}
	}

	/** Resource parsing is deferred to the existing export worker and cached once. */
	public static ResolverV1 load(Gson gson)
	{
		ResolverV1 value = bundled;
		if (value != null)
			return value;
		synchronized (ResolverV1.class)
		{
			if (bundled != null)
				return bundled;
			try (InputStream input = ResolverV1.class.getResourceAsStream("/com/tickreplaylogger/resolver-v1.json"))
			{
				if (input == null)
					bundled = new ResolverV1(null, null);
				else
				{
					byte[] bytes = input.readAllBytes();
					bundled = new ResolverV1(gson.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class),
						digest(bytes));
				}
			}
			catch (RuntimeException | java.io.IOException invalid)
			{
				bundled = new ResolverV1(null);
			}
			return bundled;
		}
	}

	public boolean isAvailable()
	{
		return available;
	}

	public String id()
	{
		return id;
	}

	public boolean ordinaryOnly(int npcId)
	{
		String key = "npc:" + npcId;
		return ordinary.containsKey(key) && !special.containsKey(key);
	}

	public boolean hasPattern(String kind, int id, String pattern)
	{
		for (JsonObject rule : special.getOrDefault(kind + ":" + id, Collections.emptyList()))
			if (pattern.equals(text(rule, "pattern")))
				return true;
		return false;
	}

	public boolean hasInteractionRule(String kind, int id, String option)
	{
		for (JsonObject rule : special.getOrDefault(kind + ":" + id, Collections.emptyList()))
			if ("interaction_damage".equals(text(rule, "pattern")) && option != null && contains(
				object(rule.get("signals")).get("interactionOptions"), new com.google.gson.JsonPrimitive(option)))
				return true;
		return false;
	}

	/** Separate retaliation actors need an explicit data predicate relating the real trigger target. */
	public List<String> retaliationRules(int sourceId, int targetId, boolean separateActor)
	{
		List<String> ids = new ArrayList<>();
		for (JsonObject rule : special.getOrDefault("npc:" + sourceId, Collections.emptyList()))
		{
			if (!"retaliation".equals(text(rule, "pattern")))
				continue;
			boolean declared = false, matches = true;
			for (JsonElement value : array(rule.get("when")))
			{
				JsonObject condition = object(value);
				if (!"trigger_target_npc_id".equals(text(condition, "fact"))
					|| !"source_event".equals(text(condition, "at")))
					continue;
				declared = true;
				JsonElement target = new com.google.gson.JsonPrimitive(targetId);
				matches &= "equals".equals(text(condition, "op")) ? equal(condition.get("value"), target)
					: "one_of".equals(text(condition, "op")) && contains(condition.get("value"), target);
			}
			if (matches && (!separateActor || declared && sourceId != targetId))
				ids.add(text(rule, "id"));
		}
		return ids;
	}

	private static void index(JsonElement rows, Map<String, List<JsonObject>> index, boolean ordinary)
	{
		if (!(rows instanceof JsonArray) || rows.getAsJsonArray().size() > 10000)
			return;
		for (JsonElement element : rows.getAsJsonArray())
		{
			JsonObject rule = object(element), source = object(rule.get("source"));
			String kind = text(source, "kind");
			if (text(rule, "id") == null || kind == null || !ordinary && !PATTERNS.contains(text(rule, "pattern")))
				continue;
			JsonObject frozen = rule.deepCopy();
			if ("status".equals(kind))
				index.computeIfAbsent("status:" + text(source, "statusFamily"), k -> new ArrayList<>()).add(frozen);
			else
				for (JsonElement item : array(source.get("ids")))
				{
					Integer number = integer(item);
					if (number != null && number >= 0)
						index.computeIfAbsent(kind + ":" + number, k -> new ArrayList<>()).add(frozen);
				}
		}
	}

	/** Shared JSON DTO boundary, also used by the independent replay implementation/conformance cases. */
	public JsonObject resolve(JsonObject input)
	{
		JsonObject output = new JsonObject();
		JsonArray results = new JsonArray();
		output.add("candidates", results);
		output.addProperty("unidentifiedPossible", true);
		JsonObject hit = object(input == null ? null : input.get("hit"));
		if (!available || number(hit.get("amount")) == null || text(hit, "recipientRef") == null)
			return output;
		Set<String> ordinaryCohorts = new HashSet<>();
		Map<String, List<JsonObject>> qualifiedBounds = new HashMap<>();
		Map<String, JsonObject> cueIndex = new HashMap<>();
		int inspected = 0;
		for (JsonElement element : array(input.get("cues")))
		{
			if (++inspected > MAX_CUES)
				break;
			JsonObject cue = object(element);
			String cohort = cohort(cue);
			if (cohort != null && positiveOrdinaryBinding(cue, hit))
				ordinaryCohorts.add(cohort);
			if (text(cue, "key") != null)
				cueIndex.put(text(cue, "key") + "|" + sourceKey(object(cue.get("source"))), cue);
			if (cohort != null)
				for (JsonObject bound : eligibleSourceBounds(cue, hit))
					qualifiedBounds.computeIfAbsent(cohort, k -> new ArrayList<>()).add(bound);
		}
		int count = 0;
		for (JsonElement element : array(input.get("cues")))
		{
			if (++count > MAX_CUES || results.size() >= MAX_RESULTS)
			{
				output.addProperty("truncated", true);
				break;
			}
			JsonObject cue = object(element), source = object(cue.get("source"));
			String pattern = text(cue, "pattern"), key = sourceKey(source);
			if (key == null || !PATTERNS.contains(pattern))
				continue;
			String lookup = "status".equals(text(source, "kind")) ? "status:" + text(source, "statusFamily")
				: text(source, "kind") + ":" + integer(source.get("id"));
			if (Boolean.TRUE.equals(bool(cue.get("nativePresenceObserved"))))
			{
				presenceAssociations(cue, hit, lookup, results);
				continue;
			}
			JsonObject activity = nonDamagingActivity(cue, hit, lookup);
			if (activity != null)
			{
				JsonObject row = row(text(activity, "id"), cue);
				row.addProperty("status", "EXCLUDED");
				row.addProperty("identityQualified", true);
				array(row.get("evidence")).add("ACTIVITY_CLASSIFICATION");
				array(row.get("reasons")).add("NON_DAMAGING_ACTIVITY");
				results.add(row);
				continue;
			}
			boolean bound = false, identified = false;
			boolean positiveOrdinary = positiveOrdinaryBinding(cue, hit);
			if (Arrays.asList("direct_attack", "projectile_impact").contains(pattern))
				for (JsonObject profile : ordinary.getOrDefault(lookup, Collections.emptyList()))
				{
					if (results.size() >= MAX_RESULTS)
						break;
					boolean signalsBound = ordinaryBinding(profile, cue, hit);
					if (positiveOrdinary && !signalsBound)
						continue;
					if (!Boolean.TRUE.equals(bool(cue.get("ordinary"))) && !signalsBound)
						continue;
					JsonObject row = match(profile, cue, hit, true,
						signalsBound || !Boolean.FALSE.equals(bool(cue.get("componentBound"))));
					if (signalsBound)
						array(row.get("evidence")).add("ORDINARY_SIGNAL_BINDING");
					identified |= qualifiedComponent(row);
					results.add(row);
					bound = true;
				}
			List<JsonObject> matching = new ArrayList<>();
			boolean positive = false;
			for (JsonObject rule : special.getOrDefault(lookup, Collections.emptyList()))
				if (pattern.equals(text(rule, "pattern")))
				{
					matching.add(rule);
					positive |= applicableSpecialBinding(rule, cue, hit);
				}
			for (JsonObject rule : matching)
			{
				if (results.size() >= MAX_RESULTS)
					break;
				// An NPC ID/compatible pattern alone does not identify its individual special attack.
				boolean linked = componentBound(rule, cue, hit);
				if (positive && !applicableSpecialBinding(rule, cue, hit))
					continue;
				if (ordinaryCohorts.contains(cohort(cue)) && !declaredSpecialBinding(rule, cue, hit))
					continue;
				JsonObject row = match(rule, cue, hit, false, linked);
				identified |= qualifiedComponent(row);
				results.add(row);
				bound = true;
			}
			if ((!bound || !identified && ownedSourceCue(cue)) && !Boolean.FALSE.equals(bool(cue.get("allowFallback")))
				&& results.size() < MAX_RESULTS)
			{
				JsonObject row = row("observed:" + pattern, cue);
				array(row.get("evidence")).add("COMPONENT_UNKNOWN");
				if (scope(source, hit) == Truth.FALSE)
				{
					if (!Boolean.TRUE.equals(bool(cue.get("apiSourceObserved"))))
						continue;
					array(row.get("evidence")).add("CROSS_NATIVE_SOURCE_IDENTITY_ONLY");
				}
				results.add(row);
			}
		}
		for (JsonElement value : results)
		{
			JsonObject row = object(value);
			String rowKey = text(row, "key"), ruleId = text(row, "ruleId");
			String suffix = ":" + ruleId;
			if (rowKey == null || ruleId == null || !rowKey.endsWith(suffix))
				continue;
			JsonObject cue = cueIndex
				.get(rowKey.substring(0, rowKey.length() - suffix.length()) + "|" + text(row, "sourceKey"));
			if (cue == null)
				continue;
			for (JsonObject bound : qualifiedBounds.getOrDefault(cohort(cue), Collections.emptyList()))
				overlaySourceBound(row, bound, hit);
		}
		return output;
	}

	private void presenceAssociations(JsonObject cue, JsonObject hit, String lookup, JsonArray results)
	{
		JsonObject source = object(cue.get("source"));
		if (!"area_impact".equals(text(cue, "pattern")) || !"npc".equals(text(source, "kind"))
			|| text(source, "actorRef") == null || scope(source, hit) != Truth.TRUE)
		{
			return;
		}
		for (JsonObject association : sourceAssociations.getOrDefault(lookup, Collections.emptyList()))
		{
			if (results.size() >= MAX_RESULTS)
			{
				break;
			}
			Truth identity = identity(object(association.get("source")), source);
			Truth condition = conditions(association.get("when"), cue, hit);
			if (!"verified".equals(text(association, "authority"))
				|| !"observed_native_presence".equals(text(association, "association")) || identity != Truth.TRUE
				|| condition != Truth.TRUE)
			{
				continue;
			}
			JsonObject row = row(text(association, "id"), cue);
			row.addProperty("identityQualified", identity == Truth.TRUE);
			row.addProperty("componentIdentified", false);
			row.addProperty("componentQualified", false);
			array(row.get("evidence")).add("PASSIVE_SOURCE_PRESENCE_ONLY");
			if (association.has("references"))
			{
				row.add("references", association.get("references").deepCopy());
			}
			results.add(row);
		}
	}

	private static boolean qualifiedComponent(JsonObject row)
	{
		return Boolean.TRUE.equals(bool(row.get("identityQualified")))
			&& Boolean.TRUE.equals(bool(row.get("componentIdentified")));
	}

	private List<JsonObject> eligibleSourceBounds(JsonObject cue, JsonObject hit)
	{
		List<JsonObject> eligible = new ArrayList<>();
		JsonObject source = object(cue.get("source"));
		Integer id = integer(source.get("id"));
		JsonObject facts = object(object(cue.get("facts")).get("source_event"));
		if (!ownedSourceCue(cue) || id == null || !"npc".equals(text(source, "kind"))
			|| text(source, "actorRef") == null || Boolean.FALSE.equals(bool(cue.get("numericEligible")))
			|| !sourceObservedBeforeImpact(cue, hit) || text(cue, "targetRef") == null
			|| !Objects.equals(text(cue, "targetRef"), text(hit, "recipientRef")) || scope(source, hit) != Truth.TRUE
			|| !"NORMAL".equals(text(facts, "world_mode"))
			|| !Boolean.FALSE.equals(bool(facts.get("source_model_overrides")))
			|| text(hit, "family") != null && !"ordinary".equals(text(hit, "family"))
			|| integer(hit.get("hitsplatType")) == null)
			return eligible;
		for (JsonObject bound : sourceBounds.getOrDefault("npc:" + id, Collections.emptyList()))
		{
			JsonObject outcome = object(bound.get("outcome")), maximum = object(bound.get("maximum"));
			if (identity(object(bound.get("source")), source) != Truth.TRUE
				|| conditions(bound.get("when"), cue, hit) != Truth.TRUE
				|| !"hp_or_block".equals(text(outcome, "surface"))
				|| !Boolean.TRUE.equals(bool(outcome.get("signatureComplete")))
				|| !"verified".equals(text(outcome, "signatureAuthority"))
				|| !contains(outcome.get("hitsplatTypeIds"), hit.get("hitsplatType"))
				|| !"verified".equals(text(maximum, "authority"))
				|| conditions(maximum.get("when"), cue, hit) != Truth.TRUE)
				continue;
			BigDecimal value = maximum(maximum, cue, hit);
			if (value == null)
				continue;
			JsonObject decision = new JsonObject();
			decision.addProperty("id", text(bound, "id"));
			decision.addProperty("maximum", value);
			eligible.add(decision);
		}
		return eligible;
	}

	private static boolean sourceObservedBeforeImpact(JsonObject cue, JsonObject hit)
	{
		Boolean proof = bool(cue.get("sourceObservedBeforeImpact"));
		if (proof != null)
			return proof;
		BigDecimal sequence = number(cue.get("sequence")), hitSequence = number(hit.get("sequence"));
		return sequence != null && sequence.signum() > 0 && hitSequence != null && sequence.compareTo(hitSequence) < 0;
	}

	private static boolean declaredSpecialBinding(JsonObject rule, JsonObject cue, JsonObject hit)
	{
		if (!applicableSpecialBinding(rule, cue, hit))
			return false;
		return signalsMatch(object(rule.get("signals")), object(cue.get("signals")))
			|| contains(cue.get("ruleIds"), rule.get("id")) || Objects.equals(text(cue, "ruleId"), text(rule, "id"));
	}

	private static boolean applicableSpecialBinding(JsonObject rule, JsonObject cue, JsonObject hit)
	{
		return identity(object(rule.get("source")), object(cue.get("source"))) == Truth.TRUE
			&& conditions(rule.get("when"), cue, hit) == Truth.TRUE && componentBound(rule, cue, hit);
	}

	private static void overlaySourceBound(JsonObject row, JsonObject bound, JsonObject hit)
	{
		BigDecimal maximum = number(bound.get("maximum")), componentMaximum = number(row.get("maximum"));
		if (maximum == null)
			return;
		row.addProperty("maximum", componentMaximum == null ? maximum : componentMaximum.min(maximum));
		row.addProperty("identityQualified", true);
		array(row.get("evidence")).add("SOURCE_BOUND:" + text(bound, "id"));
		if (number(hit.get("amount")).compareTo(maximum) > 0)
		{
			row.addProperty("status", "EXCLUDED");
			row.addProperty("exclusionScope", "ALL_DIRECT_HP_COMPONENTS");
			array(row.get("reasons")).add("SOURCE_MAXIMUM_EXCEEDED");
		}
	}

	/** Verified positive bindings identify the ordinary component even when this actor has specials. */
	public boolean positiveOrdinaryBinding(JsonObject cue, JsonObject hit)
	{
		JsonObject source = object(cue.get("source"));
		Integer sourceId = integer(source.get("id"));
		if (!"npc".equals(text(source, "kind")) || sourceId == null)
			return false;
		for (JsonObject profile : ordinary.getOrDefault("npc:" + sourceId, Collections.emptyList()))
			if (ordinaryBinding(profile, cue, hit))
				return true;
		return false;
	}

	private static boolean ordinaryBinding(JsonObject profile, JsonObject cue, JsonObject hit)
	{
		JsonObject source = object(cue.get("source"));
		return ownedSourceCue(cue) && !Boolean.FALSE.equals(bool(cue.get("componentBound")))
			&& text(source, "actorRef") != null && "verified".equals(text(profile, "signalAuthority"))
			&& identity(object(profile.get("source")), source) == Truth.TRUE && scope(source, hit) == Truth.TRUE
			&& conditions(profile.get("when"), cue, hit) == Truth.TRUE
			&& signalsMatch(object(profile.get("signals")), object(cue.get("signals")));
	}

	private static boolean ownedSourceCue(JsonObject cue)
	{
		String pattern = text(cue, "pattern");
		return "direct_attack".equals(pattern) && Boolean.TRUE.equals(bool(cue.get("actorCallbackObserved")))
			|| "projectile_impact".equals(pattern) && Boolean.TRUE.equals(bool(cue.get("apiSourceObserved")));
	}

	private static String cohort(JsonObject cue)
	{
		String key = text(cue, "cohortKey");
		if (key == null)
			key = text(cue, "key");
		String source = sourceKey(object(cue.get("source")));
		return key == null || source == null ? null : key + "|" + source;
	}

	/** Positive, source-owned classifications reject this observed cue, never an actor's other attacks. */
	private JsonObject nonDamagingActivity(JsonObject cue, JsonObject hit, String lookup)
	{
		// No HP damage does not prove that this control/effect cannot accompany a zero/block callback.
		BigDecimal amount = number(hit.get("amount"));
		if (amount == null || amount.signum() <= 0)
			return null;
		JsonObject source = object(cue.get("source"));
		for (JsonObject rule : activities.getOrDefault(lookup, Collections.emptyList()))
			if ("non_damaging".equals(text(rule, "role")) && "verified".equals(text(rule, "authority"))
				&& identity(object(rule.get("source")), source) == Truth.TRUE && scope(source, hit) == Truth.TRUE
				&& conditions(rule.get("when"), cue, hit) == Truth.TRUE
				&& signalsMatch(object(rule.get("signals")), object(cue.get("signals"))))
				return rule;
		return null;
	}

	/** Also classifies independently observed projectile identities when their actor owner is unknown. */
	public boolean isNonDamagingActivity(JsonObject cue, JsonObject hit)
	{
		JsonObject source = object(cue.get("source"));
		String kind = text(source, "kind");
		Integer sourceId = integer(source.get("id"));
		return kind != null && sourceId != null && nonDamagingActivity(cue, hit, kind + ":" + sourceId) != null;
	}

	private static boolean signalsMatch(JsonObject required, JsonObject observed)
	{
		boolean positive = false;
		for (Map.Entry<String, JsonElement> field : required.entrySet())
		{
			boolean matched = false;
			for (JsonElement actual : array(observed.get(field.getKey())))
				if (contains(field.getValue(), actual))
					matched = true;
			if (!matched)
				return false;
			positive = true;
		}
		return positive;
	}

	/** Joint inspection does not greedily assign/consume a shared observation or manufacture absent hits. */
	public List<JsonObject> resolveBatch(List<JsonObject> inputs)
	{
		List<JsonObject> output = new ArrayList<>();
		Map<String, Integer> uses = new HashMap<>();
		for (JsonObject input : inputs)
		{
			JsonObject result = resolve(input);
			output.add(result);
			for (JsonElement row : array(result.get("candidates")))
				if ("POSSIBLE".equals(text(object(row), "status")))
					uses.merge(text(object(row), "key"), 1, Integer::sum);
		}
		for (JsonObject result : output)
			for (JsonElement value : array(result.get("candidates")))
			{
				JsonObject row = object(value);
				if (uses.getOrDefault(text(row, "key"), 0) > 1)
					array(row.get("evidence")).add("SHARED_OBSERVATION_ASSIGNMENT_UNESTABLISHED");
			}
		return output;
	}

	private static boolean componentBound(JsonObject rule, JsonObject cue, JsonObject hit)
	{
		if (Boolean.FALSE.equals(bool(cue.get("componentBound"))))
			return false;
		if ("contact_hazard".equals(text(rule, "pattern")) && Boolean.TRUE.equals(bool(cue.get("contactObserved"))))
			return true;
		if ("status_tick".equals(text(rule, "pattern")) && "status".equals(text(object(cue.get("source")), "kind")))
			return true;
		JsonObject required = object(rule.get("signals")), observed = object(cue.get("signals"));
		boolean positive = false;
		for (Map.Entry<String, JsonElement> field : required.entrySet())
		{
			boolean matched = false;
			for (JsonElement actual : array(observed.get(field.getKey())))
				if (contains(field.getValue(), actual))
					matched = true;
			// Lists are useful cues, not exhaustive negative signatures.
			if (!matched)
				return false;
			positive = true;
		}
		if (positive)
			return true;
		if (Boolean.TRUE.equals(bool(cue.get("componentBound")))
			&& (cue.has("ruleIds") ? contains(cue.get("ruleIds"), rule.get("id"))
				: text(cue, "ruleId") == null || Objects.equals(text(cue, "ruleId"), text(rule, "id"))))
			return true;
		String kind = text(object(cue.get("source")), "kind");
		return "contact_hazard".equals(text(rule, "pattern"))
			&& Arrays.asList("npc", "object", "graphic").contains(kind)
			&& scope(object(cue.get("source")), hit) == Truth.TRUE
			&& geometry(object(rule.get("geometry")), cue, hit) == Truth.TRUE;
	}

	private JsonObject match(JsonObject rule, JsonObject cue, JsonObject hit, boolean profile, boolean component)
	{
		JsonObject row = row(text(rule, "id"), cue);
		if (profile)
			row.addProperty("ordinary", true);
		if (text(rule, "label") != null)
			row.addProperty("label", text(rule, "label"));
		JsonArray evidence = array(row.get("evidence")), reasons = array(row.get("reasons"));
		if (!component)
			evidence.add("COMPONENT_UNKNOWN");
		JsonObject source = object(cue.get("source"));
		Truth identity = identity(object(rule.get("source")), source), scope = scope(source, hit);
		// Actor ownership can be observed across native views; different coordinates cannot qualify
		// geometry or numeric pruning, but do not invalidate that original projectile source identity.
		if (scope == Truth.FALSE && (profile && "projectile_impact".equals(text(cue, "pattern"))
			|| "recorded_transition".equals(text(rule, "scopePolicy"))))
			scope = Truth.UNKNOWN;
		row.addProperty("identityQualified", identity == Truth.TRUE && scope == Truth.TRUE);
		gate(identity, "IDENTITY", evidence, reasons, true);
		gate(scope, "SCOPE", evidence, reasons, true);
		Truth conditions = conditions(rule.get("when"), cue, hit);
		gate(conditions, "CONDITION", evidence, reasons, true);
		Truth timing = profile ? Truth.TRUE : timing(object(rule.get("timing")), cue, hit);
		gate(timing, "TIMING", evidence, reasons,
			!"reference_only".equals(text(object(rule.get("timing")), "authority")));
		Truth recipients = profile ? recipient("direct_target", cue, hit)
			: recipient(text(rule, "recipients"), cue, hit);
		gate(recipients, "RECIPIENT", evidence, reasons, true);
		JsonObject area = object(rule.get("geometry"));
		Truth geometry = profile ? Truth.TRUE : geometry(area, cue, hit);
		boolean identified = component && identity == Truth.TRUE && scope == Truth.TRUE && conditions == Truth.TRUE;
		row.addProperty("componentIdentified", identified);
		boolean qualified = identified
			&& (timing != Truth.FALSE || "reference_only".equals(text(object(rule.get("timing")), "authority")));
		row.addProperty("componentQualified", qualified);
		gate(geometry, "GEOMETRY", evidence, reasons, qualified && "verified".equals(text(area, "authority")));
		JsonObject outcome = object(rule.get("outcome"));
		if (profile && Arrays.asList("poison", "venom", "burn", "bleed").contains(text(hit, "family")))
			reasons.add("ORDINARY_FAMILY_MISMATCH");
		String style = text(object(object(cue.get("facts")).get("attack")), "attack_style");
		if (profile && style != null && !"unknown".equals(style)
			&& !contains(rule.get("styles"), new com.google.gson.JsonPrimitive(style)))
		{
			evidence.add("ORDINARY_STYLE_INCOMPATIBLE");
			qualified = false;
		}
		if (qualified && !profile && Boolean.TRUE.equals(bool(outcome.get("signatureComplete")))
			&& "verified".equals(text(outcome, "signatureAuthority")))
		{
			Truth signature = Truth.TRUE;
			for (String field : Arrays.asList("damageFamilies", "hitsplatTypeIds"))
				if (outcome.has(field))
				{
					JsonElement value = hit.get("damageFamilies".equals(field) ? "family" : "hitsplatType");
					signature = signature.and(value == null || value.isJsonNull() ? Truth.UNKNOWN
						: contains(outcome.get(field), value) ? Truth.TRUE : Truth.FALSE);
				}
			gate(signature, "SIGNATURE", evidence, reasons, true);
		}
		JsonObject limitOwner = profile ? rule : outcome;
		BigDecimal maximum = null;
		if (qualified && !Boolean.FALSE.equals(bool(cue.get("numericEligible")))
			&& integer(hit.get("hitsplatType")) != null && text(hit, "family") != null && recipients != Truth.FALSE
			&& reasons.size() == 0)
		{
			List<JsonElement> limits = new ArrayList<>();
			if (limitOwner.has("maximum"))
				limits.add(limitOwner.get("maximum"));
			for (JsonElement limit : array(limitOwner.get("additionalMaximums")))
				limits.add(limit);
			for (JsonElement value : limits)
			{
				JsonObject limit = object(value);
				if (!"verified".equals(text(limit, "authority")))
				{
					evidence.add("LIMIT_REFERENCE_ONLY");
					continue;
				}
				Truth applies = conditions(limit.get("when"), cue, hit);
				if (applies != Truth.TRUE)
				{
					evidence.add(applies == Truth.UNKNOWN ? "LIMIT_CONDITION_UNKNOWN" : "LIMIT_NOT_APPLICABLE");
					continue;
				}
				BigDecimal candidate = maximum(limit, cue, hit);
				if (candidate == null)
				{
					evidence.add("LIMIT_INPUT_UNKNOWN");
					continue;
				}
				maximum = maximum == null ? candidate : maximum.min(candidate);
			}
		}
		if (maximum != null)
		{
			row.addProperty("maximum", maximum);
			if (number(hit.get("amount")).compareTo(maximum) > 0)
				reasons.add("MAXIMUM_EXCEEDED");
			else
				evidence.add("WITHIN_COMPONENT_MAXIMUM");
		}
		if (rule.has("calculationTick"))
			evidence.add("CALCULATION_REFERENCE:" + text(rule, "calculationTick"));
		if (rule.has("hitMultiplicity"))
		{
			row.add("hitMultiplicity", rule.get("hitMultiplicity").deepCopy());
			evidence.add("MULTIPLICITY_DOES_NOT_CREATE_HITS");
		}
		if (rule.has("references"))
			row.add("references", rule.get("references").deepCopy());
		if (reasons.size() > 0)
			row.addProperty("status", "EXCLUDED");
		return row;
	}

	private static JsonObject row(String ruleId, JsonObject cue)
	{
		JsonObject row = new JsonObject(), source = object(cue.get("source"));
		row.addProperty("key", String.valueOf(text(cue, "key")) + ":" + ruleId);
		row.addProperty("ruleId", ruleId);
		row.addProperty("sourceKey", sourceKey(source));
		row.addProperty("ordinary", Boolean.TRUE.equals(bool(cue.get("ordinary"))));
		row.addProperty("status", "POSSIBLE");
		row.add("source", source.deepCopy());
		JsonArray evidence = new JsonArray();
		evidence.add("RECORDED_ASSOCIATION_ONLY");
		if (Boolean.TRUE.equals(bool(cue.get("originalNpcContact"))))
			evidence.add("ORIGINAL_NPC_CONTACT_OBSERVATION");
		if (Boolean.TRUE.equals(bool(cue.get("hitFamilyOnly"))))
			evidence.add("OBSERVED_HIT_FAMILY_ONLY");
		if (Boolean.TRUE.equals(bool(cue.get("unstableOriginalSource"))))
			evidence.add("ORIGINAL_API_SOURCE_HISTORY_UNSTABLE");
		String clock = text(cue, "clockEvidence");
		if ("HP_DURING_OBSERVED_FLIGHT".equals(clock) || "CLIENT_END_CYCLE_CORRELATION".equals(clock))
			evidence.add(clock);
		row.add("evidence", evidence);
		row.add("reasons", new JsonArray());
		return row;
	}

	private enum Truth
	{
		TRUE, FALSE, UNKNOWN;

		Truth and(Truth other)
		{
			return this == FALSE || other == FALSE ? FALSE : this == UNKNOWN || other == UNKNOWN ? UNKNOWN : TRUE;
		}
	}

	private static Truth identity(JsonObject selector, JsonObject source)
	{
		Truth result = Truth.TRUE;
		for (String field : Arrays.asList("names", "combatLevels"))
			if (selector.has(field))
			{
				JsonElement actual = source.get("names".equals(field) ? "name" : "combatLevel");
				if (actual == null || actual.isJsonNull())
					result = result.and(Truth.UNKNOWN);
				else if ("names".equals(field))
				{
					boolean found = false;
					for (JsonElement name : array(selector.get(field)))
						if (normalize(name.getAsString()).equals(normalize(actual.getAsString())))
							found = true;
					if (!found)
						return Truth.FALSE;
				}
				else if (!contains(selector.get(field), actual))
					return Truth.FALSE;
			}
		return result;
	}

	private static Truth scope(JsonObject source, JsonObject hit)
	{
		JsonObject a = object(source.get("scope")), b = object(hit.get("scope"));
		Truth result = Truth.TRUE;
		for (String key : Arrays.asList("sceneKey", "worldViewId", "plane"))
		{
			if (!known(a.get(key)) || !known(b.get(key)))
				result = result.and(Truth.UNKNOWN);
			else if (!equal(a.get(key), b.get(key)))
				return Truth.FALSE;
		}
		return result;
	}

	private static Truth conditions(JsonElement values, JsonObject cue, JsonObject hit)
	{
		Truth result = Truth.TRUE;
		for (JsonElement value : array(values))
		{
			JsonObject c = object(value);
			String at = text(c, "at"), fact = text(c, "fact"), op = text(c, "op");
			JsonObject facts = object(object(("impact".equals(at) ? hit : cue).get("facts")).get(at));
			if ("impact".equals(at) && fact != null && fact.startsWith("source_"))
			{
				// Source state belongs to this candidate, never another actor's receiver-wide facts.
				facts = object(object(cue.get("facts")).get("impact"));
			}
			JsonElement actual = facts.get(fact), expected = c.get("value");
			if (!known(actual))
			{
				result = result.and(Truth.UNKNOWN);
				continue;
			}
			boolean matches;
			if ("equals".equals(op))
				matches = equal(actual, expected);
			else if ("one_of".equals(op))
				matches = contains(expected, actual);
			else if ("contains_any".equals(op) || "contains_all".equals(op))
			{
				if (!(actual instanceof JsonArray))
				{
					result = result.and(Truth.UNKNOWN);
					continue;
				}
				matches = "contains_all".equals(op);
				for (JsonElement element : array(expected))
					if ("contains_all".equals(op))
						matches &= contains(actual, element);
					else
						matches |= contains(actual, element);
			}
			else
			{
				result = result.and(Truth.UNKNOWN);
				continue;
			}
			result = result.and(matches ? Truth.TRUE : Truth.FALSE);
		}
		return result;
	}

	private static Truth timing(JsonObject timing, JsonObject cue, JsonObject hit)
	{
		if ("unknown".equals(text(timing, "authority")))
			return Truth.UNKNOWN;
		String field = "client_cycle".equals(text(timing, "clock")) ? "cycle"
			: "game_tick".equals(text(timing, "clock")) ? "tick" : null;
		BigDecimal start = field == null ? null : number(cue.get(field)),
			end = field == null ? null : number(hit.get(field));
		BigDecimal min = number(timing.get("minimumOffset")), max = number(timing.get("maximumOffset"));
		if (start == null || end == null || min == null || max == null || start.signum() < 0 || end.signum() < 0
			|| min.compareTo(max) > 0)
			return Truth.UNKNOWN;
		BigDecimal offset = end.subtract(start);
		return offset.compareTo(min) >= 0 && offset.compareTo(max) <= 0 ? Truth.TRUE : Truth.FALSE;
	}

	private static Truth recipient(String recipients, JsonObject cue, JsonObject hit)
	{
		if ("within_area".equals(recipients))
			return Truth.TRUE;
		String target = text(cue, "targetRef");
		return target == null ? Truth.UNKNOWN : target.equals(text(hit, "recipientRef")) ? Truth.TRUE : Truth.FALSE;
	}

	private static Truth geometry(JsonObject geometry, JsonObject cue, JsonObject hit)
	{
		String shape = text(geometry, "shape"), anchor = text(geometry, "anchor");
		if ("unknown".equals(shape) || shape == null)
			return Truth.UNKNOWN;
		if ("target".equals(shape))
			return recipient("direct_target", cue, hit);
		JsonObject point = object(hit.get("position"));
		Double x = finite(point.get("x")), y = finite(point.get("y"));
		if (x == null || y == null)
			return Truth.UNKNOWN;
		if ("occupied_bounds".equals(shape))
		{
			JsonObject b = object(cue.get("bounds"));
			Double minX = finite(b.get("minX")), minY = finite(b.get("minY")), maxX = finite(b.get("maxX")),
				maxY = finite(b.get("maxY"));
			if (minX == null || minY == null || maxX == null || maxY == null || minX > maxX || minY > maxY
				|| maxX - minX >= 104 || maxY - minY >= 104)
				return Truth.UNKNOWN;
			return truth(x >= minX && x <= maxX && y >= minY && y <= maxY);
		}
		JsonObject origin = object(cue.get("projectile_endpoint".equals(anchor) ? "endpoint" : "position"));
		Double ox = finite(origin.get("x")), oy = finite(origin.get("y"));
		if (ox == null || oy == null)
			return Truth.UNKNOWN;
		double dx = x - ox, dy = y - oy;
		if ("point".equals(shape))
			return truth(dx == 0 && dy == 0);
		if ("box".equals(shape))
		{
			Integer width = integer(geometry.get("widthTiles")), height = integer(geometry.get("heightTiles"));
			if (width == null || height == null || width < 1 || height < 1 || width > 104 || height > 104)
				return Truth.UNKNOWN;
			if ("center".equals(text(geometry, "origin")))
			{
				if (width % 2 == 0 || height % 2 == 0)
					return Truth.UNKNOWN;
				dx += width / 2;
				dy += height / 2;
			}
			else if (!"south_west".equals(text(geometry, "origin")))
				return Truth.UNKNOWN;
			return truth(dx >= 0 && dx < width && dy >= 0 && dy < height);
		}
		if ("radius".equals(shape))
		{
			Double radius = finite(geometry.get("radiusTiles"));
			if (radius == null || radius < 0 || radius > 104)
				return Truth.UNKNOWN;
			String metric = text(geometry, "metric");
			double distance = "chebyshev".equals(metric) ? Math.max(Math.abs(dx), Math.abs(dy))
				: "manhattan".equals(metric) ? Math.abs(dx) + Math.abs(dy)
					: "euclidean".equals(metric) ? Math.hypot(dx, dy) : Double.NaN;
			return Double.isNaN(distance) ? Truth.UNKNOWN : truth(distance <= radius);
		}
		JsonObject direction = object(cue.get("direction"));
		Double vx = finite(direction.get("x")), vy = finite(direction.get("y")),
			length = finite(geometry.get("lengthTiles"));
		if (vx == null || vy == null || length == null || length <= 0 || length > 104 || Math.hypot(vx, vy) == 0)
			return Truth.UNKNOWN;
		double dot = (dx * vx + dy * vy) / Math.hypot(vx, vy), distance = Math.hypot(dx, dy);
		if ("line".equals(shape))
			return truth(dot >= 0 && dot <= length && Math.abs(dx * vy - dy * vx) < 1e-9);
		Double angle = finite(geometry.get("angleDegrees"));
		if (!"cone".equals(shape) || angle == null || angle <= 0 || angle > 360)
			return Truth.UNKNOWN;
		return truth(
			distance <= length && (distance == 0 || dot / distance >= Math.cos(Math.toRadians(angle / 2)) - 1e-9));
	}

	private static BigDecimal maximum(JsonObject limit, JsonObject cue, JsonObject hit)
	{
		BigDecimal value;
		if ("fixed_max".equals(text(limit, "kind")))
			value = number(limit.get("value"));
		else if ("linear_max".equals(text(limit, "kind")))
		{
			String inputName = text(limit, "input");
			JsonElement original = "observed_trigger_damage".equals(inputName)
				? object(object(cue.get("facts")).get("source_event")).get(inputName)
				: object(hit.get("inputs")).get(inputName);
			if (!known(original) && !"observed_trigger_damage".equals(inputName))
				original = object(object(hit.get("facts")).get("impact")).get(inputName);
			BigDecimal input = number(original), numerator = number(limit.get("numerator")),
				denominator = number(limit.get("denominator")), offset = number(limit.get("offset"));
			if (input == null || input.signum() < 0 || numerator == null || numerator.signum() < 0
				|| denominator == null || denominator.signum() <= 0 || offset == null)
				return null;
			value = input.multiply(numerator).divide(denominator, 16, RoundingMode.HALF_EVEN).add(offset);
			if ("floor".equals(text(limit, "rounding")))
				value = value.setScale(0, RoundingMode.FLOOR);
			else if ("ceil".equals(text(limit, "rounding")))
				value = value.setScale(0, RoundingMode.CEILING);
			else if (!"none".equals(text(limit, "rounding")))
				return null;
		}
		else
			return null;
		return value == null || value.signum() < 0 ? null : value;
	}

	private static void gate(Truth value, String code, JsonArray evidence, JsonArray reasons, boolean hard)
	{
		if (value == Truth.UNKNOWN)
			evidence.add(code + "_UNKNOWN");
		else if (value == Truth.FALSE)
		{
			if (hard)
				reasons.add(code + ("CONDITION".equals(code) ? "_FALSE" : "_MISMATCH"));
			else
				evidence.add(code + "_REFERENCE_ONLY");
		}
	}

	public static String sourceKey(JsonObject source)
	{
		String kind = text(source, "kind"), actor = text(source, "actorRef");
		if (kind == null)
			return null;
		String identity = actor != null ? actor
			: "status".equals(kind) ? text(source, "statusFamily") : String.valueOf(integer(source.get("id")));
		if (identity == null || "null".equals(identity))
			return null;
		JsonObject scope = object(source.get("scope"));
		return kind + ":" + identity + ":" + text(scope, "sceneKey") + ":" + integer(scope.get("worldViewId")) + ":"
			+ integer(scope.get("plane"));
	}

	private static Truth truth(boolean value)
	{
		return value ? Truth.TRUE : Truth.FALSE;
	}

	static String normalize(String name)
	{
		return name.replaceAll("(?i)</?col(?:=[0-9a-f]{6})?>", "").trim();
	}

	static JsonObject object(JsonElement value)
	{
		return value instanceof JsonObject ? value.getAsJsonObject() : new JsonObject();
	}

	static JsonArray array(JsonElement value)
	{
		return value instanceof JsonArray ? value.getAsJsonArray() : new JsonArray();
	}

	static String text(JsonObject object, String key)
	{
		JsonElement value = object.get(key);
		return known(value) && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString()
			: null;
	}

	static boolean known(JsonElement value)
	{
		return value != null && !value.isJsonNull();
	}

	static Boolean bool(JsonElement value)
	{
		return known(value) && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean()
			: null;
	}

	static BigDecimal number(JsonElement value)
	{
		try
		{
			if (!known(value) || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
				|| value.getAsString().length() >= 100)
				return null;
			BigDecimal result = value.getAsBigDecimal();
			return Math.abs((long) result.scale()) <= 100 && result.precision() <= 100 ? result : null;
		}
		catch (NumberFormatException invalid)
		{
			return null;
		}
	}

	static Integer integer(JsonElement value)
	{
		try
		{
			BigDecimal n = number(value);
			return n == null ? null : n.intValueExact();
		}
		catch (ArithmeticException invalid)
		{
			return null;
		}
	}

	private static String digest(byte[] bytes)
	{
		try
		{
			byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
			StringBuilder value = new StringBuilder(64);
			for (byte b : hash)
				value.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
			return value.toString();
		}
		catch (java.security.NoSuchAlgorithmException impossible)
		{
			throw new IllegalStateException(impossible);
		}
	}

	private static Double finite(JsonElement value)
	{
		BigDecimal n = number(value);
		if (n == null)
			return null;
		double d = n.doubleValue();
		return Double.isFinite(d) ? d : null;
	}

	private static boolean equal(JsonElement a, JsonElement b)
	{
		BigDecimal na = number(a), nb = number(b);
		return na != null && nb != null ? na.compareTo(nb) == 0 : Objects.equals(a, b);
	}

	private static boolean contains(JsonElement list, JsonElement value)
	{
		for (JsonElement item : array(list))
			if (equal(item, value))
				return true;
		return false;
	}
}
