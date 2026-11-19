/* Copyright (c) 2026, rstubbs94. All rights reserved.
 * Redistribution and use are permitted under the BSD 2-Clause license distributed with this project.
 * THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
 */
package com.tickreplaylogger.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import static com.tickreplaylogger.capture.ResolverV1.*;

/** Reference annotations for sources already admitted by the interpreter; never decides eligibility. */
final class PreRuleIrSourceAnnotations
{
	private PreRuleIrSourceAnnotations() { }

	static JsonObject hit(JsonObject hit, LegacyCombatCatalog catalog)
	{
		JsonObject result = new JsonObject();
		Integer type = integer(hit.get("hitsplatType"));
		if (catalog == null || type == null) return result;
		put(result, "prayerEffect", catalog.hitsplatPlayerPrayerEffect(type));
		if (result.has("prayerEffect")) result.addProperty("damagePropertyEvidence", "SOURCE_REFERENCE");
		put(result, "damageReferenceKey", catalog.hitsplatReferenceKey(type));
		String family = catalog.hitsplatDamageType(type);
		if (Arrays.asList("poison", "venom", "burn", "bleed").contains(family))
		{
			result.addProperty("damageType", family);
			result.addProperty("damageTypeEvidence", "HITSPLAT_TYPE");
			result.addProperty("attackerName", Character.toUpperCase(family.charAt(0)) + family.substring(1));
		}
		return result;
	}

	static JsonObject cue(JsonObject cue, JsonObject row, LegacyCombatCatalog catalog, PreRuleIrDamageMap bindings)
	{
		JsonObject result = new JsonObject(), source = object(row.get("source")), reference = object(row.get("referenceAnnotations"));
		Set<String> hints = new LinkedHashSet<>();
		Integer animation = single(object(cue.get("signals")).get("animationIds"));
		Integer projectile = single(object(cue.get("signals")).get("projectileIds"));
		Integer id = integer(source.get("id")), level = integer(source.get("combatLevel"));
		String type = null, timing = null, mapRef = null;
		if (bindings != null && animation != null && Boolean.TRUE.equals(bool(cue.get("animationReferenceEligible"))))
			put(result, "playerActionStyle", bindings.styleForPlayerAnimation(animation));
		if (catalog != null && bindings != null && Boolean.TRUE.equals(bool(cue.get("staticReferenceEligible"))) && id != null && "npc".equals(text(source,"kind")))
		{
			PreRuleIrDamageMap.AttackRule attack = Boolean.TRUE.equals(bool(cue.get("actorCallbackObserved"))) && animation != null
				? bindings.attackRuleForNpc(id, animation, level, true) : null;
			if (attack != null)
			{
				type = attack.getDamageType();
				mapRef = attack.getMapRef();
				put(result, "damageTypeEvidence", "LABELLED_FROM_MAP");
				put(result, "attackStyleName", attack.getName());
			}
			if (projectile != null)
			{
				if (type == null)
				{
					type = catalog.projectileDamageType(id, projectile, level, true);
					if (type != null) put(result, "damageTypeEvidence", "SOURCE_REFERENCE");
					put(result, "damageTypeRuleKey", catalog.projectileDamageTypeRuleKey(id, projectile, level, true));
				}
				timing = bindings.determinationForAttack(id, projectile, level, true);
				put(result, "determinationRuleKey", catalog.prayerTimingRuleKey(id, projectile, level, true));
			}
			if (type == null)
			{
				type = catalog.entityDamageType(id, level, true);
				if (type != null)
				{
					mapRef = "replay_bindings.json:" + catalog.entityRuleKey(id);
					put(result, "damageTypeEvidence", "SCOPED_DEFINITION_RULE");
				}
			}
			String sole = bindings.soleStyleForNpc(id, level, true);
			if (sole != null) hints.add(sole);
			if (catalog.prayerIneffective(id, level, true)) result.addProperty("prayerEffect", "NONE_REVIEWED");
		}
		if (bindings != null && projectile != null && Boolean.TRUE.equals(bool(cue.get("symbolReferenceEligible"))))
		{
			String hint = bindings.styleForProjectile(projectile);
			if (hint != null) hints.add(hint);
		}
		if (catalog != null && type == null && Boolean.TRUE.equals(bool(cue.get("hazardReferenceEligible"))))
		{
			type = catalog.hazardDamageType(text(row, "label"));
			if (type == null) type = catalog.hazardDamageType(text(source, "name"));
			if (type != null)
			{
				mapRef = "replay_bindings.json:hazard_damage_type";
				put(result, "damageTypeEvidence", "SOURCE_REFERENCE");
			}
		}
		// Explicit component annotations can refine a broad entity reference. Multi-style lists stay hints.
		if (Boolean.TRUE.equals(bool(row.get("componentQualified"))) && text(reference, "damageType") != null)
		{
			type = text(reference, "damageType");
			put(result, "damageTypeEvidence", "SOURCE_REFERENCE");
		}
		if (Boolean.TRUE.equals(bool(row.get("componentQualified"))))
		{
			if (text(reference, "mapRef") != null) mapRef = text(reference, "mapRef");
			put(result, "attackStyleName", text(reference, "attackStyleName"));
		}
		for (String field : Arrays.asList("styles", "attackStyles"))
			for (JsonElement value : array(reference.get(field))) if (value.isJsonPrimitive()) hints.add(value.getAsString().toLowerCase(java.util.Locale.ROOT));
		if (type != null && !type.contains("|")) put(result, "damageType", type);
		if ("typeless".equalsIgnoreCase(type)) timing = null;
		if (timing != null && !"UNKNOWN".equals(timing)) put(result, "determination", timing);
		else result.remove("determinationRuleKey");
		put(result, "mapRef", mapRef);
		if (result.has("damageTypeRuleKey") && catalog != null) result.addProperty("damageTypeCatalogId", catalog.id());
		if (!hints.isEmpty())
		{
			JsonArray values = new JsonArray();
			hints.forEach(values::add);
			result.add("candidateStyles", values);
		}
		if (level != null) result.addProperty("attackerCombatLevel", level);
		return result;
	}

	private static void put(JsonObject target, String key, String value) { if (value != null) target.addProperty(key, value); }
	private static Integer single(JsonElement values) { JsonArray list = array(values); return list.size() == 1 ? integer(list.get(0)) : null; }
}
