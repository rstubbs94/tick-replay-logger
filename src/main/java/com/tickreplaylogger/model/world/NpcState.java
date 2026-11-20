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
package com.tickreplaylogger.model.world;

import java.util.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * One NPC's per-tick state in a {@link WorldSnapshot}. Captured for every NPC in the loaded scene
 * retaining each observed enemy pose and position relative to the player.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@SuperBuilder(toBuilder = true)
public class NpcState extends ObservedActorState
{
	private final Integer sceneId; // unavailable is distinct from observed index zero
	private final String actorRef;
	private final Integer npcId;
	private final Integer baseNpcId;
	private final String definitionName;
	private final String definitionEvidence;
	private final NpcDefinitionStateReference definitionStateReference;
	private final Boolean definitionHasTransforms;
	private final int[] definitionTransformIds;
	private final List<String> variantCandidates;
	private final List<String> researchReferenceKeys;
	private final String actionEvidence;
	private final String attackOnsetEvidence; // observed animation change/reset; attack/cooldown interpretation remains estimated
	private final String observedPhase;
	private final String phaseEvidence;
	private final String phaseRule;
	private final Integer phaseObservedTick;
	private final Integer phaseLastObservedTick;
	// Direct API occupied area; this is not a server damage footprint or activation claim.
	private final Map<String, Integer> worldArea;
	private final Boolean worldAreaKnown;
	private final Boolean worldAreaWorldViewKnown;
	private final Integer worldAreaWorldViewId;
	private final String worldAreaEvidence;
	private final String name;
	private final Boolean normalWorldContextObserved;
	private final Boolean modelOverridesPresent;
	private final int[] overrideModelIds;
	private final Integer size;
	private final int[] overheadArchiveIds;
	private final Boolean overheadIconsKnown;
	private final short[] overheadSpriteIds;

	private final Integer healthRatio; // -1 if no bar; null if unavailable
	private final Integer healthScale;

	private final Boolean interactingWithPlayer;

	private final String derivedAction; // IDLE|MOVING|ATTACKING|MOVING_AND_ATTACKING|COOLDOWN
	private final Integer attackCooldownTicksRemaining;
}
