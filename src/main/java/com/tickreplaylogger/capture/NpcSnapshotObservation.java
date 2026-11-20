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
final class NpcSnapshotObservation extends ActorObservation
{
	final Integer index, baseId, id, size;
	final Boolean hasTransforms;
	final NpcOverrides overrides;
	final NpcState.NpcStateBuilder<?, ?> state;

	NpcSnapshotObservation(NPC npc, WorldPoint observedWorld, String worldIssue)
	{
		super(npc, observedWorld, worldIssue);
		index = read("sceneId", npc::getIndex);
		NpcDefinitionObservation definition = NpcDefinitionObservation.capture(npc, issues, "");
		baseId = definition.getBaseId();
		position(false);
		name = read("name", npc::getName);
		level = read("combatLevel", npc::getCombatLevel);
		int[] transforms = definition.getTransforms();
		hasTransforms = definition.getHasTransforms();
		id = definition.getId();
		String evidence = definition.getEvidence(), definitionName = definition.getDefinitionName();
		size = definition.getSize();
		overrides = read("modelOverrides", npc::getModelOverrides);
		int[] overrideIds = overrides == null ? null : read("overrideModelIds", () -> copy(overrides.getModelIds()));
		animations();
		orientation();
		health();
		speech();
		int[] archives = read("overheadArchiveIds", () -> copy(npc.getOverheadArchiveIds()));
		short[] sprites = read("overheadSpriteIds", () ->
		{
			short[] value = npc.getOverheadSpriteIds();
			return value == null ? null : value.clone();
		});
		interaction(true);
		graphics();
		state = fill(NpcState.builder()).sceneId(index).npcId(id).baseNpcId(baseId).definitionName(definitionName)
			.definitionEvidence(evidence).definitionHasTransforms(hasTransforms).definitionTransformIds(transforms)
			.name(name == null ? null : removeTags(name).trim()).size(size)
			.modelOverridesPresent(issues.containsKey("modelOverrides") ? null : overrides != null)
			.overrideModelIds(overrideIds).healthRatio(healthRatio).healthScale(healthScale)
			.overheadArchiveIds(archives).overheadSpriteIds(sprites)
			.overheadIconsKnown(archives != null && sprites != null);
		captureOccupiedArea(viewId);
	}

	/** Independent sampling point; callbacks deliberately perform their own subsequent area read. */
	private void captureOccupiedArea(Integer viewId)
	{
		area();
		snapshotArea(state);
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
