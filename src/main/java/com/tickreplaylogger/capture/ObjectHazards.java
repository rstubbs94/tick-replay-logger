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

import static net.runelite.client.util.Text.removeTags;
import lombok.Value;
import net.runelite.api.*;

/** Resolves the currently observed object definition, including varbit/varp-driven floors. */
public final class ObjectHazards
{
	private ObjectHazards()
	{
	}

	/** Recorder-local API identities, not game/server object IDs or scene object keys. */
	public static final class ObservationIdentities
	{
		private final WeakIdentityIds observations = new WeakIdentityIds();

		public long id(TileObject object)
		{
			return observations.id(object);
		}

		public long id(net.runelite.api.GraphicsObject graphic)
		{
			return observations.id(graphic);
		}
	}

	@Value
	public static class Resolved
	{
		int id;
		int baseId;
		String name;
		String state;
		Boolean damaging;
		String evidence;
		String mapRef;
		String sourceEligibility;
		String ruleKey;
		boolean potentialSource;

		public boolean isDamageCandidate()
		{
			// Known warning/inactive/unresolved floors are never treated as active hazards.
			return Boolean.TRUE.equals(damaging);
		}
	}

	public static Resolved resolve(Client client, TileObject object, DamageMap map)
	{
		if (object == null || map == null)
		{
			return null;
		}
		return resolve(client, object.getId(), map);
	}

	static Resolved resolve(Client client, int baseId, DamageMap map)
	{
		if (map == null)
		{
			return null;
		}
		int id = baseId;
		String evidence = "OBJECT_DEFINITION";
		DamageMap.ObjectHazardRule baseRule = map.objectHazardRule(baseId);
		ObjectComposition definition;
		try
		{
			definition = client.getObjectDefinition(baseId);
		}
		catch (RuntimeException unavailable)
		{
			if (baseRule != null && baseRule.getSurfaceOperator() != null)
			{
				return surface(baseId, baseRule, null);
			}
			throw unavailable;
		}
		if (baseRule != null && baseRule.getSurfaceOperator() != null)
		{
			return surface(baseId, baseRule, definition);
		}
		boolean resolved = definition != null || baseRule == null || !"inactive".equals(baseRule.getState());
		if (definition != null && definition.getImpostorIds() != null)
		{
			definition = definition.getImpostor();
			resolved = definition != null;
			evidence = resolved ? "TRANSFORMED_OBJECT_DEFINITION" : "TRANSFORM_UNAVAILABLE";
			if (resolved)
			{
				id = definition.getId();
			}
		}
		DamageMap.ObjectHazardRule rule = map.objectHazardRule(id);
		String name = rule != null ? rule.getName() : map.hazardName("object", id);
		if (name == null && baseRule != null)
		{
			name = baseRule.getName();
		}
		if (name == null)
		{
			return null;
		}
		boolean possible = resolved && rule != null && Boolean.TRUE.equals(rule.getDamaging())
			|| !resolved && (rule != null || baseRule != null);
		String eligibility = !resolved ? "STATE_UNAVAILABLE"
			: rule != null && Boolean.TRUE.equals(rule.getDamaging()) ? "ELIGIBLE_REFERENCE_SURFACE"
				: "NOT_DAMAGE_SURFACE";
		return new Resolved(id, baseId, name,
			!resolved || rule == null && baseRule != null ? "unknown" : rule != null ? rule.getState() : null,
			resolved && rule != null ? rule.getDamaging() : null, resolved ? evidence : "TRANSFORM_UNAVAILABLE",
			rule != null ? rule.getMapRef() : null, eligibility, rule != null ? rule.getKey() : null, possible);
	}

	private static Resolved surface(int id, DamageMap.ObjectHazardRule rule, ObjectComposition definition)
	{
		boolean qualified = false;
		try
		{
			DamageMap.ObjectSurfaceDefinition expected = rule.getRequiredDefinition();
			int[] transforms = definition == null ? null : definition.getImpostorIds();
			qualified = definition != null && expected != null && (transforms == null || transforms.length == 0)
				&& definition.getId() == id && clean(definition.getName()).equals(clean(expected.getName()))
				&& definition.getSizeX() == expected.getSizeX() && definition.getSizeY() == expected.getSizeY();
		}
		catch (RuntimeException unavailable)
		{
			qualified = false;
		}
		boolean resource = "RESOURCE_TILE".equals(rule.getSurfaceOperator());
		String eligibility = !qualified ? "DEFINITION_NOT_QUALIFIED"
			: resource ? "RESOURCE_REFERENCE_ONLY"
				: "CONDITIONAL_HP_TILE".equals(rule.getSurfaceOperator())
					|| "CONDITIONAL_HP_OBJECT_FOOTPRINT".equals(rule.getSurfaceOperator()) ? "CONDITION_NOT_OBSERVED"
						: "ELIGIBLE_REFERENCE_SURFACE";
		return new Resolved(id, id, rule.getName(), qualified ? rule.getState() : "unknown",
			qualified ? rule.getDamaging() : null,
			qualified ? "REVIEWED_STATIC_OBJECT_SURFACE" : "SURFACE_DEFINITION_UNAVAILABLE_OR_CONFLICTING",
			rule.getMapRef(), eligibility, rule.getKey(), !resource || !qualified);
	}

	private static String clean(String value)
	{
		return value == null ? "" : removeTags(value).trim().toLowerCase(java.util.Locale.ROOT);
	}
}
