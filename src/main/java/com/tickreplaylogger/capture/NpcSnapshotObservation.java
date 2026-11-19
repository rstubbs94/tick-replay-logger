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
import com.tickreplaylogger.model.world.NpcState;
import java.util.*;
import java.util.function.Supplier;
import net.runelite.api.*;
import net.runelite.api.coords.*;

/** Independent reads of one reported NPC; a failed field cannot erase another observation. */
final class NpcSnapshotObservation
{
	final NPC actor;
	final Map<String, String> issues = new LinkedHashMap<>();
	final Integer index, baseId, id, level, size, animation, animationFrame;
	final LocalPoint local;
	final WorldPoint world;
	final WorldView view;
	final Actor target;
	final Boolean interactionFlag, hasTransforms;
	final NpcOverrides overrides;
	final String name, speech;
	final NpcState.NpcStateBuilder state;

	NpcSnapshotObservation(NPC npc, WorldPoint observedWorld, String worldIssue)
	{
		actor = npc;
		if (worldIssue != null)
		{
			issues.put("worldPosition", worldIssue);
		}
		index = read("sceneId", npc::getIndex);
		NpcDefinitionObservation definition = NpcDefinitionObservation.capture(npc, issues, "");
		baseId = definition.getBaseId();
		view = read("worldView", npc::getWorldView);
		Integer viewId = view == null ? null : read("worldViewId", view::getId);
		world = observedWorld;
		local = read("localPosition", npc::getLocalLocation);
		name = read("name", npc::getName);
		level = read("combatLevel", npc::getCombatLevel);
		int[] transforms = definition.getTransforms();
		hasTransforms = definition.getHasTransforms();
		id = definition.getId();
		String evidence = definition.getEvidence(), definitionName = definition.getDefinitionName();
		size = definition.getSize();
		overrides = read("modelOverrides", npc::getModelOverrides);
		int[] overrideIds = overrides == null ? null : read("overrideModelIds", () -> copy(overrides.getModelIds()));
		animation = read("animationId", npc::getAnimation);
		animationFrame = animation == null || animation == -1 ? null : read("animationFrame", npc::getAnimationFrame);
		Integer pose = read("poseAnimationId", npc::getPoseAnimation);
		Integer poseFrame = pose == null || pose == -1 ? null : read("poseAnimationFrame", npc::getPoseAnimationFrame);
		Integer orientation = read("orientation", npc::getOrientation);
		Integer currentOrientation = read("currentOrientation", npc::getCurrentOrientation);
		Integer healthRatio = read("healthRatio", npc::getHealthRatio);
		Integer healthScale = read("healthScale", npc::getHealthScale);
		speech = read("overheadText", npc::getOverheadText);
		Integer overheadCycle = speech == null ? null : read("overheadCycle", npc::getOverheadCycle);
		int[] archives = read("overheadArchiveIds", () -> copy(npc.getOverheadArchiveIds()));
		short[] sprites = read("overheadSpriteIds", () ->
		{
			short[] value = npc.getOverheadSpriteIds();
			return value == null ? null : value.clone();
		});
		target = read("interactionTarget", npc::getInteracting);
		interactionFlag = read("reportedInteracting", npc::isInteracting);
		ActorGraphicsObservation observedGraphics = new ActorGraphicsObservation(npc);
		com.tickreplaylogger.model.world.ActorGraphicsState graphicsState = observedGraphics.getState();
		if (graphicsState.getObservationIssues() != null)
		{
			issues.putAll(graphicsState.getObservationIssues());
		}
		state = NpcState.builder().sceneId(index).npcId(id).baseNpcId(baseId).worldViewId(viewId)
			.definitionName(definitionName).definitionEvidence(evidence).definitionHasTransforms(hasTransforms)
			.definitionTransformIds(transforms).name(name == null ? null : removeTags(name).trim()).combatLevel(level)
			.localPositionKnown(local != null).worldPositionKnown(world != null).planeKnown(world != null)
			.localX(local == null ? 0 : local.getX()).localY(local == null ? 0 : local.getY())
			.worldX(world == null ? 0 : world.getX()).worldY(world == null ? 0 : world.getY())
			.plane(world == null ? 0 : world.getPlane()).size(size)
			.modelOverridesPresent(issues.containsKey("modelOverrides") ? null : overrides != null)
			.overrideModelIds(overrideIds).animationId(animation).animationFrame(animationFrame).poseAnimationId(pose)
			.poseAnimationFrame(poseFrame).orientation(orientation).currentOrientation(currentOrientation)
			.healthRatio(healthRatio).healthScale(healthScale).overheadText(speech).overheadCycle(overheadCycle)
			.overheadArchiveIds(archives).overheadSpriteIds(sprites)
			.overheadIconsKnown(archives != null && sprites != null).graphicIds(observedGraphics.getIds())
			.graphicsKnown(observedGraphics.isIdsKnown()).graphicsState(graphicsState);
		captureOccupiedArea(viewId);
	}

	/** Preserve the direct API observation independently of coordinates, definition size and combat state. */
	private void captureOccupiedArea(Integer viewId)
	{
		boolean viewKnown = view != null && viewId != null && !issues.containsKey("worldView")
			&& !issues.containsKey("worldViewId");
		state.worldAreaKnown(false).worldAreaWorldViewKnown(viewKnown).worldAreaWorldViewId(viewKnown ? viewId : null)
			.worldAreaEvidence("UNAVAILABLE");
		WorldArea area = read("worldArea", actor::getWorldArea);
		if (area == null)
		{
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
			state.worldArea(Collections.unmodifiableMap(bounds)).worldAreaKnown(true)
				.worldAreaEvidence("ACTOR_GET_WORLD_AREA");
		}
		catch (RuntimeException unavailable)
		{
			issues.put("worldArea", unavailable.getClass().getSimpleName());
		}
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

	private static int[] copy(int[] value)
	{
		return value == null ? null : value.clone();
	}

	boolean timingInputsKnown()
	{
		return index != null && id != null && level != null && animation != null
			&& (animation == -1 || animationFrame != null) && !issues.containsKey("modelOverrides")
			&& !issues.containsKey("phaseEvaluation");
	}
}
