/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.tickreplaylogger.model.world.ActorGraphicsState;
import com.tickreplaylogger.model.world.*;
import static com.tickreplaylogger.capture.ObservationReads.read;
import java.util.*;
import java.util.function.Supplier;
import net.runelite.api.*;
import net.runelite.api.coords.*;

/** One independent actor sample. Read groups run at their existing capture points and in their existing order. */
class ActorObservation
{
	final Actor actor;
	final WorldPoint world;
	final Map<String, String> issues = new LinkedHashMap<>();
	LocalPoint local;
	WorldView view;
	Integer viewId, level, orientation, currentOrientation, animation, animationFrame, pose, poseFrame, overheadCycle,
		healthRatio, healthScale;
	String name, speech;
	Actor target;
	Boolean interactionFlag;
	int[] graphics;
	boolean graphicsKnown;
	ActorGraphicsState graphicsState;
	Map<String, Integer> worldArea;
	String worldAreaEvidence;
	boolean worldAreaViewKnown;

	ActorObservation(Actor actor, WorldPoint world, String worldIssue)
	{
		this.actor = actor;
		this.world = world;
		if (worldIssue != null)
		{
			issues.put("worldPosition", worldIssue);
		}
	}

	void position(boolean localFirst)
	{
		if (localFirst)
		{
			local = read("localPosition", actor::getLocalLocation);
		}
		view = read("worldView", actor::getWorldView);
		viewId = view == null ? null : read("worldViewId", view::getId);
		if (!localFirst)
		{
			local = read("localPosition", actor::getLocalLocation);
		}
	}

	void orientation()
	{
		orientation = read("orientation", actor::getOrientation);
		currentOrientation = read("currentOrientation", actor::getCurrentOrientation);
	}

	void animations()
	{
		animation = read("animationId", actor::getAnimation);
		animationFrame = animation == null || animation == -1 ? null : read("animationFrame", actor::getAnimationFrame);
		pose = read("poseAnimationId", actor::getPoseAnimation);
		poseFrame = pose == null || pose == -1 ? null : read("poseAnimationFrame", actor::getPoseAnimationFrame);
	}

	void speech()
	{
		speech = read("overheadText", actor::getOverheadText);
		overheadCycle = speech == null ? null : read("overheadCycle", actor::getOverheadCycle);
	}

	void health()
	{
		healthRatio = read("healthRatio", actor::getHealthRatio);
		healthScale = read("healthScale", actor::getHealthScale);
	}

	void interaction(boolean targetFirst)
	{
		if (targetFirst)
		{
			target = read("interactionTarget", actor::getInteracting);
		}
		interactionFlag = read("reportedInteracting", actor::isInteracting);
		if (!targetFirst)
		{
			target = read("interactionTarget", actor::getInteracting);
		}
	}

	void graphics()
	{
		ActorGraphicsObservation observed = new ActorGraphicsObservation(actor);
		graphicsState = observed.getState();
		graphics = observed.getIds();
		graphicsKnown = observed.isIdsKnown();
		if (graphicsState.getObservationIssues() != null)
		{
			issues.putAll(graphicsState.getObservationIssues());
		}
	}

	/** Projection only: the independent getter sample has already completed. */
	<B extends ObservedActorState.ObservedActorStateBuilder<?, ?>> B fill(B state)
	{
		state.worldViewId(viewId).combatLevel(level).localPositionKnown(local != null).worldPositionKnown(world != null)
			.planeKnown(world != null).worldX(world == null ? 0 : world.getX()).worldY(world == null ? 0 : world.getY())
			.plane(world == null ? 0 : world.getPlane()).localX(local == null ? 0 : local.getX())
			.localY(local == null ? 0 : local.getY()).orientation(orientation).currentOrientation(currentOrientation)
			.animationId(animation).animationFrame(animationFrame).poseAnimationId(pose).poseAnimationFrame(poseFrame)
			.graphicIds(graphics).graphicsKnown(graphicsKnown).graphicsState(graphicsState).overheadText(speech)
			.overheadCycle(overheadCycle);
		return state;
	}

	void callback(Map<String, Object> result)
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
		result.put("name", name);
		result.put("combatLevel", level);
		result.put("orientation", orientation);
		result.put("currentOrientation", currentOrientation);
		result.put("animationId", animation);
		result.put("animationFrame", animationFrame);
		result.put("poseAnimationId", pose);
		result.put("poseAnimationFrame", poseFrame);
		result.put("overheadText", speech);
		result.put("overheadCycle", overheadCycle);
		result.put("healthRatio", healthRatio);
		result.put("healthScale", healthScale);
		result.put("graphicIds", graphics);
		result.put("graphicsKnown", graphicsKnown);
		result.put("graphicsState", graphicsState);
	}

	void area()
	{
		worldArea = null;
		worldAreaEvidence = null;
		worldAreaViewKnown = view != null && viewId != null && !issues.containsKey("worldView")
			&& !issues.containsKey("worldViewId");
		WorldArea area = read("worldArea", actor::getWorldArea);
		if (area == null)
		{
			worldAreaEvidence = "UNAVAILABLE";
			return;
		}
		try
		{
			if (area.getWidth() <= 0 || area.getHeight() <= 0)
			{
				issues.put("worldArea", "INVALID_BOUNDS");
				return;
			}
			Map<String, Integer> bounds = new LinkedHashMap<>();
			bounds.put("minX", area.getX());
			bounds.put("minY", area.getY());
			bounds.put("maxX", Math.addExact(area.getX(), area.getWidth() - 1));
			bounds.put("maxY", Math.addExact(area.getY(), area.getHeight() - 1));
			bounds.put("plane", area.getPlane());
			worldArea = Collections.unmodifiableMap(bounds);
			worldAreaEvidence = "ACTOR_GET_WORLD_AREA";
		}
		catch (RuntimeException unavailable)
		{
			issues.put("worldArea", unavailable.getClass().getSimpleName());
		}
	}

	void snapshotArea(NpcState.NpcStateBuilder<?, ?> state)
	{
		state.worldAreaKnown(worldArea != null).worldArea(worldArea).worldAreaWorldViewKnown(worldAreaViewKnown)
			.worldAreaWorldViewId(worldAreaViewKnown ? viewId : null)
			.worldAreaEvidence(worldAreaEvidence == null ? "UNAVAILABLE" : worldAreaEvidence);
	}

	void callbackArea(Map<String, Object> result)
	{
		result.put("worldAreaKnown", worldArea != null);
		result.put("worldArea", worldArea);
		result.put("worldAreaWorldViewKnown", worldAreaViewKnown);
		result.put("worldAreaWorldViewId", worldAreaViewKnown ? viewId : null);
		if (worldAreaEvidence != null)
		{
			result.put("worldAreaEvidence", worldAreaEvidence);
		}
	}

	<T> T read(String field, Supplier<T> getter)
	{
		return ObservationReads.read(issues, field, getter);
	}

}
