/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.world.*;
import java.math.*;
import java.util.*;
import static com.tickreplaylogger.capture.ResolverEvidence.*;

/** Integrity of original observations. Missing evidence never becomes a positive proof. */
final class OriginalSourceProof
{
	private OriginalSourceProof()
	{
	}

	static Truth scope(Scope a, Scope b)
	{
		if (a == null || b == null)
		{
			return Truth.UNKNOWN;
		}
		Truth result = Truth.TRUE;
		Object[] left =
		{ a.sceneKey, a.worldViewId, a.plane, a.epoch };
		Object[] right =
		{ b.sceneKey, b.worldViewId, b.plane, b.epoch };
		for (int i = 0; i < left.length; i++)
		{
			if (i == 0 && (left[i] == null || right[i] == null || a.sceneKey.isEmpty() || b.sceneKey.isEmpty())
				|| i == 1 && (a.worldViewId == null || b.worldViewId == null || a.worldViewId < 0 || b.worldViewId < 0)
				|| i == 2
					&& (a.plane == null || b.plane == null || a.plane < 0 || b.plane < 0 || a.plane > 3 || b.plane > 3))
			{
				result = Truth.UNKNOWN;
				continue;
			}
			if (left[i] != null && right[i] != null && !left[i].equals(right[i]))
			{
				return Truth.FALSE;
			}
			if (i < 3 && (left[i] == null || right[i] == null))
			{
				result = Truth.UNKNOWN;
			}
		}
		return result;
	}

	static boolean before(Clock source, Clock hit, boolean strict)
	{
		if (source == null || hit == null || source.tick == null || hit.tick == null || source.tick < 0
			|| source.tick > hit.tick)
		{
			return false;
		}
		if (source.tick < hit.tick)
		{
			return source.cycle == null || hit.cycle == null || source.cycle <= hit.cycle;
		}
		if (source.cycle == null || hit.cycle == null || source.cycle < 0 || source.cycle > hit.cycle)
		{
			return false;
		}
		if (source.cycle < hit.cycle)
		{
			return true;
		}
		return source.sequence != null && hit.sequence != null && source.sequence > 0
			&& (strict ? source.sequence < hit.sequence : source.sequence <= hit.sequence);
	}

	static Truth identity(Identity a, Identity b)
	{
		if (a == null || b == null)
		{
			return Truth.UNKNOWN;
		}
		if (a.kind != null && b.kind != null && !a.kind.equals(b.kind)
			|| a.id != null && b.id != null && !a.id.equals(b.id)
			|| a.actorRef != null && b.actorRef != null && !a.actorRef.equals(b.actorRef))
		{
			return Truth.FALSE;
		}
		return a.kind != null && a.kind.equals(b.kind) && a.actorRef != null && a.actorRef.equals(b.actorRef)
			&& Objects.equals(a.id, b.id) ? scope(a.scope, b.scope) : Truth.UNKNOWN;
	}

	static Truth samePhysical(Source a, Source b)
	{
		Truth nativeScope = scope(a.identity().scope, b.identity().scope);
		if (nativeScope == Truth.FALSE
			|| a.identity().kind != null && b.identity().kind != null && !a.identity().kind.equals(b.identity().kind))
		{
			return Truth.FALSE;
		}
		if (nativeScope != Truth.TRUE || a.identity().kind == null || b.identity().kind == null
			|| a.identity().id == null || b.identity().id == null)
		{
			return Truth.UNKNOWN;
		}
		if (!a.identity().id.equals(b.identity().id))
		{
			return Truth.FALSE;
		}
		if ("npc".equals(a.identity().kind) || "player".equals(a.identity().kind))
		{
			return identity(a.identity(), b.identity());
		}
		String field = "graphic".equals(a.identity().kind) ? "graphicObservationId" : "objectObservationId";
		BigDecimal first = number(a.raw().get(field)), second = number(b.raw().get(field));
		return first == null || second == null || first.signum() <= 0 || second.signum() <= 0 ? Truth.UNKNOWN
			: first.compareTo(second) == 0 ? Truth.TRUE : Truth.FALSE;
	}

	static boolean originalPosition(Hit hit)
	{
		return originalPosition(hit, true);
	}

	private static boolean originalPosition(Hit hit, boolean requireSceneKey)
	{
		Receipt receipt = hit.callback();
		if (receipt == null)
		{
			return false;
		}
		JsonObject raw = receipt.raw, actor = object(raw.get("actorState"));
		return receipt.clock != null && receipt.scope != null
			&& Objects.equals(receipt.clock.tick, hit.record().getImpactTick())
			&& Objects.equals(receipt.clock.cycle, hit.record().getObservedCycle())
			&& Objects.equals(receipt.clock.sequence, hit.record().getObservationSequence())
			&& (requireSceneKey
				? receipt.scope.sceneKey != null && receipt.scope.sceneKey.equals(hit.record().getImpactSceneKey())
				: receipt.scope.sceneKey == null || hit.record().getImpactSceneKey() == null
					|| receipt.scope.sceneKey.equals(hit.record().getImpactSceneKey()))
			&& "PLAYER".equals(text(raw, "receiverKind")) && "LOCAL_PLAYER".equals(text(actor, "actorKind"))
			&& Boolean.TRUE.equals(bool(actor.get("worldPositionKnown")))
			&& Boolean.TRUE.equals(bool(actor.get("planeKnown")))
			&& same(actor.get("worldViewId"), hit.record().getImpactWorldViewId())
			&& same(actor.get("plane"), hit.record().getImpactPlane())
			&& same(actor.get("worldX"), hit.record().getImpactX())
			&& same(actor.get("worldY"), hit.record().getImpactY()) && same(raw.get("amount"), hit.record().getAmount())
			&& same(raw.get("hitsplatType"), hit.record().getHitsplatType())
			&& clean(raw, "captureIssues", "worldPosition", "worldView", "callbackGameState", "callbackCycle",
				"observationActorScope", "observationActorWorld", "observationContext", "primary", "hitsplatType",
				"hitsplat.type", "impactPositionScope")
			&& clean(actor, "observationIssues", "worldPosition", "worldView", "plane", "scope");
	}

	static boolean statusEnvelope(Source source, Hit hit)
	{
		Receipt receipt = source.receipt(), hp = hit.callback();
		if (!Boolean.TRUE.equals(bool(source.raw().get("active")))
			|| !Boolean.TRUE.equals(bool(source.raw().get("valueObserved"))) || receipt == null || hp == null
			|| scope(receipt.scope, hp.scope) != Truth.TRUE || receipt.clock.tick == null || hp.clock.tick == null
			|| receipt.clock.tick > hp.clock.tick)
		{
			return false;
		}
		return receipt.clock.tick < hp.clock.tick
			? receipt.clock.cycle == null || hp.clock.cycle == null || receipt.clock.cycle <= hp.clock.cycle
			: receipt.clock.cycle != null && hp.clock.cycle != null && receipt.clock.cycle < hp.clock.cycle;
	}

	static Truth sourceActive(Source source, Hit hit)
	{
		Identity identity = source.identity();
		Receipt hp = hit.callback();
		if (hp == null || identity.actorRef == null || identity.id == null || !"npc".equals(identity.kind))
		{
			return Truth.UNKNOWN;
		}
		Receipt sample = source.receipt();
		if (sample != null
			&& Arrays.asList(Channel.HP_ACTOR_POSE, Channel.HP_CONTACT, Channel.NPC_EMITTER).contains(source.channel())
			&& Objects.equals(sample.clock.tick, hp.clock.tick) && Objects.equals(sample.clock.cycle, hp.clock.cycle)
			&& Objects.equals(sample.clock.sequence, hp.clock.sequence) && sample.clock.sequence != null
			&& sample.clock.sequence > 0 && scope(identity.scope, hp.scope) == Truth.TRUE)
		{
			return Truth.TRUE;
		}
		Receipt despawn = null;
		for (Receipt event : hit.lifecycles())
		{
			if (event.event == null || !"NPC_DESPAWNED".equals(event.event.getType())
				|| !identity.actorRef.equals(event.event.getActorRef()) || event.clock.tick == null
				|| hp.clock.tick == null || event.clock.tick >= hp.clock.tick || !sameLifecycle(identity, event))
			{
				continue;
			}
			if (despawn == null || before(despawn.clock, event.clock, true))
			{
				despawn = event;
			}
		}
		if (despawn == null)
		{
			return Truth.UNKNOWN;
		}
		if (sample != null && before(despawn.clock, sample.clock, true) && before(sample.clock, hp.clock, false))
		{
			return Truth.UNKNOWN;
		}
		for (Receipt event : hit.lifecycles())
		{
			if (event.event != null && "NPC_SPAWNED".equals(event.event.getType()) && sameLifecycle(identity, event)
				&& before(despawn.clock, event.clock, true) && before(event.clock, hp.clock, false))
			{
				return Truth.UNKNOWN;
			}
		}
		return Truth.FALSE;
	}

	static boolean observedBirthAfterImpact(Source source, Hit hit)
	{
		if (hit.callback() == null || source.channel() == Channel.PROJECTILE
			|| source.channel() == Channel.PROJECTILE_ORIGIN)
		{
			return false;
		}
		Identity identity = source.identity();
		for (Receipt event : hit.lifecycles())
		{
			if (event.event == null || !before(hit.callback().clock, event.clock, true))
			{
				continue;
			}
			String type = event.event.getType();
			JsonObject raw = event.raw;
			boolean physical = false;
			Scope nativeScope = event.scope;
			if ("npc".equals(identity.kind) && "NPC_SPAWNED".equals(type))
			{
				if (sameLifecycle(identity, event))
				{
					return true;
				}
			}
			else if ("graphic".equals(identity.kind) && "GRAPHICS_OBJECT_CREATED".equals(type))
			{
				physical = same(raw.get("id"), identity.id)
					&& positiveSame(source.raw().get("graphicObservationId"), raw.get("graphicObservationId"));
			}
			else if ("object".equals(identity.kind) && "OBJECT_SPAWNED".equals(type))
			{
				JsonObject object = object(raw.get("object"));
				String kind = text(source.raw(), "objectKind");
				if (kind == null)
				{
					kind = text(source.raw(), "kind");
				}
				physical = kind != null && kind.equals(text(object, "kind")) && same(object.get("id"), identity.id)
					&& positiveSame(source.raw().get("objectKey"), object.get("objectKey"));
				nativeScope = new Scope(text(raw, "nativeSceneKey"), integer(object.get("worldViewId")),
					integer(object.get("plane")), event.scope == null ? null : event.scope.epoch);
			}
			if (physical && scope(identity.scope, nativeScope) == Truth.TRUE)
			{
				return true;
			}
		}
		return false;
	}

	private static boolean sameLifecycle(Identity identity, Receipt event)
	{
		Integer definition = integer(object(event.raw.get("actorState")).get("npcId"));
		return event.event != null && identity.actorRef != null && !identity.actorRef.trim().isEmpty()
			&& identity.actorRef.equals(event.event.getActorRef()) && identity.scope != null && event.scope != null
			&& identity.scope.sceneKey != null && identity.scope.sceneKey.equals(event.scope.sceneKey)
			&& scope(identity.scope, event.scope) != Truth.FALSE
			&& (identity.id == null || definition == null || identity.id.equals(definition));
	}

	private static boolean positiveSame(JsonElement first, JsonElement second)
	{
		BigDecimal a = number(first), b = number(second);
		return a != null && b != null && a.signum() > 0 && b.signum() > 0 && a.compareTo(b) == 0;
	}

	/** Retires generic carrier correlations; a declared delayed effect has its own timing rule. */
	static boolean expiredCarrier(Source source, Hit hit)
	{
		if (source.channel() != Channel.PROJECTILE && source.channel() != Channel.PROJECTILE_ORIGIN)
		{
			return false;
		}
		Integer end = integer(sample(source).get("endCycle")), cycle = hit.record().getObservedCycle();
		return end != null && end > 0 && cycle != null && (long) cycle > end + 30L;
	}

	static Truth contact(Source source, Hit hit)
	{
		return contact(source, hit, true);
	}

	private static Truth contact(Source source, Hit hit, boolean requireNative)
	{
		Receipt receipt = source.receipt(), hp = hit.callback();
		if (receipt == null || hp == null || source.channel() != Channel.HP_CONTACT)
		{
			return Truth.UNKNOWN;
		}
		Truth nativeScope = scope(source.identity().scope, hp.scope);
		if (nativeScope == Truth.FALSE || requireNative && nativeScope != Truth.TRUE)
		{
			return nativeScope;
		}
		JsonObject raw = source.raw();
		if (Boolean.FALSE.equals(bool(raw.get("damaging"))) || Boolean.TRUE.equals(bool(raw.get("finished")))
			|| Arrays.asList("inactive", "warning").contains(text(raw, "state"))
			|| "NOT_DAMAGE_SURFACE".equals(text(raw, "sourceEligibility"))
			|| Boolean.FALSE.equals(bool(raw.get("objectNativeScopeKnown"))))
		{
			return Truth.FALSE;
		}
		for (String field : Arrays.asList("observationStage", "defenderPositionEvidence"))
		{
			String actual = text(raw, field),
				expected = "observationStage".equals(field) ? "HITSPLAT_CALLBACK" : "SERVER_REPORTED_TILE";
			if (actual == null)
			{
				return Truth.UNKNOWN;
			}
			if (!expected.equals(actual))
			{
				return Truth.FALSE;
			}
		}
		if (!Objects.equals(receipt.clock.tick, hp.clock.tick) || !Objects.equals(receipt.clock.cycle, hp.clock.cycle)
			|| !Objects.equals(receipt.clock.sequence, hp.clock.sequence) || receipt.clock.sequence == null
			|| receipt.clock.sequence <= 0 || !originalPosition(hit, requireNative))
		{
			return Truth.UNKNOWN;
		}
		if (integer(raw.get("observedCycle")) == null)
		{
			return Truth.UNKNOWN;
		}
		if (!Objects.equals(integer(raw.get("observedCycle")), hp.clock.cycle))
		{
			return Truth.FALSE;
		}
		if (raw.has("contactWorldX") || raw.has("contactWorldY"))
		{
			return integer(raw.get("contactWorldX")) == null || integer(raw.get("contactWorldY")) == null
				? Truth.UNKNOWN
				: truth(same(raw.get("contactWorldX"), hit.record().getImpactX())
					&& same(raw.get("contactWorldY"), hit.record().getImpactY()));
		}
		String predicate = text(raw, "contactPredicate");
		Integer x = integer(raw.get("worldX")), y = integer(raw.get("worldY"));
		if (x == null || y == null)
		{
			return Truth.UNKNOWN;
		}
		if ("SAME_SERVER_TILE".equals(predicate))
		{
			return truth(x == hit.record().getImpactX() && y == hit.record().getImpactY());
		}
		if (!"COHERENT_SERVER_FOOTPRINT".equals(predicate))
		{
			return Truth.UNKNOWN;
		}
		Integer size = integer(raw.get("observedSize")), width = integer(raw.get("footprintWidth")),
			height = integer(raw.get("footprintHeight"));
		if (size == null || size < 1 || size > 64 || !size.equals(width) || !size.equals(height))
		{
			return Truth.UNKNOWN;
		}
		JsonObject actor = source.actor(), area = object(actor.get("worldArea"));
		if (!Boolean.TRUE.equals(bool(actor.get("worldAreaKnown")))
			|| !"ACTOR_GET_WORLD_AREA".equals(text(actor, "worldAreaEvidence"))
			|| requireNative && (!Boolean.TRUE.equals(bool(actor.get("worldAreaWorldViewKnown")))
				|| !same(actor.get("worldAreaWorldViewId"), source.identity().scope.worldViewId))
			|| Boolean.TRUE.equals(bool(actor.get("worldAreaWorldViewKnown")))
				&& !same(actor.get("worldAreaWorldViewId"), hp.scope.worldViewId)
			|| !same(area.get("plane"), hp.scope.plane) || !same(area.get("minX"), x) || !same(area.get("minY"), y)
			|| !same(area.get("maxX"), (long) x + width - 1) || !same(area.get("maxY"), (long) y + height - 1))
		{
			return Truth.UNKNOWN;
		}
		return truth(hit.record().getImpactX() >= x && hit.record().getImpactX() < (long) x + width
			&& hit.record().getImpactY() >= y && hit.record().getImpactY() < (long) y + height);
	}

	static boolean api(Source source)
	{
		return source.channel() == Channel.PROJECTILE && "ACTOR".equals(text(source.first(), "sourceEvidence"))
			&& Boolean.TRUE.equals(bool(source.first().get("sourceActorKnown")));
	}

	static boolean stableOwner(Source source)
	{
		return api(source) && historyStable(source, false);
	}

	static boolean historyStable(Source source, boolean includeAliases)
	{
		JsonObject history = object(sample(source).get("sourceHistory"));
		if (!Integer.valueOf(1).equals(integer(history.get("version"))))
		{
			return false;
		}
		for (String flag : Arrays.asList("actorChanged", "scopeChanged", "observationGap",
			"projectileDefinitionChanged"))
		{
			if (!Boolean.FALSE.equals(bool(history.get(flag))))
			{
				return false;
			}
		}
		return !includeAliases || Boolean.FALSE.equals(bool(history.get("referenceChanged")))
			&& Boolean.FALSE.equals(bool(history.get("definitionChanged")));
	}

	static boolean staticReference(Source source, Hit hit)
	{
		JsonObject raw = api(source) ? source.first() : source.actor();
		Identity identity = source.identity();
		return identity != null && "npc".equals(identity.kind)
			&& same(raw.get(api(source) ? "sourceNpcId" : "npcId"), identity.id)
			&& same(raw.get(api(source) ? "sourceCombatLevel" : "combatLevel"), identity.combatLevel)
			&& Objects.equals(identity.name, text(raw, api(source) ? "sourceObservedName" : "name"))
			&& identity.name != null && (!api(source) || Boolean.TRUE.equals(bool(raw.get("sourceNameKnown"))))
			&& Boolean.TRUE
				.equals(bool(raw.get(api(source) ? "sourceNormalWorldContextObserved" : "normalWorldContextObserved")))
			&& Boolean.FALSE
				.equals(bool(raw.get(api(source) ? "sourceModelOverridesPresent" : "modelOverridesPresent")))
			&& clean(raw, "observationIssues", "npcId", "definitionId", "name", "combatLevel", "transformedComposition",
				"modelOverrides", "source.name", "source.combatLevel", "source.definition", "source.modelOverrides",
				"sourceActor")
			&& (!api(source) || historyStable(source, false)
				&& Boolean.FALSE.equals(bool(object(sample(source).get("sourceHistory")).get("definitionChanged"))))
			&& source.receipt() != null && hit.callback() != null && source.receipt().clock.cycle != null
			&& hit.callback().clock.cycle != null && source.receipt().clock.cycle >= 0
			&& hit.callback().clock.cycle >= 0 && before(source.receipt().clock, hit.callback().clock, false);
	}

	static boolean numericReference(Source source, Hit hit)
	{
		if (source.channel() == Channel.HP_ACTOR_POSE || !staticReference(source, hit)
			|| api(source) && !historyStable(source, true) || hit.callback() == null
			|| scope(source.identity().scope, hit.callback().scope) != Truth.TRUE)
		{
			return false;
		}
		Scope nativeScope = source.identity().scope;
		JsonObject raw = api(source) ? source.first() : source.actor();
		boolean nativeFields = same(raw.get(api(source) ? "sourceWorldViewId" : "worldViewId"), nativeScope.worldViewId)
			&& same(raw.get(api(source) ? "sourcePlane" : "plane"), nativeScope.plane);
		if (!api(source))
		{
			return nativeFields;
		}
		if (same(raw.get("sourceSceneKey"), nativeScope.sceneKey))
		{
			return nativeFields;
		}
		WorldSnapshot frame = source.frame();
		return nativeFields && frame != null && Boolean.TRUE.equals(frame.getNativeContextKnown())
			&& Boolean.TRUE.equals(frame.getGameCycleKnown()) && source.receipt() != null
			&& Objects.equals(frame.getTick(), source.receipt().clock.tick)
			&& Objects.equals(frame.getGameCycle(), source.receipt().clock.cycle)
			&& Objects.equals(frame.getSceneKey(), nativeScope.sceneKey)
			&& Objects.equals(frame.getWorldViewId(), nativeScope.worldViewId)
			&& Objects.equals(frame.getPlane(), nativeScope.plane);
	}

	static boolean owner(Source source, Hit hit, boolean requireNativeSource, boolean noOverrides)
	{
		JsonObject first = source.first(), sample = sample(source);
		Identity identity = source.identity();
		return identity != null && api(source) && stableOwner(source) && "NPC".equals(text(first, "actorKind"))
			&& Boolean.TRUE.equals(bool(first.get("sourceNameKnown")))
			&& Boolean.TRUE.equals(bool(first.get("sourceNormalWorldContextObserved")))
			&& Arrays.asList("NPC_API_ID", "TRANSFORMED_COMPOSITION").contains(text(first, "sourceDefinitionEvidence"))
			&& identity.actorRef != null && identity.actorRef.equals(text(first, "sourceRef"))
			&& Objects.equals(identity.name, text(first, "sourceObservedName"))
			&& same(first.get("sourceNpcId"), identity.id)
			&& same(first.get("projectileId"), sample.get("projectileId"))
			&& (!noOverrides || Boolean.FALSE.equals(bool(first.get("sourceModelOverridesPresent"))))
			&& hit.callback() != null && source.receipt() != null && source.sample() != null
			&& same(first.get("observedTick"), source.receipt().clock.tick)
			&& same(first.get("observedCycle"), source.receipt().clock.cycle) && source.receipt().clock.cycle != null
			&& source.receipt().clock.cycle >= 0 && before(source.receipt().clock, hit.callback().clock, false)
			&& before(source.sample().clock, hit.callback().clock, false)
			&& (requireNativeSource
				? scope(identity.scope, hit.callback().scope) == Truth.TRUE
					&& same(first.get("sourceSceneKey"), identity.scope.sceneKey)
					&& same(first.get("sourceWorldViewId"), identity.scope.worldViewId)
					&& same(first.get("sourcePlane"), identity.scope.plane)
				: scope(identity.scope, hit.callback().scope) == Truth.TRUE
					|| scope(source.sample().scope, hit.callback().scope) == Truth.TRUE)
			&& clean(first, "observationIssues", "source.", "sourceActor", "sourcePlane", "projectileId");
	}

	static Truth geometry(Source source, Hit hit, JsonObject rule)
	{
		String shape = text(rule, "shape");
		if ("target".equals(shape))
		{
			String target = text(sample(source), "targetRef");
			return target == null ? Truth.UNKNOWN
				: target.equals(hit.record().getDefenderRef()) ? Truth.TRUE : Truth.FALSE;
		}
		boolean endpointAnchor = "projectile_endpoint".equals(text(rule, "anchor"));
		Scope geometricScope = endpointAnchor && source.sample() != null ? source.sample().scope
			: source.identity().scope;
		Truth nativeScope = hit.callback() == null ? Truth.UNKNOWN : scope(geometricScope, hit.callback().scope);
		if (nativeScope != Truth.TRUE)
		{
			return nativeScope;
		}
		if (!originalPosition(hit))
		{
			return Truth.UNKNOWN;
		}
		double x = hit.record().getImpactX(), y = hit.record().getImpactY();
		JsonObject actor = source.actor(), raw = source.raw();
		JsonObject bounds = object(actor.get("worldArea"));
		boolean body = "occupied_bounds".equals(shape) || "source_bounds".equals(text(rule, "anchor"));
		if (body)
		{
			Double minX, minY, maxX, maxY;
			if ("object".equals(source.identity().kind))
			{
				Scope objectScope = source.identity().scope;
				if (hit.scene() == null || objectScope == null
					|| !Objects.equals(hit.scene().getSceneKey(), objectScope.sceneKey)
					|| !Objects.equals(hit.scene().getWorldViewId(), objectScope.worldViewId)
					|| !same(raw.get("objectWorldViewId"), objectScope.worldViewId)
					|| !same(raw.get("objectPlane"), objectScope.plane)
					|| !Boolean.TRUE.equals(bool(raw.get("objectNativeScopeKnown"))))
				{
					return Truth.UNKNOWN;
				}
				minX = coordinate(raw.get("minSceneX"), hit.scene().getBaseX());
				minY = coordinate(raw.get("minSceneY"), hit.scene().getBaseY());
				maxX = coordinate(raw.get("maxSceneX"), hit.scene().getBaseX());
				maxY = coordinate(raw.get("maxSceneY"), hit.scene().getBaseY());
			}
			else
			{
				if (!Boolean.TRUE.equals(bool(actor.get("worldAreaKnown")))
					|| !Boolean.TRUE.equals(bool(actor.get("worldAreaWorldViewKnown")))
					|| !"ACTOR_GET_WORLD_AREA".equals(text(actor, "worldAreaEvidence"))
					|| source.identity().scope == null
					|| !same(actor.get("worldAreaWorldViewId"), source.identity().scope.worldViewId)
					|| !same(bounds.get("plane"), source.identity().scope.plane))
				{
					return Truth.UNKNOWN;
				}
				minX = finite(bounds.get("minX"));
				minY = finite(bounds.get("minY"));
				maxX = finite(bounds.get("maxX"));
				maxY = finite(bounds.get("maxY"));
			}
			if (minX == null || minY == null || maxX == null || maxY == null || minX > maxX || minY > maxY
				|| maxX - minX >= 104 || maxY - minY >= 104)
			{
				return Truth.UNKNOWN;
			}
			double distance = Math.max(Math.max(Math.max(minX - x, x - maxX), 0),
				Math.max(Math.max(minY - y, y - maxY), 0));
			Double radius = "occupied_bounds".equals(shape) ? 0.0 : finite(rule.get("radiusTiles"));
			Double minimum = rule.has("minimumRadiusTiles") ? finite(rule.get("minimumRadiusTiles")) : 0.0;
			return radius == null || minimum == null || minimum < 0 || radius < minimum || radius > 104 ? Truth.UNKNOWN
				: truth(distance >= minimum && distance <= radius);
		}
		boolean endpoint = "projectile_endpoint".equals(text(rule, "anchor"));
		boolean actorPosition = Arrays.asList("npc", "player").contains(source.identity().kind) && actor.size() > 0;
		JsonObject position = endpoint ? sample(source) : actorPosition ? actor : raw;
		if (endpoint)
		{
			Integer view = integer(position.get("targetWorldViewId")),
				plane = integer(position.get("destinationPlane"));
			if (view != null && !Objects.equals(view, hit.record().getImpactWorldViewId())
				|| plane != null && !Objects.equals(plane, hit.record().getImpactPlane()))
			{
				return Truth.FALSE;
			}
			if (view == null || plane == null)
			{
				return Truth.UNKNOWN;
			}
		}
		Double ox = finite(position.get(endpoint ? "endWorldX" : "worldX"));
		Double oy = finite(position.get(endpoint ? "endWorldY" : "worldY"));
		if (!endpoint && (ox == null || oy == null) && source.channel() == Channel.GRAPHIC_BIRTH
			&& Boolean.TRUE.equals(bool(raw.get("scopeKnown")))
			&& Boolean.TRUE.equals(bool(raw.get("positionScopeKnown"))) && hit.scene() != null && geometricScope != null
			&& Objects.equals(text(raw, "nativeSceneKey"), hit.scene().getSceneKey())
			&& Objects.equals(geometricScope.sceneKey, hit.scene().getSceneKey())
			&& Objects.equals(geometricScope.worldViewId, hit.scene().getWorldViewId())
			&& Objects.equals(geometricScope.plane, hit.scene().getPlane())
			&& same(raw.get("localWorldViewId"), geometricScope.worldViewId))
		{
			Integer localX = integer(raw.get("localX")), localY = integer(raw.get("localY"));
			if (localX != null && localY != null && localX >= 0 && localY >= 0 && (localX >> 7) < hit.scene().getWidth()
				&& (localY >> 7) < hit.scene().getHeight())
			{
				ox = (double) hit.scene().getBaseX() + (localX >> 7);
				oy = (double) hit.scene().getBaseY() + (localY >> 7);
			}
		}
		if (ox == null || oy == null || endpoint && !Boolean.TRUE.equals(bool(position.get("destinationKnown")))
			|| !endpoint && (Boolean.FALSE.equals(bool(position.get("worldPositionKnown")))
				|| actorPosition && !Boolean.TRUE.equals(bool(actor.get("worldPositionKnown"))))
			|| !clean(position, "observationIssues", "worldPosition", "plane", "worldView"))
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
			Integer width = integer(rule.get("widthTiles")), height = integer(rule.get("heightTiles"));
			if (width == null || height == null || width < 1 || height < 1 || width > 104 || height > 104)
			{
				return Truth.UNKNOWN;
			}
			if ("center".equals(text(rule, "origin")))
			{
				if (width % 2 == 0 || height % 2 == 0)
				{
					return Truth.UNKNOWN;
				}
				dx += width / 2;
				dy += height / 2;
			}
			else if (!"south_west".equals(text(rule, "origin")))
			{
				return Truth.UNKNOWN;
			}
			return truth(dx >= 0 && dx < width && dy >= 0 && dy < height);
		}
		Double radius = finite(rule.get("radiusTiles"));
		String metric = text(rule, "metric");
		if ("radius".equals(shape))
		{
			double distance = "chebyshev".equals(metric) ? Math.max(Math.abs(dx), Math.abs(dy))
				: "manhattan".equals(metric) ? Math.abs(dx) + Math.abs(dy)
					: "euclidean".equals(metric) ? Math.hypot(dx, dy) : Double.NaN;
			return radius == null || radius < 0 || radius > 104 || Double.isNaN(distance) ? Truth.UNKNOWN
				: truth(distance <= radius);
		}
		JsonObject direction = object(source.raw().get("direction"));
		Double vx = finite(direction.get("x")), vy = finite(direction.get("y"));
		Double length = finite(rule.get("lengthTiles"));
		if (vx == null || vy == null || length == null || length <= 0 || length > 104 || Math.hypot(vx, vy) == 0)
		{
			return Truth.UNKNOWN;
		}
		double dot = (dx * vx + dy * vy) / Math.hypot(vx, vy), distance = Math.hypot(dx, dy);
		if ("line".equals(shape))
		{
			return truth(dot >= 0 && dot <= length && Math.abs(dx * vy - dy * vx) < 1e-9);
		}
		Double angle = finite(rule.get("angleDegrees"));
		return !"cone".equals(shape) || angle == null || angle <= 0 || angle > 360 ? Truth.UNKNOWN
			: truth(
				distance <= length && (distance == 0 || dot / distance >= Math.cos(Math.toRadians(angle / 2)) - 1e-9));
	}

	static void scalar(Source source, Hit hit, ResolutionCandidate row)
	{
		boolean contactCorrelation = source.channel() == Channel.HP_CONTACT
			&& Boolean.TRUE.equals(bool(source.raw().get("damaging"))) && contact(source, hit, false) == Truth.TRUE;
		if ("EXCLUDED".equals(row.status)
			|| row.sourceOnly && !contactCorrelation && source.channel() != Channel.PROJECTILE_ORIGIN)
		{
			row.scalarEligible = false;
			row.confidence = "UNKNOWN";
			return;
		}
		if (Boolean.TRUE.equals(row.scalarEligible) && row.confidence != null)
		{
			return;
		}
		row.scalarEligible = false;
		row.confidence = "UNKNOWN";
		JsonObject sample = sample(source), interaction = object(source.actor().get("interaction"));
		String target = text(sample, "targetRef");
		if (target == null)
		{
			target = text(interaction, "actorRef");
		}
		boolean targeted = "player".equals(target);
		boolean original = source.receipt() != null && hit.callback() != null
			&& before(source.receipt().clock, hit.callback().clock, false)
			&& scope(source.identity().scope, hit.callback().scope) != Truth.FALSE;
		JsonObject proxy = object(source.first().get("proximityCandidate"));
		boolean origin = source.channel() == Channel.PROJECTILE_ORIGIN
			&& Boolean.TRUE.equals(bool(source.first().get("proximitySampleRetained")))
			&& source.identity().actorRef != null && source.identity().actorRef.equals(text(proxy, "actorRef"));
		if (targeted && original && (api(source) && stableOwner(source) || origin)
			&& integer(sample.get("endCycle")) != null && hit.record().getObservedCycle() != null
			&& Math.abs((long) integer(sample.get("endCycle")) - hit.record().getObservedCycle()) <= 30)
		{
			row.confidence = origin ? "PROXIMITY" : "TIMED";
			row.attributionEvidence = "PROJECTILE_TIMING_CORRELATION";
		}
		else if (targeted && original
			&& (source.channel() == Channel.HP_ACTOR_POSE
				|| scope(source.identity().scope, hit.callback().scope) == Truth.TRUE)
			&& (source.channel() == Channel.ACTOR_CALLBACK || source.channel() == Channel.HP_ACTOR_POSE)
			&& !Boolean.FALSE.equals(bool(interaction.get("reportedInteracting")))
			&& integer(source.actor().get("animationId")) != null && integer(source.actor().get("animationId")) >= 0)
		{
			boolean player = "player".equals(source.identity().kind);
			if (!player || text(object(row.derivedAnnotations), "playerActionStyle") != null)
			{
				row.confidence = player || scope(source.identity().scope, hit.callback().scope) != Truth.TRUE
					? "PROXIMITY"
					: "TIMED";
				row.attributionEvidence = player ? "PLAYER_ANIMATION_CORRELATION" : "NPC_ANIMATION_CORRELATION";
			}
		}
		else if (Arrays.asList("ITEM_CONSUMPTION_CORRELATION", "SELF_DAMAGE_INPUT_CORRELATION")
			.contains(row.sourceAssociation))
		{
			row.confidence = "TIMED";
			row.attributionEvidence = row.sourceAssociation;
		}
		else if ("INTERACTION_RESPONSE".equals(text(object(row.rule), "evidenceMode"))
			&& Boolean.TRUE.equals(row.componentQualified) && row.sourceAssociation != null)
		{
			row.confidence = "TIMED";
			row.attributionEvidence = row.sourceAssociation;
		}
		else if (contactCorrelation)
		{
			row.confidence = "ENVIRONMENTAL";
			row.attributionEvidence = "HAZARD_ON_IMPACT_TILE";
		}
		row.scalarEligible = !"UNKNOWN".equals(row.confidence);
	}

	static boolean clean(JsonObject raw, String field, String... prefixes)
	{
		JsonElement issues = raw.get(field);
		if (issues == null || issues.isJsonNull())
		{
			return true;
		}
		if (!issues.isJsonObject() && !issues.isJsonArray())
		{
			return false;
		}
		Set<String> keys = new LinkedHashSet<>();
		if (issues.isJsonObject())
		{
			keys.addAll(issues.getAsJsonObject().keySet());
		}
		else
		{
			for (JsonElement issue : issues.getAsJsonArray())
			{
				if (!issue.isJsonPrimitive())
				{
					return false;
				}
				keys.add(issue.getAsString());
			}
		}
		return keys.stream().noneMatch(key -> Arrays.stream(prefixes).anyMatch(key::startsWith));
	}

	private static JsonObject sample(Source source)
	{
		return source.sample() == null ? new JsonObject() : source.sample().raw;
	}

	private static boolean same(JsonElement actual, Object expected)
	{
		if (actual == null || actual.isJsonNull() || expected == null)
		{
			return false;
		}
		if (expected instanceof Number)
		{
			BigDecimal n = number(actual);
			return n != null && n.compareTo(new BigDecimal(expected.toString())) == 0;
		}
		if (expected instanceof JsonElement)
		{
			BigDecimal a = number(actual), b = number((JsonElement) expected);
			return a != null && b != null ? a.compareTo(b) == 0 : actual.equals(expected);
		}
		return actual.isJsonPrimitive() && expected.equals(actual.getAsString());
	}

	private static Double coordinate(JsonElement value, int base)
	{
		Integer tile = integer(value);
		return tile == null || tile < 0 || tile >= 104 ? null : (double) base + tile;
	}

	private static Double finite(JsonElement value)
	{
		BigDecimal n = number(value);
		return n == null || !Double.isFinite(n.doubleValue()) ? null : n.doubleValue();
	}

	private static Truth truth(boolean value)
	{
		return value ? Truth.TRUE : Truth.FALSE;
	}
}
