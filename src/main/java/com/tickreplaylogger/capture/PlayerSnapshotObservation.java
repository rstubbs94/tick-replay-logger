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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.runelite.api.Actor;
import net.runelite.api.HeadIcon;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/** Independent actor reads shared by local and enabled other-player snapshots. */
final class PlayerSnapshotObservation
{
	final Map<String, String> issues = new LinkedHashMap<>();
	final WorldPoint world;
	final LocalPoint local;
	final WorldView view;
	final Integer viewId, level, orientation, currentOrientation, animation, animationFrame, pose, poseFrame,
		overheadCycle;
	final Actor target;
	final Boolean interactionFlag;
	final String speech;
	final HeadIcon icon;
	final int[] graphics;
	final boolean graphicsKnown;
	final com.tickreplaylogger.model.world.ActorGraphicsState graphicsState;

	PlayerSnapshotObservation(Player actor, WorldPoint observedWorld, String worldIssue)
	{
		world = observedWorld;
		if (worldIssue != null)
			issues.put("worldPosition", worldIssue);
		local = read("localPosition", actor::getLocalLocation);
		view = read("worldView", actor::getWorldView);
		viewId = view == null ? null : read("worldViewId", view::getId);
		level = read("combatLevel", actor::getCombatLevel);
		orientation = read("orientation", actor::getOrientation);
		currentOrientation = read("currentOrientation", actor::getCurrentOrientation);
		animation = read("animationId", actor::getAnimation);
		animationFrame = animation == null || animation == -1 ? null : read("animationFrame", actor::getAnimationFrame);
		pose = read("poseAnimationId", actor::getPoseAnimation);
		poseFrame = pose == null || pose == -1 ? null : read("poseAnimationFrame", actor::getPoseAnimationFrame);
		speech = read("overheadText", actor::getOverheadText);
		overheadCycle = speech == null ? null : read("overheadCycle", actor::getOverheadCycle);
		icon = read("overheadIcon", actor::getOverheadIcon);
		target = read("interactionTarget", actor::getInteracting);
		interactionFlag = read("reportedInteracting", actor::isInteracting);
		ActorGraphicsObservation observedGraphics = new ActorGraphicsObservation(actor);
		graphicsState = observedGraphics.getState();
		graphics = observedGraphics.getIds();
		graphicsKnown = observedGraphics.isIdsKnown();
		if (graphicsState.getObservationIssues() != null)
			issues.putAll(graphicsState.getObservationIssues());
	}

	<T> T read(String field, Supplier<T> getter)
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
