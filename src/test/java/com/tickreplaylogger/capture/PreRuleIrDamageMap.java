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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import lombok.Getter;

/**
 * Loads the bundled, versioned combat-source map (generated from the osrs-pvm-research data map)
 * and provides fast lookups used to LABEL observed events: an NPC's attack styles, a hazard
 * graphic/object name, and whether a spotanim on the player is a known freeze/teleblock/bind.
 *
 * <p>The map only ever sharpens labels; the raw capture is the source of truth (observed over
 * predicted). Missing bindings retain unknown state; reference coverage alone is not a guarantee
 * of correct mechanic interpretation or causal attribution.
 */
@Slf4j
public class PreRuleIrDamageMap
{
	private LegacyCombatCatalog catalog;
	private String version = "unknown";
	private Map<String, List<String>> npcStyles = Collections.emptyMap();
	private Map<String, Integer> npcAttackSpeeds = Collections.emptyMap();
	private Map<String, Map<String, String>> hazardNamesByKind = Collections.emptyMap();
	private Map<String, ObjectHazardRule> objectHazardRules = Collections.emptyMap();
	private Map<String, String> effectSpotanims = Collections.emptyMap();
	private Map<String, String> projectileStyles = Collections.emptyMap();
	private Map<String, String> playerAnimationStyles = Collections.emptyMap();
	private Map<String, DamageRule> damageRules = Collections.emptyMap();
	private Map<String, Map<String, AttackRule>> npcAttackRules = Collections.emptyMap();

	@Getter
	public static class ObjectHazardRule
	{
		private String key;
		private String name;
		private String state;
		private Boolean damaging;
		private String mapRef;
		private String surfaceOperator;
		private String contactMode;
		private com.google.gson.JsonObject contactAdmission;
		private List<String> conditions;
		private ObjectSurfaceDefinition requiredDefinition;
	}

	@Getter
	public static class ObjectSurfaceDefinition
	{
		private String name;
		private int sizeX;
		private int sizeY;
	}

	@Getter
	public static class DamageRule
	{
		private String name;
		private String damageType;
		private String prayable;
		private String mapRef;
		private List<String> sourceKeys;
	}

	@Getter
	public static class AttackRule
	{
		private String name;
		private String damageType;
		private boolean requiresProjectile;
		private String mapRef;
	}

	private static class Model
	{
		String version;
		Map<String, List<String>> npcStyles;
		Map<String, Integer> npcAttackSpeeds;
		Map<String, Map<String, String>> hazardNamesByKind;
		Map<String, ObjectHazardRule> objectHazardRules;
		Map<String, String> effectSpotanims;
		Map<String, String> projectileStyles;
		Map<String, String> playerAnimationStyles;
		Map<String, DamageRule> damageRules;
		Map<String, Map<String, AttackRule>> npcAttackRules;
	}

	/** Load the bundled {@code damage_map.json} resource. Never throws — logs and no-ops on failure. */
	public static PreRuleIrDamageMap load(Gson gson)
	{
		return load(gson, LegacyCombatCatalog.load(gson));
	}

	static PreRuleIrDamageMap load(Gson gson, LegacyCombatCatalog references)
	{
		PreRuleIrDamageMap map = new PreRuleIrDamageMap();
		map.catalog = references;
		try (InputStream input = PreRuleIrDamageMap.class.getResourceAsStream("/com/tickreplaylogger/damage_map.json"))
		{
			Model m = gson.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), Model.class);
			if (m != null)
			{
				if (m.version != null)
					map.version = m.version;
				if (m.npcStyles != null)
					map.npcStyles = m.npcStyles;
				if (m.npcAttackSpeeds != null)
					map.npcAttackSpeeds = m.npcAttackSpeeds;
				if (m.hazardNamesByKind != null)
					map.hazardNamesByKind = m.hazardNamesByKind;
				if (m.objectHazardRules != null)
					map.objectHazardRules = m.objectHazardRules;
				if (m.effectSpotanims != null)
					map.effectSpotanims = m.effectSpotanims;
				if (m.projectileStyles != null)
					map.projectileStyles = m.projectileStyles;
				if (m.playerAnimationStyles != null)
					map.playerAnimationStyles = m.playerAnimationStyles;
				if (m.damageRules != null)
					map.damageRules = m.damageRules;
				if (m.npcAttackRules != null)
					map.npcAttackRules = m.npcAttackRules;
			}
			log.debug("Loaded damage map v{}: {} npc ids, {} hazards, {} effect spotanims", map.version,
				map.npcStyles.size(), map.hazardNamesByKind.values().stream().mapToInt(Map::size).sum(),
				map.effectSpotanims.size());
		}
		catch (Exception e)
		{
			log.warn("Failed to load bundled damage map: {}", e.getMessage());
		}
		return map;
	}

	public String getVersion()
	{
		return version;
	}

	public LegacyCombatCatalog catalog()
	{
		return catalog;
	}

	public String styleForProjectile(int id)
	{
		return projectileStyles.get(String.valueOf(id));
	}

	public String styleForPlayerAnimation(int id)
	{
		return playerAnimationStyles.get(String.valueOf(id));
	}

	public DamageRule environmentalRule()
	{
		return damageRules.get("environmental");
	}

	public DamageRule pickpocketRule()
	{
		return damageRules.get("pickpocket");
	}

	public AttackRule attackRuleForNpc(int npcId, int animationId)
	{
		return attackRuleForNpc(npcId, animationId, null);
	}

	public AttackRule attackRuleForNpc(int npcId, int animationId, Integer level)
	{
		return attackRuleForNpc(npcId, animationId, level, false);
	}

	public AttackRule attackRuleForNpc(int npcId, int animationId, Integer level, boolean normalContext)
	{
		if (!normalContext || (catalog != null && !catalog.baseVariantEstablished(npcId, level, true)))
			return null;
		Map<String, AttackRule> attacks = npcAttackRules.get(String.valueOf(npcId));
		return attacks == null ? null : attacks.get(String.valueOf(animationId));
	}

	/** Attack styles (damage types) for an NPC id, or empty. */
	public List<String> stylesForNpc(int npcId)
	{
		List<String> s = npcStyles.get(String.valueOf(npcId));
		return s != null ? s : Collections.emptyList();
	}

	/** Single infobox/reference candidate, not proof of an individual hit's damage type. */
	public String soleStyleForNpc(int npcId)
	{
		return soleStyleForNpc(npcId, null);
	}

	public String soleStyleForNpc(int npcId, Integer level)
	{
		return soleStyleForNpc(npcId, level, false);
	}

	public String soleStyleForNpc(int npcId, Integer level, boolean normalContext)
	{
		if (!normalContext || (catalog != null && !catalog.baseVariantEstablished(npcId, level, true)))
			return null;
		String reviewed = catalog == null ? null : catalog.entityDamageType(npcId, level, normalContext);
		if (reviewed != null)
			return reviewed;
		List<String> s = stylesForNpc(npcId);
		return s.size() == 1 ? s.get(0) : null;
	}

	/** Attack speed in ticks for an NPC id, or 0 if unknown. */
	public int attackSpeedForNpc(int npcId)
	{
		return attackSpeedForNpc(npcId, null);
	}

	public int attackSpeedForNpc(int npcId, Integer level)
	{
		return attackSpeedForNpc(npcId, level, false);
	}

	public int attackSpeedForNpc(int npcId, Integer level, boolean normalContext)
	{
		if (!normalContext || (catalog != null && !catalog.baseVariantEstablished(npcId, level, true)))
			return 0;
		Integer s = npcAttackSpeeds.get(String.valueOf(npcId));
		return s != null ? s : 0;
	}

	/** Namespaces are essential: a graphic and an object with the same number are unrelated. */
	public String hazardName(String kind, int id)
	{
		String namespace = "gameObject".equals(kind) || "groundObject".equals(kind) || "wallObject".equals(kind)
			|| "decorativeObject".equals(kind) ? "object" : kind;
		Map<String, String> names = hazardNamesByKind.get(namespace);
		return names == null ? null : names.get(String.valueOf(id));
	}

	public ObjectHazardRule objectHazardRule(int id)
	{
		return objectHazardRules.get(String.valueOf(id));
	}

	/** Source-associated graphic family only; the runtime must qualify its client target. Never applied-effect proof. */
	public String effectForSpotanim(int spotanimId)
	{
		return effectSpotanims.get(String.valueOf(spotanimId));
	}

	/** Legacy name-only lookup. An actor name never establishes an individual attack's timing. */
	public String determinationFor(String attackerName)
	{
		// A boss name does not distinguish its ordinary, melee and special attacks.
		return "UNKNOWN";
	}

	public String determinationForAttack(int npcId, int projectileId)
	{
		return catalog == null ? "UNKNOWN" : catalog.prayerTiming(npcId, projectileId);
	}

	public String determinationForAttack(int npcId, int projectileId, Integer level)
	{
		return catalog == null ? "UNKNOWN" : catalog.prayerTiming(npcId, projectileId, level);
	}

	public String determinationForAttack(int npcId, int projectileId, Integer level, boolean normalContext)
	{
		return catalog == null ? "UNKNOWN" : catalog.prayerTiming(npcId, projectileId, level, normalContext);
	}
}
