/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;
import static com.tickreplaylogger.capture.ResolverV1.*;

/** Shared Java integrity checks over one original receipt; never creates an attack or predicts a hit. */
final class OriginalSourceProof
{
	private OriginalSourceProof()
	{
	}

	static JsonObject evaluate(JsonObject cue, JsonObject hit)
	{
		OriginalSourceView view = OriginalSourceView.read(cue);
		JsonObject source = view.source(), state = view.actor(), first = view.first(), witness = view.witness();
		boolean api = Boolean.TRUE.equals(bool(cue.get("apiSourceObserved")));
		JsonObject raw = api ? first : state;
		boolean model = same(source, "id", raw, api ? "sourceNpcId" : "npcId")
			&& same(source, "combatLevel", raw, api ? "sourceCombatLevel" : "combatLevel")
			&& same(source, "name", raw, api ? "sourceObservedName" : "name")
			&& (!api || Boolean.TRUE.equals(bool(first.get("sourceNameKnown"))))
			&& clean(raw, "observationIssues", "npcId", "definitionId", "combatLevel", "name", "transformedComposition",
				"modelOverrides", "source.name", "source.combatLevel", "source.modelOverrides", "source.definition",
				"sourceActor");
		boolean normal = Boolean.TRUE
			.equals(bool(raw.get(api ? "sourceNormalWorldContextObserved" : "normalWorldContextObserved")));
		boolean unmodified = Boolean.FALSE
			.equals(bool(raw.get(api ? "sourceModelOverridesPresent" : "modelOverridesPresent")));
		JsonObject nativeScope = object(source.get("scope"));
		boolean nativeKnown = api
			? same(nativeScope, "sceneKey", first, "sourceSceneKey")
				&& same(nativeScope, "worldViewId", first, "sourceWorldViewId")
				&& same(nativeScope, "plane", first, "sourcePlane")
				&& clean(first, "observationIssues", "source.worldView", "source.plane", "source.nativeSceneKey",
					"source.modelOverrides")
			: same(nativeScope, "worldViewId", state, "worldViewId") && same(nativeScope, "plane", state, "plane")
				&& clean(state, "observationIssues", "worldView", "plane", "normalWorldContextObserved", "worldTypes",
					"leagueType");
		boolean stable = notTrue(cue.get("unstableOriginalSource"));
		JsonObject history = object(witness.get("sourceHistory"));
		boolean stableOwner = !api || "ACTOR".equals(text(first, "sourceEvidence"))
			&& "NPC".equals(text(first, "actorKind")) && Boolean.TRUE.equals(bool(first.get("sourceActorKnown")))
			&& Integer.valueOf(1).equals(integer(history.get("version")))
			&& allFalse(history, "actorChanged", "referenceChanged", "definitionChanged", "scopeChanged",
				"observationGap", "projectileDefinitionChanged");
		boolean clock = api
			? ordered(first, "observedTick", hit, "tick", false) && nonnegative(first.get("observedCycle"))
				&& ordered(first, "observedCycle", hit, "cycle", false)
				&& ordered(first, "observedCycle", witness, "observedCycle", false)
				&& ordered(witness, "observedCycle", hit, "cycle", false)
				&& (ordered(first, "observedCycle", hit, "cycle", true)
					|| ordered(cue, "sequence", hit, "sequence", false))
			: ordered(cue, "tick", hit, "tick", false) && ordered(cue, "cycle", hit, "cycle", false)
				&& positive(cue.get("sequence")) && ordered(cue, "sequence", hit, "sequence", false);
		boolean nativeQualified = nativeKnown && nativeMatches(source, hit);
		boolean targeted = "player".equals(text(cue, "targetRef"));
		JsonObject interaction = view.interaction();
		boolean activity = notTrue(cue.get("actorInteractionConflict"))
			&& notFalse(interaction.get("reportedInteracting"))
			&& (Boolean.TRUE.equals(bool(cue.get("actorCallbackObserved")))
				|| "HP_CALLBACK_ACTOR_SAMPLE".equals(text(cue, "observationKind")));
		boolean animation = nonnegative(state.get("animationId"));
		JsonObject result = new JsonObject();
		put(result, "original_model", model);
		put(result, "normal_reference", normal);
		put(result, "unmodified_reference", unmodified);
		put(result, "original_native", nativeKnown);
		put(result, "static_reference", model && normal && unmodified && stable && stableOwner && clock);
		put(result, "numeric_reference", model && stable && stableOwner && normal && unmodified && clock
			&& notFalse(cue.get("numericEligible")) && notTrue(cue.get("identityOnly")) && nativeQualified);
		put(result, "ordinary_actor",
			"npc".equals(text(source, "kind")) && "direct_attack".equals(text(cue, "pattern")) && targeted && animation
				&& Boolean.FALSE.equals(bool(state.get("modelOverridesPresent")))
				&& clean(state, "observationIssues", "npcId", "name", "combatLevel", "normalWorldContextObserved",
					"modelOverrides", "worldView", "plane", "animationId"));
		put(result, "ordinary_projectile", api && "projectile_impact".equals(text(cue, "pattern")) && targeted);
		put(result, "ordinary_proximity",
			Boolean.TRUE.equals(bool(cue.get("retainedOriginAssociation")))
				&& "projectile_impact".equals(text(cue, "pattern")) && targeted
				&& Boolean.FALSE.equals(bool(cue.get("numericEligible")))
				&& Boolean.FALSE.equals(bool(cue.get("componentBound"))) && same(source, "id", witness, "npcId")
				&& same(source, "actorRef", witness, "actorRef") && same(nativeScope, "sceneKey", witness, "sceneKey")
				&& same(nativeScope, "worldViewId", witness, "worldViewId")
				&& same(nativeScope, "plane", witness, "plane"));
		put(result, "hp_actor_activity", animation);
		put(result, "hp_pose_receipt",
			Arrays.asList("NPC_ACTIVITY_OBSERVATION", "PLAYER_ACTIVITY_OBSERVATION").contains(text(witness, "kind"))
				&& "HITSPLAT_CALLBACK".equals(text(witness, "observationStage"))
				&& numericSame(witness, "observedTick", hit, "tick")
				&& numericSame(witness, "observedCycle", hit, "cycle")
				&& numericSame(witness, "impactSequence", hit, "sequence")
				&& (same(source, "actorRef", witness, "actorRef") || text(source, "observationKey") != null));

		put(result, "status_envelope",
			ordered(cue, "tick", hit, "tick", false) && (ordered(cue, "tick", hit, "tick", true)
				|| same(cue, "tick", hit, "tick") && ordered(cue, "cycle", hit, "cycle", true)));
		put(result, "scalar_api",
			stable && targeted && api && nonnegative(witness.get("endCycle"))
				&& (ordered(first, "observedCycle", hit, "cycle", true)
					|| ordered(witness, "endCycle", hit, "cycle", false)));
		put(result, "scalar_actor", stable && targeted && activity && animation);
		boolean owner = api && stableOwner && !Boolean.TRUE.equals(bool(cue.get("unstableOriginalSource")))
			&& Boolean.TRUE.equals(bool(first.get("sourceNameKnown"))) && normal
			&& Arrays.asList("NPC_API_ID", "TRANSFORMED_COMPOSITION").contains(text(first, "sourceDefinitionEvidence"))
			&& text(first, "sourceRef") != null && Objects.equals(text(first, "sourceRef"), text(source, "actorRef"))
			&& Objects.equals(text(first, "sourceObservedName"), text(source, "name"))
			&& numericSame(first, "sourceNpcId", source, "id")
			&& numericSame(first, "projectileId", witness, "projectileId") && nonnegative(first.get("observedTick"))
			&& nonnegative(first.get("observedCycle")) && ordered(first, "observedTick", hit, "tick", false)
			&& ordered(first, "observedCycle", hit, "cycle", false);
		boolean nativeOwner = owner && nativeMatches(source, hit) && !Boolean.TRUE.equals(bool(cue.get("identityOnly")))
			&& numericSame(first, "sourceWorldViewId", nativeScope, "worldViewId")
			&& numericSame(first, "sourcePlane", nativeScope, "plane")
			&& clean(first, "observationIssues", "source.", "sourceActor", "sourcePlane", "projectileId");
		JsonObject carrier = new JsonObject();
		carrier.add("scope", cue.get("projectileScope"));
		put(result, "strict_owner", nativeOwner && unmodified);
		put(result, "strict_owner_with_overrides", nativeOwner);
		put(result, "strict_component",
			owner && unmodified && (nativeMatches(source, hit) || nativeMatches(carrier, hit))
				&& clean(first, "observationIssues", "source.name", "source.definition", "source.modelOverrides",
					"source.normalWorld", "sourceActor", "projectileId"));

		return result;
	}

	static void scalar(JsonObject cue, ResolutionCandidate row)
	{
		// Independent request/server-response admission already supplied its own factual correlation.
		if (Boolean.TRUE.equals(row.scalarEligible) && row.confidence != null)
		{
			return;
		}
		row.scalarEligible = false;
		row.confidence = "UNKNOWN";
		if (!notTrue(cue.get("forceScalarUnknown")))
		{
			return;
		}
		JsonObject proof = object(cue.get("originalProof"));
		String association = row.sourceAssociation, kind = text(object(cue.get("source")), "kind");
		String confidence = null, evidence = null;
		if (Boolean.TRUE.equals(bool(proof.get("scalar_api"))))
		{
			confidence = "TIMED";
			evidence = "PROJECTILE_TIMING_CORRELATION";
		}
		else if (Boolean.TRUE.equals(bool(proof.get("scalar_actor"))) && "npc".equals(kind))
		{
			confidence = "TIMED";
			evidence = "NPC_ANIMATION_CORRELATION";
		}
		else if (Boolean.TRUE.equals(bool(proof.get("scalar_actor"))) && "player".equals(kind)
			&& text(object(row.derivedAnnotations), "playerActionStyle") != null)
		{
			confidence = "PROXIMITY";
			evidence = "PLAYER_ANIMATION_CORRELATION";
		}
		else if (Arrays.asList("ITEM_CONSUMPTION_CORRELATION", "SELF_DAMAGE_INPUT_CORRELATION").contains(association))
		{
			confidence = "TIMED";
			evidence = association;
		}
		else if ((Boolean.TRUE.equals(bool(cue.get("contactObserved")))
			|| Boolean.TRUE.equals(bool(cue.get("originalNpcContact")))) && Boolean.TRUE.equals(row.componentQualified)
			&& (!"ORIGINAL_CONTACT".equals(text(cue, "evidenceMode"))
				|| "ORIGINAL_CONTACT_AGREEMENT".equals(association)))
		{
			confidence = "ENVIRONMENTAL";
			evidence = "HAZARD_ON_IMPACT_TILE";
		}
		if (confidence != null)
		{
			row.scalarEligible = true;
			row.confidence = confidence;
			row.attributionEvidence = evidence;
		}
	}

	private static void put(JsonObject target, String key, boolean value)
	{
		target.addProperty(key, value);
	}

	private static boolean notTrue(JsonElement value)
	{
		return !known(value) || Boolean.FALSE.equals(bool(value));
	}

	private static boolean notFalse(JsonElement value)
	{
		return !known(value) || Boolean.TRUE.equals(bool(value));
	}

	private static boolean positive(JsonElement value)
	{
		BigDecimal n = number(value);
		return n != null && n.signum() > 0;
	}

	private static boolean nonnegative(JsonElement value)
	{
		BigDecimal n = number(value);
		return n != null && n.signum() >= 0;
	}

	private static boolean same(JsonObject a, String left, JsonObject b, String right)
	{
		return known(a.get(left)) && known(b.get(right)) && equal(a.get(left), b.get(right));
	}

	private static boolean numericSame(JsonObject a, String left, JsonObject b, String right)
	{
		return number(a.get(left)) != null && number(b.get(right)) != null && equal(a.get(left), b.get(right));
	}

	private static boolean ordered(JsonObject a, String left, JsonObject b, String right, boolean strict)
	{
		BigDecimal x = number(a.get(left)), y = number(b.get(right));
		return x != null && y != null && (strict ? x.compareTo(y) < 0 : x.compareTo(y) <= 0);
	}

	private static boolean allFalse(JsonObject raw, String... keys)
	{
		for (String key : keys)
		{
			if (!Boolean.FALSE.equals(bool(raw.get(key))))
			{
				return false;
			}
		}
		return true;
	}

	private static boolean clean(JsonObject raw, String field, String... prefixes)
	{
		return !issuePresent(raw.get(field), prefixes);
	}
}
