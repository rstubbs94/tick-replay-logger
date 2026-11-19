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
	private final Map<String, List<JsonObject>> ruleIndex = new HashMap<>();
	private final Map<String, JsonObject> rulesById = new HashMap<>();

	private JsonArray knownHitsplatTypes = new JsonArray();
	private final String id;
	private final boolean available;
	private static volatile ResolverV1 bundled;
	private CombatCatalog references;
	private DamageMap captureBindings;
	private Gson gson;

	public ResolverV1(JsonObject document)
	{
		this(document, document == null ? null : digest(document.toString().getBytes(StandardCharsets.UTF_8)));
	}

	/** Optional qualified reference views for an independently supplied canonical rule document. */
	public ResolverV1(JsonObject document, Gson gson, CombatCatalog references, DamageMap captureBindings)
	{
		this(document);
		this.gson = gson;
		this.references = references;
		this.captureBindings = captureBindings;
	}

	private ResolverV1(JsonObject document, String hash)
	{
		id = hash == null ? "resolver-v1:unavailable" : "resolver-v1:sha256:" + hash;
		available = document != null && Integer.valueOf(1).equals(integer(document.get("schemaVersion")))
			&& "retrospective_damage_resolution".equals(text(document, "purpose"))
			&& document.get("resolutionRules") instanceof JsonArray;
		if (available)
		{
			knownHitsplatTypes = array(document.get("knownHitsplatTypes"));
			for (JsonElement value : array(document.get("resolutionRules")))
			{
				JsonObject rule = object(value), source = object(rule.get("source"));
				String role = text(rule, "role"), name = text(rule, "id"), kind = text(source, "kind");
				if (role == null || name == null)
				{
					continue;
				}
				rulesById.put(name, rule);
				ruleIndex.computeIfAbsent(role + "|*", key -> new ArrayList<>()).add(rule);
				if ("status".equals(kind))
				{
					addRule(role, "status:" + text(source, "statusFamily"), rule);
				}
				else
				{
					for (JsonElement sourceId : array(source.get("ids")))
					{
						if (integer(sourceId) != null && integer(sourceId) >= 0)
						{
							addRule(role, kind + ":" + integer(sourceId), rule);
						}
					}
				}
			}
		}
	}

	private void addRule(String role, String source, JsonObject rule)
	{
		ruleIndex.computeIfAbsent(role + "|" + source, key -> new ArrayList<>()).add(rule);
	}

	private List<JsonObject> rules(String role, String source)
	{
		return ruleIndex.getOrDefault(role + "|" + source, Collections.emptyList());
	}

	/** Resource parsing is deferred to the existing export worker and cached once. */
	public static ResolverV1 load(Gson gson)
	{
		ResolverV1 value = bundled;
		if (value != null)
		{
			return value;
		}
		synchronized (ResolverV1.class)
		{
			if (bundled != null)
			{
				return bundled;
			}
			JsonObject document = RuntimeCatalog.load(gson).section("resolver");
			CombatCatalog references = CombatCatalog.referenceView(RuntimeCatalog.load(gson).section("references"));
			ResolverV1 loaded = new ResolverV1(document.size() == 0 ? null : document, gson, references,
				DamageMap.load(gson, references));
			bundled = loaded;
			return loaded;
		}
	}

	/** Read-only canonical declarations; enumeration does not establish an observed source. */
	public List<JsonObject> evidenceRules(String mode)
	{
		List<JsonObject> found = new ArrayList<>();
		for (JsonObject rule : rules("evidence", "*"))
		{
			if (Objects.equals(mode, text(rule, "evidenceMode")))
			{
				found.add(rule);
			}
		}
		return Collections.unmodifiableList(found);
	}

	public boolean hasEvidenceSource(String mode, String kind, Integer id)
	{
		if (id != null)
		{
			for (JsonObject rule : rules("evidence", kind + ":" + id))
			{
				if (Objects.equals(mode, text(rule, "evidenceMode")))
				{
					return true;
				}
			}
		}
		return false;
	}

	public JsonObject matchedRule(String ruleId)
	{
		return rulesById.get(ruleId);
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
		boolean profile = false;
		for (JsonObject rule : rules("component", key))
		{
			if (!Boolean.TRUE.equals(bool(rule.get("profile"))))
			{
				return false;
			}
			profile = true;
		}
		return profile;
	}

	public boolean hasPattern(String kind, int id, String pattern)
	{
		for (JsonObject rule : specials(kind + ":" + id))
		{
			if (pattern.equals(text(rule, "pattern")))
			{
				return true;
			}
		}
		return false;
	}

	public boolean hasInteractionRule(String kind, int id, String option)
	{
		for (JsonObject rule : specials(kind + ":" + id))
		{
			if ("interaction_damage".equals(text(rule, "pattern")) && option != null && contains(
				object(rule.get("signals")).get("interactionOptions"), new com.google.gson.JsonPrimitive(option)))
			{
				return true;
			}
		}
		return false;
	}

	/** Separate retaliation actors need an explicit data predicate relating the real trigger target. */
	public List<String> retaliationRules(int sourceId, int targetId, boolean separateActor)
	{
		List<String> ids = new ArrayList<>();
		for (JsonObject rule : specials("npc:" + sourceId))
		{
			if (!"retaliation".equals(text(rule, "pattern")))
			{
				continue;
			}
			boolean declared = false, matches = true;
			for (JsonElement value : array(rule.get("when")))
			{
				JsonObject condition = object(value);
				if (!"trigger_target_npc_id".equals(text(condition, "fact"))
					|| !"source_event".equals(text(condition, "at")))
				{
					continue;
				}
				declared = true;
				JsonElement target = new com.google.gson.JsonPrimitive(targetId);
				matches &= "equals".equals(text(condition, "op")) ? equal(condition.get("value"), target)
					: "one_of".equals(text(condition, "op")) && contains(condition.get("value"), target);
			}
			if (matches && (!separateActor || declared && sourceId != targetId))
			{
				ids.add(text(rule, "id"));
			}
		}
		return ids;
	}

	private List<JsonObject> profiles(String source)
	{
		return components(source, true);
	}

	private List<JsonObject> specials(String source)
	{
		return components(source, false);
	}

	private List<JsonObject> components(String source, boolean profile)
	{
		List<JsonObject> result = new ArrayList<>();
		for (JsonObject rule : rules("component", source))
		{
			if (profile == Boolean.TRUE.equals(bool(rule.get("profile"))))
			{
				result.add(rule);
			}
		}
		return result;
	}

	/** Shared JSON DTO boundary, also used by the independent replay implementation/conformance cases. */
	public ResolutionResult evaluate(JsonObject input)
	{
		ResolutionResult output = new ResolutionResult();
		List<ResolutionCandidate> results = output.candidates;
		JsonObject hit = new JsonObject();
		for (Map.Entry<String, JsonElement> field : object(input == null ? null : input.get("hit")).entrySet())
		{
			hit.add(field.getKey(), field.getValue());
		}
		hit.remove("originalPositionProof");
		hit.addProperty("originalPositionProof", originalHpPosition(hit));
		output.hitAnnotations = annotations("hit", new JsonObject(), null, hit);
		if (!available || number(hit.get("amount")) == null || text(hit, "recipientRef") == null)
		{
			return output;
		}
		Set<String> ordinaryCohorts = new HashSet<>();
		Map<String, List<JsonObject>> qualifiedBounds = new HashMap<>();

		Map<String, JsonObject> limitDecisions = new HashMap<>();
		Map<String, String> itemLabels = inputLabels(input, hit);
		Map<String, JsonObject> receipts = inputReceipts(input, hit);
		List<JsonObject> normalized = new ArrayList<>();
		JsonArray cueViews = expandChannels(array(input.get("cues")));
		int inspected = 0;
		for (JsonElement element : cueViews)
		{
			if (++inspected > MAX_CUES)
			{
				break;
			}
			JsonObject original = inputCue(object(element), hit, itemLabels, receipts);
			if (sourceKey(object(original.get("source"))) != null
				&& !Boolean.TRUE.equals(bool(original.get("hitFamilyOnly"))) && nativeConflict(original, hit))
			{
				List<JsonObject> declared = new ArrayList<>();
				JsonObject source = object(original.get("source"));
				String lookup = text(source, "kind") + ":" + integer(source.get("id")),
					pattern = text(original, "pattern");
				if (!Boolean.TRUE.equals(bool(original.get("nativePresenceObserved")))
					&& Arrays.asList("direct_attack", "projectile_impact").contains(pattern))
				{
					declared.addAll(profiles(lookup));
				}
				for (JsonObject rule : rules("component", lookup))
				{
					if (!Boolean.TRUE.equals(bool(original.get("nativePresenceObserved")))
						&& !Boolean.TRUE.equals(bool(rule.get("profile")))
						&& Objects.equals(pattern, text(rule, "pattern")))
					{
						declared.add(rule);
					}
				}
				if (declared.isEmpty())
				{
					declared.add(new JsonObject());
				}
				for (JsonObject rule : declared)
				{
					ResolutionCandidate conflict = ResolutionCandidate.observed(
						text(rule, "id") == null ? "observed:native_context_conflict" : text(rule, "id"), original);
					conflict.status = "EXCLUDED";
					conflict.reasons.add("RECORDED_NATIVE_CONTEXT_CONFLICT");
					conflict.evidence.add("ORIGINAL_SOURCE_OBSERVATION_RETAINED_OUTSIDE_IMPACT_CONTEXT");
					results.add(conflict);
				}
				continue;
			}
			JsonObject cue = interpretedCue(bindFacts(original, hit), hit);
			if (differentKnown(hit.get("contextEpoch"), hit.get("finalizationContextEpoch"))
				&& !sameKnown(object(object(cue.get("source")).get("scope")).get("contextEpoch"),
					hit.get("contextEpoch")))
			{
				cue.addProperty("identityOnly", true);
				cue.addProperty("numericEligible", false);
				cue.addProperty("componentBound", false);
				cue.addProperty("staticReferenceEligible", false);
				cue.addProperty("forceScalarUnknown", true);
			}
			normalized.add(cue);
			String cohort = cohort(cue);
			if (cohort != null && positiveOrdinaryBinding(cue, hit))
			{
				ordinaryCohorts.add(cohort);
			}
			if (cohort != null && text(cue, "evidenceMode") != null)
			{
				limitDecisions.merge(cohort, evaluateQualifications(cue, hit),
					(prior,
						next) -> "ALL_DIRECT_HP_COMPONENTS".equals(text(next, "exclusionScope"))
							&& Boolean.TRUE.equals(bool(next.get("excluded"))) ? next
								: Boolean.TRUE.equals(bool(prior.get("excluded"))) ? prior : next);
			}
			if (cohort != null)
			{
				for (JsonObject bound : eligibleSourceBounds(cue, hit))
				{
					qualifiedBounds.computeIfAbsent(cohort, k -> new ArrayList<>()).add(bound);
				}
			}
		}
		if (cueViews.size() > MAX_CUES)
		{
			output.truncated = true;
		}
		for (ResolutionCandidate association : interactionRows(input, hit))
		{
			results.add(association);
		}
		int count = 0;
		for (JsonObject cue : normalized)
		{
			if (++count > MAX_CUES || results.size() >= MAX_RESULTS)
			{
				output.truncated = true;
				break;
			}
			JsonObject source = object(cue.get("source"));
			String pattern = text(cue, "pattern"), key = sourceKey(source);
			if (key == null || !PATTERNS.contains(pattern))
			{
				continue;
			}
			String lookup = "status".equals(text(source, "kind")) ? "status:" + text(source, "statusFamily")
				: text(source, "kind") + ":" + integer(source.get("id"));
			String mode = text(cue, "evidenceMode");
			if ("ACTOR_ACTIVITY".equals(mode) && "HP_CALLBACK_ACTOR_SAMPLE".equals(text(cue, "observationKind"))
				&& !policy("hp_actor_activity", cue, hit))
			{
				if (policy("hp_pose_receipt", cue, hit))
				{
					ResolutionCandidate pose = ResolutionCandidate.hint("observed:hp-actor-reference", cue);
					pose.evidence.add("CURRENT_ENGAGED_POSE");
					results.add(pose);
				}
				continue;
			}
			if ("ORIGINAL_CONTACT".equals(mode))
			{
				Truth contact = contactQualification(cue, hit);
				if (contact != Truth.TRUE)
				{
					if (contact == Truth.UNKNOWN)
					{
						ResolutionCandidate weak = weakReference(cue, hit, "ORIGINAL_CONTACT_UNQUALIFIED");
						if (weak != null)
						{
							results.add(weak);
						}
					}
					continue;
				}
			}
			List<ResolutionCandidate> hinted = evidenceRows(cue, hit, input);
			for (ResolutionCandidate row : hinted)
			{
				results.add(row);
			}
			if (Arrays.asList("INVENTORY_POSSESSION", "SYSTEM_RESPONSE", "STATUS_ENVELOPE", "RETAINED_NAMED",
				"CONSUMPTION", "ENDPOINT_COOCCURRENCE", "SETTLED_REFERENCE").contains(mode))
			{
				continue;
			}
			if (Boolean.TRUE.equals(bool(cue.get("nativePresenceObserved"))))
			{
				presenceAssociations(cue, hit, lookup, results);
				continue;
			}
			JsonObject activity = nonDamagingActivity(cue, hit, lookup);
			if (activity != null)
			{
				ResolutionCandidate row = ResolutionCandidate.observed(text(activity, "id"), cue);
				row.status = "EXCLUDED";
				row.identityQualified = true;
				row.evidence.add("ACTIVITY_CLASSIFICATION");
				row.reasons.add("NON_DAMAGING_ACTIVITY");
				results.add(row);
				continue;
			}
			boolean bound = false, identified = false, positive = false;
			boolean positiveOrdinary = positiveOrdinaryBinding(cue, hit);
			for (JsonObject rule : rules("component", lookup))
			{
				if (!Boolean.TRUE.equals(bool(rule.get("profile"))) && pattern.equals(text(rule, "pattern")))
				{
					positive |= applicableSpecialBinding(rule, cue, hit);
				}
			}
			for (JsonObject rule : rules("component", lookup))
			{
				if (results.size() >= MAX_RESULTS)
				{
					break;
				}
				boolean profile = Boolean.TRUE.equals(bool(rule.get("profile")));
				boolean linked = profile ? ordinaryBinding(rule, cue, hit) : componentBound(rule, cue, hit);
				if (profile)
				{
					if (!Arrays.asList("direct_attack", "projectile_impact").contains(pattern)
						|| positiveOrdinary && !linked || !Boolean.TRUE.equals(bool(cue.get("ordinary"))) && !linked)
					{
						continue;
					}
				}
				else if (!pattern.equals(text(rule, "pattern"))
					|| Arrays.asList("ORIGINAL_CONTACT", "RETAINED_LIFECYCLE").contains(mode) && hinted.isEmpty()
						&& !linked
					|| positive && !applicableSpecialBinding(rule, cue, hit)
					|| ordinaryCohorts.contains(cohort(cue)) && !declaredSpecialBinding(rule, cue, hit))
				{
					continue;
				}
				ResolutionCandidate candidate = match(rule, cue, hit, profile,
					linked || profile && !Boolean.FALSE.equals(bool(cue.get("componentBound"))));
				if (profile && linked)
				{
					candidate.evidence.add("ORDINARY_SIGNAL_BINDING");
				}
				identified |= qualifiedComponent(candidate);
				results.add(candidate);
				bound = true;
			}
			if ((!bound || !identified && ownedSourceCue(cue)) && !Boolean.FALSE.equals(bool(cue.get("allowFallback")))
				&& results.size() < MAX_RESULTS)
			{
				ResolutionCandidate row = ResolutionCandidate.observed("observed:" + pattern, cue);
				row.evidence.add("COMPONENT_UNKNOWN");
				if (scope(source, hit) == Truth.FALSE)
				{
					if (!Boolean.TRUE.equals(bool(cue.get("apiSourceObserved"))))
					{
						continue;
					}
					row.evidence.add("CROSS_NATIVE_SOURCE_IDENTITY_ONLY");
				}
				results.add(row);
			}
		}
		for (ResolutionCandidate row : results)
		{
			if (row.sourceOnly)
			{
				continue;
			}
			JsonObject cue = row.cue;
			if (cue == null)
			{
				continue;
			}
			for (JsonObject bound : qualifiedBounds.getOrDefault(cohort(cue), Collections.emptyList()))
			{
				overlaySourceBound(row, bound, hit);
			}
			JsonObject decision = limitDecisions.get(cohort(cue));
			if (decision != null)
			{
				for (JsonElement evidence : array(decision.get("evidence")))
				{
					row.evidence.add(evidence);
				}
				boolean independentSpecial = !Boolean.TRUE.equals(row.ordinary) && qualifiedComponent(row);
				if (Boolean.TRUE.equals(bool(decision.get("excluded")))
					&& !("ORDINARY_ATTACK".equals(text(decision, "exclusionScope")) && independentSpecial))
				{
					row.status = "EXCLUDED";
					row.exclusionScope = text(decision, "exclusionScope");
					for (JsonElement reason : array(decision.get("reasons")))
					{
						row.reasons.add(reason);
					}
					boolean numericOnly = true;
					for (JsonElement reason : row.reasons)
					{
						numericOnly &= reason.getAsString().startsWith("MAXIMUM_EXCEEDED");
					}
					row.legacyApiReferenceConflictEligible = numericOnly
						&& Boolean.TRUE.equals(bool(decision.get("legacyApiReferenceConflictEligible")));
				}
			}
			applyContactLimit(row, cue, hit);
			applyResponseLimit(row, cue, hit, input);
			applyInputLimits(row, cue, hit);
			applyOutcomeBindings(row, cue, hit);
			if ("ORIGINAL_CONTACT".equals(text(cue, "evidenceMode")) && "POSSIBLE".equals(row.status)
				&& qualifiedComponent(row) && contactAgreement(cue, hit))
			{
				row.sourceAssociation = "ORIGINAL_CONTACT_AGREEMENT";
			}
			if ("POSSIBLE".equals(row.status) && references != null)
			{
				cue.addProperty("hazardReferenceEligible",
					"ORIGINAL_CONTACT".equals(text(cue, "evidenceMode")) && qualifiedComponent(row));
				row.derivedAnnotations = object(annotations("cue", cue, row, hit));
			}
			if ("POSSIBLE".equals(row.status))
			{
				OriginalSourceProof.scalar(cue, row);
			}
		}
		retireWeakWholeSourceSiblings(results);
		return output;
	}

	/** Alternative observed relationships share their original immutable payload and physical cohort. */
	static JsonArray expandChannels(JsonArray physical)
	{
		JsonArray views = new JsonArray();
		for (JsonElement value : physical)
		{
			JsonObject cue = object(value);
			JsonArray channels = array(cue.get("channels"));
			if (channels.size() == 0)
			{
				views.add(cue);
			}
			else
			{
				for (JsonElement channel : channels)
				{
					JsonObject facet = object(channel);
					if (!PATTERNS.contains(text(facet, "pattern")))
					{
						continue;
					}
					JsonObject view = new JsonObject();
					for (Map.Entry<String, JsonElement> field : cue.entrySet())
					{
						if (!"channels".equals(field.getKey()))
						{
							view.add(field.getKey(), field.getValue());
						}
					}
					for (String field : Arrays.asList("key", "pattern", "evidenceMode", "allowFallback",
						"numericEligible", "componentBound"))
					{
						if (facet.has(field))
						{
							view.add(field, facet.get(field));
						}
					}
					if (!view.has("cohortKey"))
					{
						view.add("cohortKey", cue.get("key"));
					}
					views.add(view);
					if (views.size() > MAX_CUES)
					{
						return views;
					}
				}
			}
			if (views.size() > MAX_CUES)
			{
				return views;
			}
		}
		return views;
	}

	/** A qualified current whole-source ceiling also governs weaker siblings of that same recorded actor. */
	private static void retireWeakWholeSourceSiblings(List<ResolutionCandidate> rows)
	{
		Map<String, List<ResolutionCandidate>> excluded = new HashMap<>();
		for (ResolutionCandidate row : rows)
		{
			if ("EXCLUDED".equals(row.status) && "ALL_DIRECT_HP_COMPONENTS".equals(row.exclusionScope)
				&& Boolean.TRUE.equals(row.identityQualified))
			{
				excluded.computeIfAbsent(row.sourceKey, key -> new ArrayList<>()).add(row);
			}
		}
		for (ResolutionCandidate row : rows)
		{
			JsonObject source = row.source;
			if (row.sourceOnly || !"POSSIBLE".equals(row.status) || !"npc".equals(text(source, "kind"))
				|| text(source, "actorRef") == null || Boolean.TRUE.equals(row.componentQualified))
			{
				continue;
			}
			for (ResolutionCandidate bound : excluded.getOrDefault(row.sourceKey, Collections.emptyList()))
			{
				JsonObject boundSource = bound.source;
				if (!Objects.equals(source.get("id"), boundSource.get("id")) || scope(source, boundSource) != Truth.TRUE
					|| contradict(source, boundSource, Arrays.asList("name", "combatLevel")))
				{
					continue;
				}
				JsonObject facts = object(object(row.cue.get("facts")).get("source_event")),
					boundFacts = object(object(bound.cue.get("facts")).get("source_event"));
				if (contradict(facts, boundFacts,
					Arrays.asList("world_mode", "source_model_overrides", "encounter_activity", "encounter_mode")))
				{
					continue;
				}
				row.status = "EXCLUDED";
				row.exclusionScope = "ALL_DIRECT_HP_COMPONENTS";
				row.reasons.add("QUALIFIED_WHOLE_SOURCE_EXCLUSION");
				break;
			}
		}
	}

	private static boolean nativeConflict(JsonObject cue, JsonObject hit)
	{
		JsonObject source = object(cue.get("source"));
		boolean api = Boolean.TRUE.equals(bool(cue.get("apiSourceObserved")));
		return contradict(object(source.get("scope")), object(hit.get("scope")),
			api ? Arrays.asList("sceneKey", "contextEpoch")
				: Arrays.asList("sceneKey", "worldViewId", "plane", "contextEpoch"))
			|| contradict(object(cue.get("projectileScope")), object(hit.get("scope")),
				Arrays.asList("sceneKey", "worldViewId", "plane", "contextEpoch"))
			|| differentKnown(object(hit.get("originalHpCallback")).get("causalSceneEpoch"), hit.get("contextEpoch"));
	}

	private static boolean sameKnown(JsonElement first, JsonElement second)
	{
		return known(first) && known(second) && equal(first, second);
	}

	private static boolean differentKnown(JsonElement first, JsonElement second)
	{
		return known(first) && known(second) && !equal(first, second);
	}

	private static boolean contradict(JsonObject first, JsonObject second, List<String> fields)
	{
		for (String field : fields)
		{
			if (differentKnown(first.get(field), second.get(field)))
			{
				return true;
			}
		}
		return false;
	}

	private void presenceAssociations(JsonObject cue, JsonObject hit, String lookup, List<ResolutionCandidate> results)
	{
		JsonObject source = object(cue.get("source"));
		if (!"area_impact".equals(text(cue, "pattern")) || !"npc".equals(text(source, "kind"))
			|| text(source, "actorRef") == null || scope(source, hit) != Truth.TRUE)
		{
			return;
		}
		for (JsonObject association : rules("presence", lookup))
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
			ResolutionCandidate row = ResolutionCandidate.observed(text(association, "id"), cue);
			row.identityQualified = identity == Truth.TRUE;
			row.componentIdentified = false;
			row.componentQualified = false;
			row.evidence.add("PASSIVE_SOURCE_PRESENCE_ONLY");
			if (association.has("references"))
			{
				row.references = array(association.get("references").deepCopy());
			}
			results.add(row);
		}
	}

	/** A recorded request/response can explain an observed callback; it never generates one. */
	private List<ResolutionCandidate> interactionRows(JsonObject input, JsonObject hit)
	{
		List<ResolutionCandidate> rows = new ArrayList<>();
		JsonObject hp = object(hit.get("originalHpCallback"));
		if (!"player".equals(text(hit, "recipientRef")) || !"RECEIVED".equals(text(hit, "direction"))
			|| number(hit.get("amount")) == null || number(hit.get("amount")).signum() <= 0
			|| !sameNumber(hp, "amount", hit, "amount") || !sameNumber(hp, "hitsplatType", hit, "hitsplatType")
			|| !"PLAYER".equals(text(hp, "receiverKind"))
			|| !"LOCAL_PLAYER".equals(text(object(hp.get("actorState")), "actorKind")))
		{
			return rows;
		}
		for (JsonObject policy : evidenceRules("INTERACTION_RESPONSE"))
		{
			JsonObject responseRule = object(policy.get("response")), requestRule = object(policy.get("request")),
				admission = object(policy.get("admission"));
			if (!contains(admission.get("hitsplatTypeIds"), hit.get("hitsplatType")))
			{
				continue;
			}
			int callbacks = 0;
			for (JsonElement value : array(input.get("impactObservations")))
			{
				JsonObject callback = object(value);
				if (sameNumber(callback, "tick", hit, "tick") && "player".equals(text(callback, "recipientRef"))
					&& number(callback.get("amount")) != null && number(callback.get("amount")).signum() > 0
					&& contains(admission.get("hitsplatTypeIds"), callback.get("hitsplatType")))
				{
					callbacks++;
				}
			}
			if (!Integer.valueOf(callbacks).equals(integer(admission.get("positiveCallbacksPerTick"))))
			{
				continue;
			}
			List<JsonObject> replies = new ArrayList<>();
			JsonObject request = null;
			for (JsonElement value : array(input.get("interactionObservations")))
			{
				JsonObject event = object(value), data = object(event.get("data"));
				if ("SYSTEM_MESSAGE".equals(text(event, "type")) && sameNumber(event, "tick", hit, "tick")
					&& number(event.get("sequence")) != null && number(event.get("sequence")).signum() > 0
					&& contains(responseRule.get("channels"), data.get("channel"))
					&& Boolean.TRUE.equals(bool(data.get("messagePresent")))
					&& "LOGGED_IN".equals(text(data, "callbackGameState")) && text(data, "message") != null
					&& text(data, "message").length() <= 4096 && text(responseRule, "messageRegex") != null
					&& text(data, "message").replaceAll("<[^>]*>", "").matches(text(responseRule, "messageRegex")))
				{
					replies.add(event);
				}
				if ("MENU_OPTION".equals(text(event, "type"))
					&& Objects.equals(text(requestRule, "operation"), text(data, "option"))
					&& integer(event.get("tick")) != null && integer(hit.get("tick")) != null
					&& integer(requestRule.get("maximumAgeTicks")) != null
					&& (long) integer(hit.get("tick")) - integer(event.get("tick")) >= 0
					&& (long) integer(hit.get("tick"))
						- integer(event.get("tick")) <= integer(requestRule.get("maximumAgeTicks"))
					&& number(event.get("sequence")) != null && number(event.get("sequence")).signum() > 0
					&& number(hit.get("sequence")) != null
					&& number(event.get("sequence")).compareTo(number(hit.get("sequence"))) < 0 && (request == null
						|| number(event.get("sequence")).compareTo(number(request.get("sequence"))) > 0))
				{
					request = event;
				}
			}
			if (replies.size() != 1)
			{
				continue;
			}
			JsonObject response = replies.get(0), source = new JsonObject(),
				data = object(request == null ? null : request.get("data"));
			JsonObject npc = object(data.get("relatedActorState")), local = object(data.get("actorState"));
			Integer distance = integer(requestRule.get("maximumDistanceTiles"));
			boolean nearby = request != null && "NPC".equals(text(npc, "actorKind")) && text(npc, "name") != null
				&& distance != null && Boolean.TRUE.equals(bool(npc.get("worldPositionKnown")))
				&& Boolean.TRUE.equals(bool(local.get("worldPositionKnown")))
				&& Boolean.TRUE.equals(bool(npc.get("planeKnown")))
				&& Boolean.TRUE.equals(bool(local.get("planeKnown")))
				&& (!known(npc.get("worldViewId")) || !known(local.get("worldViewId"))
					|| equal(npc.get("worldViewId"), local.get("worldViewId")))
				&& sameNumber(npc, "plane", local, "plane") && number(npc.get("worldX")) != null
				&& number(npc.get("worldY")) != null && number(local.get("worldX")) != null
				&& number(local.get("worldY")) != null
				&& number(npc.get("worldX")).subtract(number(local.get("worldX"))).abs()
					.compareTo(new BigDecimal(distance)) <= 0
				&& number(npc.get("worldY")).subtract(number(local.get("worldY"))).abs()
					.compareTo(new BigDecimal(distance)) <= 0;
			source.addProperty("kind", nearby ? "npc" : "witness");
			source.addProperty("name",
				nearby ? normalize(text(npc, "name")) + text(policy, "labelSuffix") : text(policy, "fallbackLabel"));
			if (nearby && integer(npc.get("npcId")) != null)
			{
				source.add("id", npc.get("npcId"));
			}
			if (nearby && text(request, "relatedActorRef") != null)
			{
				source.addProperty("actorRef", text(request, "relatedActorRef"));
			}
			source.addProperty("observationKey", "interaction-response:" + number(response.get("sequence")));
			JsonObject nativeContext = new JsonObject();
			nativeContext.addProperty("sceneKey",
				nearby && sameNumber(npc, "worldViewId", local, "worldViewId") ? text(request, "sceneKey")
					: nearby ? null : text(response, "sceneKey"));
			if (nearby)
			{
				nativeContext.add("worldViewId", npc.get("worldViewId"));
				nativeContext.add("plane", npc.get("plane"));
			}
			source.add("scope", nativeContext);
			source.addProperty("familyKey", "interaction-response:" + text(policy, "id"));
			JsonObject cue = new JsonObject();
			cue.addProperty("key", "response:" + number(response.get("sequence")));
			cue.add("source", source);
			cue.addProperty("evidenceMode", "INTERACTION_RESPONSE");
			cue.addProperty("sourceAssociation", text(policy, "sourceAssociation"));
			ResolutionCandidate row = ResolutionCandidate.hint(text(policy, "id"), cue);
			row.componentQualified = true;
			row.componentIdentified = true;
			row.scalarEligible = true;
			row.confidence = "TIMED";
			row.referenceAnnotations = object(policy.get("referenceAnnotations"));
			row.evidence.add("RECORDED_INTERACTION_RESPONSE:" + text(policy, "id"));
			rows.add(row);
		}
		return rows;
	}

	private JsonObject annotations(String domain, JsonObject cue, ResolutionCandidate row, JsonObject hit)
	{
		List<JsonObject> admitted = new ArrayList<>();
		if (row != null)
		{
			JsonObject facts = new JsonObject();
			facts.addProperty("label", row.label);
			cue.add("evaluation", facts);
		}
		for (JsonObject rule : rules("annotation", "*"))
		{
			if (domain.equals(text(rule, "domain"))
				&& (!rule.has("source") || selectedSource(object(rule.get("source")), object(cue.get("source"))))
				&& (!rule.has("signals") || signalsMatch(object(rule.get("signals")), object(cue.get("signals"))))
				&& conditions(rule.get("when"), cue, hit) == Truth.TRUE)
			{
				admitted.add(rule);
			}
		}
		JsonObject result = SourceAnnotations.fold(admitted, cue, row);
		cue.remove("evaluation");
		return result;
	}

	/** Global annotation rows need the same kind/definition selection otherwise provided by the source index. */
	private static boolean selectedSource(JsonObject selector, JsonObject source)
	{
		return (!selector.has("kind") || Objects.equals(text(selector, "kind"), text(source, "kind")))
			&& (!selector.has("ids") || contains(selector.get("ids"), source.get("id")))
			&& identity(selector, source) == Truth.TRUE;
	}

	/** Finite aliases select only original recorded source paths, without inventing missing values. */
	private JsonObject bindFacts(JsonObject original, JsonObject hit)
	{
		JsonObject cue = new JsonObject();
		for (Map.Entry<String, JsonElement> field : original.entrySet())
		{
			cue.add(field.getKey(), field.getValue());
		}
		JsonObject actor = OriginalSourceView.read(cue).actor();
		if (actor.size() > 0)
		{
			cue.add("originalActorState", actor);
		}
		for (JsonObject rule : rules("fact", "*"))
		{
			if (conditions(rule.get("when"), cue, hit) == Truth.TRUE)
			{
				for (JsonElement value : array(object(rule.get("effects")).get("assign")))
				{
					JsonObject assignment = object(value), from = object(assignment.get("valueFrom"));
					String target = text(assignment, "target"), fact = text(from, "fact");
					if (target == null || !Arrays.asList("facts.source_event.world_mode",
						"facts.source_event.source_model_overrides", "position.x", "position.y", "bounds", "targetRef",
						"endpoint.x", "endpoint.y", "originalActorState").contains(target) || known(path(cue, target)))
					{
						continue;
					}
					JsonElement actual = assignment.has("valueFrom")
						? path(factDomain(text(from, "at"), fact, cue, hit), fact)
						: assignment.get("value");
					if (!known(actual))
					{
						continue;
					}
					JsonObject parent = cue;
					String[] parts = target.split("\\.");
					for (int n = 0; n < parts.length - 1; n++)
					{
						JsonObject child = object(parent.get(parts[n])).deepCopy();
						parent.add(parts[n], child);
						parent = child;
					}
					parent.add(parts[parts.length - 1], actual);
				}
			}
		}
		return cue;
	}

	/** Select qualified reference facts here, never in the observation adapter. */
	private JsonObject interpretedCue(JsonObject original, JsonObject hit)
	{
		JsonObject cue = new JsonObject();
		for (Map.Entry<String, JsonElement> field : original.entrySet())
		{
			cue.add(field.getKey(), field.getValue());
		}
		if (Boolean.TRUE.equals(bool(cue.get("apiSourceObserved")))
			&& (Boolean.TRUE.equals(bool(cue.get("unstableOriginalSource")))
				|| scope(object(cue.get("source")), hit) != Truth.TRUE
				|| text(object(cue.get("source")), "actorRef") == null))
		{
			cue.addProperty("identityOnly", true);
			cue.addProperty("numericEligible", false);
			cue.addProperty("componentBound", false);
		}
		if ("SETTLED_REFERENCE".equals(text(cue, "evidenceMode")))
		{
			cue.addProperty("numericEligible", false);
			cue.addProperty("componentBound", false);
			cue.addProperty("forceScalarUnknown", true);
		}
		cue.add("originalProof", OriginalSourceProof.evaluate(cue, hit));
		boolean symbolic = false;
		for (JsonElement signal : array(object(cue.get("signals")).get("projectileIds")))
		{
			symbolic |= integer(signal) != null && integer(signal) >= 0;
		}
		cue.addProperty("symbolReferenceEligible", symbolic
			&& !Boolean.TRUE.equals(bool(cue.get("unstableOriginalSource"))) && sourceObservedBeforeImpact(cue, hit));
		cue.addProperty("staticReferenceEligible", false);
		Integer observedAnimation = integer(object(cue.get("originalActorState")).get("animationId"));
		cue.addProperty("animationReferenceEligible",
			"player".equals(text(object(cue.get("source")), "kind")) && observedAnimation != null
				&& observedAnimation >= 0 && (Boolean.TRUE.equals(bool(cue.get("actorCallbackObserved")))
					|| "HP_CALLBACK_ACTOR_SAMPLE".equals(text(cue, "observationKind"))));
		if (references == null || captureBindings == null || !"npc".equals(text(object(cue.get("source")), "kind")))
		{
			return cue;
		}
		JsonObject source = object(cue.get("source")).deepCopy();
		cue.add("source", source);
		JsonObject first = object(cue.get("originalSourceObservation")), state = object(cue.get("originalActorState"));
		boolean projectile = first.size() > 0 && Boolean.TRUE.equals(bool(cue.get("apiSourceObserved")));
		Integer id = integer(source.get("id")), level = integer(source.get("combatLevel"));
		String name = text(source, "name");
		boolean sameModel = policy("original_model", cue, hit);
		cue.addProperty("originalModelMatched", sameModel);
		cue.addProperty("originalNativeQualified",
			scope(source, hit) == Truth.TRUE && policy("original_native", cue, hit));
		cue.addProperty("staticReferenceEligible",
			policy("static_reference", cue, hit) && id != null && references.observedNameCompatible(id, name, true));
		boolean numerical = policy("numeric_reference", cue, hit);
		cue.addProperty("numericEligible", numerical);
		boolean normal = numerical && sameModel && policy("normal_reference", cue, hit);
		boolean noOverrides = numerical && sameModel && policy("unmodified_reference", cue, hit);
		if (id != null && ordinaryOnly(id) && (policy("ordinary_actor", cue, hit)
			|| policy("ordinary_projectile", cue, hit) || policy("ordinary_proximity", cue, hit)))
		{
			cue.addProperty("ordinary", true);
		}
		String family = id == null ? null : references.attributionFamily(id, name, level, normal);
		if (family != null)
		{
			source.addProperty("referenceFamilyKey", family);
			if (text(source, "observationKey") == null || text(source, "actorRef") != null)
			{
				source.addProperty("familyKey", family);
			}
		}
		boolean qualified = numerical && sameModel && family != null
			&& references.attributionBaseProfile(id, level, family);
		Integer animation = projectile ? null : integer(state.get("animationId"));
		Integer projectileId = null;
		for (JsonElement value : array(object(cue.get("signals")).get("projectileIds")))
		{
			if (projectileId != null && !projectileId.equals(integer(value)))
			{
				projectileId = null;
				break;
			}
			projectileId = integer(value);
		}
		boolean direct = ownedSourceCue(cue) || "ACTOR_ACTIVITY".equals(text(cue, "evidenceMode"))
			&& Arrays.asList("direct_attack", "area_impact").contains(text(cue, "pattern"));
		String mechanism = id == null ? null : references.outcomeMechanism(id, animation, projectileId, level, normal);
		DamageMap.AttackRule attack = id == null || animation == null ? null
			: captureBindings.attackRuleForNpc(id, animation, level, normal);
		boolean attackCue = direct && id != null
			&& (animation != null && (references.reviewedAttackCue(id, animation, level, normal) || attack != null)
				|| projectile && projectileId != null && "ORDINARY_ATTACK".equals(mechanism));
		Integer hitType = integer(hit.get("hitsplatType"));
		boolean single = integer(hit.get("sequence")) != null && integer(hit.get("sequence")) > 0 && hitType != null
			&& Arrays.asList("HP", "BLOCK").contains(references.hitsplatSurface(hitType));
		JsonObject observed = object(cue.get("observations")).deepCopy();
		observed.addProperty("QUALIFIED_NPC_VARIANT", qualified);
		observed.addProperty("NORMAL_WORLD_CONTEXT", normal);
		observed.addProperty("NO_MODEL_OVERRIDES_OBSERVED", noOverrides);
		observed.addProperty("ORDINARY_ATTACK_CUE", attackCue);
		observed.addProperty("ORDINARY_ATTACK_COMPONENT",
			direct && qualified && normal && noOverrides && "ORDINARY_ATTACK".equals(mechanism));
		observed.addProperty("SINGLE_HIT_COMPONENT", single);
		observed.addProperty("NPC_DIRECT_SOURCE_HYPOTHESIS",
			direct && sameModel && text(source, "actorRef") != null && scoped(source));
		JsonObject context = object(hit.get("impactContext"));
		boolean exceptional = mechanism != null && !"ORDINARY_ATTACK".equals(mechanism)
			|| Boolean.TRUE.equals(bool(cue.get("unstableOriginalSource")))
			|| Boolean.TRUE.equals(bool(cue.get("identityOnly"))) || known(context.get("raidLevel"))
			|| known(context.get("scaledTeamSize"));
		boolean envelope = qualified && normal && noOverrides && attackCue && single && !exceptional;
		for (JsonObject limit : rules("qualified_limit", "npc:" + id))
		{
			if (!"independent_guard".equals(text(limit, "composition")))
			{
				List<String> blocked = new ArrayList<>();
				for (JsonElement value : array(object(limit.get("envelopePolicy")).get("blockedActivities")))
				{
					blocked.add(value.getAsString());
				}
				envelope &= "BASE_PROFILE_ENVELOPE".equals(text(object(limit.get("envelopePolicy")), "operator"))
					&& gson != null && baseContextKnown(context, blocked)
					&& !blocked.contains(text(context, "activity"));
			}
		}
		observed.addProperty("BASE_PROFILE_ENVELOPE_APPLICABLE", envelope);
		cue.add("observations", observed);
		if (direct)
		{
			cue.addProperty("mechanism", "ORDINARY_ATTACK");
		}
		cue.addProperty("damageComponent", "SINGLE_HIT");
		if (normal)
		{
			cue.addProperty("worldMode", "NORMAL");
		}
		String style = id == null || projectileId == null ? null
			: references.projectileDamageType(id, projectileId, level, normal);
		if (style == null && projectileId == null && attack != null && !attack.isRequiresProjectile())
		{
			style = attack.getDamageType();
		}
		if (style != null
			&& Arrays.asList("MELEE", "RANGED", "MAGIC").contains(style.toUpperCase(java.util.Locale.ROOT)))
		{
			cue.addProperty("observedAttackStyle", style.toUpperCase(java.util.Locale.ROOT));
		}
		boolean apiCorrelation = projectile && sameModel && text(source, "actorRef") != null
			&& Objects.equals(text(source, "actorRef"), text(first, "sourceRef"))
			&& Boolean.TRUE.equals(bool(first.get("sourceActorKnown"))) && "ACTOR".equals(text(first, "sourceEvidence"))
			&& Boolean.TRUE.equals(bool(cue.get("sourceObservedBeforeImpact"))) && scope(source, hit) == Truth.TRUE
			&& Objects.equals(text(cue, "targetRef"), text(hit, "recipientRef"))
			&& !Boolean.TRUE.equals(bool(cue.get("identityOnly")))
			&& !Boolean.TRUE.equals(bool(cue.get("unstableOriginalSource")));
		if (apiCorrelation)
		{
			cue.addProperty("candidateStrength", "STRONG");
		}
		JsonObject numeric = object(cue.get("numericFacts")).deepCopy();
		if (sameModel)
		{
			numeric.addProperty("source.sample.combatLevel", level);
		}
		if (apiCorrelation)
		{
			numeric.addProperty("source.apiProjectileCorrelated", 1);
		}
		for (JsonObject limit : rules("qualified_limit", "npc:" + id))
		{
			for (JsonElement value : array(limit.get("when")))
			{
				String fact = text(object(value), "fact"), activity = text(context, "activity");
				if ("numericFacts".equals(text(object(value), "at")) && fact != null
					&& fact.startsWith("context.activity.") && activity != null)
				{
					numeric.addProperty(fact, activity.equals(fact.substring("context.activity.".length())) ? 1 : 0);
				}
			}
		}
		cue.add("numericFacts", numeric);
		return cue;
	}

	private boolean baseContextKnown(JsonObject context, List<String> blocked)
	{
		try
		{
			return references.baseProfileContextKnown(
				gson.fromJson(context, com.tickreplaylogger.model.world.ContextBlock.class), blocked);
		}
		catch (RuntimeException unavailable)
		{
			return false;
		}
	}

	/** Source hints add possibilities from declared evidence; they never assert an executed attack. */
	private List<ResolutionCandidate> evidenceRows(JsonObject cue, JsonObject hit, JsonObject input)
	{
		List<ResolutionCandidate> rows = new ArrayList<>();
		String mode = text(cue, "evidenceMode");
		JsonObject source = object(cue.get("source")), witness = object(cue.get("witness"));
		if (mode == null || !"player".equals(text(hit, "recipientRef")) || number(hit.get("amount")) == null)
		{
			return rows;
		}
		if ("SETTLED_REFERENCE".equals(mode))
		{
			if (scope(source, hit) != Truth.TRUE || !sameNumber(cue, "tick", hit, "tick")
				|| Boolean.FALSE.equals(bool(witness.get("damaging")))
				|| Boolean.TRUE.equals(bool(witness.get("finished")))
				|| Arrays.asList("inactive", "warning").contains(text(witness, "state"))
				|| Arrays.asList("NOT_DAMAGE_SURFACE", "RESOURCE_REFERENCE_ONLY", "DEFINITION_NOT_QUALIFIED",
					"UNCLASSIFIED_GRAPHIC_SURFACE").contains(text(witness, "sourceEligibility"))
				|| settledBirthAfter(cue, hit, input))
			{
				return rows;
			}
			for (JsonObject policy : rules("contact_limit", text(source, "kind") + ":" + integer(source.get("id"))))
			{
				if (!nativeObjectContact(policy, cue, hit))
				{
					return rows;
				}
			}
			boolean reviewed = false;
			for (JsonObject rule : rules("evidence", text(source, "kind") + ":" + integer(source.get("id"))))
			{
				if (Boolean.TRUE.equals(bool(rule.get("causalAssociationOnly")))
					&& "SAME_RECORDED_TICK".equals(text(rule, "clockPolicy"))
					&& "RECORDED_NPC_SNAPSHOT".equals(text(object(rule.get("declaration")), "observationKind"))
					&& identity(object(rule.get("source")), source) != Truth.FALSE)
				{
					reviewed = true;
					if (identity(object(rule.get("source")), source) == Truth.TRUE
						&& hintAdmitted(rule, cue, hit, input))
					{
						ResolutionCandidate row = ResolutionCandidate.hint(text(rule, "id"), cue);
						row.sourceOnly = true;
						row.scalarEligible = false;
						row.confidence = "UNKNOWN";
						if (text(rule, "evidencePrefix") != null)
						{
							row.evidence.add(text(rule, "evidencePrefix") + text(rule, "id"));
						}
						rows.add(row);
					}
				}
			}
			if (reviewed)
			{
				return rows;
			}
			ResolutionCandidate weak = weakReference(cue, hit, "SETTLED_REFERENCE_ONLY");
			if (weak != null)
			{
				rows.add(weak);
			}
			return rows;
		}
		if ("RETAINED_NAMED".equals(mode) || "CONSUMPTION".equals(mode) || "INPUT_REQUEST".equals(mode))
		{
			String kind = text(witness, "kind");
			if (kind != null && !kind.startsWith("RECENT_ATTACKER") && !kind.startsWith("ACTIVE_")
				&& sourceKey(source) != null && text(source, "name") != null)
			{
				rows.add(ResolutionCandidate.hint("observed:retained-named", cue));
			}
			return rows;
		}
		if (scope(source, hit) != Truth.TRUE)
		{
			return rows;
		}
		if ("STATUS_ENVELOPE".equals(mode))
		{
			String family = text(source, "statusFamily"), key = text(witness, "key");
			if (policy("status_envelope", cue, hit) && references != null && family != null && key != null
				&& Boolean.TRUE.equals(bool(witness.get("valueObserved")))
				&& Boolean.TRUE.equals(bool(witness.get("active"))) && Objects.equals(family, text(witness, "state"))
				&& references.localStatusCanRepresentDamage(key, family)
				&& (family
					.equals(references.hitsplatDamageType(
						integer(hit.get("hitsplatType")) == null ? -1 : integer(hit.get("hitsplatType"))))
					|| references
						.possibleStatusFamilies(
							integer(hit.get("hitsplatType")) == null ? -1 : integer(hit.get("hitsplatType")))
						.contains(family)))
			{
				rows.add(ResolutionCandidate.hint("observed:status-envelope:" + key, cue));
			}
			return rows;
		}
		String index = text(source, "kind") + ":" + integer(source.get("id"));
		for (JsonObject rule : rules("evidence", index))
		{
			if (!Objects.equals(mode, text(rule, "evidenceMode"))
				|| identity(object(rule.get("source")), source) != Truth.TRUE || !hintAdmitted(rule, cue, hit, input))
			{
				continue;
			}
			ResolutionCandidate row = ResolutionCandidate.hint(text(rule, "id"), cue);
			if (text(rule, "evidencePrefix") != null)
			{
				row.evidence.add(text(rule, "evidencePrefix") + text(rule, "id"));
			}
			JsonObject declared = object(rule.get("declaration"));
			String family = "INVENTORY_POSSESSION".equals(mode) ? "item-possession-rule:" + text(rule, "id")
				: "SYSTEM_RESPONSE".equals(mode) ? "input-rule:wiki:" + text(rule, "label")
					: "graphic".equals(text(source, "kind")) ? "graphic-definition:" + integer(source.get("id"))
						: text(rule, "familyKey");
			if (family != null)
			{
				object(row.source).addProperty("familyKey", family);
			}
			if (text(rule, "label") != null)
			{
				row.label = text(rule, "label");
			}
			for (JsonElement reference : array(declared.get("referenceKeys")))
			{
				row.evidence.add("REFERENCE_KEY:" + reference.getAsString());
			}
			JsonObject bound = object(declared.get("componentBound"));
			if ("INVENTORY_POSSESSION".equals(mode) && number(bound.get("maximum")) != null
				&& normalContext(object(witness.get("numericContext")), object(bound.get("contextPredicate")))
				&& "STATIC_WORLD_AT_HP_CALLBACK".equals(text(object(witness.get("numericContext")), "observationKind"))
				&& sameNumber(object(witness.get("numericContext")), "observedTick", hit, "tick")
				&& sameNumber(object(witness.get("numericContext")), "observedCycle", hit, "cycle"))
			{
				row.applyMaximum(bound.get("maximum"), hit, text(rule, "id") + ":possession-hp-maximum");
			}
			if (number(rule.get("perHitMaximum")) != null)
			{
				row.applyMaximum(rule.get("perHitMaximum"), hit, text(rule, "id"));
			}
			rows.add(row);
		}
		return rows;
	}

	private static boolean settledBirthAfter(JsonObject cue, JsonObject hit, JsonObject input)
	{
		JsonObject witness = object(cue.get("witness")), source = object(cue.get("source"));
		BigDecimal start = number(witness.get("startCycle")), impact = number(hit.get("cycle"));
		if (start != null && impact != null && start.compareTo(impact) > 0)
		{
			return true;
		}
		for (JsonElement value : array(input.get("sourceLifecycles")))
		{
			JsonObject event = object(value), data = object(event.get("data"));
			String type = text(event, "type"), kind = text(source, "kind");
			if (!Arrays.asList("NPC_SPAWNED", "OBJECT_SPAWNED", "GRAPHICS_OBJECT_CREATED").contains(type)
				|| differentKnown(event.get("sceneKey"), object(source.get("scope")).get("sceneKey")))
			{
				continue;
			}
			boolean same = false;
			if ("npc".equals(kind) && "NPC_SPAWNED".equals(type))
			{
				same = known(source.get("actorRef")) && equal(source.get("actorRef"), event.get("actorRef"));
			}
			else if ("object".equals(kind) && "OBJECT_SPAWNED".equals(type))
			{
				same = sameNumber(witness, "objectKey", object(data.get("object")), "objectKey");
			}
			else if ("graphic".equals(kind) && "GRAPHICS_OBJECT_CREATED".equals(type))
			{
				same = sameNumber(witness, "graphicObservationId", data, "graphicObservationId");
			}
			BigDecimal cycle = number(event.get("gameCycle")), sequence = number(event.get("sequence")),
				hpSequence = number(hit.get("sequence"));
			if (same && cycle != null && impact != null && (cycle.compareTo(impact) > 0 || cycle.compareTo(impact) == 0
				&& sequence != null && hpSequence != null && sequence.compareTo(hpSequence) > 0))
			{
				return true;
			}
		}
		return false;
	}

	private boolean hintAdmitted(JsonObject rule, JsonObject cue, JsonObject hit, JsonObject input)
	{
		JsonObject raw = object(rule.get("declaration")), witness = object(cue.get("witness"));
		String mode = text(cue, "evidenceMode"), clock = text(rule, "clockPolicy");
		if (!rule.has("admissionWhen") || conditions(rule.get("admissionWhen"), cue, hit) != Truth.TRUE
			|| Boolean.TRUE.equals(bool(rule.get("originalHpPositionRequired"))) && (!originalHpPosition(hit)
				|| !normalContext(object(hit.get("numericContext")), object(rule.get("contextPredicate"))))
			|| rule.has("admittedHitsplatTypes")
				&& !contains(rule.get("admittedHitsplatTypes"), hit.get("hitsplatType"))
			|| integer(hit.get("sequence")) == null || integer(hit.get("sequence")) <= 0)
		{
			return false;
		}
		for (Map.Entry<String, JsonElement> expected : object(rule.get("witnessBindings")).entrySet())
		{
			if (!equal(witness.get(expected.getKey()), expected.getValue()))
			{
				return false;
			}
		}
		if ("SYSTEM_RESPONSE".equals(mode))
		{
			return qualifiedResponse(rule, cue, hit) && number(raw.get("admissionMaximum")) != null
				&& number(hit.get("amount")).compareTo(number(raw.get("admissionMaximum"))) <= 0;
		}
		if (rule.has("signals") && !signalsMatch(object(rule.get("signals")), object(cue.get("signals"))))
		{
			return false;
		}
		if ("CLIENT_END_CYCLE_COOCCURRENCE".equals(clock))
		{
			Integer end = integer(witness.get("endCycle")), impact = integer(hit.get("cycle"));
			JsonObject window = object(raw.get("analysisWindow"));
			Integer min = integer(window.get("minOffsetCycles")), max = integer(window.get("maxOffsetCycles"));
			if (end == null || impact == null || min == null || max == null || (long) impact - end < min
				|| (long) impact - end > max || !qualifiedEndpoint(cue, hit, input))
			{
				return false;
			}
		}
		return (!Boolean.TRUE.equals(bool(rule.get("retainedWindowRequired")))
			|| retainedWindowComplete(rule, cue, hit, input))
			&& (Boolean.FALSE.equals(bool(rule.get("geometryRequired")))
				|| geometry(object(rule.get("geometry")), cue, hit) == Truth.TRUE);
	}

	private static boolean retainedWindowComplete(JsonObject rule, JsonObject cue, JsonObject hit, JsonObject input)
	{
		JsonObject interval = object(object(rule.get("declaration")).get("interval"));
		Integer lower = integer(input.get("windowLowerTick")), upper = integer(input.get("windowUpperTick"));
		Integer sample = integer(cue.get("tick")), impact = integer(hit.get("tick"));
		Integer min = integer(interval.get("minDelayTicks")), max = integer(interval.get("maxDelayTicks"));
		if (sample == null || impact == null || min == null || max == null || (long) impact - sample < min
			|| (long) impact - sample > max || !Boolean.TRUE.equals(bool(input.get("eventsKnown")))
			|| Boolean.TRUE.equals(bool(input.get("eventsTruncated"))) || !input.has("captureGaps")
			|| !input.get("captureGaps").isJsonArray() || lower != null && sample < lower
			|| upper != null && impact > upper)
		{
			return false;
		}
		for (JsonElement gap : array(input.get("captureGaps")))
		{
			Integer tick = integer(object(gap).get("tick"));
			if (tick == null || tick >= (long) impact - max && tick <= impact)
			{
				return false;
			}
		}
		return true;
	}

	private void applyInputLimits(ResolutionCandidate row, JsonObject cue, JsonObject hit)
	{
		JsonObject witness = object(cue.get("witness")), source = object(cue.get("source"));
		if (!"INPUT_REQUEST".equals(text(cue, "evidenceMode"))
			|| !"SELF_DAMAGE_INPUT_CANDIDATE".equals(text(witness, "kind")) || !"item".equals(text(source, "kind"))
			|| references == null || integer(hit.get("hitsplatType")) == null
			|| !Arrays.asList("HP", "BLOCK").contains(references.hitsplatSurface(integer(hit.get("hitsplatType"))))
			|| !Boolean.TRUE.equals(bool(object(hit.get("impactFrame")).get("nativeContextKnown")))
			|| scope(source, hit) != Truth.TRUE)
		{
			return;
		}
		Integer id = integer(witness.get("itemId"));
		String sourceRef = text(witness, "sourceRef"), option = text(witness, "option");
		if (id == null || sourceRef == null || option == null
			|| !Objects.equals("input-rule:" + sourceRef, text(source, "familyKey")))
		{
			return;
		}
		for (JsonObject limit : rules("input_limit", "item:" + id))
		{
			JsonObject selector = object(limit.get("selector"));
			boolean operation = false;
			for (JsonElement value : array(selector.get("operations")))
			{
				operation |= option.equalsIgnoreCase(value.getAsString());
			}
			if (!operation || !Objects.equals(sourceRef, text(selector, "inputSourceRef")))
			{
				continue;
			}
			if (!qualifiedSelfInput(cue, hit, selector)
				|| !Objects.equals(text(witness, "pageHash"), text(selector, "pageHash"))
				|| !normalContext(object(hit.get("numericContext")), object(limit.get("contextPredicate"))))
			{
				return;
			}
			if (number(limit.get("maximum")) != null)
			{
				row.applyMaximum(limit.get("maximum"), hit, text(limit, "key"));
			}
		}
	}

	private boolean qualifiedSelfInput(JsonObject cue, JsonObject hit, JsonObject selector)
	{
		JsonObject witness = object(cue.get("witness"));
		return declaredInput(cue, hit, false) != null
			&& Objects.equals(text(witness, "sourceRef"), text(selector, "inputSourceRef"))
			&& containsIgnoreCase(selector.get("operations"), String.valueOf(text(witness, "option")));
	}

	private JsonObject declaredInput(JsonObject cue, JsonObject hit, boolean consumption)
	{
		JsonObject witness = object(cue.get("witness")), source = object(cue.get("source"));
		String ref = text(witness, "sourceRef"), hash = text(witness, "pageHash"), option = text(witness, "option");
		Integer id = integer(witness.get("itemId")), tick = integer(witness.get("inputTick"));
		BigDecimal sequence = number(witness.get("inputSequence"));
		if (references == null || !"item".equals(text(source, "kind")) || !"player".equals(text(hit, "recipientRef"))
			|| !Arrays.asList("RECEIVED", "RESOURCE_IMPACT").contains(text(hit, "direction")) || id == null || id <= 0
			|| !sameNumber(source, "id", witness, "itemId") || tick == null || tick < 0
			|| integer(hit.get("tick")) == null || tick > integer(hit.get("tick")) || sequence == null
			|| sequence.signum() <= 0 || number(hit.get("sequence")) == null
			|| sequence.compareTo(number(hit.get("sequence"))) >= 0 || ref == null || hash == null
			|| !hash.matches("[0-9a-f]{64}"))
		{
			return null;
		}
		if (consumption)
		{
			Integer slot = integer(witness.get("slot"));
			BigDecimal before = number(witness.get("quantityBefore")), after = number(witness.get("quantityAfter"));
			if (!"ITEM_CONSUMPTION_CANDIDATE".equals(text(witness, "kind"))
				|| !"OBSERVED_INVENTORY_DECREASE_NOT_CAUSAL_PROOF".equals(text(witness, "evidence")) || slot == null
				|| slot < 0 || slot >= 28 || before == null || after == null || after.signum() < 0
				|| before.compareTo(after) <= 0)
			{
				return null;
			}
		}
		else if (!"SELF_DAMAGE_INPUT_CANDIDATE".equals(text(witness, "kind"))
			|| !"INPUT_CORRELATION_NOT_EXECUTION_PROOF".equals(text(witness, "evidence")) || option == null)
		{
			return null;
		}
		for (JsonElement value : references.selfDamageInputs())
		{
			JsonObject declared = object(value);
			Integer window = integer(declared.get("candidateWindowTicks"));
			if (!declared.has("observationKind") && ref.equals("wiki:" + text(declared, "page"))
				&& hash.equals(text(declared, "pageHash")) && window != null && window >= 0 && window <= 5000
				&& (long) integer(hit.get("tick")) - tick <= window
				&& contains(
					declared.has("candidateItemIds") ? declared.get("candidateItemIds") : declared.get("itemIds"),
					witness.get("itemId"))
				&& (consumption || containsIgnoreCase(declared.get("candidateOperations"), option)))
			{
				return declared;
			}
		}
		return null;
	}

	private static String inputKey(JsonObject cue)
	{
		JsonObject witness = object(cue.get("witness"));
		return text(witness, "sourceRef") + "|" + integer(witness.get("itemId")) + "|"
			+ number(witness.get("inputSequence"));
	}

	private Map<String, String> inputLabels(JsonObject input, JsonObject hit)
	{
		Map<String, String> names = new HashMap<>();
		Set<String> conflicts = new HashSet<>();
		int count = 0;
		for (JsonElement value : array(input.get("cues")))
		{
			if (++count > MAX_CUES)
			{
				break;
			}
			JsonObject cue = object(value);
			if (!"CONSUMPTION".equals(text(cue, "evidenceMode")) || declaredInput(cue, hit, true) == null)
			{
				continue;
			}
			String key = inputKey(cue), name = text(object(cue.get("source")), "name");
			if (name != null && names.putIfAbsent(key, name) != null && !name.equals(names.get(key)))
			{
				conflicts.add(key);
			}
		}
		for (String conflict : conflicts)
		{
			names.remove(conflict);
		}
		return names;
	}

	private Map<String, JsonObject> inputReceipts(JsonObject input, JsonObject hit)
	{
		Map<String, JsonObject> result = new HashMap<>();
		Set<String> conflicts = new HashSet<>();
		int count = 0;
		for (JsonElement value : array(input.get("cues")))
		{
			if (++count > MAX_CUES)
			{
				break;
			}
			JsonObject cue = object(value);
			if (!"INPUT_REQUEST".equals(text(cue, "evidenceMode")) || declaredInput(cue, hit, false) == null)
			{
				continue;
			}
			JsonObject witness = object(cue.get("witness"));
			String key = integer(witness.get("itemId")) + "|" + number(witness.get("inputSequence"));
			JsonObject previous = result.putIfAbsent(key, witness);
			if (previous != null && !Objects.equals(text(previous, "sourceRef"), text(witness, "sourceRef")))
			{
				conflicts.add(key);
			}
		}
		for (String conflict : conflicts)
		{
			result.remove(conflict);
		}
		return result;
	}

	private JsonObject inputCue(JsonObject original, JsonObject hit, Map<String, String> labels,
		Map<String, JsonObject> receipts)
	{
		if (text(original, "evidenceMode") == null && "interaction_damage".equals(text(original, "pattern"))
			&& "item".equals(text(object(original.get("source")), "kind")))
		{
			JsonObject receipt = receipts
				.get(integer(object(original.get("source")).get("id")) + "|" + number(original.get("sequence")));
			if (receipt != null && containsIgnoreCase(object(original.get("signals")).get("interactionOptions"),
				text(receipt, "option")))
			{
				original = original.deepCopy();
				original.addProperty("evidenceMode", "INPUT_REQUEST");
				original.add("witness", receipt);
				original.addProperty("recordedMenuReceiptLinked", true);
			}
		}
		String mode = text(original, "evidenceMode");
		boolean consumption = "CONSUMPTION".equals(mode);
		if ((!consumption && !"INPUT_REQUEST".equals(mode)) || declaredInput(original, hit, consumption) == null)
		{
			return original;
		}
		JsonObject cue = new JsonObject();
		for (Map.Entry<String, JsonElement> field : original.entrySet())
		{
			cue.add(field.getKey(), field.getValue());
		}
		JsonObject source = object(original.get("source")).deepCopy();
		cue.add("source", source);
		source.addProperty("familyKey", "input-rule:" + text(object(cue.get("witness")), "sourceRef"));
		String name = labels.get(inputKey(cue));
		if (name != null)
		{
			source.addProperty("name", name);
			cue.addProperty("observedLabel", name);
		}
		cue.addProperty("sourceAssociation",
			consumption || name != null ? "ITEM_CONSUMPTION_CORRELATION" : "SELF_DAMAGE_INPUT_CORRELATION");
		return cue;
	}

	private static boolean containsIgnoreCase(JsonElement values, String expected)
	{
		for (JsonElement value : array(values))
		{
			if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
				&& expected.equalsIgnoreCase(value.getAsString()))
			{
				return true;
			}
		}
		return false;
	}

	/** Closed exhaustive outcome masks reject a component only after its original witness qualifies. */
	private void applyOutcomeBindings(ResolutionCandidate row, JsonObject cue, JsonObject hit)
	{
		if (references == null || integer(hit.get("hitsplatType")) == null)
		{
			return;
		}
		Integer type = integer(hit.get("hitsplatType"));
		String surface = references.hitsplatSurface(type);
		if (!contains(knownHitsplatTypes, hit.get("hitsplatType")) || surface == null || "UNKNOWN".equals(surface)
			|| "CONTEXTUAL_RESOURCE".equals(surface) || surface.contains("_OR_"))
		{
			return;
		}
		JsonObject source = object(cue.get("source"));
		Map<String, Boolean> completeDomains = new LinkedHashMap<>(), matchedDomains = new LinkedHashMap<>();
		Map<String, JsonArray> rejectedDomains = new LinkedHashMap<>();
		for (JsonElement value : rules("outcome", "*"))
		{
			JsonObject rule = object(value), selector = object(rule.get("selector"));
			String kind = text(selector, "kind");
			boolean selected = "NPC_ORDINARY_ATTACK".equals(kind) && "npc".equals(text(source, "kind"))
				&& "ORDINARY_ATTACK".equals(text(cue, "mechanism"))
				|| "OBJECT".equals(kind) && "object".equals(text(source, "kind"))
					&& "contact_hazard".equals(text(cue, "pattern"))
				|| "NPC_PROJECTILE_COMPONENT".equals(kind) && "npc".equals(text(source, "kind"))
					&& "projectile_impact".equals(text(cue, "pattern"))
				|| "ITEM".equals(kind) && "item".equals(text(source, "kind"))
					&& "INPUT_REQUEST".equals(text(cue, "evidenceMode"));
			if (!selected || selector.has("ids") && !contains(selector.get("ids"), source.get("id")))
			{
				continue;
			}
			if ("ITEM".equals(kind)
				&& (!Objects.equals(text(selector, "inputSourceRef"), text(object(cue.get("witness")), "sourceRef"))
					|| !containsIgnoreCase(selector.get("operations"),
						String.valueOf(text(object(cue.get("witness")), "option")))))
			{
				continue;
			}
			if ("NPC_PROJECTILE_COMPONENT".equals(kind))
			{
				boolean projectile = false;
				for (JsonElement id : array(object(cue.get("signals")).get("projectileIds")))
				{
					projectile |= contains(selector.get("projectileIds"), id);
				}
				if (!projectile || !contains(selector.get("observedNames"), source.get("name"))
					|| !Arrays.asList("HP", "BLOCK").contains(surface))
				{
					continue;
				}
			}
			completeDomains.putIfAbsent(kind, true);
			matchedDomains.putIfAbsent(kind, false);
			boolean qualified = Boolean.TRUE.equals(bool(rule.get("complete")));
			for (JsonElement required : array(rule.get("requiredObservations")))
			{
				String flag = required.getAsString();
				qualified &= "QUALIFIED_SELF_INPUT".equals(flag) ? qualifiedSelfInput(cue, hit, selector)
					: "QUALIFIED_ORDINARY_COMPONENT".equals(flag)
						? observed(cue, "ORDINARY_ATTACK_COMPONENT")
							&& !Boolean.TRUE.equals(bool(cue.get("unstableOriginalSource")))
						: "SOURCE_DEFINITION_BOUND".equals(flag)
							? integer(source.get("id")) != null && "ELIGIBLE_REFERENCE_SURFACE"
								.equals(text(object(cue.get("witness")), "sourceEligibility"))
							: "QUALIFIED_ORIGINAL_PROJECTILE_COMPONENT".equals(flag)
								? originalProjectileOwner(cue, hit, true, false)
								: observed(cue, flag);
			}
			JsonElement allowed = number(hit.get("amount")).signum() == 0 ? rule.get("zeroHitsplatTypes")
				: rule.get("positiveHitsplatTypes");
			if (!qualified || !known(allowed))
			{
				completeDomains.put(kind, false);
				row.evidence.add("HITSPLAT_SIGNATURE_UNESTABLISHED:" + text(rule, "key"));
				continue;
			}
			boolean compatible = contains(allowed, hit.get("hitsplatType"));
			matchedDomains.put(kind, matchedDomains.get(kind) || compatible);
			row.evidence.add("HITSPLAT_SIGNATURE:" + text(rule, "key") + ":observed=" + type + ";allowed=" + allowed);
			if (!compatible)
			{
				rejectedDomains.computeIfAbsent(kind, key -> new JsonArray())
					.add("HITSPLAT_TYPE_MISMATCH:" + text(rule, "key") + ":observed=" + type + ";allowed=" + allowed);
			}
		}
		for (String kind : completeDomains.keySet())
		{
			if (completeDomains.get(kind) && !matchedDomains.get(kind) && rejectedDomains.containsKey(kind))
			{
				row.status = "EXCLUDED";
				row.legacyApiReferenceConflictEligible = false;
				for (JsonElement reason : rejectedDomains.get(kind))
				{
					row.reasons.add(reason);
				}
			}
		}
	}

	private boolean contactAdmitted(JsonObject cue, JsonObject hit)
	{
		return contactQualification(cue, hit) == Truth.TRUE;
	}

	private Truth contactQualification(JsonObject cue, JsonObject hit)
	{
		JsonObject source = object(cue.get("source")), witness = object(cue.get("witness"));
		Truth nativeScope = scope(source, hit);
		if (nativeScope == Truth.FALSE || Boolean.FALSE.equals(bool(witness.get("damaging")))
			|| Boolean.TRUE.equals(bool(witness.get("finished")))
			|| Arrays.asList("inactive", "warning").contains(text(witness, "state"))
			|| Arrays.asList("NOT_DAMAGE_SURFACE", "RESOURCE_REFERENCE_ONLY", "DEFINITION_NOT_QUALIFIED",
				"UNCLASSIFIED_GRAPHIC_SURFACE").contains(text(witness, "sourceEligibility")))
		{
			return Truth.FALSE;
		}
		boolean query = Boolean.TRUE.equals(bool(cue.get("contactObserved")))
			|| Boolean.TRUE.equals(bool(cue.get("originalNpcContact")));
		if (query)
		{
			String stage = text(witness, "observationStage");
			if (stage != null && !"HITSPLAT_CALLBACK".equals(stage)
				|| differentKnown(witness.get("observedCycle"), hit.get("cycle"))
				|| differentKnown(cue.get("tick"), hit.get("tick"))
				|| differentKnown(cue.get("cycle"), hit.get("cycle"))
				|| differentKnown(cue.get("sequence"), hit.get("sequence")))
			{
				return Truth.FALSE;
			}
			if (stage == null || !known(witness.get("observedCycle")))
			{
				return Truth.UNKNOWN;
			}
		}
		if (nativeScope == Truth.UNKNOWN)
		{
			return Truth.UNKNOWN;
		}
		for (JsonObject policy : rules("contact_limit", text(source, "kind") + ":" + integer(source.get("id"))))
		{
			if (!nativeObjectContact(policy, cue, hit) || policy.has("observedHitsplatTypes")
				&& (!contains(policy.get("observedHitsplatTypes"), hit.get("hitsplatType")) || !originalHpPosition(hit)
					|| !"RECEIVED".equals(text(hit, "direction")) || !"player".equals(text(hit, "recipientRef"))
					|| !qualifiedSurface(witness)))
			{
				return Truth.FALSE;
			}
		}
		return Truth.TRUE;
	}

	private ResolutionCandidate weakReference(JsonObject cue, JsonObject hit, String reason)
	{
		JsonObject source = object(cue.get("source"));
		ResolutionCandidate row = null;
		boolean allRejected = true;
		for (JsonObject rule : rules("component", text(source, "kind") + ":" + integer(source.get("id"))))
		{
			if (!Boolean.TRUE.equals(bool(rule.get("profile")))
				&& Arrays.asList("contact_hazard", "area_impact").contains(text(rule, "pattern"))
				&& identity(object(rule.get("source")), source) != Truth.FALSE
				&& conditions(rule.get("when"), cue, hit) != Truth.FALSE)
			{
				if (row == null)
				{
					row = ResolutionCandidate.hint("observed:source-reference", cue);
				}
				row.evidence.add(reason + ":" + text(rule, "id"));
				allRejected &= identity(object(rule.get("source")), source) == Truth.TRUE
					&& conditions(rule.get("when"), cue, hit) == Truth.TRUE
					&& componentSignature(object(rule.get("outcome")), hit) == Truth.FALSE;
			}
		}
		if (row != null)
		{
			if (allRejected)
			{
				row.status = "EXCLUDED";
				row.reasons.add("SIGNATURE_MISMATCH");
			}
			else
			{
				applyOutcomeBindings(row, cue, hit);
			}
			row.scalarEligible = false;
			row.confidence = "UNKNOWN";
			row.sourceOnly = true;
		}
		return row;
	}

	/** Physical agreement is an observed association, never proof that this entity caused HP loss. */
	private boolean contactAgreement(JsonObject cue, JsonObject hit)
	{
		JsonObject source = object(cue.get("source")), nativeScope = object(hit.get("scope")),
			frame = object(hit.get("impactFrame"));
		if (differentKnown(hit.get("contextEpoch"), hit.get("finalizationContextEpoch"))
			&& !sameKnown(frame.get("contextEpoch"), hit.get("contextEpoch")))
		{
			return false;
		}
		if (!originalHpPosition(hit) || scope(source, hit) != Truth.TRUE
			|| !Boolean.TRUE.equals(bool(frame.get("nativeContextKnown"))) || !sameNumber(frame, "tick", hit, "tick")
			|| !sameNumber(frame, "worldViewId", nativeScope, "worldViewId")
			|| !sameNumber(frame, "plane", nativeScope, "plane")
			|| !Objects.equals(text(frame, "sceneKey"), text(nativeScope, "sceneKey")))
		{
			return false;
		}
		String kind = text(source, "kind");
		for (JsonElement value : array(hit.get("observedHazardContacts")))
		{
			JsonObject contact = object(value);
			if (!"HITSPLAT_CALLBACK".equals(text(contact, "observationStage"))
				|| !sameNumber(contact, "observedCycle", hit, "cycle")
				|| !"SERVER_REPORTED_TILE".equals(text(contact, "defenderPositionEvidence"))
				|| !"ELIGIBLE_REFERENCE_SURFACE".equals(text(contact, "sourceEligibility"))
				|| !Boolean.TRUE.equals(bool(contact.get("damaging")))
				|| !sameNumber(contact, "worldViewId", nativeScope, "worldViewId")
				|| !sameNumber(contact, "plane", nativeScope, "plane")
				|| !Objects.equals(text(contact, "sceneKey"), text(nativeScope, "sceneKey")))
			{
				continue;
			}
			if ("npc".equals(kind))
			{
				if (!"NPC_CONTACT".equals(text(contact, "kind")) || !sameNumber(contact, "npcId", source, "id")
					|| text(source, "actorRef") == null
					|| !Objects.equals(text(contact, "actorRef"), text(source, "actorRef")))
				{
					continue;
				}
				for (JsonElement actor : array(hit.get("impactNpcs")))
				{
					JsonObject npc = object(actor);
					Integer size = integer(npc.get("size"));
					if (!Objects.equals(text(npc, "actorRef"), text(contact, "actorRef"))
						|| !sameNumber(npc, "npcId", contact, "npcId")
						|| !sameNumber(npc, "worldViewId", nativeScope, "worldViewId")
						|| !sameNumber(npc, "plane", nativeScope, "plane")
						|| !Boolean.TRUE.equals(bool(npc.get("worldPositionKnown")))
						|| !Boolean.TRUE.equals(bool(npc.get("planeKnown"))) || size == null || size < 1 || size > 64
						|| number(npc.get("worldX")) == null || number(npc.get("worldY")) == null
						|| !sameNumber(npc, "size", contact, "observedSize"))
					{
						continue;
					}
					JsonObject area = new JsonObject();
					area.addProperty("shape", "occupied_bounds");
					area.addProperty("anchor", "source_bounds");
					JsonObject observed = new JsonObject(), bounds = new JsonObject();
					bounds.add("minX", npc.get("worldX"));
					bounds.add("minY", npc.get("worldY"));
					bounds.addProperty("maxX", number(npc.get("worldX")).add(new BigDecimal(size - 1)));
					bounds.addProperty("maxY", number(npc.get("worldY")).add(new BigDecimal(size - 1)));
					observed.add("bounds", bounds);
					if (geometry(area, observed, hit) == Truth.TRUE)
					{
						return true;
					}
				}
				continue;
			}
			String identifier = "object".equals(kind) ? "objectId" : "graphic".equals(kind) ? "graphicId" : null;
			String physical = "object".equals(kind) ? "objectObservationId" : "graphicObservationId";
			if (identifier == null || !sameNumber(contact, identifier, source, "id")
				|| number(contact.get(physical)) == null || number(contact.get(physical)).signum() <= 0
				|| Boolean.TRUE.equals(bool(contact.get("finished"))))
			{
				continue;
			}
			for (JsonElement sample : array(hit.get("impactSurfaces")))
			{
				JsonObject surface = object(sample);
				if (!sameNumber(surface, "id", source, "id") || !sameNumber(surface, physical, contact, physical)
					|| !sameNumber(surface, "worldViewId", nativeScope, "worldViewId")
					|| !sameNumber(surface, "plane", nativeScope, "plane")
					|| !Boolean.TRUE.equals(bool(surface.get("damaging")))
					|| Boolean.TRUE.equals(bool(surface.get("finished")))
					|| !"ELIGIBLE_REFERENCE_SURFACE".equals(text(surface, "sourceEligibility"))
					|| "graphic".equals(kind) != "graphicsObject".equals(text(surface, "kind"))
					|| "object".equals(kind) && !Objects.equals(text(contact, "objectKind"), text(surface, "kind")))
				{
					continue;
				}
				JsonObject settled = cue.deepCopy();
				settled.add("witness", surface);
				settled.addProperty("contactObserved", false);
				if (contactAdmitted(settled, hit))
				{
					return true;
				}
			}
		}
		return false;
	}

	private void applyContactLimit(ResolutionCandidate row, JsonObject cue, JsonObject hit)
	{
		if (!"ORIGINAL_CONTACT".equals(text(cue, "evidenceMode")) || !originalHpPosition(hit))
		{
			return;
		}
		JsonObject source = object(cue.get("source"));
		for (JsonObject policy : rules("contact_limit", text(source, "kind") + ":" + integer(source.get("id"))))
		{
			if (number(policy.get("perHitMaximum")) != null && qualifiedSurface(object(cue.get("witness")))
				&& normalContext(object(hit.get("numericContext")), object(policy.get("contextPredicate"))))
			{
				row.applyMaximum(policy.get("perHitMaximum"), hit, text(policy, "id") + ":contact-hp-maximum");
			}
		}
	}

	private boolean nativeObjectContact(JsonObject policy, JsonObject cue, JsonObject hit)
	{
		JsonObject witness = object(cue.get("witness")), nativeScope = object(hit.get("scope"));
		JsonObject frame = object(hit.get("impactFrame")), point = object(hit.get("position")),
			scene = object(hit.get("sceneMetadata"));
		if (!originalHpPosition(hit) || !scoped(object(cue.get("source")))
			|| !sameNumber(frame, "worldViewId", nativeScope, "worldViewId")
			|| !sameNumber(frame, "plane", nativeScope, "plane")
			|| !Objects.equals(text(frame, "sceneKey"), text(nativeScope, "sceneKey"))
			|| !Boolean.TRUE.equals(bool(witness.get("objectNativeScopeKnown")))
			|| !sameNumber(witness, "objectWorldViewId", nativeScope, "worldViewId")
			|| !sameNumber(witness, "objectPlane", nativeScope, "plane") || number(point.get("x")) == null
			|| number(point.get("y")) == null
			|| hasIssue(hit, "captureIssues", "impactPositionScope", "impactWorldPosition"))
		{
			return false;
		}
		String kind = text(witness, Boolean.TRUE.equals(bool(cue.get("contactObserved"))) ? "objectKind" : "kind");
		if (!Arrays.asList("gameObject", "groundObject", "wallObject", "decorativeObject").contains(kind))
		{
			return false;
		}
		boolean footprint = "CONDITIONAL_HP_OBJECT_FOOTPRINT".equals(text(policy, "surfaceOperator"));
		if (footprint && (!"gameObject".equals(kind) || references == null || integer(hit.get("hitsplatType")) == null
			|| !Arrays.asList("HP", "BLOCK").contains(references.hitsplatSurface(integer(hit.get("hitsplatType"))))
			|| references.hitsplatDamageType(integer(hit.get("hitsplatType"))) != null))
		{
			return false;
		}
		if (Boolean.TRUE.equals(bool(cue.get("contactObserved"))))
		{
			return "NATIVE_SCENE_TILE_MEMBERSHIP".equals(text(witness, "scopeEvidence"))
				&& "SERVER_REPORTED_TILE".equals(text(witness, "defenderPositionEvidence"))
				&& (footprint || sameNumber(witness, "contactWorldX", point, "x")
					&& sameNumber(witness, "contactWorldY", point, "y"));
		}
		if (!Objects.equals(text(scene, "sceneKey"), text(nativeScope, "sceneKey"))
			|| !sameNumber(scene, "worldViewId", nativeScope, "worldViewId")
			|| !sameNumber(scene, "plane", nativeScope, "plane"))
		{
			return false;
		}
		if (footprint
			|| Boolean.TRUE.equals(bool(witness.get("localPositionKnown"))) && known(witness.get("minSceneX")))
		{
			Integer width = integer(scene.get("width")), height = integer(scene.get("height"));
			Integer x1 = integer(witness.get("minSceneX")), y1 = integer(witness.get("minSceneY")),
				x2 = integer(witness.get("maxSceneX")), y2 = integer(witness.get("maxSceneY"));
			Integer baseX = integer(scene.get("baseX")), baseY = integer(scene.get("baseY"));
			if (width == null || height == null || width < 1 || height < 1 || width > 104 || height > 104
				|| baseX == null || baseY == null || x1 == null || x2 == null || y1 == null || y2 == null || x1 < 0
				|| y1 < 0 || x1 > x2 || y1 > y2 || x2 >= width || y2 >= height
				|| !footprint && (!x1.equals(x2) || !y1.equals(y2)))
			{
				return false;
			}
			BigDecimal x = number(point.get("x")).subtract(new BigDecimal(baseX)),
				y = number(point.get("y")).subtract(new BigDecimal(baseY));
			return x.compareTo(new BigDecimal(x1)) >= 0 && x.compareTo(new BigDecimal(x2)) <= 0
				&& y.compareTo(new BigDecimal(y1)) >= 0 && y.compareTo(new BigDecimal(y2)) <= 0;
		}
		return Boolean.TRUE.equals(bool(witness.get("worldPositionKnown"))) && sameNumber(witness, "worldX", point, "x")
			&& sameNumber(witness, "worldY", point, "y");
	}

	private static boolean qualifiedSurface(JsonObject witness)
	{
		return Arrays.asList("CONDITION_NOT_OBSERVED", "ELIGIBLE_REFERENCE_SURFACE")
			.contains(text(witness, "sourceEligibility"));
	}

	private static boolean originalHpPosition(JsonObject hit)
	{
		if (hit.has("originalPositionProof"))
		{
			return Boolean.TRUE.equals(bool(hit.get("originalPositionProof")));
		}
		JsonObject raw = object(hit.get("originalHpCallback")), actor = object(raw.get("actorState")),
			nativeScope = object(hit.get("scope"));
		return "PLAYER".equals(text(raw, "receiverKind")) && "LOCAL_PLAYER".equals(text(actor, "actorKind"))
			&& Boolean.TRUE.equals(bool(actor.get("worldPositionKnown")))
			&& Boolean.TRUE.equals(bool(actor.get("planeKnown"))) && sameNumber(raw, "amount", hit, "amount")
			&& sameNumber(raw, "hitsplatType", hit, "hitsplatType")
			&& sameNumber(actor, "worldViewId", nativeScope, "worldViewId")
			&& sameNumber(actor, "plane", nativeScope, "plane")
			&& sameNumber(actor, "worldX", object(hit.get("position")), "x")
			&& sameNumber(actor, "worldY", object(hit.get("position")), "y")
			&& !hasIssue(raw, "captureIssues", "worldPosition", "worldView", "callbackGameState", "callbackCycle",
				"observationActorScope", "observationActorWorld", "observationContext", "primary", "hitsplatType",
				"hitsplat.type", "impactPositionScope")
			&& !hasIssue(actor, "observationIssues", "worldPosition", "worldView", "plane", "scope");
	}

	private boolean qualifiedResponse(JsonObject rule, JsonObject cue, JsonObject hit)
	{
		JsonObject witness = object(cue.get("witness")), frame = object(hit.get("impactFrame"));
		String message = text(witness, "message"), channel = text(witness, "channel");
		if (message != null)
		{
			message = message.replaceAll("<[^>]*>", "");
		}
		return scope(object(cue.get("source")), hit) == Truth.TRUE
			&& contains(rule.get("admittedHitsplatTypes"), hit.get("hitsplatType")) && message != null
			&& contains(object(rule.get("signals")).get("messages"), new com.google.gson.JsonPrimitive(message))
			&& channel != null
			&& contains(object(rule.get("signals")).get("channels"), new com.google.gson.JsonPrimitive(channel))
			&& Boolean.TRUE.equals(bool(witness.get("messagePresent")))
			&& "LOGGED_IN".equals(text(witness, "callbackGameState")) && !known(witness.get("captureIssues"))
			&& sameNumber(cue, "tick", hit, "tick") && number(cue.get("sequence")) != null
			&& number(cue.get("sequence")).signum() > 0 && Boolean.TRUE.equals(bool(frame.get("nativeContextKnown")))
			&& sameNumber(frame, "tick", hit, "tick")
			&& normalContext(object(hit.get("numericContext")), object(rule.get("contextPredicate")));
	}

	private void applyResponseLimit(ResolutionCandidate row, JsonObject cue, JsonObject hit, JsonObject input)
	{
		String family = text(object(row.source), "familyKey");
		if (family == null || !family.startsWith("input-rule:") || scope(object(row.source), hit) != Truth.TRUE)
		{
			return;
		}
		for (JsonElement value : array(input.get("cues")))
		{
			JsonObject response = object(value);
			if (!"SYSTEM_RESPONSE".equals(text(response, "evidenceMode")))
			{
				continue;
			}
			for (JsonObject rule : evidenceRules("SYSTEM_RESPONSE"))
			{
				if (family.equals("input-rule:wiki:" + text(rule, "label")) && qualifiedResponse(rule, response, hit))
				{
					JsonElement maximum = object(rule.get("declaration")).get("admissionMaximum");
					if (number(maximum) != null && number(hit.get("amount")).compareTo(number(maximum)) > 0)
					{
						row.status = "EXCLUDED";
						row.legacyApiReferenceConflictEligible = false;
						row.reasons.add("SELF_RESPONSE_AMOUNT_EXCEEDED:" + text(rule, "id"));
					}
				}
			}
		}
	}

	private boolean qualifiedEndpoint(JsonObject cue, JsonObject hit, JsonObject input)
	{
		JsonObject state = object(cue.get("witness")), scope = object(object(cue.get("source")).get("scope"));
		if (!originalProjectileOwner(cue, hit, false) || !Boolean.TRUE.equals(bool(input.get("eventsKnown")))
			|| Boolean.TRUE.equals(bool(input.get("eventsTruncated")))
			|| !Boolean.TRUE.equals(bool(state.get("destinationKnown")))
			|| !sameNumber(state, "targetWorldViewId", scope, "worldViewId")
			|| !sameNumber(state, "destinationPlane", scope, "plane")
			|| !sameNumber(state, "endWorldX", object(hit.get("position")), "x")
			|| !sameNumber(state, "endWorldY", object(hit.get("position")), "y")
			|| number(state.get("observedCycle")) == null || number(state.get("observedCycle")).signum() < 0
			|| number(state.get("observedCycle")).compareTo(number(hit.get("cycle"))) > 0
			|| number(cue.get("sequence")) == null || number(hit.get("sequence")) == null
			|| number(cue.get("sequence")).signum() <= 0
			|| number(cue.get("sequence")).compareTo(number(hit.get("sequence"))) >= 0
			|| hasIssue(state, "observationIssues", "sourceActor", "source.name", "source.actorRef", "source.worldView",
				"sourcePlane", "projectileId", "endCycle"))
		{
			return false;
		}
		for (String field : Arrays.asList("targetPlane", "targetActorWorldViewId", "targetLocalWorldViewId"))
		{
			if (known(state.get(field))
				&& !equal(state.get(field), scope.get("targetPlane".equals(field) ? "plane" : "worldViewId")))
			{
				return false;
			}
		}
		String destination = text(state, "destinationEvidence");
		return "PROJECTILE_API_TARGET_POINT".equals(destination) && !hasIssue(state, "observationIssues", "targetPoint")
			|| "TARGET_LOCAL_COORDINATE_CONVERSION".equals(destination)
				&& !hasIssue(state, "observationIssues", "targetPointConversion", "targetPlane")
				&& integer(state.get("targetLocalX")) != null && integer(state.get("targetLocalY")) != null
				&& integer(state.get("targetLocalWorldViewId")) != null && integer(state.get("targetPlane")) != null
				&& Arrays.asList("MOVED_CALLBACK_TARGET", "PROJECTILE_API_TARGET")
					.contains(text(state, "targetPositionEvidence"));
	}

	private static boolean originalProjectileOwner(JsonObject cue, JsonObject hit)
	{
		return originalProjectileOwner(cue, hit, true);
	}

	private static boolean originalProjectileOwner(JsonObject cue, JsonObject hit, boolean noOverrides)
	{
		return originalProjectileOwner(cue, hit, noOverrides, true);
	}

	private static boolean originalProjectileOwner(JsonObject cue, JsonObject hit, boolean noOverrides,
		boolean nativeSource)
	{
		return Boolean.TRUE.equals(bool(object(cue.get("originalProof"))
			.get(!nativeSource ? "strict_component" : noOverrides ? "strict_owner" : "strict_owner_with_overrides")));
	}

	private static boolean sameNumber(JsonObject a, String fieldA, JsonObject b, String fieldB)
	{
		return number(a.get(fieldA)) != null && number(b.get(fieldB)) != null && equal(a.get(fieldA), b.get(fieldB));
	}

	private static boolean hasIssue(JsonObject value, String field, String... prefixes)
	{
		return issuePresent(value.get(field), prefixes);
	}

	static boolean issuePresent(JsonElement issues, String... prefixes)
	{
		if (!known(issues))
		{
			return false;
		}
		if (!(issues instanceof JsonObject) && !(issues instanceof JsonArray))
		{
			return true;
		}
		List<String> keys = new ArrayList<>();
		if (issues instanceof JsonObject)
		{
			keys.addAll(issues.getAsJsonObject().keySet());
		}
		else
		{
			for (JsonElement item : issues.getAsJsonArray())
			{
				if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString())
				{
					return true;
				}
				keys.add(item.getAsString());
			}
		}
		for (String key : keys)
		{
			for (String prefix : prefixes)
			{
				if (key.startsWith(prefix))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static boolean normalContext(JsonObject context, JsonObject predicate)
	{
		JsonObject evidence = object(context.get("evidence"));
		if (!predicate.has("requiredZeroVariables") || !predicate.get("requiredZeroVariables").isJsonArray()
			|| !predicate.has("excludedWorldTypeFragments")
			|| !predicate.get("excludedWorldTypeFragments").isJsonArray() || !context.has("worldTypes")
			|| !context.get("worldTypes").isJsonArray()
			|| !"OBSERVED_CLIENT_WORLD_TYPES".equals(text(evidence, "worldTypes")))
		{
			return false;
		}
		for (JsonElement zero : array(predicate.get("requiredZeroVariables")))
		{
			if (!"LEAGUE_TYPE".equals(zero.getAsString())
				|| !"OBSERVED_CLIENT_VARBIT".equals(text(evidence, zero.getAsString()))
				|| !Integer.valueOf(0).equals(integer(context.get("leagueType"))))
			{
				return false;
			}
		}
		for (JsonElement type : array(context.get("worldTypes")))
		{
			if (!type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString())
			{
				return false;
			}
			for (JsonElement excluded : array(predicate.get("excludedWorldTypeFragments")))
			{
				if (type.getAsString().contains(excluded.getAsString()))
				{
					return false;
				}
			}
		}
		return true;
	}

	/** Intersect independent guards; alternative cases exclude only when all cases are qualified. */
	public JsonObject evaluateQualifications(JsonObject cue, JsonObject hit)
	{
		JsonObject decision = new JsonObject(), source = object(cue.get("source"));
		JsonArray evidence = new JsonArray(), reasons = new JsonArray();
		decision.add("evidence", evidence);
		decision.add("reasons", reasons);
		boolean alternatives = false, allExcluded = true, guardExcluded = false;
		String exclusionScope = null;
		if (!"npc".equals(text(source, "kind")) || number(hit.get("amount")) == null)
		{
			decision.addProperty("excluded", false);
			return decision;
		}
		for (JsonObject limit : rules("qualified_limit", "npc:" + integer(source.get("id"))))
		{
			boolean independent = "independent_guard".equals(text(limit, "composition"));
			alternatives |= !independent;
			BigDecimal maximum = number(limit.get("maximum"));
			if (maximum == null || maximum.signum() < 0)
			{
				allExcluded &= independent;
				continue;
			}
			List<String> missing = new ArrayList<>();
			if ("ALL_DIRECT_HP_COMPONENTS".equals(text(limit, "exclusionScope"))
				&& (text(source, "actorRef") == null || text(source, "actorRef").trim().isEmpty() || !scoped(source)))
			{
				missing.add("NPC_DIRECT_SOURCE_HYPOTHESIS");
			}
			for (JsonElement reason : array(limit.get("staticMissing")))
			{
				missing.add(reason.getAsString());
			}
			for (JsonElement value : array(limit.get("when")))
			{
				JsonObject predicate = object(value);
				JsonArray one = new JsonArray();
				one.add(predicate);
				Truth match = conditions(one, cue, hit);
				if (match != Truth.TRUE)
				{
					missing.add("numericFacts".equals(text(predicate, "at")) && match == Truth.FALSE
						? "BOUND_CONDITION_NOT_APPLICABLE"
						: text(predicate, "reason"));
					if (match == Truth.UNKNOWN && "numericFacts".equals(text(predicate, "at")))
					{
						evidence.add("BOUND_CONDITION_MISSING_FACTS:" + text(predicate, "fact"));
					}
				}
			}
			boolean qualified = "verified".equals(text(limit, "authority")) && missing.isEmpty();
			boolean exceeds = number(hit.get("amount")).compareTo(maximum) > 0;
			String comparison = number(hit.get("amount")).toPlainString() + (exceeds ? " > " : " <= ")
				+ maximum.toPlainString();
			evidence
				.add((qualified ? "QUALIFIED_MAXIMUM:" : "REFERENCE_MAXIMUM:") + text(limit, "id") + ":" + comparison);
			if (!missing.isEmpty())
			{
				evidence.add("BOUND_CONDITIONS_UNESTABLISHED:" + String.join(",", missing));
			}
			if (!independent && (!qualified || !exceeds))
			{
				allExcluded = false;
			}
			if (qualified && exceeds)
			{
				reasons.add("MAXIMUM_EXCEEDED:" + text(limit, "id") + ":" + comparison);
				guardExcluded |= independent;
				String scope = text(limit, "exclusionScope");
				if (!"ALL_DIRECT_HP_COMPONENTS".equals(exclusionScope))
				{
					exclusionScope = scope;
				}
			}
		}
		boolean excluded = (guardExcluded || alternatives && allExcluded) && reasons.size() > 0;
		decision.addProperty("excluded", excluded);
		decision.addProperty("exclusionScope", exclusionScope);
		decision.addProperty("legacyApiReferenceConflictEligible",
			excluded && !"ALL_DIRECT_HP_COMPONENTS".equals(exclusionScope)
				&& Boolean.TRUE.equals(bool(cue.get("apiSourceObserved")))
				&& "STRONG".equals(text(cue, "candidateStrength")) && text(source, "actorRef") != null
				&& scoped(source));
		return decision;
	}

	private static boolean observed(JsonObject cue, String flag)
	{
		return Boolean.TRUE.equals(bool(object(cue.get("observations")).get(flag)));
	}

	private static boolean scoped(JsonObject source)
	{
		JsonObject scope = object(source.get("scope"));
		return text(scope, "sceneKey") != null && !text(scope, "sceneKey").trim().isEmpty()
			&& integer(scope.get("worldViewId")) != null && integer(scope.get("plane")) != null;
	}

	private static boolean qualifiedComponent(ResolutionCandidate row)
	{
		return Boolean.TRUE.equals(row.identityQualified) && Boolean.TRUE.equals(row.componentIdentified);
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
		{
			return eligible;
		}
		for (JsonObject bound : rules("source_bound", "npc:" + id))
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
			{
				continue;
			}
			BigDecimal value = maximum(maximum, cue, hit);
			if (value == null)
			{
				continue;
			}
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
		{
			return proof;
		}
		BigDecimal sequence = number(cue.get("sequence")), hitSequence = number(hit.get("sequence"));
		return sequence != null && sequence.signum() > 0 && hitSequence != null && sequence.compareTo(hitSequence) < 0;
	}

	private static boolean declaredSpecialBinding(JsonObject rule, JsonObject cue, JsonObject hit)
	{
		if (!applicableSpecialBinding(rule, cue, hit))
		{
			return false;
		}
		return signalsMatch(object(rule.get("signals")), object(cue.get("signals")))
			|| contains(cue.get("ruleIds"), rule.get("id")) || Objects.equals(text(cue, "ruleId"), text(rule, "id"));
	}

	private static boolean applicableSpecialBinding(JsonObject rule, JsonObject cue, JsonObject hit)
	{
		return identity(object(rule.get("source")), object(cue.get("source"))) == Truth.TRUE
			&& conditions(rule.get("when"), cue, hit) == Truth.TRUE && componentBound(rule, cue, hit);
	}

	private static void overlaySourceBound(ResolutionCandidate row, JsonObject bound, JsonObject hit)
	{
		BigDecimal maximum = number(bound.get("maximum")), componentMaximum = number(row.maximum);
		if (maximum == null)
		{
			return;
		}
		row.maximum = new com.google.gson.JsonPrimitive(
			componentMaximum == null ? maximum : componentMaximum.min(maximum));
		row.identityQualified = true;
		row.evidence.add("SOURCE_BOUND:" + text(bound, "id"));
		if (number(hit.get("amount")).compareTo(maximum) > 0)
		{
			row.status = "EXCLUDED";
			row.exclusionScope = "ALL_DIRECT_HP_COMPONENTS";
			row.reasons.add("SOURCE_MAXIMUM_EXCEEDED");
		}
	}

	/** Verified positive bindings identify the ordinary component even when this actor has specials. */
	public boolean positiveOrdinaryBinding(JsonObject cue, JsonObject hit)
	{
		JsonObject source = object(cue.get("source"));
		Integer sourceId = integer(source.get("id"));
		if (!"npc".equals(text(source, "kind")) || sourceId == null)
		{
			return false;
		}
		for (JsonObject profile : profiles("npc:" + sourceId))
		{
			if (ordinaryBinding(profile, cue, hit))
			{
				return true;
			}
		}
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
		{
			key = text(cue, "key");
		}
		String source = sourceKey(object(cue.get("source")));
		return key == null || source == null ? null : key + "|" + source;
	}

	/** Positive, source-owned classifications reject this observed cue, never an actor's other attacks. */
	private JsonObject nonDamagingActivity(JsonObject cue, JsonObject hit, String lookup)
	{
		// No HP damage does not prove that this control/effect cannot accompany a zero/block callback.
		BigDecimal amount = number(hit.get("amount"));
		if (amount == null || amount.signum() <= 0)
		{
			return null;
		}
		JsonObject source = object(cue.get("source"));
		for (JsonObject rule : rules("activity", lookup))
		{
			if ("non_damaging".equals(text(rule, "classificationRole")) && "verified".equals(text(rule, "authority"))
				&& identity(object(rule.get("source")), source) == Truth.TRUE && scope(source, hit) == Truth.TRUE
				&& conditions(rule.get("when"), cue, hit) == Truth.TRUE
				&& signalsMatch(object(rule.get("signals")), object(cue.get("signals"))))
			{
				return rule;
			}
		}
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
			{
				if (contains(field.getValue(), actual))
				{
					matched = true;
				}
			}
			if (!matched)
			{
				return false;
			}
			positive = true;
		}
		return positive;
	}

	/** Joint inspection does not greedily assign/consume a shared observation or manufacture absent hits. */

	public List<ResolutionResult> evaluateBatch(List<JsonObject> inputs)
	{
		List<ResolutionResult> outputs = new ArrayList<>();
		Map<String, Integer> uses = new HashMap<>();
		for (JsonObject input : inputs)
		{
			ResolutionResult output = evaluate(input);
			outputs.add(output);
			Set<String> perImpact = new HashSet<>();
			for (ResolutionCandidate row : output.candidates)
			{
				if ("POSSIBLE".equals(row.status))
				{
					perImpact.add(row.key);
				}
			}
			for (String key : perImpact)
			{
				uses.merge(key, 1, Integer::sum);
			}
		}
		for (ResolutionResult output : outputs)
		{
			for (ResolutionCandidate row : output.candidates)
			{
				if (uses.getOrDefault(row.key, 0) > 1)
				{
					row.evidence.add("SHARED_OBSERVATION_ASSIGNMENT_UNESTABLISHED");
					output.scalarAssociation = "AMBIGUOUS";
				}
			}
		}
		return outputs;
	}

	private static boolean componentBound(JsonObject rule, JsonObject cue, JsonObject hit)
	{
		if (Boolean.FALSE.equals(bool(cue.get("componentBound"))))
		{
			return false;
		}
		if ("contact_hazard".equals(text(rule, "pattern")) && Boolean.TRUE.equals(bool(cue.get("contactObserved"))))
		{
			return true;
		}
		if ("status_tick".equals(text(rule, "pattern")) && "status".equals(text(object(cue.get("source")), "kind")))
		{
			return true;
		}
		JsonObject required = object(rule.get("signals")), observed = object(cue.get("signals"));
		boolean positive = false;
		for (Map.Entry<String, JsonElement> field : required.entrySet())
		{
			boolean matched = false;
			for (JsonElement actual : array(observed.get(field.getKey())))
			{
				if (contains(field.getValue(), actual))
				{
					matched = true;
				}
			}
			// Lists are useful cues, not exhaustive negative signatures.
			if (!matched)
			{
				return false;
			}
			positive = true;
		}
		if (positive)
		{
			return true;
		}
		if (Boolean.TRUE.equals(bool(cue.get("componentBound")))
			&& (cue.has("ruleIds") ? contains(cue.get("ruleIds"), rule.get("id"))
				: text(cue, "ruleId") == null || Objects.equals(text(cue, "ruleId"), text(rule, "id"))))
		{
			return true;
		}
		String kind = text(object(cue.get("source")), "kind");
		return "contact_hazard".equals(text(rule, "pattern"))
			&& Arrays.asList("npc", "object", "graphic").contains(kind)
			&& scope(object(cue.get("source")), hit) == Truth.TRUE
			&& geometry(object(rule.get("geometry")), cue, hit) == Truth.TRUE;
	}

	private static Truth componentSignature(JsonObject outcome, JsonObject hit)
	{
		if (!Boolean.TRUE.equals(bool(outcome.get("signatureComplete")))
			|| !"verified".equals(text(outcome, "signatureAuthority")))
		{
			return Truth.UNKNOWN;
		}
		Truth signature = Truth.TRUE;
		for (String field : Arrays.asList("damageFamilies", "hitsplatTypeIds"))
		{
			if (outcome.has(field))
			{
				JsonElement actual = hit.get("damageFamilies".equals(field) ? "family" : "hitsplatType");
				signature = signature.and(!known(actual) ? Truth.UNKNOWN : truth(contains(outcome.get(field), actual)));
			}
		}
		return signature;
	}

	private ResolutionCandidate match(JsonObject rule, JsonObject cue, JsonObject hit, boolean profile,
		boolean component)
	{
		ResolutionCandidate row = ResolutionCandidate.observed(text(rule, "id"), cue);
		if (profile)
		{
			row.ordinary = true;
		}
		if (text(rule, "label") != null)
		{
			row.label = text(rule, "label");
		}
		JsonArray evidence = row.evidence, reasons = row.reasons;
		if (!component)
		{
			evidence.add("COMPONENT_UNKNOWN");
		}
		JsonObject source = object(cue.get("source"));
		Truth identity = identity(object(rule.get("source")), source), scope = scope(source, hit);
		// Actor ownership can be observed across native views; different coordinates cannot qualify
		// geometry or numeric pruning, but do not invalidate that original projectile source identity.
		if (scope == Truth.FALSE && (profile && "projectile_impact".equals(text(cue, "pattern"))
			|| "recorded_transition".equals(text(rule, "scopePolicy"))))
		{
			scope = Truth.UNKNOWN;
		}
		row.identityQualified = identity == Truth.TRUE && scope == Truth.TRUE;
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
		row.componentIdentified = identified;
		boolean qualified = identified
			&& (timing != Truth.FALSE || "reference_only".equals(text(object(rule.get("timing")), "authority")));
		row.componentQualified = qualified;
		gate(geometry, "GEOMETRY", evidence, reasons, qualified && "verified".equals(text(area, "authority")));
		JsonObject outcome = object(rule.get("outcome"));
		if (profile && Arrays.asList("poison", "venom", "burn", "bleed").contains(text(hit, "family")))
		{
			reasons.add("ORDINARY_FAMILY_MISMATCH");
		}
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
			gate(componentSignature(outcome, hit), "SIGNATURE", evidence, reasons, true);
		}
		JsonObject limitOwner = profile ? rule : outcome;
		BigDecimal maximum = null;
		if (qualified && !Boolean.FALSE.equals(bool(cue.get("numericEligible")))
			&& (!profile || references == null || observed(cue, "QUALIFIED_NPC_VARIANT")
				|| object(rule.get("source")).has("names") && object(rule.get("source")).has("combatLevels"))
			&& integer(hit.get("hitsplatType")) != null && text(hit, "family") != null && recipients != Truth.FALSE
			&& reasons.size() == 0)
		{
			List<JsonElement> limits = new ArrayList<>();
			if (limitOwner.has("maximum"))
			{
				limits.add(limitOwner.get("maximum"));
			}
			for (JsonElement limit : array(limitOwner.get("additionalMaximums")))
			{
				limits.add(limit);
			}
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
			row.maximum = new com.google.gson.JsonPrimitive(maximum);
			if (number(hit.get("amount")).compareTo(maximum) > 0)
			{
				reasons.add("MAXIMUM_EXCEEDED");
			}
			else
			{
				evidence.add("WITHIN_COMPONENT_MAXIMUM");
			}
		}
		if (rule.has("calculationTick"))
		{
			evidence.add("CALCULATION_REFERENCE:" + text(rule, "calculationTick"));
		}
		if (rule.has("hitMultiplicity"))
		{
			row.hitMultiplicity = rule.get("hitMultiplicity").deepCopy();
			evidence.add("MULTIPLICITY_DOES_NOT_CREATE_HITS");
		}
		JsonObject annotations = new JsonObject();
		for (String field : Arrays.asList("calculationTick", "styles", "attackStyles", "outcome"))
		{
			if (rule.has(field))
			{
				annotations.add(field, rule.get(field).deepCopy());
			}
		}
		if (annotations.size() > 0)
		{
			row.referenceAnnotations = object(annotations);
		}
		if (rule.has("references"))
		{
			row.references = array(rule.get("references").deepCopy());
		}
		if (reasons.size() > 0)
		{
			row.status = "EXCLUDED";
		}
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
		{
			if (selector.has(field))
			{
				JsonElement actual = source.get("names".equals(field) ? "name" : "combatLevel");
				if (actual == null || actual.isJsonNull())
				{
					result = result.and(Truth.UNKNOWN);
				}
				else if ("names".equals(field))
				{
					boolean found = false;
					for (JsonElement name : array(selector.get(field)))
					{
						if (normalize(name.getAsString()).equals(normalize(actual.getAsString())))
						{
							found = true;
						}
					}
					if (!found)
					{
						return Truth.FALSE;
					}
				}
				else if (!contains(selector.get(field), actual))
				{
					return Truth.FALSE;
				}
			}
		}
		return result;
	}

	static boolean nativeMatches(JsonObject source, JsonObject hit)
	{
		return scope(source, hit) == Truth.TRUE;
	}

	private static Truth scope(JsonObject source, JsonObject hit)
	{
		JsonObject a = object(source.get("scope")), b = object(hit.get("scope"));
		Truth result = Truth.TRUE;
		for (String key : Arrays.asList("sceneKey", "worldViewId", "plane"))
		{
			if (!known(a.get(key)) || !known(b.get(key)))
			{
				result = result.and(Truth.UNKNOWN);
			}
			else if (!equal(a.get(key), b.get(key)))
			{
				return Truth.FALSE;
			}
		}
		return result;
	}

	private static Truth conditions(JsonElement values, JsonObject cue, JsonObject hit)
	{
		return conditions(values, cue, hit, 0, new int[]
		{ 0 });
	}

	private static Truth conditions(JsonElement values, JsonObject cue, JsonObject hit, int depth, int[] budget)
	{
		if (depth > 12 || ++budget[0] > MAX_CUES)
		{
			return Truth.UNKNOWN;
		}
		if (values instanceof JsonObject)
		{
			JsonObject group = object(values);
			if (group.has("at"))
			{
				JsonArray leaf = new JsonArray();
				leaf.add(group);
				return conditions(leaf, cue, hit, depth + 1, budget);
			}
			boolean any = group.has("any");
			if (group.size() != 1 || !any && !group.has("all")
				|| !(group.get(any ? "any" : "all") instanceof JsonArray))
			{
				return Truth.UNKNOWN;
			}
			Truth combined = any ? Truth.FALSE : Truth.TRUE;
			for (JsonElement branch : array(group.get(any ? "any" : "all")))
			{
				Truth value = conditions(branch, cue, hit, depth + 1, budget);
				combined = any
					? (combined == Truth.TRUE || value == Truth.TRUE ? Truth.TRUE
						: combined == Truth.UNKNOWN || value == Truth.UNKNOWN ? Truth.UNKNOWN : Truth.FALSE)
					: combined.and(value);
			}
			return combined;
		}
		if (known(values) && !(values instanceof JsonArray) || array(values).size() > MAX_CUES)
		{
			return Truth.UNKNOWN;
		}
		Truth result = Truth.TRUE;
		for (JsonElement value : array(values))
		{
			JsonObject c = object(value);
			if (c.has("all") || c.has("any"))
			{
				result = result.and(conditions(c, cue, hit, depth + 1, budget));
				continue;
			}
			if (++budget[0] > MAX_CUES)
			{
				return Truth.UNKNOWN;
			}
			String at = text(c, "at"), fact = text(c, "fact"), op = text(c, "op");
			if (fact == null || fact.isEmpty() || fact.length() > 256 || fact.split("\\.").length > 12)
			{
				result = result.and(Truth.UNKNOWN);
				continue;
			}
			JsonObject facts = factDomain(at, fact, cue, hit);
			JsonElement actual = path(facts, fact), expected = c.get("value");
			if (c.has("valueFrom"))
			{
				JsonObject other = object(c.get("valueFrom"));
				String domain = text(other, "at");
				expected = path(factDomain(domain, text(other, "fact"), cue, hit), text(other, "fact"));
			}
			if ("not_true".equals(op) || "not_false".equals(op))
			{
				Boolean observedFlag = bool(actual);
				result = result.and(!known(actual) ? Truth.TRUE
					: observedFlag == null ? Truth.UNKNOWN
						: truth("not_true".equals(op) ? !observedFlag : observedFlag));
				continue;
			}
			if ("no_issue".equals(op))
			{
				if (!(expected instanceof JsonArray) || array(expected).size() > MAX_CUES
					|| known(actual) && !(actual instanceof JsonObject) && !(actual instanceof JsonArray)
					|| actual instanceof JsonArray && array(actual).size() > MAX_CUES
					|| actual instanceof JsonObject && actual.getAsJsonObject().size() > MAX_CUES)
				{
					result = result.and(Truth.UNKNOWN);
					continue;
				}
				boolean typed = true;
				for (JsonElement item : array(actual))
				{
					typed &= textPrimitive(item) != null;
				}
				for (JsonElement item : array(expected))
				{
					typed &= textPrimitive(item) != null && !item.getAsString().isEmpty()
						&& item.getAsString().length() <= 128;
				}
				if (!typed)
				{
					result = result.and(Truth.UNKNOWN);
					continue;
				}
				List<String> prefixes = new ArrayList<>();
				for (JsonElement prefix : array(expected))
				{
					if (textPrimitive(prefix) != null)
					{
						prefixes.add(prefix.getAsString());
					}
				}
				result = result.and(
					prefixes.isEmpty() ? Truth.UNKNOWN : truth(!issuePresent(actual, prefixes.toArray(new String[0]))));
				continue;
			}
			if (!known(actual))
			{
				result = result.and(Truth.UNKNOWN);
				continue;
			}
			if ("type_is".equals(op))
			{
				String type = textPrimitive(expected);
				boolean valid = "string".equals(type) ? textPrimitive(actual) != null
					: "integer".equals(type) ? integer(actual) != null
						: "number".equals(type) ? number(actual) != null
							: "boolean".equals(type) ? bool(actual) != null
								: "nonempty_object".equals(type) && actual instanceof JsonObject
									&& actual.getAsJsonObject().size() > 0;
				result = result.and(type != null
					&& Arrays.asList("string", "integer", "number", "boolean", "nonempty_object").contains(type)
						? truth(valid)
						: Truth.UNKNOWN);
				continue;
			}
			boolean matches;
			if (!known(expected) || expected instanceof JsonArray && array(expected).size() > MAX_CUES
				|| actual instanceof JsonArray && array(actual).size() > MAX_CUES)
			{
				result = result.and(Truth.UNKNOWN);
				continue;
			}
			if (Arrays.asList("gt", "gte", "lt", "lte").contains(op))
			{
				BigDecimal left = number(actual), right = number(expected);
				if (left == null || right == null)
				{
					result = result.and(Truth.UNKNOWN);
					continue;
				}
				int order = left.compareTo(right);
				matches = "gt".equals(op) ? order > 0
					: "gte".equals(op) ? order >= 0 : "lt".equals(op) ? order < 0 : order <= 0;
			}
			else if ("equals".equals(op))
			{
				matches = equal(actual, expected);
			}
			else if ("one_of".equals(op))
			{
				matches = contains(expected, actual);
			}
			else if ("contains_any".equals(op) || "contains_all".equals(op))
			{
				if (!(actual instanceof JsonArray))
				{
					result = result.and(Truth.UNKNOWN);
					continue;
				}
				matches = "contains_all".equals(op);
				for (JsonElement element : array(expected))
				{
					if ("contains_all".equals(op))
					{
						matches &= contains(actual, element);
					}
					else
					{
						matches |= contains(actual, element);
					}
				}
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

	private static JsonObject factDomain(String at, String fact, JsonObject cue, JsonObject hit)
	{
		return "row".equals(at) ? object(cue.get("evaluation"))
			: "cue".equals(at) ? cue
				: "hit".equals(at) ? hit
					: "source".equals(at) ? object(cue.get("source"))
						: Arrays.asList("observations", "numericFacts").contains(at) ? object(cue.get(at))
							: object(object(
								("impact".equals(at) && (fact == null || !fact.startsWith("source_")) ? hit : cue)
									.get("facts")).get(at));
	}

	private boolean policy(String name, JsonObject cue, JsonObject hit)
	{
		return Boolean.TRUE.equals(bool(object(cue.get("originalProof")).get(name)));
	}

	private static JsonElement path(JsonObject root, String fact)
	{
		if (fact == null || fact.length() > 256)
		{
			return null;
		}
		if (root.has(fact))
		{
			return root.get(fact);
		}
		String[] parts = fact.split("\\.");
		if (parts.length > 12)
		{
			return null;
		}
		JsonElement value = root;
		for (String part : parts)
		{
			if (!(value instanceof JsonObject))
			{
				return null;
			}
			value = value.getAsJsonObject().get(part);
		}
		return value;
	}

	private static String textPrimitive(JsonElement value)
	{
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString()
			: null;
	}

	private static Truth timing(JsonObject timing, JsonObject cue, JsonObject hit)
	{
		if ("unknown".equals(text(timing, "authority")))
		{
			return Truth.UNKNOWN;
		}
		String field = "client_cycle".equals(text(timing, "clock")) ? "cycle"
			: "game_tick".equals(text(timing, "clock")) ? "tick" : null;
		BigDecimal start = field == null ? null : number(cue.get(field)),
			end = field == null ? null : number(hit.get(field));
		BigDecimal min = number(timing.get("minimumOffset")), max = number(timing.get("maximumOffset"));
		if (start == null || end == null || min == null || max == null || start.signum() < 0 || end.signum() < 0
			|| min.compareTo(max) > 0)
		{
			return Truth.UNKNOWN;
		}
		BigDecimal offset = end.subtract(start);
		return offset.compareTo(min) >= 0 && offset.compareTo(max) <= 0 ? Truth.TRUE : Truth.FALSE;
	}

	private static Truth recipient(String recipients, JsonObject cue, JsonObject hit)
	{
		if ("within_area".equals(recipients))
		{
			return Truth.TRUE;
		}
		String target = text(cue, "targetRef");
		return target == null ? Truth.UNKNOWN : target.equals(text(hit, "recipientRef")) ? Truth.TRUE : Truth.FALSE;
	}

	private static Truth geometry(JsonObject geometry, JsonObject cue, JsonObject hit)
	{
		String shape = text(geometry, "shape"), anchor = text(geometry, "anchor");
		if ("unknown".equals(shape) || shape == null)
		{
			return Truth.UNKNOWN;
		}
		if ("target".equals(shape))
		{
			return recipient("direct_target", cue, hit);
		}
		JsonObject point = object(hit.get("position"));
		Double x = finite(point.get("x")), y = finite(point.get("y"));
		if (x == null || y == null)
		{
			return Truth.UNKNOWN;
		}
		if ("occupied_bounds".equals(shape))
		{
			JsonObject b = object(cue.get("bounds"));
			Double minX = finite(b.get("minX")), minY = finite(b.get("minY")), maxX = finite(b.get("maxX")),
				maxY = finite(b.get("maxY"));
			if (minX == null || minY == null || maxX == null || maxY == null || minX > maxX || minY > maxY
				|| maxX - minX >= 104 || maxY - minY >= 104)
			{
				return Truth.UNKNOWN;
			}
			return truth(x >= minX && x <= maxX && y >= minY && y <= maxY);
		}
		if ("radius".equals(shape) && "source_bounds".equals(anchor))
		{
			JsonObject bounds = object(cue.get("bounds"));
			Double minX = finite(bounds.get("minX")), minY = finite(bounds.get("minY")),
				maxX = finite(bounds.get("maxX")), maxY = finite(bounds.get("maxY"));
			Double radius = finite(geometry.get("radiusTiles")),
				minimum = geometry.has("minimumRadiusTiles") ? finite(geometry.get("minimumRadiusTiles")) : 0.0;
			if (minX == null || minY == null || maxX == null || maxY == null || minX > maxX || minY > maxY
				|| maxX - minX >= 104 || maxY - minY >= 104 || radius == null || minimum == null || minimum < 0
				|| minimum > radius || radius > 104)
			{
				return Truth.UNKNOWN;
			}
			double distance = Math.max(Math.max(Math.max(minX - x, x - maxX), 0),
				Math.max(Math.max(minY - y, y - maxY), 0));
			return truth(distance >= minimum && distance <= radius);
		}
		JsonObject origin = object(cue.get("projectile_endpoint".equals(anchor) ? "endpoint" : "position"));
		Double ox = finite(origin.get("x")), oy = finite(origin.get("y"));
		if (ox == null || oy == null)
		{
			return Truth.UNKNOWN;
		}
		double dx = x - ox, dy = y - oy;
		if ("point".equals(shape))
		{
			return truth(dx == 0 && dy == 0);
		}
		if ("box".equals(shape))
		{
			Integer width = integer(geometry.get("widthTiles")), height = integer(geometry.get("heightTiles"));
			if (width == null || height == null || width < 1 || height < 1 || width > 104 || height > 104)
			{
				return Truth.UNKNOWN;
			}
			if ("center".equals(text(geometry, "origin")))
			{
				if (width % 2 == 0 || height % 2 == 0)
				{
					return Truth.UNKNOWN;
				}
				dx += width / 2;
				dy += height / 2;
			}
			else if (!"south_west".equals(text(geometry, "origin")))
			{
				return Truth.UNKNOWN;
			}
			return truth(dx >= 0 && dx < width && dy >= 0 && dy < height);
		}
		if ("radius".equals(shape))
		{
			Double radius = finite(geometry.get("radiusTiles"));
			if (radius == null || radius < 0 || radius > 104)
			{
				return Truth.UNKNOWN;
			}
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
		{
			return Truth.UNKNOWN;
		}
		double dot = (dx * vx + dy * vy) / Math.hypot(vx, vy), distance = Math.hypot(dx, dy);
		if ("line".equals(shape))
		{
			return truth(dot >= 0 && dot <= length && Math.abs(dx * vy - dy * vx) < 1e-9);
		}
		Double angle = finite(geometry.get("angleDegrees"));
		if (!"cone".equals(shape) || angle == null || angle <= 0 || angle > 360)
		{
			return Truth.UNKNOWN;
		}
		return truth(
			distance <= length && (distance == 0 || dot / distance >= Math.cos(Math.toRadians(angle / 2)) - 1e-9));
	}

	private static BigDecimal maximum(JsonObject limit, JsonObject cue, JsonObject hit)
	{
		BigDecimal value;
		if ("fixed_max".equals(text(limit, "kind")))
		{
			value = number(limit.get("value"));
		}
		else if ("linear_max".equals(text(limit, "kind")))
		{
			String inputName = text(limit, "input");
			JsonElement original = "observed_trigger_damage".equals(inputName)
				? object(object(cue.get("facts")).get("source_event")).get(inputName)
				: object(hit.get("inputs")).get(inputName);
			if (!known(original) && !"observed_trigger_damage".equals(inputName))
			{
				original = object(object(hit.get("facts")).get("impact")).get(inputName);
			}
			BigDecimal input = number(original), numerator = number(limit.get("numerator")),
				denominator = number(limit.get("denominator")), offset = number(limit.get("offset"));
			if (input == null || input.signum() < 0 || numerator == null || numerator.signum() < 0
				|| denominator == null || denominator.signum() <= 0 || offset == null)
			{
				return null;
			}
			value = input.multiply(numerator).divide(denominator, 16, RoundingMode.HALF_EVEN).add(offset);
			if ("floor".equals(text(limit, "rounding")))
			{
				value = value.setScale(0, RoundingMode.FLOOR);
			}
			else if ("ceil".equals(text(limit, "rounding")))
			{
				value = value.setScale(0, RoundingMode.CEILING);
			}
			else if (!"none".equals(text(limit, "rounding")))
			{
				return null;
			}
		}
		else
		{
			return null;
		}
		return value == null || value.signum() < 0 ? null : value;
	}

	private static void gate(Truth value, String code, JsonArray evidence, JsonArray reasons, boolean hard)
	{
		if (value == Truth.UNKNOWN)
		{
			evidence.add(code + "_UNKNOWN");
		}
		else if (value == Truth.FALSE)
		{
			if (hard)
			{
				reasons.add(code + ("CONDITION".equals(code) ? "_FALSE" : "_MISMATCH"));
			}
			else
			{
				evidence.add(code + "_REFERENCE_ONLY");
			}
		}
	}

	public static String sourceKey(JsonObject source)
	{
		String kind = text(source, "kind"), actor = text(source, "actorRef");
		if (kind == null)
		{
			return null;
		}
		String identity = actor != null ? actor
			: "status".equals(kind) ? text(source, "statusFamily")
				: text(source, "observationKey") != null ? "observation:" + text(source, "observationKey")
					: integer(source.get("id")) != null ? String.valueOf(integer(source.get("id")))
						: text(source, "familyKey") == null ? null : "family:" + text(source, "familyKey");
		if (identity == null || "null".equals(identity))
		{
			return null;
		}
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
			{
				return null;
			}
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
			{
				value.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
			}
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
		{
			return null;
		}
		double d = n.doubleValue();
		return Double.isFinite(d) ? d : null;
	}

	static boolean equal(JsonElement a, JsonElement b)
	{
		BigDecimal na = number(a), nb = number(b);
		return na != null && nb != null ? na.compareTo(nb) == 0 : Objects.equals(a, b);
	}

	private static boolean contains(JsonElement list, JsonElement value)
	{
		for (JsonElement item : array(list))
		{
			if (equal(item, value))
			{
				return true;
			}
		}
		return false;
	}
}
