/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.JsonObject;
import java.util.Arrays;
import static com.tickreplaylogger.capture.ResolverV1.*;

/** Read-only references to one captured source. This class never decides whether it caused an impact. */
final class OriginalSourceView
{
	private final JsonObject source, actor, first, witness, interaction;
	private final Integer tick, cycle;
	private final Long sequence;

	private OriginalSourceView(JsonObject source, JsonObject actor, JsonObject first, JsonObject witness, Integer tick,
		Integer cycle, Long sequence)
	{
		this.source = source;
		this.actor = actor;
		this.first = first;
		this.witness = witness;
		this.interaction = object(actor.get("interaction"));
		this.tick = tick;
		this.cycle = cycle;
		this.sequence = sequence;
	}

	static OriginalSourceView read(JsonObject cue)
	{
		JsonObject witness = object(cue.get("witness")), actor = object(witness.get("originalActorState"));
		if (actor.size() == 0)
		{
			actor = object(witness.get("actorState"));
		}
		if (actor.size() == 0)
		{
			actor = object(cue.get("originalActorState"));
		}
		if (actor.size() == 0 && "SETTLED_NPC_FOOTPRINT".equals(text(cue, "observationKind")))
		{
			actor = witness;
		}
		JsonObject first = object(cue.get("originalSourceObservation"));
		return new OriginalSourceView(object(cue.get("source")), actor, first, witness,
			first.size() == 0 ? integer(cue.get("tick")) : integer(first.get("observedTick")),
			first.size() == 0 ? integer(cue.get("cycle")) : integer(first.get("observedCycle")),
			longValue(cue.get("sourceObservationSequence")));
	}

	static OriginalSourceView actor(JsonObject raw, String actorRef, String sceneKey)
	{
		return actor(raw, actorRef, sceneKey, false);
	}

	static OriginalSourceView snapshot(JsonObject raw, String sceneKey)
	{
		return actor(raw, text(raw, "actorRef"), sceneKey, true);
	}

	private static OriginalSourceView actor(JsonObject raw, String actorRef, String sceneKey, boolean npcCollection)
	{
		Integer id = integer(raw.get("npcId"));
		String actorKind = text(raw, "actorKind");
		boolean player = Arrays.asList("PLAYER", "OTHER_PLAYER").contains(actorKind);
		boolean npc = !player && !"LOCAL_PLAYER".equals(actorKind)
			&& (npcCollection || "NPC".equals(actorKind) || actorKind == null && id != null && id >= 0);
		String kind = player ? "player" : npc ? "npc" : null;
		JsonObject source = new JsonObject();
		if (kind != null && (actorRef != null || npcCollection) && !"player".equals(actorRef))
		{
			source.addProperty("kind", kind);
			if (npc && id != null && id >= 0)
			{
				source.addProperty("id", id);
			}
			if (!issue(raw, "name") && text(raw, "name") != null)
			{
				source.addProperty("name", text(raw, "name"));
			}
			if (!issue(raw, "combatLevel") && integer(raw.get("combatLevel")) != null)
			{
				source.addProperty("combatLevel", integer(raw.get("combatLevel")));
			}
			if (actorRef != null)
			{
				source.addProperty("actorRef", actorRef);
			}
			Integer view = integer(raw.get("worldViewId"));
			Integer plane = Boolean.TRUE.equals(bool(raw.get("planeKnown"))) ? integer(raw.get("plane")) : null;
			if (issue(raw, "worldView"))
			{
				view = null;
			}
			if (issue(raw, "plane"))
			{
				plane = null;
			}
			if (npc && (issue(raw, "worldView") || issue(raw, "plane")))
			{
				view = null;
				plane = null;
			}
			source.add("scope", scope(sceneKey, view, plane));
		}
		return new OriginalSourceView(source, raw, new JsonObject(), raw, null, null, null);
	}

	static OriginalSourceView hpActor(JsonObject witness, long hpSequence, int ordinal)
	{
		JsonObject raw = object(witness.get("actorState"));
		String ref = text(witness, "actorRef") == null ? text(raw, "actorRef") : text(witness, "actorRef");
		OriginalSourceView sampled = actor(raw, ref, text(witness, "sceneKey"));
		JsonObject source = sampled.source;
		if (source.size() == 0 && !"LOCAL_PLAYER".equals(text(raw, "actorKind")) && !"player".equals(ref))
		{
			String name = text(raw, "name") == null ? text(witness, "name") : text(raw, "name");
			if (ref != null || com.tickreplaylogger.util.SourceResolutionLabels.usableName(name) != null)
			{
				boolean player = Arrays.asList("PLAYER", "OTHER_PLAYER").contains(text(raw, "actorKind"))
					|| !"NPC".equals(text(raw, "actorKind"))
						&& "PLAYER_ACTIVITY_OBSERVATION".equals(text(witness, "kind"));
				source.addProperty("kind", player ? "player" : "npc");
				if (name != null)
				{
					source.addProperty("name", name);
				}
				if (ref != null)
				{
					source.addProperty("actorRef", ref);
					if (!player && integer(witness.get("npcId")) != null && integer(witness.get("npcId")) >= 0)
					{
						source.add("id", witness.get("npcId"));
					}
					if (integer(witness.get("combatLevel")) != null)
					{
						source.add("combatLevel", witness.get("combatLevel"));
					}
					source.add("scope", scope(text(witness, "sceneKey"), integer(witness.get("worldViewId")),
						integer(witness.get("plane"))));
				}
				else
				{
					source.addProperty("observationKey", "untracked-actor-observation:" + hpSequence + ":" + ordinal);
					source.add("scope", new JsonObject());
				}
			}
		}
		return new OriginalSourceView(source, raw, new JsonObject(), witness, integer(witness.get("observedTick")),
			integer(witness.get("observedCycle")), longValue(witness.get("impactSequence")));
	}

	private static JsonObject scope(String scene, Integer view, Integer plane)
	{
		JsonObject scope = new JsonObject();
		if (scene != null)
		{
			scope.addProperty("sceneKey", scene);
		}
		if (view != null && view >= 0)
		{
			scope.addProperty("worldViewId", view);
		}
		if (plane != null && plane >= 0 && plane <= 3)
		{
			scope.addProperty("plane", plane);
		}
		return scope;
	}

	private static boolean issue(JsonObject raw, String prefix)
	{
		return object(raw.get("observationIssues")).keySet().stream().anyMatch(key -> key.startsWith(prefix));
	}

	private static Long longValue(com.google.gson.JsonElement value)
	{
		try
		{
			return number(value) == null ? null : number(value).longValueExact();
		}
		catch (ArithmeticException invalid)
		{
			return null;
		}
	}

	JsonObject source()
	{
		return source;
	}

	JsonObject actor()
	{
		return actor;
	}

	JsonObject first()
	{
		return first;
	}

	JsonObject witness()
	{
		return witness;
	}

	JsonObject interaction()
	{
		return interaction;
	}

	Integer sourceTick()
	{
		return tick;
	}

	Integer sourceCycle()
	{
		return cycle;
	}

	Long sourceSequence()
	{
		return sequence;
	}
}
