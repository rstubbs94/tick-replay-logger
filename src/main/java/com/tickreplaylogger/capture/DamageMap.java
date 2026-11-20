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
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import lombok.Getter;

/**
 * Bundled factual capture lookups for NPC attack speeds and named object/graphic surfaces.
 * Attack and effect interpretation belongs to the shared retrospective resolver. Missing
 * references preserve unknown state and never establish an actual damage cause.
 */
@Slf4j
public class DamageMap
{
	private CombatCatalog catalog;
	private String version = "unknown";
	private Map<String, Integer> npcAttackSpeeds = Collections.emptyMap();
	private Map<String, Map<String, String>> hazardNamesByKind = Collections.emptyMap();
	private Map<String, ObjectHazardRule> objectHazardRules = Collections.emptyMap();

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

	private static class Model
	{
		String version;
		Map<String, Integer> npcAttackSpeeds;
		Map<String, Map<String, String>> hazardNamesByKind;
		Map<String, ObjectHazardRule> objectHazardRules;
	}

	/** Load the capture view of the bundled mechanics package. Never throws — logs and no-ops on failure. */
	public static DamageMap load(Gson gson)
	{
		return load(gson, CombatCatalog.load(gson));
	}

	static DamageMap load(Gson gson, CombatCatalog references)
	{
		DamageMap map = new DamageMap();
		map.catalog = references;
		try
		{
			Model m = gson.fromJson(RuntimeCatalog.load(gson).section("capture"), Model.class);
			if (m != null)
			{
				if (m.version != null)
				{
					map.version = m.version;
				}
				if (m.npcAttackSpeeds != null)
				{
					map.npcAttackSpeeds = m.npcAttackSpeeds;
				}
				if (m.hazardNamesByKind != null)
				{
					map.hazardNamesByKind = m.hazardNamesByKind;
				}
				if (m.objectHazardRules != null)
				{
					map.objectHazardRules = m.objectHazardRules;
				}
			}
			log.debug("Loaded capture map v{}: {} npc ids, {} hazards", map.version, map.npcAttackSpeeds.size(),
				map.hazardNamesByKind.values().stream().mapToInt(Map::size).sum());
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

	public CombatCatalog catalog()
	{
		return catalog;
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
		{
			return 0;
		}
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

}
