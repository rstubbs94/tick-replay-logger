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

import com.tickreplaylogger.model.world.NpcState;
import java.util.*;
import java.util.function.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;

/** Callback-time actor fields. Optional reads cannot erase an independently observed field. */
final class LegacyActorCallbackObservation22
{
	private LegacyActorCallbackObservation22()
	{
	}

	/** Internal callback sample. Native pointers are never inserted in exported data. */
	public static final class Sample
	{
		private final Map<String, Object> data;
		private final WorldPoint world;
		private final LocalPoint local;
		private final WorldView view;

		private Sample(Map<String, Object> data, WorldPoint world, LocalPoint local, WorldView view)
		{
			this.data = data;
			this.world = world;
			this.local = local;
			this.view = view;
		}

		public Map<String, Object> data()
		{
			return data;
		}

		public WorldPoint world()
		{
			return world;
		}

		public LocalPoint local()
		{
			return local;
		}

		public WorldView view()
		{
			return view;
		}
	}

	/** The supplied old composition is not transformed using today's actor/varbit state. */
	public static Map<String, Object> oldComposition(NPCComposition composition)
	{
		if (composition == null)
		{
			return null;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		Map<String, String> issues = new LinkedHashMap<>();
		result.put("evidence", "NPC_CHANGED_OLD_COMPOSITION_EVENT_PAYLOAD");
		result.put("id", read(issues, "id", composition::getId));
		result.put("name", read(issues, "name", composition::getName));
		result.put("size", read(issues, "size", composition::getSize));
		result.put("combatLevel", read(issues, "combatLevel", composition::getCombatLevel));
		int[] configs = read(issues, "transformIds", () ->
		{
			int[] values = composition.getConfigs();
			return values == null ? null : values.clone();
		});
		result.put("transformIds", configs);
		result.put("hasTransforms", issues.containsKey("transformIds") ? null : configs != null);
		if (!issues.isEmpty())
		{
			result.put("observationIssues", Collections.unmodifiableMap(new LinkedHashMap<>(issues)));
		}
		return Collections.unmodifiableMap(result);
	}

	public static Map<String, Object> capture(Actor actor, Client readyClient, Player local, boolean includeOthers,
		Function<Actor, String> refs)
	{
		return captureSample(actor, readyClient, local, includeOthers, refs).data();
	}

	public static Sample captureSample(Actor actor, Client readyClient, Player local, boolean includeOthers,
		Function<Actor, String> refs)
	{
		return captureSample(actor, readyClient, local, includeOthers, refs, null);
	}

	public static Sample captureSample(Actor actor, Client readyClient, Player local, boolean includeOthers,
		Function<Actor, String> refs, BiFunction<Actor, Map<String, Object>, String> observedRefs)
	{
		if (actor == null || actor instanceof Player && actor != local && !includeOthers)
		{
			return new Sample(Collections.emptyMap(), null, null, null);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		Map<String, String> issues = new LinkedHashMap<>();
		WorldPoint world = read(issues, "worldPosition", actor::getWorldLocation);
		WorldView observedView = null;
		LocalPoint observedLocal = null;
		String worldIssue = issues.get("worldPosition");
		result.put("actorKind",
			actor instanceof NPC ? "NPC"
				: actor == local ? "LOCAL_PLAYER"
					: actor instanceof Player ? local == null ? "PLAYER" : "OTHER_PLAYER" : "ACTOR");
		if (actor instanceof NPC)
		{
			LegacyNpcSnapshotObservation22 sample = new LegacyNpcSnapshotObservation22((NPC) actor, world, worldIssue);
			issues = sample.issues;
			NpcState state = sample.state.build();
			observedView = sample.view;
			observedLocal = sample.local;
			coordinates(result, sample.local, world, state.getWorldViewId());
			worldArea(result, issues, actor, sample.view, state.getWorldViewId());
			result.put("name", sample.name);
			result.put("sceneId", sample.index);
			result.put("npcId", sample.id);
			result.put("baseNpcId", sample.baseId);
			result.put("combatLevel", sample.level);
			result.put("size", sample.size);
			result.put("definitionName", state.getDefinitionName());
			result.put("definitionEvidence", state.getDefinitionEvidence());
			result.put("definitionHasTransforms", sample.hasTransforms);
			result.put("definitionTransformIds", state.getDefinitionTransformIds());
			result.put("modelOverridesPresent", state.getModelOverridesPresent());
			result.put("overrideModelIds", state.getOverrideModelIds());
			result.put("normalWorldContextObserved", readyClient == null ? null
				: !issues.containsKey("modelOverrides")
					&& NpcIdentityContext.normalWorldObservedWithoutOverrides(readyClient, sample.overrides == null));
			motion(result, state.getOrientation(), state.getCurrentOrientation(), sample.animation,
				sample.animationFrame, state.getPoseAnimationId(), state.getPoseAnimationFrame(), sample.speech,
				state.getOverheadCycle());
			result.put("healthRatio", state.getHealthRatio());
			result.put("healthScale", state.getHealthScale());
			result.put("overheadArchiveIds", state.getOverheadArchiveIds());
			result.put("overheadSpriteIds", state.getOverheadSpriteIds());
			result.put("overheadIconsKnown", state.getOverheadIconsKnown());
			result.put("graphicIds", state.getGraphicIds());
			result.put("graphicsKnown", state.getGraphicsKnown());
			result.put("graphicsState", state.getGraphicsState());
			if (observedRefs != null && sample.target == actor)
			{
				observedRefs.apply(actor, result);
			}
			result.put("interaction",
				ActorInteractions.captureObserved(sample.target, sample.interactionFlag,
					!issues.containsKey("interactionTarget"), local, includeOthers, refs,
					sample.target == actor ? result : null, issues));
		}
		else if (actor instanceof Player)
		{
			Player player = (Player) actor;
			LegacyPlayerSnapshotObservation22 sample = new LegacyPlayerSnapshotObservation22(player, world, worldIssue);
			issues = sample.issues;
			observedView = sample.view;
			observedLocal = sample.local;
			coordinates(result, sample.local, world, sample.viewId);
			result.put("name", sample.read("name", player::getName));
			result.put("playerId", sample.read("playerId", player::getId));
			result.put("combatLevel", sample.level);
			motion(result, sample.orientation, sample.currentOrientation, sample.animation, sample.animationFrame,
				sample.pose, sample.poseFrame, sample.speech, sample.overheadCycle);
			result.put("healthRatio", sample.read("healthRatio", player::getHealthRatio));
			result.put("healthScale", sample.read("healthScale", player::getHealthScale));
			result.put("overheadIcon", sample.icon == null ? null : sample.icon.name());
			result.put("overheadIconKnown", !issues.containsKey("overheadIcon"));
			result.put("graphicIds", sample.graphics);
			result.put("graphicsKnown", sample.graphicsKnown);
			result.put("graphicsState", sample.graphicsState);
			result.put("appearance", PlayerAppearance.capture(player));
			if (observedRefs != null && sample.target == actor)
			{
				observedRefs.apply(actor, result);
			}
			result.put("interaction",
				ActorInteractions.captureObserved(sample.target, sample.interactionFlag,
					!issues.containsKey("interactionTarget"), local, includeOthers, refs,
					sample.target == actor ? result : null, issues));
		}
		else
		{
			LocalPoint point = read(issues, "localPosition", actor::getLocalLocation);
			WorldView view = read(issues, "worldView", actor::getWorldView);
			observedView = view;
			observedLocal = point;
			coordinates(result, point, world, view == null ? null : read(issues, "worldViewId", view::getId));
			result.put("name", read(issues, "name", actor::getName));
			result.put("combatLevel", read(issues, "combatLevel", actor::getCombatLevel));
			Integer animation = read(issues, "animationId", actor::getAnimation),
				pose = read(issues, "poseAnimationId", actor::getPoseAnimation);
			String text = read(issues, "overheadText", actor::getOverheadText);
			motion(result, read(issues, "orientation", actor::getOrientation),
				read(issues, "currentOrientation", actor::getCurrentOrientation), animation,
				animation == null || animation == -1 ? null : read(issues, "animationFrame", actor::getAnimationFrame),
				pose,
				pose == null || pose == -1 ? null : read(issues, "poseAnimationFrame", actor::getPoseAnimationFrame),
				text, text == null ? null : read(issues, "overheadCycle", actor::getOverheadCycle));
			result.put("healthRatio", read(issues, "healthRatio", actor::getHealthRatio));
			result.put("healthScale", read(issues, "healthScale", actor::getHealthScale));
			ActorGraphicsObservation graphics = new ActorGraphicsObservation(actor);
			result.put("graphicIds", graphics.getIds());
			result.put("graphicsKnown", graphics.isIdsKnown());
			result.put("graphicsState", graphics.getState());
			if (graphics.getState().getObservationIssues() != null)
			{
				issues.putAll(graphics.getState().getObservationIssues());
			}
			Boolean interactionFlag = read(issues, "reportedInteracting", actor::isInteracting);
			Actor target = read(issues, "interactionTarget", actor::getInteracting);
			if (observedRefs != null && target == actor)
			{
				observedRefs.apply(actor, result);
			}
			result.put("interaction",
				ActorInteractions.captureObserved(target, interactionFlag, !issues.containsKey("interactionTarget"),
					local, includeOthers, refs, target == actor ? result : null, issues));
		}
		if (!issues.isEmpty())
		{
			result.put("observationIssues", Collections.unmodifiableMap(new LinkedHashMap<>(issues)));
			result.put("captureStatus", "PARTIAL_ACTOR_METADATA");
		}
		return new Sample(Collections.unmodifiableMap(result), world, observedLocal, observedView);
	}

	private static void coordinates(Map<String, Object> result, LocalPoint local, WorldPoint world, Integer viewId)
	{
		result.put("worldViewId", viewId);
		result.put("localPositionKnown", local != null);
		result.put("worldPositionKnown", world != null);
		result.put("planeKnown", world != null);
		result.put("localX", local == null ? null : local.getX());
		result.put("localY", local == null ? null : local.getY());
		result.put("worldX", world == null ? null : world.getX());
		result.put("worldY", world == null ? null : world.getY());
		result.put("plane", world == null ? null : world.getPlane());
	}

	/** Direct API occupied bounds; not a server damage footprint or activation claim. */
	private static void worldArea(Map<String, Object> result, Map<String, String> issues, Actor actor, WorldView view,
		Integer viewId)
	{
		result.put("worldAreaKnown", false);
		result.put("worldArea", null);
		boolean viewKnown = view != null && viewId != null && !issues.containsKey("worldView")
			&& !issues.containsKey("worldViewId");
		result.put("worldAreaWorldViewKnown", viewKnown);
		result.put("worldAreaWorldViewId", viewKnown ? viewId : null);
		WorldArea area = read(issues, "worldArea", actor::getWorldArea);
		if (area == null)
		{
			// A successful null read is unavailable evidence, not an API failure.
			result.put("worldAreaEvidence", "UNAVAILABLE");
			return;
		}
		try
		{
			if (area.getWidth() <= 0 || area.getHeight() <= 0)
			{
				issues.put("worldArea", "INVALID_BOUNDS");
				return;
			}
			Map<String, Object> bounds = new LinkedHashMap<>();
			bounds.put("minX", area.getX());
			bounds.put("minY", area.getY());
			bounds.put("maxX", Math.addExact(area.getX(), area.getWidth() - 1));
			bounds.put("maxY", Math.addExact(area.getY(), area.getHeight() - 1));
			bounds.put("plane", area.getPlane());
			result.put("worldArea", Collections.unmodifiableMap(bounds));
			result.put("worldAreaKnown", true);
			result.put("worldAreaEvidence", "ACTOR_GET_WORLD_AREA");
		}
		catch (RuntimeException unavailable)
		{
			issues.put("worldArea", unavailable.getClass().getSimpleName());
		}
	}

	private static void motion(Map<String, Object> result, Integer orientation, Integer currentOrientation,
		Integer animation, Integer frame, Integer pose, Integer poseFrame, String speech, Integer overheadCycle)
	{
		result.put("orientation", orientation);
		result.put("currentOrientation", currentOrientation);
		result.put("animationId", animation);
		result.put("animationFrame", frame);
		result.put("poseAnimationId", pose);
		result.put("poseAnimationFrame", poseFrame);
		result.put("overheadText", speech);
		result.put("overheadCycle", overheadCycle);
	}

	private static <T> T read(Map<String, String> issues, String field, Supplier<T> getter)
	{
		try
		{
			return getter.get();
		}
		catch (RuntimeException unavailable)
		{
			issues.put(field, unavailable.getClass().getSimpleName());
			return null;
		}
	}
}
