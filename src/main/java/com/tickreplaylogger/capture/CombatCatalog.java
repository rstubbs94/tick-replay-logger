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

import com.google.gson.*;
import com.tickreplaylogger.model.world.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import lombok.*;
import lombok.extern.slf4j.*;
import static com.tickreplaylogger.capture.ResolverEvidence.array;

/** Bundled factual identity and state references; never treats prose as an event. */
@Slf4j
public final class CombatCatalog
{
	private JsonObject root = new JsonObject();
	private volatile ObservedInventorySourceHints inventorySourceHintIndex;

	/** Shared factual lookup view; optional legacy indexes remain lazy during migration. */
	static CombatCatalog referenceView(JsonObject references)
	{
		CombatCatalog catalog = new CombatCatalog();
		catalog.root = references;
		return catalog;
	}

	public static CombatCatalog load(Gson gson)
	{
		CombatCatalog catalog = new CombatCatalog();
		try
		{
			catalog.root = RuntimeCatalog.load(gson).section("references");
			log.debug("Loaded combat research catalog {}", catalog.id());
		}
		catch (Exception error)
		{
			log.warn("Combat research reference unavailable; raw capture remains enabled.");
		}
		return catalog;
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
	public String observedBossName(NpcState npc)
	{
		if (npc == null || npc.getNpcId() == null || !Boolean.TRUE.equals(npc.getNormalWorldContextObserved())
			|| npc.getObservationIssues() != null && npc.getObservationIssues().containsKey("name")
			|| !baseVariantEstablished(npc.getNpcId(), npc.getCombatLevel(), true)
			|| !observedNameCompatible(npc.getNpcId(), npc.getName(), true))
		{
			return null;
		}
		String name = definitionName(npc.getName());
		if (name.isEmpty())
		{
			return null;
		}
		NpcDefinitionStateReference definition = definitionStateReference(npc.getNpcId(), npc.getName(), true,
			npc.getCombatLevel(), npc.getSize(), true);
		if (definition != null && "Boss".equals(definition.getRole()))
		{
			return npc.getName().replaceAll("<[^>]*>", "").trim();
		}
		JsonObject override = object(
			object(section("reviewedBindings").get("npcOverrides")).get(String.valueOf(npc.getNpcId())));
		if ("Boss".equals(string(override, "role")) && name.equals(definitionName(string(override, "name"))))
		{
			return npc.getName().replaceAll("<[^>]*>", "").trim();
		}
		boolean boss = false;
		for (JsonElement item : npcCandidates(npc.getNpcId()))
		{
			JsonObject candidate = object(item);
			String page = string(candidate, "page");
			if (!name.equals(definitionName(string(candidate, "name"))) && !name.equals(definitionName(page)))
			{
				continue;
			}
			if (!"monster".equals(string(candidate, "infoboxKind"))
				|| !"Boss".equals(string(object(section("entityRoleReferences").get(page)), "role")))
			{
				return null;
			}
			boss = true;
		}
		return boss ? npc.getName().replaceAll("<[^>]*>", "").trim() : null;
	}

	/** A definition interpretation requires all independently observed identity/shape guards. */
	public NpcDefinitionStateReference definitionStateReference(Integer npcId, String name, boolean nameKnown,
		Integer level, Integer size, boolean normalContext)
	{
		if (!normalContext || !nameKnown || npcId == null || level == null || size == null)
		{
			return null;
		}
		JsonObject row = object(section("npcDefinitionStates").get(String.valueOf(npcId)));
		if (!"NPC_DEFINITION_SOURCE_REFERENCE".equals(string(row, "evidence")) || !row.has("requiredCombatLevel")
			|| !row.has("requiredSize"))
		{
			return null;
		}
		String expected = string(row, "expectedName");
		if (expected == null || !definitionName(name).equals(definitionName(expected))
			|| row.get("requiredCombatLevel").getAsInt() != level || row.get("requiredSize").getAsInt() != size)
		{
			return null;
		}
		List<String> refs = new ArrayList<>();
		for (JsonElement ref : row.getAsJsonArray("sourceRefs"))
		{
			refs.add(ref.getAsString());
		}
		return NpcDefinitionStateReference.builder().key(string(row, "key")).catalogId(id()).npcId(npcId)
			.name(string(row, "name")).role(string(row, "role")).form(string(row, "form"))
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
		{
			return false;
		}
		String observed = definitionName(name);
		if (observed.isEmpty())
		{
			return true;
		} // A successful unnamed read is distinct from a failed read.
		JsonElement candidates = object(section("npcIdentities").get(String.valueOf(npcId))).get("candidates");
		boolean namedReference = false;
		for (JsonElement value : array(candidates))
		{
			JsonObject candidate = object(value);
			for (String field : Arrays.asList("name", "page"))
			{
				String expected = definitionName(string(candidate, field));
				if (expected.isEmpty())
				{
					continue;
				}
				namedReference = true;
				if (expected.equals(observed))
				{
					return true;
				}
			}
		}
		return !namedReference; // No name reference cannot establish a positive contradiction.
	}

	public JsonObject contextBindings()
	{
		return section("contextBindings");
	}

	public ObservedInventorySourceHints inventorySourceHints()
	{
		ObservedInventorySourceHints index = inventorySourceHintIndex;
		if (index == null)
		{
			synchronized (this)
			{
				index = inventorySourceHintIndex;
				if (index == null)
				{
					inventorySourceHintIndex = index = new ObservedInventorySourceHints(
						section("inventorySourceCapture"));
				}
			}
		}
		return index;
	}

	private JsonObject localStatusRule(String key)
	{
		if (!CaptureBuild.runtimeReferenceMatched())
		{
			return new JsonObject();
		}
		JsonElement rules = contextBindings().get("statusValueRules");
		for (JsonElement value : array(rules))
		{
			JsonObject rule = value.getAsJsonObject();
			if (key.equals(string(rule, "key")) && Arrays.asList("varp", "varbit").contains(string(rule, "namespace"))
				&& "player".equals(string(rule, "actorRef")) && CaptureBuild
					.supportsClientReference(string(rule, "clientApiVersion"), string(rule, "clientArtifactSha256")))
			{
				return rule;
			}
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
		for (JsonElement value : array(cases))
		{
			if (damageType != null && damageType.equals(string(object(value), "state")))
			{
				return true;
			}
		}
		return false;
	}

	public JsonObject localStatusCase(String key, int value)
	{
		JsonObject rule = localStatusRule(key);
		JsonElement cases = rule.get("cases");
		for (JsonElement entry : array(cases))
		{
			JsonObject range = entry.getAsJsonObject();
			if (value >= range.get("min").getAsInt() && value <= range.get("max").getAsInt())
			{
				return range;
			}
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
		for (JsonElement value : array(rules))
		{
			JsonObject rule = value.getAsJsonObject();
			if (rule.has("id") && rule.get("id").getAsInt() == id && "graphic".equals(string(rule, "namespace"))
				&& "player".equals(string(rule, "actorRef")) && CaptureBuild
					.supportsClientReference(string(rule, "clientApiVersion"), string(rule, "clientArtifactSha256")))
			{
				return rule;
			}
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
			{
				continue;
			}
			for (JsonElement value : ids.getAsJsonArray())
			{
				id |= value.getAsInt() == itemId;
			}
			for (JsonElement value : actions.getAsJsonArray())
			{
				action |= value.getAsString().equalsIgnoreCase(operation);
			}
			if (!id || !action)
			{
				continue;
			}
			if (selected != null)
			{
				return null;
			}
			selected = rule;
		}
		return selected == null ? null : selected.deepCopy();
	}

	/** Canonical source identity after the existing definition/name/mode guards, never a name join. */
	public String attributionFamily(int npcId, String observedName, Integer level, boolean normalContext)
	{
		if (!baseVariantEstablished(npcId, level, normalContext) || !observedNameCompatible(npcId, observedName, true))
		{
			return null;
		}
		String selected = null;
		for (JsonElement element : npcCandidates(npcId))
		{
			JsonObject row = object(element);
			String requiredLevel = string(row, "combat_level"), family = string(row, "source_ref");
			if (family == null
				|| requiredLevel != null && level != null && !requiredLevel.equals(String.valueOf(level)))
			{
				continue;
			}
			if (selected != null && !selected.equals(family))
			{
				return null;
			}
			selected = family;
		}
		return selected;
	}

	/** Positive current base-profile witness; an absent/changed combat level does not match a reference. */
	public boolean attributionBaseProfile(int npcId, Integer level, String family)
	{
		if (level == null || family == null)
		{
			return false;
		}
		boolean matched = false;
		JsonElement candidates = object(section("npcIdentities").get(String.valueOf(npcId))).get("candidates");
		if (candidates == null || !candidates.isJsonArray())
		{
			return false;
		}
		for (JsonElement value : candidates.getAsJsonArray())
		{
			JsonObject row = object(value);
			if (family.equals(string(row, "source_ref")))
			{
				String expected = string(row, "combat_level");
				if (expected == null || !expected.matches("[0-9]+"))
				{
					return false;
				}
				// A reused definition can have several independently observed levels.
				// Other known levels do not contradict this exact sample. Component
				// expressions still require their own variant-level condition.
				matched |= expected.equals(String.valueOf(level));
			}
		}
		return matched;
	}

	private static String outcomeText(JsonObject row, String key)
	{
		JsonElement value = row.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
			&& !value.getAsString().trim().isEmpty() ? value.getAsString() : null;
	}

	/** Reference mechanism from positively recorded original animation/projectile identity. */
	private static List<String> outcomeStrings(JsonElement value)
	{
		if (value == null || !value.isJsonArray())
		{
			throw new IllegalArgumentException("Outcome evidence unavailable");
		}
		List<String> result = new ArrayList<>();
		for (JsonElement item : value.getAsJsonArray())
		{
			if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || item.getAsString().trim().isEmpty())
			{
				throw new IllegalArgumentException("Invalid outcome evidence");
			}
			result.add(item.getAsString());
		}
		return Collections.unmodifiableList(result);
	}

	/** Exact mechanism tags only; two contradicting bindings do not qualify an ordinary branch. */
	public String outcomeMechanism(int npcId, Integer animationId, Integer projectileId, Integer level,
		boolean normalContext)
	{
		if (!baseVariantEstablished(npcId, level, normalContext))
		{
			return null;
		}
		JsonObject npc = object(object(section("reviewedBindings").get("npcOverrides")).get(String.valueOf(npcId)));
		String selected = animationId == null ? null
			: outcomeText(object(object(npc.get("animations")).get(String.valueOf(animationId))), "outcomeMechanism");
		JsonElement rows = section("reviewedBindings").get("projectileStyleRules");
		if (projectileId != null && rows != null && rows.isJsonArray())
		{
			for (JsonElement value : rows.getAsJsonArray())
			{
				try
				{
					JsonObject rule = object(value);
					String mechanism = outcomeText(rule, "outcomeMechanism");
					JsonElement ids = rule.get("npcIds");
					if (mechanism == null || ids == null || !ids.isJsonArray())
					{
						continue;
					}
					for (JsonElement id : ids.getAsJsonArray())
					{
						if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber() && id.getAsInt() == npcId
							&& object(rule.get("styles")).has(String.valueOf(projectileId)))
						{
							if (selected != null && !selected.equals(mechanism))
							{
								return "CONFLICTING_MECHANISM_REFERENCES";
							}
							selected = mechanism;
						}
					}
				}
				catch (RuntimeException malformed)
				{
					/* Optional malformed evidence cannot qualify a mechanism. */ }
			}
		}
		return selected;
	}

	/** Positive context for the data-selected base branch; missing relevant samples preserve alternatives. */
	public boolean baseProfileContextKnown(ContextBlock context, List<String> blocked)
	{
		if (context == null || context.getTemplateRegionId() == null || context.isInInstance() == null
			|| blocked == null)
		{
			return false;
		}
		Map<String, String> evidence = context.getEvidence();
		if (evidence != null && ("UNAVAILABLE".equals(evidence.get("templateRegionId"))
			|| "UNAVAILABLE".equals(evidence.get("inInstance"))))
		{
			return false;
		}
		JsonObject bindings = contextBindings();
		JsonObject region = object(
			object(bindings.get("regionActivities")).get(String.valueOf(context.getTemplateRegionId())));
		String regionActivity = string(region, "activity");
		if (regionActivity != null && (!region.has("requireInstance") || !region.get("requireInstance").getAsBoolean()
			|| Boolean.TRUE.equals(context.isInInstance())))
		{
			return !blocked.contains(regionActivity);
		}
		JsonObject rules = object(bindings.get("captureRules"));
		JsonElement version = rules.get("version"), fallback = rules.get("activityFallbackRules");
		if (version == null || !version.isJsonPrimitive() || !"1".equals(version.getAsString()) || fallback == null
			|| !fallback.isJsonArray())
		{
			return false;
		}
		for (JsonElement value : fallback.getAsJsonArray())
		{
			JsonObject rule = object(value);
			if (!blocked.contains(string(rule, "activity")))
			{
				continue;
			}
			String variable = string(rule, "variable");
			Map<String, Integer> variables = context.getObservedVariables();
			Integer observed = variable == null || variables == null ? null : variables.get(variable);
			if (observed == null || evidence != null && "UNAVAILABLE".equals(evidence.get(variable)))
			{
				return false;
			}
			JsonElement cases = rule.get("cases");
			if (cases == null || !cases.isJsonArray())
			{
				return false;
			}
			for (JsonElement c : cases.getAsJsonArray())
			{
				JsonElement values = object(c).get("values");
				if (values == null || !values.isJsonArray())
				{
					return false;
				}
				for (JsonElement active : values.getAsJsonArray())
				{
					if (!active.isJsonPrimitive() || !active.getAsJsonPrimitive().isNumber())
					{
						return false;
					}
					else if (active.getAsInt() == observed)
					{
						return false;
					}
				}
			}
		}
		return true;
	}

	public JsonArray selfDamageCombatCandidates()
	{
		JsonElement rows = section("selfDamageInputs").get("combatCandidates");
		return rows != null && rows.isJsonArray() ? rows.getAsJsonArray() : new JsonArray();
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
		for (JsonElement id : array(ids))
		{
			if (id.getAsInt() == npcId)
			{
				return true;
			}
		}
		return hasConditionalVariants(npcId);
	}

	public boolean baseVariantEstablished(int npcId, Integer level, boolean normalContext)
	{
		if (!normalContext)
		{
			return false;
		}
		if (!modeSensitive(npcId))
		{
			return true;
		}
		if (level == null)
		{
			return false;
		}
		JsonElement candidates = object(section("npcIdentities").get(String.valueOf(npcId))).get("candidates");
		for (JsonElement value : array(candidates))
		{
			if (String.valueOf(level).equals(string(value.getAsJsonObject(), "combat_level")))
			{
				return true;
			}
		}
		return false;
	}

	public String damagingGraphicName(int id)
	{
		GraphicSurface surface = graphicSurface(id);
		return surface != null && Boolean.TRUE.equals(surface.getDamaging()) ? surface.getName() : null;
	}

	/** A definition binding is distinct from a raw cache-symbol name or an observed hit cause. */
	@Value
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
		{
			return null;
		}
		JsonElement value = rule.get("damaging");
		Boolean damaging = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
			? value.getAsBoolean()
			: null;
		return new GraphicSurface(string(rule, "name"), string(rule, "state"), damaging, string(rule, "mapRef"),
			string(rule, "key"));
	}

	public String npcContactHazardName(int npcId, Integer level, boolean normalContext)
	{
		return npcContactHazardName(npcId, level, normalContext, 1, false);
	}

	public String npcContactHazardName(int npcId, Integer level, boolean normalContext, int observedSize,
		boolean coherentOccupiedFootprint)
	{
		if (!normalContext || !baseVariantEstablished(npcId, level, true))
		{
			return null;
		}
		JsonObject rule = object(object(section("reviewedBindings").get("npcHazardRules")).get(String.valueOf(npcId)));
		JsonElement damaging = rule.get("damaging");
		if (damaging == null || !damaging.isJsonPrimitive() || !damaging.getAsJsonPrimitive().isBoolean()
			|| !damaging.getAsBoolean())
		{
			return null;
		}
		JsonElement size = rule.get("requiredObservedSize");
		if (observedSize < 1 || observedSize > 64 || size == null || !size.isJsonPrimitive()
			|| !size.getAsJsonPrimitive().isNumber() || size.getAsDouble() != observedSize)
		{
			return null;
		}
		String mode = string(rule, "contactMode");
		return ("same_server_tile".equals(mode) && observedSize == 1)
			|| ("within_server_footprint".equals(mode) && coherentOccupiedFootprint) ? string(rule, "name") : null;
	}

	public String projectileDamageType(int npcId, int projectileId, Integer level, boolean normalContext)
	{
		if (!normalContext || !baseVariantEstablished(npcId, level, true))
		{
			return null;
		}
		String resolved = null;
		JsonElement rules = section("reviewedBindings").get("projectileStyleRules");
		for (JsonElement element : array(rules))
		{
			JsonObject rule = element.getAsJsonObject();
			boolean npc = false;
			for (JsonElement id : rule.getAsJsonArray("npcIds"))
			{
				if (id.getAsInt() == npcId)
				{
					npc = true;
				}
			}
			if (npc)
			{
				String style = string(object(rule.get("styles")), String.valueOf(projectileId));
				if (style != null)
				{
					if (resolved != null && !resolved.equals(style))
					{
						return null;
					}
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
		{
			return Arrays.asList("HP", "UNKNOWN");
		}
		if ("HP".equals(surface))
		{
			return Collections.singletonList("HP");
		}
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
		{
			return Collections.emptyList();
		}
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
		{
			return null;
		}
		String key = null;
		JsonElement rows = section("reviewedBindings").get("projectileStyleRules");
		for (JsonElement element : array(rows))
		{
			JsonObject row = element.getAsJsonObject();
			boolean actor = false;
			for (JsonElement id : row.getAsJsonArray("npcIds"))
			{
				actor |= id.getAsInt() == npcId;
			}
			if (actor && style.equals(string(object(row.get("styles")), String.valueOf(projectileId))))
			{
				String candidate = string(row, "key");
				if (candidate == null || key != null)
				{
					return null;
				}
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
		for (JsonElement key : array(value))
		{
			keys.add(key.getAsString());
		}
		return keys;
	}

	public JsonArray npcCandidates(int id)
	{
		JsonArray result = new JsonArray();
		JsonElement v = object(section("npcIdentities").get(String.valueOf(id))).get("candidates");
		for (JsonElement item : array(v))
		{
			result.add(item);
		}
		JsonElement conditional = section("conditionalNpcIdentities").get(String.valueOf(id));
		for (JsonElement item : array(conditional))
		{
			result.add(item);
		}
		return result;
	}

	public List<String> referenceKeys(int id)
	{
		Set<String> keys = new LinkedHashSet<>();
		JsonElement direct = section("attackReferencesByNpcId").get(String.valueOf(id));
		for (JsonElement key : array(direct))
		{
			keys.add(key.getAsString());
		}
		for (JsonElement candidate : npcCandidates(id))
		{
			JsonElement values = object(candidate).get("referenceKeys");
			for (JsonElement key : array(values))
			{
				keys.add(key.getAsString());
			}
			String page = string(object(candidate), "page");
			if (page != null)
			{
				JsonElement references = section("attackReferencesByPage")
					.get(page.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim());
				for (JsonElement key : array(references))
				{
					keys.add(key.getAsString());
				}
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
			{
				labels.add(version);
			}
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
		{
			return reviewed;
		}
		String symbol = symbol("animation", animationId);
		if (symbol == null)
		{
			return "unknown";
		}
		Set<String> tokens = new HashSet<>(Arrays.asList(symbol.split("_")));
		for (String t : Arrays.asList("TRANSITION", "EMERGE", "EMERGEFAST", "SINK", "SINKFAST", "ENRAGE", "TELEGRAPH"))
		{
			if (tokens.contains(t))
			{
				return "transition";
			}
		}
		for (String t : Arrays.asList("DEATH", "DYING", "DIE"))
		{
			if (tokens.contains(t))
			{
				return "death";
			}
		}
		if (tokens.contains("SPAWN") || tokens.contains("SPAWNING"))
		{
			return "spawn";
		}
		if (tokens.contains("DEFEND") || tokens.contains("BLOCK"))
		{
			return "reaction";
		}
		return tokens.contains("ATTACK") ? "attack" : "action";
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
