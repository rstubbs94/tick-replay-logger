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

import lombok.Builder;
import lombok.Data;

/**
 * Another player's per-tick state (PvP + bystanders). Captured locally so PvP deaths reconstruct;
 * equipment is included because it is how we identify the attacker's weapon/spell. Transmission of
 * this data is a Phase-3 decision — Phase 1 is local export only.
 */
@Data
@Builder
public class OtherPlayerState
{
	private final String actorRef;
	private final Integer playerId;
	private final Integer worldViewId;
	private final ActorInteractionState interaction;
	private final Integer combatLevel;
	private final java.util.Map<String, String> observationIssues;
	private final Boolean localPositionKnown;
	private final Boolean worldPositionKnown;
	private final Boolean planeKnown;
	private final String name;
	private final int worldX;
	private final int worldY;
	private final int plane;
	private final int localX;
	private final int localY;
	private final Integer orientation;
	private final Integer currentOrientation;

	private final Integer animationId;
	private final Integer animationFrame;
	private final Integer poseAnimationId;
	private final Integer poseAnimationFrame;
	private final int[] graphicIds;
	private final Boolean graphicsKnown;
	private final ActorGraphicsState graphicsState;
	private final String overheadPrayer;
	private final String overheadText;
	private final Integer overheadCycle;
	private final Integer healthRatio;
	private final Integer healthScale;
	private final Boolean overheadIconKnown;
	private final Integer interactingNpcIndex;
	private final Boolean interactingWithPlayer;

	private final int[] equipmentItemIds; // decoded visible appearance slots, not proof of server inventory
	private final String equipmentEvidence;
	private final PlayerAppearanceState appearance;
	private final Integer team; // observed team-cape team; not clan/group membership
}
