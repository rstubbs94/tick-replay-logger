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

import com.tickreplaylogger.model.world.*;
import java.util.*;
import java.util.function.*;
import lombok.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import static net.runelite.client.util.Text.removeTags;

/** One independent read of each projectile field; optional source metadata cannot erase motion. */
@Getter
public final class ProjectileObservation
{
	private final Map<String, String> issues = new LinkedHashMap<>();
	private final Actor sourceActor, targetActor;
	private final Integer remainingCycles;
	private final boolean referenceContext;
	private final ProjectileState state;

	public ProjectileObservation(Projectile projectile, Client client, Player local, boolean includeOthers,
		CombatCatalog catalog, Function<Actor, String> actorRef, BiFunction<WorldPoint, Actor, String> proximityName,
		long observationId, int spawnTick, int tick, Integer cycle, String evidence, LocalPoint callbackTarget)
	{
		this(projectile, client, local, includeOthers, catalog, actorRef, proximityName, observationId, spawnTick, tick,
			cycle, evidence, callbackTarget, null);
	}

	public ProjectileObservation(Projectile projectile, Client client, Player local, boolean includeOthers,
		CombatCatalog catalog, Function<Actor, String> actorRef, BiFunction<WorldPoint, Actor, String> proximityName,
		long observationId, int spawnTick, int tick, Integer cycle, String evidence, LocalPoint callbackTarget,
		BiFunction<ProjectileState, Actor, ProjectileState> enrich)
	{
		Integer id = read("projectileId", projectile::getId);
		sourceActor = read("sourceActor", projectile::getSourceActor);
		targetActor = read("targetActor", projectile::getTargetActor);
		boolean sourceKnown = !issues.containsKey("sourceActor"), targetKnown = !issues.containsKey("targetActor");
		boolean sourceRedacted = sourceActor instanceof Player && !includeOthers && sourceActor != local;
		boolean targetRedacted = targetActor instanceof Player && !includeOthers && targetActor != local;
		String observedName = sourceActor == null || sourceRedacted ? null : read("source.name", sourceActor::getName);
		Boolean nameKnown = sourceActor == null || sourceRedacted ? null : !issues.containsKey("source.name");
		String name = sourceRedacted ? "Other player" : clean(observedName);
		String sourceEvidence = sourceActor != null ? "ACTOR" : "UNKNOWN";
		String sourceReference = sourceActor == null || sourceRedacted ? null
			: read("source.actorRef", () -> actorRef.apply(sourceActor));
		String targetReference = targetActor == null || targetRedacted ? null
			: read("target.actorRef", () -> actorRef.apply(targetActor));
		Integer sourceIndex = sourceActor instanceof NPC ? read("source.sceneId", () -> ((NPC) sourceActor).getIndex())
			: null;
		Integer targetIndex = targetActor instanceof NPC ? read("target.sceneId", () -> ((NPC) targetActor).getIndex())
			: null;
		WorldView sourceView = sourceActor == null ? null : read("source.worldView", sourceActor::getWorldView);
		WorldView targetView = targetActor == null ? null : read("target.worldView", targetActor::getWorldView);
		Integer sourceViewId = sourceView == null ? null : read("source.worldViewId", sourceView::getId);
		Integer actorTargetViewId = targetView == null ? null : read("target.worldViewId", targetView::getId);
		Integer sourcePlane = read("sourcePlane", projectile::getSourceLevel),
			targetPlane = read("targetPlane", projectile::getTargetLevel);
		WorldPoint origin = read("sourcePoint", projectile::getSourcePoint),
			destination = read("targetPoint", projectile::getTargetPoint);
		LocalPoint target = callbackTarget != null ? callbackTarget : read("targetLocalPoint", projectile::getTarget);
		Integer localViewId = target == null ? null : read("targetLocalWorldViewId", target::getWorldView);
		WorldView localView = localViewId == null ? null
			: read("targetLocalWorldView", () -> localViewId == WorldView.TOPLEVEL ? client.getTopLevelWorldView()
				: client.getWorldView(localViewId));
		Integer targetViewId = target == null ? actorTargetViewId
			: localView != null ? read("targetLocalResolvedWorldViewId", localView::getId)
				: localViewId != null && localViewId != WorldView.TOPLEVEL ? localViewId : null;
		String destinationEvidence = destination == null ? null : "PROJECTILE_API_TARGET_POINT";
		if (destination == null && target != null && targetPlane != null && localView != null)
		{
			destination = read("targetPointConversion",
				() -> WorldPoint.fromLocal(localView, target.getX(), target.getY(), targetPlane));
			if (destination != null)
			{
				destinationEvidence = "TARGET_LOCAL_COORDINATE_CONVERSION";
			}
		}
		if (sourceActor == null && sourceKnown && origin != null && proximityName != null)
		{
			WorldPoint observedOrigin = origin;
			String candidate = read("source.proximityName", () -> proximityName.apply(observedOrigin, targetActor));
			if (candidate != null && !candidate.trim().isEmpty())
			{
				name = clean(candidate);
				sourceEvidence = "PROXIMITY";
			}
		}
		NpcDefinitionObservation definition = sourceActor instanceof NPC
			? NpcDefinitionObservation.capture((NPC) sourceActor, issues, "source.")
			: null;
		Integer level = sourceActor instanceof NPC ? read("source.combatLevel", sourceActor::getCombatLevel) : null;
		Boolean normal = null;
		Boolean modelOverridesPresent = null;
		if (sourceActor instanceof NPC)
		{
			NpcOverrides overrides = read("source.modelOverrides", () -> ((NPC) sourceActor).getModelOverrides());
			if (!issues.containsKey("source.modelOverrides"))
			{
				modelOverridesPresent = overrides != null;
			}
			normal = !issues.containsKey("source.modelOverrides")
				&& NpcIdentityContext.normalWorldObservedWithoutOverrides(client, overrides == null);
		}
		referenceContext = Boolean.TRUE.equals(normal) && definition != null && definition.getId() != null
			&& catalog != null
			&& catalog.observedNameCompatible(definition.getId(), observedName, Boolean.TRUE.equals(nameKnown));
		String type = id == null || definition == null || definition.getId() == null || catalog == null ? null
			: read("damageTypeReference",
				() -> catalog.projectileDamageType(definition.getId(), id, level, referenceContext));
		String rule = type == null ? null
			: read("damageTypeRule",
				() -> catalog.projectileDamageTypeRuleKey(definition.getId(), id, level, referenceContext));
		remainingCycles = read("remainingCycles", projectile::getRemainingCycles);
		Integer estimated = remainingCycles == null ? null
			: tick + Math.max(0, (int) Math.ceil(remainingCycles / 30.0));
		Animation animation = read("animation", projectile::getAnimation);
		Integer animationId = animation == null ? null : read("animationId", animation::getId);
		ProjectileState.ProjectileStateBuilder out = ProjectileState.builder().projectileId(id)
			.observationId(observationId).observedTick(tick).observedCycle(cycle).spawnTick(spawnTick)
			.spawnTickEvidence("FIRST_OBSERVED").positionEvidence(evidence).sourceRef(sourceReference)
			.targetRef(targetReference).sourceActorKnown(sourceKnown).targetActorKnown(targetKnown).sourceName(name)
			.sourceObservedName(sourceRedacted ? null : observedName).sourceNameKnown(nameKnown)
			.sourceEvidence(sourceEvidence).sourceNpcIndex(sourceIndex).targetNpcIndex(targetIndex)
			.sourceNpcId(definition == null ? null : definition.getId())
			.sourceBaseNpcId(definition == null ? null : definition.getBaseId())
			.sourceDefinitionEvidence(definition == null ? null : definition.getEvidence())
			.sourceDefinitionHasTransforms(definition == null ? null : definition.getHasTransforms())
			.sourceCombatLevel(level).sourceNormalWorldContextObserved(normal)
			.sourceModelOverridesPresent(modelOverridesPresent).damageType(type).damageTypeRuleKey(rule)
			.damageTypeEvidence(type == null ? null : "SCOPED_SOURCE_PROJECTILE_REFERENCE")
			.damageTypeCatalogId(type == null ? null : catalog.id()).worldViewId(sourceViewId)
			.sourceWorldViewId(sourceViewId).targetWorldViewId(targetViewId).targetActorWorldViewId(actorTargetViewId)
			.targetLocalWorldViewId(localViewId).sourcePlane(sourcePlane).targetPlane(targetPlane).plane(sourcePlane)
			.samplePlaneKnown(sourcePlane != null)
			.coordinateEvidence(sourceViewId == null || sourcePlane == null ? "UNRESOLVED" : "SOURCE_ACTOR_WORLD_VIEW")
			.originKnown(origin != null).startWorldX(origin == null ? null : origin.getX())
			.startWorldY(origin == null ? null : origin.getY()).originPlane(origin == null ? null : origin.getPlane())
			.destinationKnown(destination != null).endWorldX(destination == null ? null : destination.getX())
			.endWorldY(destination == null ? null : destination.getY())
			.destinationPlane(destination == null ? null : destination.getPlane())
			.destinationEvidence(destinationEvidence).targetLocalX(target == null ? null : target.getX())
			.targetLocalY(target == null ? null : target.getY())
			.targetPositionEvidence(
				target == null ? null : callbackTarget != null ? "MOVED_CALLBACK_TARGET" : "PROJECTILE_API_TARGET")
			.targetsPlayer(!targetKnown || local == null ? null : targetActor == local).remainingCycles(remainingCycles)
			.estimatedImpactTick(estimated).startCycle(read("startCycle", projectile::getStartCycle))
			.endCycle(read("endCycle", projectile::getEndCycle)).slope(read("slope", projectile::getSlope))
			.startHeight(read("startHeight", projectile::getStartHeight))
			.endHeight(read("endHeight", projectile::getEndHeight)).startPos(read("startPos", projectile::getStartPos))
			.orientation(read("orientation", projectile::getOrientation)).animationId(animationId)
			.animationFrame(read("animationFrame", projectile::getAnimationFrame))
			.localX(finite("localX", projectile::getX)).localY(finite("localY", projectile::getY))
			.z(finite("z", projectile::getZ));
		ProjectileState sample = out
			.observationIssues(issues.isEmpty() ? null : Collections.unmodifiableMap(new LinkedHashMap<>(issues)))
			.build();
		state = enrich == null ? sample : enrich.apply(sample, sourceActor);
	}

	private <T> T read(String field, Supplier<T> getter)
	{
		return ObservationReads.read(issues, field, getter);
	}

	private Double finite(String field, Supplier<Double> getter)
	{
		Double value = read(field, getter);
		if (value != null && !Double.isFinite(value))
		{
			issues.put(field, "NON_FINITE:" + value);
			return null;
		}
		return value;
	}

	private static String clean(String name)
	{
		String value = name == null ? "" : removeTags(name).trim();
		return value.isEmpty() || value.equalsIgnoreCase("null") ? "Unknown" : value;
	}
}
