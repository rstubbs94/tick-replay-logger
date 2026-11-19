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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/** Bundled factual identity and state references; never treats prose as an event. */
@Slf4j
public final class CombatCatalog
{
	private JsonObject root = new JsonObject();
	private volatile Map<Integer, List<AttributionConstraint>> attributionConstraintIndex;
	private volatile DamageCompatibilityIndex damageCompatibilityIndex;
	private volatile MechanismRules mechanismRuleIndex;
	private volatile SelfInputConstraints selfInputConstraintIndex;
	private volatile ObservedInventorySourceHints inventorySourceHintIndex;
	private volatile ObservedProjectileEndpointResolver.Rules projectileEndpointIndex;

	public static CombatCatalog load(Gson gson)
	{
		CombatCatalog catalog = new CombatCatalog();
		try (InputStream in = CombatCatalog.class.getResourceAsStream("/com/tickreplaylogger/combat_catalog.json"))
		{
			if (in != null)
				catalog.root = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
			catalog.attributionConstraintIndex = catalog.indexAttributionConstraints();
			catalog.damageCompatibilityIndex = catalog.indexDamageCompatibility();
			catalog.mechanismRuleIndex = MechanismRules.load(catalog.root.get("mechanismRules"));
			log.debug("Loaded combat research catalog {}", catalog.id());
		}
		catch (Exception error)
		{
			log.warn("Combat research reference unavailable; raw capture remains enabled.");
		}
		return catalog;
	}

	public MechanismRules mechanismRules()
	{
		MechanismRules result = mechanismRuleIndex;
		if (result == null)
		{
			synchronized (this)
			{
				result = mechanismRuleIndex;
				if (result == null)
					mechanismRuleIndex = result = MechanismRules.load(root.get("mechanismRules"));
			}
		}
		return result;
	}

	private JsonObject section(String key)
	{
		return object(root.get(key));
	}

	private static JsonObject object(JsonElement value)
	{
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
	}

	private static String string(JsonObject row, String key)
	{
		JsonElement v = row.get(key);
		return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
	}

	public String id()
	{
		return string(root, "catalogId");
	}

	/** Display context from an observed identity, never a damage-source assignment. */
	public String observedBossName(com.tickreplaylogger.model.world.NpcState npc)
	{
		if (npc == null || npc.getNpcId() == null || !Boolean.TRUE.equals(npc.getNormalWorldContextObserved())
			|| npc.getObservationIssues() != null && npc.getObservationIssues().containsKey("name")
			|| !baseVariantEstablished(npc.getNpcId(), npc.getCombatLevel(), true)
			|| !observedNameCompatible(npc.getNpcId(), npc.getName(), true))
			return null;
		String name = definitionName(npc.getName());
		if (name.isEmpty())
			return null;
		com.tickreplaylogger.model.world.NpcDefinitionStateReference definition = definitionStateReference(
			npc.getNpcId(), npc.getName(), true, npc.getCombatLevel(), npc.getSize(), true);
		if (definition != null && "Boss".equals(definition.getRole()))
			return npc.getName().replaceAll("<[^>]*>", "").trim();
		JsonObject override = object(
			object(section("reviewedBindings").get("npcOverrides")).get(String.valueOf(npc.getNpcId())));
		if ("Boss".equals(string(override, "role")) && name.equals(definitionName(string(override, "name"))))
			return npc.getName().replaceAll("<[^>]*>", "").trim();
		boolean boss = false;
		for (JsonElement item : npcCandidates(npc.getNpcId()))
		{
			JsonObject candidate = object(item);
			String page = string(candidate, "page");
			if (!name.equals(definitionName(string(candidate, "name"))) && !name.equals(definitionName(page)))
				continue;
			if (!"monster".equals(string(candidate, "infoboxKind"))
				|| !"Boss".equals(string(object(section("entityRoleReferences").get(page)), "role")))
				return null;
			boss = true;
		}
		return boss ? npc.getName().replaceAll("<[^>]*>", "").trim() : null;
	}

	/** A definition interpretation requires all independently observed identity/shape guards. */
	public com.tickreplaylogger.model.world.NpcDefinitionStateReference definitionStateReference(Integer npcId,
		String name, boolean nameKnown, Integer level, Integer size, boolean normalContext)
	{
		if (!normalContext || !nameKnown || npcId == null || level == null || size == null)
			return null;
		JsonObject row = object(section("npcDefinitionStates").get(String.valueOf(npcId)));
		if (!"NPC_DEFINITION_SOURCE_REFERENCE".equals(string(row, "evidence")) || !row.has("requiredCombatLevel")
			|| !row.has("requiredSize"))
			return null;
		String expected = string(row, "expectedName");
		if (expected == null || !definitionName(name).equals(definitionName(expected))
			|| row.get("requiredCombatLevel").getAsInt() != level || row.get("requiredSize").getAsInt() != size)
			return null;
		List<String> refs = new ArrayList<>();
		for (JsonElement ref : row.getAsJsonArray("sourceRefs"))
			refs.add(ref.getAsString());
		return com.tickreplaylogger.model.world.NpcDefinitionStateReference.builder().key(string(row, "key"))
			.catalogId(id()).npcId(npcId).name(string(row, "name")).role(string(row, "role")).form(string(row, "form"))
			.modeReference(string(row, "modeReference")).evidence(string(row, "evidence"))
			.sourceRefs(Collections.unmodifiableList(refs)).build();
	}

	private static String definitionName(String name)
	{
		String clean = name == null ? "" : name.replaceAll("<[^>]*>", "").trim();
		return "null".equalsIgnoreCase(clean) ? "" : clean.toLowerCase(Locale.ROOT);
	}

	/** Reject an observed name contradiction; compatibility alone does not certify identity or cache epoch. */
	public boolean observedNameCompatible(int npcId, String name, boolean nameKnown)
	{
		if (!nameKnown)
			return false;
		String observed = definitionName(name);
		if (observed.isEmpty())
			return true; // A successful unnamed read is distinct from a failed read.
		JsonElement candidates = object(section("npcIdentities").get(String.valueOf(npcId))).get("candidates");
		boolean namedReference = false;
		if (candidates != null && candidates.isJsonArray())
			for (JsonElement value : candidates.getAsJsonArray())
			{
				JsonObject candidate = object(value);
				for (String field : Arrays.asList("name", "page"))
				{
					String expected = definitionName(string(candidate, field));
					if (expected.isEmpty())
						continue;
					namedReference = true;
					if (expected.equals(observed))
						return true;
				}
			}
		return !namedReference; // No name reference cannot establish a positive contradiction.
	}

	public JsonObject contextBindings()
	{
		return section("contextBindings");
	}

	public JsonObject observedGraphicCues()
	{
		return section("observedGraphicCues");
	}

	public JsonObject observedNpcContactHints()
	{
		return section("observedNpcContactHints");
	}

	public JsonObject observedSelfDamageTextHints()
	{
		return section("observedSelfDamageTextHints");
	}

	public SelfInputConstraints selfInputConstraints()
	{
		SelfInputConstraints index = selfInputConstraintIndex;
		if (index == null)
			synchronized (this)
			{
				index = selfInputConstraintIndex;
				if (index == null)
					selfInputConstraintIndex = index = new SelfInputConstraints(section("selfInputConstraints"), this);
			}
		return index;
	}

	public ObservedInventorySourceHints inventorySourceHints()
	{
		ObservedInventorySourceHints index = inventorySourceHintIndex;
		if (index == null)
			synchronized (this)
			{
				index = inventorySourceHintIndex;
				if (index == null)
					inventorySourceHintIndex = index = new ObservedInventorySourceHints(
						section("observedInventorySourceHints"));
			}
		return index;
	}

	public ObservedProjectileEndpointResolver.Rules projectileEndpointHints()
	{
		ObservedProjectileEndpointResolver.Rules index = projectileEndpointIndex;
		if (index == null)
			synchronized (this)
			{
				index = projectileEndpointIndex;
				if (index == null)
					projectileEndpointIndex = index = new ObservedProjectileEndpointResolver.Rules(
						section("observedProjectileEndpointHints"));
			}
		return index;
	}

	private JsonObject localStatusRule(String key)
	{
		if (!CaptureBuild.runtimeReferenceMatched())
			return new JsonObject();
		JsonElement rules = contextBindings().get("statusValueRules");
		if (rules != null && rules.isJsonArray())
			for (JsonElement value : rules.getAsJsonArray())
			{
				JsonObject rule = value.getAsJsonObject();
				if (key.equals(string(rule, "key"))
					&& Arrays.asList("varp", "varbit").contains(string(rule, "namespace"))
					&& "player".equals(string(rule, "actorRef")) && CaptureBuild.supportsClientReference(
						string(rule, "clientApiVersion"), string(rule, "clientArtifactSha256")))
					return rule;
			}
		return new JsonObject();
	}

	public Integer localStatusVarpId(String key)
	{
		JsonObject rule = localStatusRule(key);
		return "varp".equals(string(rule, "namespace")) && rule.has("id") ? rule.get("id").getAsInt() : null;
	}

	/** Whether this guarded variable rule describes the recorded damage-type state at all. */
	public boolean localStatusCanRepresentDamage(String key, String damageType)
	{
		JsonObject rule = localStatusRule(key);
		JsonElement cases = rule.get("cases");
		if (cases != null && cases.isJsonArray())
			for (JsonElement value : cases.getAsJsonArray())
				if (damageType != null && damageType.equals(string(object(value), "state")))
					return true;
		return false;
	}

	public JsonObject localStatusCase(String key, int value)
	{
		JsonObject rule = localStatusRule(key);
		JsonElement cases = rule.get("cases");
		if (cases != null && cases.isJsonArray())
			for (JsonElement entry : cases.getAsJsonArray())
			{
				JsonObject range = entry.getAsJsonObject();
				if (value >= range.get("min").getAsInt() && value <= range.get("max").getAsInt())
					return range;
			}
		return new JsonObject();
	}

	public String localStatusState(String key, int value)
	{
		return string(localStatusCase(key, value), "state");
	}

	public JsonObject localGraphicReference(int id)
	{
		JsonElement rules = contextBindings().get("graphicReferenceRules");
		if (rules != null && rules.isJsonArray())
			for (JsonElement value : rules.getAsJsonArray())
			{
				JsonObject rule = value.getAsJsonObject();
				if (rule.has("id") && rule.get("id").getAsInt() == id && "graphic".equals(string(rule, "namespace"))
					&& "player".equals(string(rule, "actorRef")) && CaptureBuild.supportsClientReference(
						string(rule, "clientApiVersion"), string(rule, "clientArtifactSha256")))
					return rule;
			}
		return new JsonObject();
	}

	public JsonArray selfDamageInputs()
	{
		JsonElement rows = section("selfDamageInputs").get("records");
		return rows != null && rows.isJsonArray() ? rows.getAsJsonArray() : new JsonArray();
	}

	/** A unique declarative input match; a matching click is still not execution or damage proof. */
	public JsonObject selfDamageInput(int itemId, String operation)
	{
		JsonObject selected = null;
		for (JsonElement element : selfDamageInputs())
		{
			JsonObject rule = object(element);
			boolean id = false, action = false;
			JsonElement ids = rule.get("itemIds"), actions = rule.get("candidateOperations");
			if (ids == null || !ids.isJsonArray() || actions == null || !actions.isJsonArray())
				continue;
			for (JsonElement value : ids.getAsJsonArray())
				id |= value.getAsInt() == itemId;
			for (JsonElement value : actions.getAsJsonArray())
				action |= value.getAsString().equalsIgnoreCase(operation);
			if (!id || !action)
				continue;
			if (selected != null)
				return null;
			selected = rule;
		}
		return selected == null ? null : selected.deepCopy();
	}

	/** Canonical source identity after the existing definition/name/mode guards, never a name join. */
	public String attributionFamily(int npcId, String observedName, Integer level, boolean normalContext)
	{
		if (!baseVariantEstablished(npcId, level, normalContext) || !observedNameCompatible(npcId, observedName, true))
			return null;
		String selected = null;
		for (JsonElement element : npcCandidates(npcId))
		{
			JsonObject row = object(element);
			String requiredLevel = string(row, "combat_level"), family = string(row, "source_ref");
			if (family == null
				|| requiredLevel != null && level != null && !requiredLevel.equals(String.valueOf(level)))
				continue;
			if (selected != null && !selected.equals(family))
				return null;
			selected = family;
		}
		return selected;
	}

	/** An unambiguous qualified reference name can label an observed numeric identity, never create one. */
	public String attributionIdentityName(int npcId, Integer level, boolean normalContext)
	{
		if (!baseVariantEstablished(npcId, level, normalContext))
			return null;
		String selected = null;
		for (JsonElement value : npcCandidates(npcId))
		{
			JsonObject row = object(value);
			String required = string(row, "combat_level");
			if (required != null && (level == null || !required.equals(String.valueOf(level))))
				continue;
			String name = com.tickreplaylogger.util.SourceResolutionLabels.usableName(string(row, "name"));
			if (name == null)
				continue;
			if (selected != null && !selected.equals(name))
				return null;
			selected = name;
		}
		return selected;
	}

	/** Positive current base-profile witness; an absent/changed combat level does not match a reference. */
	public boolean attributionBaseProfile(int npcId, Integer level, String family)
	{
		if (level == null || family == null)
			return false;
		boolean matched = false;
		JsonElement candidates = object(section("npcIdentities").get(String.valueOf(npcId))).get("candidates");
		if (candidates == null || !candidates.isJsonArray())
			return false;
		for (JsonElement value : candidates.getAsJsonArray())
		{
			JsonObject row = object(value);
			if (family.equals(string(row, "source_ref")))
			{
				String expected = string(row, "combat_level");
				if (expected == null || !expected.matches("[0-9]+"))
					return false;
				// A reused definition can have several independently observed levels.
				// Other known levels do not contradict this exact sample. Component
				// expressions still require their own variant-level condition.
				matched |= expected.equals(String.valueOf(level));
			}
		}
		return matched;
	}

	private static final class DamageCompatibilityIndex
	{
		final Set<Integer> knownTypes;
		final Map<String, List<DamageOutcomeRule>> rules;

		DamageCompatibilityIndex(Set<Integer> knownTypes, Map<String, List<DamageOutcomeRule>> rules)
		{
			this.knownTypes = knownTypes;
			this.rules = rules;
		}
	}

	private DamageCompatibilityIndex damageCompatibility()
	{
		DamageCompatibilityIndex index = damageCompatibilityIndex;
		if (index == null)
			synchronized (this)
			{
				index = damageCompatibilityIndex;
				if (index == null)
					damageCompatibilityIndex = index = indexDamageCompatibility();
			}
		return index;
	}

	public boolean outcomeHitsplatKnown(int id)
	{
		return damageCompatibility().knownTypes.contains(id);
	}

	/** Explicit complete mechanism rules only; unknown source IDs never inherit a name match. */
	public List<DamageOutcomeRule> damageOutcomeRules(String kind, Integer id)
	{
		List<DamageOutcomeRule> result = new ArrayList<>();
		for (DamageOutcomeRule rule : damageCompatibility().rules.getOrDefault(kind, Collections.emptyList()))
			if (rule.getSourceIds() == null || id != null && rule.getSourceIds().contains(id))
				result.add(rule);
		return Collections.unmodifiableList(result);
	}

	private DamageCompatibilityIndex indexDamageCompatibility()
	{
		DamageCompatibilityIndex empty = new DamageCompatibilityIndex(Collections.emptySet(), Collections.emptyMap());
		JsonObject namespace = section("damageCompatibility");
		JsonElement version = namespace.get("version"), rows = namespace.get("rules");
		if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
			|| !"1".equals(version.getAsString()) || rows == null || !rows.isJsonArray())
			return empty;
		Set<Integer> known;
		try
		{
			known = new LinkedHashSet<>(outcomeNumbers(namespace.get("knownHitsplatTypes")));
		}
		catch (RuntimeException invalid)
		{
			return empty;
		}
		Map<String, List<DamageOutcomeRule>> rules = new LinkedHashMap<>();
		for (JsonElement value : rows.getAsJsonArray())
			try
			{
				JsonObject row = object(value), selector = object(row.get("selector"));
				String key = outcomeText(row, "key"), kind = outcomeText(selector, "kind"),
					mechanism = outcomeText(row, "mechanism");
				if (key == null || key.trim().isEmpty() || kind == null || mechanism == null)
					continue;
				List<Integer> ids = selector.has("ids") ? outcomeNumbers(selector.get("ids")) : null;
				if (ids != null && ids.isEmpty() || !"NPC_ORDINARY_ATTACK".equals(kind) && ids == null)
					continue;
				boolean itemInput = "ITEM".equals(kind);
				boolean projectileComponent = "NPC_PROJECTILE_COMPONENT".equals(kind);
				List<Integer> projectileIds = projectileComponent ? outcomeNumbers(selector.get("projectileIds"))
					: null;
				List<String> observedNames = projectileComponent ? outcomeStrings(selector.get("observedNames")) : null;
				String outcomeDomain = projectileComponent ? outcomeText(row, "outcomeDomain") : null;
				String outcomeEvidence = projectileComponent ? outcomeText(row, "outcomeSetEvidence") : null;
				List<String> nonHpKinds = projectileComponent ? outcomeStrings(row.get("nonHpOutcomeKinds")) : null;
				if (projectileComponent && (!selector.keySet().equals(
					new LinkedHashSet<>(java.util.Arrays.asList("kind", "ids", "projectileIds", "observedNames")))
					|| !"PROJECTILE_COMPONENT".equals(mechanism)
					|| !row.keySet().equals(new LinkedHashSet<>(java.util.Arrays.asList("key", "selector", "mechanism",
						"positiveHitsplatTypes", "zeroHitsplatTypes", "complete", "outcomeDomain", "outcomeSetEvidence",
						"nonHpOutcomeKinds", "requiredObservations", "sourceRefs", "provenance")))
					|| !"NON_HP_COMPONENT".equals(outcomeDomain)
					|| !"REVIEWED_EXHAUSTIVE_COMPONENT_OUTCOMES".equals(outcomeEvidence) || projectileIds.isEmpty()
					|| observedNames.isEmpty() || nonHpKinds.isEmpty() || ids.stream().anyMatch(id -> id <= 0)
					|| projectileIds.stream().anyMatch(id -> id <= 0) || new LinkedHashSet<>(ids).size() != ids.size()
					|| new LinkedHashSet<>(projectileIds).size() != projectileIds.size()
					|| new LinkedHashSet<>(observedNames).size() != observedNames.size()
					|| observedNames.stream().anyMatch(name -> !name.equals(name.trim()))
					|| new LinkedHashSet<>(nonHpKinds).size() != nonHpKinds.size()
					|| !java.util.Arrays.asList("PRAYER_RESOURCE", "PROTECTION_PRAYER_CONTROL", "STAT_CONTROL",
						"EQUIPMENT_CONTROL", "MOVEMENT_CONTROL", "NPC_HEALING").containsAll(nonHpKinds)
					|| !outcomeProvenanceAvailable(row.get("provenance"))))
					continue;
				String inputSourceRef = itemInput ? outcomeText(selector, "inputSourceRef") : null;
				List<String> inputOperations = itemInput ? outcomeStrings(selector.get("operations")) : null;
				if (itemInput && (!selector.keySet()
					.equals(new LinkedHashSet<>(java.util.Arrays.asList("kind", "ids", "inputSourceRef", "operations")))
					|| !"SELF_INPUT".equals(mechanism) || inputSourceRef == null || !inputSourceRef.startsWith("wiki:")
					|| inputSourceRef.length() <= 5 || inputOperations.isEmpty()))
					continue;
				List<Integer> positive = outcomeNumbers(row.get("positiveHitsplatTypes"));
				JsonElement zeroDomain = row.get("zeroHitsplatTypes");
				List<Integer> zero = itemInput && zeroDomain != null && zeroDomain.isJsonNull() ? null
					: outcomeNumbers(zeroDomain);
				if (!known.containsAll(positive) || zero != null && !known.containsAll(zero)
					|| itemInput && positive.isEmpty()
					|| projectileComponent && (!positive.isEmpty() || !zero.isEmpty()))
					continue;
				List<String> required = outcomeStrings(row.get("requiredObservations"));
				if (itemInput && !java.util.Collections.singletonList("QUALIFIED_SELF_INPUT").equals(required))
					continue;
				if (projectileComponent
					&& !java.util.Collections.singletonList("QUALIFIED_ORIGINAL_PROJECTILE_COMPONENT").equals(required))
					continue;
				List<String> refs = outcomeStrings(row.get("sourceRefs"));
				JsonElement complete = row.get("complete");
				boolean qualified = complete != null && complete.isJsonPrimitive()
					&& complete.getAsJsonPrimitive().isBoolean() && complete.getAsBoolean() && !required.isEmpty()
					&& !refs.isEmpty();
				DamageOutcomeRule rule = DamageOutcomeRule.builder().key(key).selectorKind(kind).sourceIds(ids)
					.inputSourceRef(inputSourceRef).inputOperations(inputOperations).projectileIds(projectileIds)
					.observedNames(observedNames).outcomeDomain(outcomeDomain).outcomeSetEvidence(outcomeEvidence)
					.nonHpOutcomeKinds(nonHpKinds).mechanism(mechanism).complete(qualified)
					.positiveHitsplatTypes(positive).zeroHitsplatTypes(zero).requiredObservations(required)
					.sourceRefs(refs).build();
				rules.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(rule);
			}
			catch (RuntimeException invalid)
			{
				/* An incomplete signature cannot exclude an observed source. */ }
		rules.replaceAll((kind, values) -> Collections.unmodifiableList(values));
		return new DamageCompatibilityIndex(Collections.unmodifiableSet(known), Collections.unmodifiableMap(rules));
	}

	/** A reviewed component cannot become an exclusion through an absent source-proof envelope. */
	private static boolean outcomeProvenanceAvailable(JsonElement value)
	{
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() == 0)
			return false;
		for (JsonElement item : value.getAsJsonArray())
		{
			JsonObject page = object(item);
			String hash = outcomeText(page, "bodySha256");
			JsonElement spans = page.get("evidence");
			if (outcomeText(page, "page") == null || hash == null || !hash.matches("[a-f0-9]{64}") || spans == null
				|| !spans.isJsonArray() || spans.getAsJsonArray().size() == 0)
				return false;
			for (JsonElement entry : spans.getAsJsonArray())
			{
				JsonObject span = object(entry);
				String start = span.has("start") ? span.get("start").getAsString() : null;
				String end = span.has("end") ? span.get("end").getAsString() : null;
				String digest = outcomeText(span, "sha256");
				if (start == null || end == null || !start.matches("[0-9]+") || !end.matches("[0-9]+")
					|| Long.parseLong(end) <= Long.parseLong(start) || digest == null
					|| !digest.matches("[a-f0-9]{64}"))
					return false;
			}
		}
		return true;
	}

	private static String outcomeText(JsonObject row, String key)
	{
		JsonElement value = row.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
			&& !value.getAsString().trim().isEmpty() ? value.getAsString() : null;
	}

	private static List<Integer> outcomeNumbers(JsonElement value)
	{
		if (value == null || !value.isJsonArray())
			throw new IllegalArgumentException("Outcome IDs unavailable");
		List<Integer> result = new ArrayList<>();
		for (JsonElement item : value.getAsJsonArray())
		{
			if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()
				|| !item.getAsString().matches("[0-9]+"))
				throw new IllegalArgumentException("Invalid outcome ID");
			result.add(Integer.valueOf(item.getAsString()));
		}
		return Collections.unmodifiableList(result);
	}

	private static List<String> outcomeStrings(JsonElement value)
	{
		if (value == null || !value.isJsonArray())
			throw new IllegalArgumentException("Outcome evidence unavailable");
		List<String> result = new ArrayList<>();
		for (JsonElement item : value.getAsJsonArray())
		{
			if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || item.getAsString().trim().isEmpty())
				throw new IllegalArgumentException("Invalid outcome evidence");
			result.add(item.getAsString());
		}
		return Collections.unmodifiableList(result);
	}

	/** Exact mechanism tags only; two contradicting bindings do not qualify an ordinary branch. */
	public String outcomeMechanism(int npcId, Integer animationId, Integer projectileId, Integer level,
		boolean normalContext)
	{
		if (!baseVariantEstablished(npcId, level, normalContext))
			return null;
		JsonObject npc = object(object(section("reviewedBindings").get("npcOverrides")).get(String.valueOf(npcId)));
		String selected = animationId == null ? null
			: outcomeText(object(object(npc.get("animations")).get(String.valueOf(animationId))), "outcomeMechanism");
		JsonElement rows = section("reviewedBindings").get("projectileStyleRules");
		if (projectileId != null && rows != null && rows.isJsonArray())
			for (JsonElement value : rows.getAsJsonArray())
				try
				{
					JsonObject rule = object(value);
					String mechanism = outcomeText(rule, "outcomeMechanism");
					JsonElement ids = rule.get("npcIds");
					if (mechanism == null || ids == null || !ids.isJsonArray())
						continue;
					for (JsonElement id : ids.getAsJsonArray())
						if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber() && id.getAsInt() == npcId
							&& object(rule.get("styles")).has(String.valueOf(projectileId)))
						{
							if (selected != null && !selected.equals(mechanism))
								return "CONFLICTING_MECHANISM_REFERENCES";
							selected = mechanism;
						}
				}
				catch (RuntimeException malformed)
				{
					/* Optional malformed evidence cannot qualify a mechanism. */ }
		return selected;
	}

	/** Positive context for the data-selected base branch; missing relevant samples preserve alternatives. */
	public boolean baseProfileContextKnown(com.tickreplaylogger.model.world.ContextBlock context, List<String> blocked)
	{
		if (context == null || context.getTemplateRegionId() == null || context.isInInstance() == null
			|| blocked == null)
			return false;
		Map<String, String> evidence = context.getEvidence();
		if (evidence != null && ("UNAVAILABLE".equals(evidence.get("templateRegionId"))
			|| "UNAVAILABLE".equals(evidence.get("inInstance"))))
			return false;
		JsonObject bindings = contextBindings();
		JsonObject region = object(
			object(bindings.get("regionActivities")).get(String.valueOf(context.getTemplateRegionId())));
		String regionActivity = string(region, "activity");
		if (regionActivity != null && (!region.has("requireInstance") || !region.get("requireInstance").getAsBoolean()
			|| Boolean.TRUE.equals(context.isInInstance())))
			return !blocked.contains(regionActivity);
		JsonObject rules = object(bindings.get("captureRules"));
		JsonElement version = rules.get("version"), fallback = rules.get("activityFallbackRules");
		if (version == null || !version.isJsonPrimitive() || !"1".equals(version.getAsString()) || fallback == null
			|| !fallback.isJsonArray())
			return false;
		for (JsonElement value : fallback.getAsJsonArray())
		{
			JsonObject rule = object(value);
			if (!blocked.contains(string(rule, "activity")))
				continue;
			String variable = string(rule, "variable");
			Map<String, Integer> variables = context.getObservedVariables();
			Integer observed = variable == null || variables == null ? null : variables.get(variable);
			if (observed == null || evidence != null && "UNAVAILABLE".equals(evidence.get(variable)))
				return false;
			JsonElement cases = rule.get("cases");
			if (cases == null || !cases.isJsonArray())
				return false;
			for (JsonElement c : cases.getAsJsonArray())
			{
				JsonElement values = object(c).get("values");
				if (values == null || !values.isJsonArray())
					return false;
				for (JsonElement active : values.getAsJsonArray())
					if (!active.isJsonPrimitive() || !active.getAsJsonPrimitive().isNumber())
						return false;
					else if (active.getAsInt() == observed)
						return false;
			}
		}
		return true;
	}

	/** Conditional bounds are compiled once; missing facts cannot become default numeric inputs. */
	public List<AttributionConstraint> attributionConstraints(int npcId)
	{
		Map<Integer, List<AttributionConstraint>> index = attributionConstraintIndex;
		if (index == null)
		{
			synchronized (this)
			{
				index = attributionConstraintIndex;
				if (index == null)
					attributionConstraintIndex = index = indexAttributionConstraints();
			}
		}
		return index.getOrDefault(npcId, Collections.emptyList());
	}

	private Map<Integer, List<AttributionConstraint>> indexAttributionConstraints()
	{
		JsonObject namespace = section("attributionConstraints");
		JsonElement version = namespace.get("version"), rows = namespace.get("rules");
		if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
			|| !"1".equals(version.getAsString()) || rows == null || !rows.isJsonArray())
			return Collections.emptyMap();
		Map<Integer, List<AttributionConstraint>> result = new HashMap<>();
		for (JsonElement value : rows.getAsJsonArray())
		{
			try
			{
				JsonObject row = object(value), selector = object(row.get("selector"));
				JsonElement ids = selector.get("npcIds");
				if (ids == null || !ids.isJsonArray() || string(row, "key") == null)
					continue;
				Integer maximum = null;
				JsonElement number = row.get("maximum");
				if (number != null && !number.isJsonNull() && number.isJsonPrimitive()
					&& number.getAsJsonPrimitive().isNumber() && number.getAsString().matches("[0-9]+"))
					maximum = Integer.valueOf(number.getAsString());
				List<String> required = new ArrayList<>();
				List<String> attackStyles = new ArrayList<>();
				JsonElement styles = row.get("attackStyles");
				if (styles != null && styles.isJsonArray())
					for (JsonElement style : styles.getAsJsonArray())
						if (style.isJsonPrimitive() && style.getAsJsonPrimitive().isString())
							attackStyles.add(style.getAsString());
				JsonElement observations = row.get("requiredObservations");
				if (observations != null && observations.isJsonArray())
					for (JsonElement observation : observations.getAsJsonArray())
						required.add(observation.getAsString());
				JsonObject envelope = object(row.get("envelopePolicy"));
				List<String> blocked = new ArrayList<>();
				boolean envelopeKnown = "BASE_PROFILE_ENVELOPE".equals(string(envelope, "operator"))
					&& "SOURCE_RELATIVE_ORDINARY_BASE_PROFILE".equals(string(envelope, "evidence"))
					&& "BLOCK_BASE_BRANCH_RETAIN_ALTERNATIVES".equals(string(envelope, "knownExceptionalContext"));
				JsonElement activities = envelope.get("blockedActivities");
				if (activities != null && activities.isJsonArray())
					for (JsonElement activity : activities.getAsJsonArray())
					{
						if (activity.isJsonPrimitive() && activity.getAsJsonPrimitive().isString())
							blocked.add(activity.getAsString());
						else
							envelopeKnown = false;
					}
				else
					envelopeKnown = false;
				String boundBasis = string(row, "boundBasis");
				if (row.has("boundBasis") && boundBasis == null)
					boundBasis = "UNAVAILABLE_BOUND_BASIS";
				List<Integer> allowedTypes = row.has("allowedHitsplatTypes")
					? outcomeNumbers(row.get("allowedHitsplatTypes"))
					: null;
				if (allowedTypes != null && (allowedTypes.isEmpty() || allowedTypes.size() > 256
					|| allowedTypes.size() != new HashSet<>(allowedTypes).size()))
					allowedTypes = null;
				if ("QUALIFIED_WHOLE_ACTOR_ENVELOPE".equals(boundBasis)
					&& !SourceEvidenceResolver.wholeActorHitsplatDomain(allowedTypes))
					allowedTypes = null;
				AttributionConstraint constraint = AttributionConstraint.builder().key(string(row, "key"))
					.familyKey(string(row, "familyKey")).maximum(maximum).qualification(string(row, "qualification"))
					.maximumExpression(
						row.has("maximumExpression") ? ObservedExpressions.compileNumeric(row.get("maximumExpression"))
							: null)
					.conditions(
						row.has("conditions") ? ObservedExpressions.compilePredicate(row.get("conditions")) : null)
					.composition(string(row, "composition")).numericScope(string(row, "numericScope"))
					.boundBasis(boundBasis).allowedHitsplatTypes(allowedTypes)
					.wholeActorWitnessQualified(wholeActorWitnessKnown(object(row.get("wholeActorWitness"))))
					.attackStyles(Collections.unmodifiableList(attackStyles)).mechanism(string(selector, "mechanism"))
					.damageComponent(string(selector, "damageComponent")).worldMode(string(selector, "worldMode"))
					.envelopeOperator(envelopeKnown ? string(envelope, "operator") : null)
					.envelopeEvidence(string(envelope, "evidence"))
					.blockedActivities(Collections.unmodifiableList(blocked))
					.requiredObservations(Collections.unmodifiableList(required)).build();
				List<Integer> validatedIds = new ArrayList<>();
				for (JsonElement id : ids.getAsJsonArray())
				{
					if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber()
						|| !id.getAsString().matches("[0-9]+"))
						throw new IllegalArgumentException("Invalid NPC id");
					validatedIds.add(Integer.valueOf(id.getAsString()));
				}
				for (Integer id : validatedIds)
					result.computeIfAbsent(id, ignored -> new ArrayList<>()).add(constraint);
			}
			catch (RuntimeException invalid)
			{
				// A malformed optional reference cannot exclude an observed source.
			}
		}
		result.replaceAll((id, constraints) -> Collections.unmodifiableList(constraints));
		return Collections.unmodifiableMap(result);
	}

	public JsonArray selfDamageCombatCandidates()
	{
		JsonElement rows = section("selfDamageInputs").get("combatCandidates");
		return rows != null && rows.isJsonArray() ? rows.getAsJsonArray() : new JsonArray();
	}

	private JsonObject entityRule(int npcId, Integer level, boolean normalContext)
	{
		if (!normalContext || !baseVariantEstablished(npcId, level, true))
			return new JsonObject();
		JsonObject rule = object(section("resolvedEntityRules").get(String.valueOf(npcId)));
		JsonElement levels = rule.get("requiredCombatLevels");
		if (levels != null)
		{
			if (level == null)
				return new JsonObject();
			boolean match = false;
			for (JsonElement value : levels.getAsJsonArray())
				match |= value.getAsInt() == level;
			if (!match)
				return new JsonObject();
		}
		return rule;
	}

	public String entityDamageType(int npcId)
	{
		return entityDamageType(npcId, null);
	}

	public String entityDamageType(int npcId, Integer level)
	{
		return entityDamageType(npcId, level, false);
	}

	public String entityDamageType(int npcId, Integer level, boolean normalContext)
	{
		return string(entityRule(npcId, level, normalContext), "damageType");
	}

	public String entityRuleKey(int npcId)
	{
		return string(object(section("resolvedEntityRules").get(String.valueOf(npcId))), "key");
	}

	public boolean prayerIneffective(int npcId)
	{
		return prayerIneffective(npcId, null);
	}

	public boolean prayerIneffective(int npcId, Integer level)
	{
		return prayerIneffective(npcId, level, false);
	}

	public boolean prayerIneffective(int npcId, Integer level, boolean normalContext)
	{
		return "none".equals(string(entityRule(npcId, level, normalContext), "prayerEffect"));
	}

	public boolean hasConditionalVariants(int npcId)
	{
		return section("conditionalNpcIdentities").has(String.valueOf(npcId));
	}

	public boolean baseVariantEstablished(int npcId, Integer level)
	{
		return baseVariantEstablished(npcId, level, false);
	}

	public boolean modeSensitive(int npcId)
	{
		JsonElement ids = root.get("modeSensitiveNpcIds");
		if (ids != null && ids.isJsonArray())
			for (JsonElement id : ids.getAsJsonArray())
				if (id.getAsInt() == npcId)
					return true;
		return hasConditionalVariants(npcId);
	}

	public boolean baseVariantEstablished(int npcId, Integer level, boolean normalContext)
	{
		if (!normalContext)
			return false;
		if (!modeSensitive(npcId))
			return true;
		if (level == null)
			return false;
		JsonElement candidates = object(section("npcIdentities").get(String.valueOf(npcId))).get("candidates");
		if (candidates != null && candidates.isJsonArray())
			for (JsonElement value : candidates.getAsJsonArray())
				if (String.valueOf(level).equals(string(value.getAsJsonObject(), "combat_level")))
					return true;
		return false;
	}

	public String prayerTiming(int npcId, int projectileId)
	{
		return prayerTiming(npcId, projectileId, null);
	}

	public String prayerTiming(int npcId, int projectileId, Integer level)
	{
		return prayerTiming(npcId, projectileId, level, false);
	}

	public String prayerTiming(int npcId, int projectileId, Integer level, boolean normalContext)
	{
		String timing = string(prayerTimingRule(npcId, projectileId, level, normalContext), "determination");
		return timing == null ? "UNKNOWN" : timing;
	}

	public String prayerTimingRuleKey(int npcId, int projectileId, Integer level, boolean normalContext)
	{
		return string(prayerTimingRule(npcId, projectileId, level, normalContext), "key");
	}

	private JsonObject prayerTimingRule(int npcId, int projectileId, Integer level, boolean normalContext)
	{
		if (!normalContext || !baseVariantEstablished(npcId, level, true) || prayerIneffective(npcId, level, true))
			return new JsonObject();
		JsonObject match = null;
		JsonElement rules = section("reviewedBindings").get("prayerTimingRules");
		if (rules != null && rules.isJsonArray())
			for (JsonElement element : rules.getAsJsonArray())
			{
				JsonObject rule = element.getAsJsonObject();
				boolean npc = false, projectile = false;
				for (JsonElement value : rule.getAsJsonArray("npcIds"))
					if (value.getAsInt() == npcId)
						npc = true;
				for (JsonElement value : rule.getAsJsonArray("projectileIds"))
					if (value.getAsInt() == projectileId)
						projectile = true;
				if (npc && projectile)
				{
					if (match != null)
						return new JsonObject();
					if (string(rule, "key") == null)
						return new JsonObject();
					match = rule;
				}
			}
		return match == null ? new JsonObject() : match;
	}

	public String damagingGraphicName(int id)
	{
		GraphicSurface surface = graphicSurface(id);
		return surface != null && Boolean.TRUE.equals(surface.getDamaging()) ? surface.getName() : null;
	}

	/** A definition binding is distinct from a raw cache-symbol name or an observed hit cause. */
	@lombok.Value
	public static class GraphicSurface
	{
		String name;
		String state;
		Boolean damaging;
		String mapRef;
		String ruleKey;
	}

	public GraphicSurface graphicSurface(int id)
	{
		JsonObject rule = object(object(section("reviewedBindings").get("graphicRules")).get(String.valueOf(id)));
		if (rule.entrySet().isEmpty())
			return null;
		JsonElement value = rule.get("damaging");
		Boolean damaging = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
			? value.getAsBoolean()
			: null;
		return new GraphicSurface(string(rule, "name"), string(rule, "state"), damaging, string(rule, "mapRef"),
			string(rule, "key"));
	}

	/** A damaging surface is not automatically typeless; its own binding must say so. */
	public String hazardDamageType(String name)
	{
		if (name == null)
			return null;
		String resolved = null;
		for (String family : new String[]
		{ "objectRules", "graphicRules", "npcHazardRules" })
		{
			JsonObject rules = object(section("reviewedBindings").get(family));
			for (java.util.Map.Entry<String, JsonElement> entry : rules.entrySet())
			{
				JsonObject rule = object(entry.getValue());
				JsonElement damaging = rule.get("damaging");
				if (damaging == null || !damaging.isJsonPrimitive() || !damaging.getAsJsonPrimitive().isBoolean()
					|| !damaging.getAsBoolean() || !name.equals(string(rule, "name")))
					continue;
				String type = string(rule, "damageType");
				if (type == null || type.isEmpty() || (resolved != null && !resolved.equals(type)))
					return null;
				resolved = type;
			}
		}
		return resolved;
	}

	public String npcContactHazardName(int npcId, Integer level, boolean normalContext)
	{
		return npcContactHazardName(npcId, level, normalContext, 1, false);
	}

	public String npcContactHazardName(int npcId, Integer level, boolean normalContext, int observedSize,
		boolean coherentOccupiedFootprint)
	{
		if (!normalContext || !baseVariantEstablished(npcId, level, true))
			return null;
		JsonObject rule = object(object(section("reviewedBindings").get("npcHazardRules")).get(String.valueOf(npcId)));
		JsonElement damaging = rule.get("damaging");
		if (damaging == null || !damaging.isJsonPrimitive() || !damaging.getAsJsonPrimitive().isBoolean()
			|| !damaging.getAsBoolean())
			return null;
		JsonElement size = rule.get("requiredObservedSize");
		if (observedSize < 1 || observedSize > 64 || size == null || !size.isJsonPrimitive()
			|| !size.getAsJsonPrimitive().isNumber() || size.getAsDouble() != observedSize)
			return null;
		String mode = string(rule, "contactMode");
		return ("same_server_tile".equals(mode) && observedSize == 1)
			|| ("within_server_footprint".equals(mode) && coherentOccupiedFootprint) ? string(rule, "name") : null;
	}

	public String projectileDamageType(int npcId, int projectileId, Integer level, boolean normalContext)
	{
		if (!normalContext || !baseVariantEstablished(npcId, level, true))
			return null;
		String resolved = null;
		JsonElement rules = section("reviewedBindings").get("projectileStyleRules");
		if (rules != null && rules.isJsonArray())
			for (JsonElement element : rules.getAsJsonArray())
			{
				JsonObject rule = element.getAsJsonObject();
				boolean npc = false;
				for (JsonElement id : rule.getAsJsonArray("npcIds"))
					if (id.getAsInt() == npcId)
						npc = true;
				if (npc)
				{
					String style = string(object(rule.get("styles")), String.valueOf(projectileId));
					if (style != null)
					{
						if (resolved != null && !resolved.equals(style))
							return null;
						resolved = style;
					}
				}
			}
		return resolved;
	}

	public String hitsplatSurface(int id)
	{
		String value = string(object(object(section("hitsplatRules").get("rules")).get(String.valueOf(id))), "surface");
		return value == null ? "UNCLASSIFIED" : value;
	}

	public List<String> possibleOutcomeSurfaces(int id)
	{
		String surface = hitsplatSurface(id);
		if ("UNCLASSIFIED".equals(surface))
			return Arrays.asList("HP", "UNKNOWN");
		if ("HP".equals(surface))
			return Collections.singletonList("HP");
		return hitsplatStringList(id, "possibleOutcomeSurfaces");
	}

	public List<String> possibleStatusFamilies(int id)
	{
		return hitsplatStringList(id, "possibleStatusFamilies");
	}

	private List<String> hitsplatStringList(int id, String key)
	{
		JsonElement value = object(object(section("hitsplatRules").get("rules")).get(String.valueOf(id))).get(key);
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > 16)
			return Collections.emptyList();
		try
		{
			List<String> strings = outcomeStrings(value);
			return new HashSet<>(strings).size() == strings.size() ? strings : Collections.emptyList();
		}
		catch (RuntimeException invalid)
		{
			return Collections.emptyList();
		}
	}

	/** A key identifies one scoped reference; competing/missing reference keys stay unestablished. */
	public String projectileDamageTypeRuleKey(int npcId, int projectileId, Integer level, boolean normalContext)
	{
		String style = projectileDamageType(npcId, projectileId, level, normalContext);
		if (style == null)
			return null;
		String key = null;
		JsonElement rows = section("reviewedBindings").get("projectileStyleRules");
		if (rows != null && rows.isJsonArray())
			for (JsonElement element : rows.getAsJsonArray())
			{
				JsonObject row = element.getAsJsonObject();
				boolean actor = false;
				for (JsonElement id : row.getAsJsonArray("npcIds"))
					actor |= id.getAsInt() == npcId;
				if (actor && style.equals(string(object(row.get("styles")), String.valueOf(projectileId))))
				{
					String candidate = string(row, "key");
					if (candidate == null || key != null)
						return null;
					key = candidate;
				}
			}
		return key;
	}

	private JsonObject hitsplatRule(int id)
	{
		return object(object(section("hitsplatRules").get("rules")).get(String.valueOf(id)));
	}

	public String hitsplatDamageType(int id)
	{
		return string(hitsplatRule(id), "damageType");
	}

	public String hitsplatReferenceKey(int id)
	{
		return string(hitsplatRule(id), "referenceKey");
	}

	public String hitsplatNpcDamageReductionStyle(int id)
	{
		return string(hitsplatRule(id), "npcDamageReductionStyle");
	}

	public String hitsplatPlayerPrayerEffect(int id)
	{
		String effect = string(hitsplatRule(id), "prayerEffect");
		return effect == null ? null : effect.toUpperCase(Locale.ROOT) + "_REVIEWED";
	}

	public JsonArray phaseRules()
	{
		JsonElement value = section("reviewedBindings").get("phaseRules");
		return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
	}

	public String symbol(String namespace, int id)
	{
		return string(object(section("symbols").get(namespace)), String.valueOf(id));
	}

	public List<String> observationReferences(String namespace, int id)
	{
		JsonElement value = section("observationReferences").get(namespace + ":" + id);
		List<String> keys = new ArrayList<>();
		if (value != null && value.isJsonArray())
			for (JsonElement key : value.getAsJsonArray())
				keys.add(key.getAsString());
		return keys;
	}

	public boolean conditionalPrayerTiming(String name)
	{
		if (name == null)
			return false;
		String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
		JsonElement names = root.get("conditionalSourceNames");
		if (names != null && names.isJsonArray())
			for (JsonElement value : names.getAsJsonArray())
				if (normalized.equals(value.getAsString()))
					return true;
		return false;
	}

	public JsonArray npcCandidates(int id)
	{
		JsonArray result = new JsonArray();
		JsonElement v = object(section("npcIdentities").get(String.valueOf(id))).get("candidates");
		if (v != null && v.isJsonArray())
			for (JsonElement item : v.getAsJsonArray())
				result.add(item);
		JsonElement conditional = section("conditionalNpcIdentities").get(String.valueOf(id));
		if (conditional != null && conditional.isJsonArray())
			for (JsonElement item : conditional.getAsJsonArray())
				result.add(item);
		return result;
	}

	private static boolean wholeActorWitnessKnown(JsonObject witness)
	{
		try
		{
			if (!witness.keySet()
				.equals(new HashSet<>(Arrays.asList("directHpComponents", "componentSetEvidence", "modifierEnvelope",
					"excludedOutcomeKinds")))
				|| !"EXHAUSTIVE_PRIMARY_SOURCE_SET".equals(string(witness, "componentSetEvidence")))
				return false;
			List<String> components = outcomeStrings(witness.get("directHpComponents")),
				excluded = outcomeStrings(witness.get("excludedOutcomeKinds"));
			JsonObject modifiers = object(witness.get("modifierEnvelope"));
			List<String> included = outcomeStrings(modifiers.get("includedStates")),
				states = outcomeStrings(modifiers.get("excludedStates"));
			return !components.isEmpty() && components.size() <= 256
				&& components.size() == new HashSet<>(components).size() && excluded.size() == 6
				&& new HashSet<>(excluded).equals(new HashSet<>(
					Arrays.asList("STATUS", "OTHER_ACTOR", "ENVIRONMENT_CONTACT", "SELF", "REFLECTED", "NON_HP")))
				&& modifiers.keySet().equals(new HashSet<>(Arrays.asList("scope", "includedStates", "excludedStates")))
				&& "SOURCE_VARIANT_ALL_DIRECT_HITS".equals(string(modifiers, "scope")) && !included.isEmpty()
				&& included.size() <= 256 && included.size() == new HashSet<>(included).size() && states.size() == 2
				&& new HashSet<>(states)
					.equals(new HashSet<>(Arrays.asList("NON_NORMAL_WORLD_CONTEXT", "MODEL_OVERRIDES")));
		}
		catch (RuntimeException malformed)
		{
			return false;
		}
	}

	public List<String> referenceKeys(int id)
	{
		Set<String> keys = new LinkedHashSet<>();
		JsonElement direct = section("attackReferencesByNpcId").get(String.valueOf(id));
		if (direct != null && direct.isJsonArray())
			for (JsonElement key : direct.getAsJsonArray())
				keys.add(key.getAsString());
		for (JsonElement candidate : npcCandidates(id))
		{
			JsonElement values = object(candidate).get("referenceKeys");
			if (values != null && values.isJsonArray())
				for (JsonElement key : values.getAsJsonArray())
					keys.add(key.getAsString());
			String page = string(object(candidate), "page");
			if (page != null)
			{
				JsonElement references = section("attackReferencesByPage")
					.get(page.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim());
				if (references != null && references.isJsonArray())
					for (JsonElement key : references.getAsJsonArray())
						keys.add(key.getAsString());
			}
		}
		return new ArrayList<>(keys);
	}

	public List<String> variants(int id)
	{
		Set<String> labels = new LinkedHashSet<>();
		for (JsonElement candidate : npcCandidates(id))
		{
			String version = string(object(candidate), "version");
			if (version != null && !version.isEmpty())
				labels.add(version);
		}
		return new ArrayList<>(labels);
	}

	public String animationKind(int npcId, int animationId)
	{
		return animationKind(npcId, animationId, null);
	}

	public String animationKind(int npcId, int animationId, Integer level)
	{
		return animationKind(npcId, animationId, level, false);
	}

	public String animationKind(int npcId, int animationId, Integer level, boolean normalContext)
	{
		JsonObject overrides = object(section("reviewedBindings").get("npcOverrides"));
		JsonObject npc = object(overrides.get(String.valueOf(npcId)));
		String reviewed = string(object(object(npc.get("animations")).get(String.valueOf(animationId))), "kind");
		if (reviewed != null && baseVariantEstablished(npcId, level, normalContext))
			return reviewed;
		String symbol = symbol("animation", animationId);
		if (symbol == null)
			return "unknown";
		Set<String> tokens = new HashSet<>(Arrays.asList(symbol.split("_")));
		for (String t : Arrays.asList("TRANSITION", "EMERGE", "EMERGEFAST", "SINK", "SINKFAST", "ENRAGE", "TELEGRAPH"))
			if (tokens.contains(t))
				return "transition";
		for (String t : Arrays.asList("DEATH", "DYING", "DIE"))
			if (tokens.contains(t))
				return "death";
		if (tokens.contains("SPAWN") || tokens.contains("SPAWNING"))
			return "spawn";
		if (tokens.contains("DEFEND") || tokens.contains("BLOCK"))
			return "reaction";
		return tokens.contains("ATTACK") ? "attack" : "action";
	}

	/** Exact reviewed animation binding only; generic symbol/name guesses are not attack proof. */
	public boolean reviewedAttackCue(int npcId, int animationId, Integer level, boolean normalContext)
	{
		if (!baseVariantEstablished(npcId, level, normalContext))
			return false;
		JsonObject npc = object(object(section("reviewedBindings").get("npcOverrides")).get(String.valueOf(npcId)));
		return "attack".equals(string(object(object(npc.get("animations")).get(String.valueOf(animationId))), "kind"));
	}

	public String animationEvidence(int npcId, int animationId)
	{
		return animationEvidence(npcId, animationId, null);
	}

	public String animationEvidence(int npcId, int animationId, Integer level)
	{
		return animationEvidence(npcId, animationId, level, false);
	}

	public String animationEvidence(int npcId, int animationId, Integer level, boolean normalContext)
	{
		JsonObject npc = object(object(section("reviewedBindings").get("npcOverrides")).get(String.valueOf(npcId)));
		return baseVariantEstablished(npcId, level, normalContext)
			&& object(npc.get("animations")).has(String.valueOf(animationId)) ? "REVIEWED_ANIMATION_BINDING"
				: symbol("animation", animationId) != null ? "CACHE_SYMBOL_INTERPRETATION" : "UNCLASSIFIED_ANIMATION";
	}
}
