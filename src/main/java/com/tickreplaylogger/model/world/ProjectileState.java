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

/**
 * Independent client observations. First observation and estimated arrival do not establish
 * a hidden attack-launch tick or a hit outcome. Missing fields are not zero values.
 */
@Data
@Builder(toBuilder = true)
public class ProjectileState
{
	private final Integer projectileId;
	private final Integer worldViewId;
	private final Integer sourceWorldViewId;
	private final Integer targetWorldViewId;
	private final Integer targetActorWorldViewId;
	private final Integer targetLocalWorldViewId;
	private final Integer sourcePlane;
	private final Integer targetPlane;
	private final String coordinateEvidence;
	private final Long observationId; // stable only within this recording session
	private final Integer observedTick;
	private final Integer observedCycle;
	private final String sourceRef; // only when a real source actor was observed
	private final String sourceSceneKey; // Original registered native scope, never the recipient's later scene.
	private final ProjectileSourceCandidate proximitySourceCandidate;
	private final String targetRef;
	private final String sourceEvidence; // ACTOR | PROXIMITY | UNKNOWN
	private final ProjectileSourceHistory sourceHistory; // Immutable first samples; never current pose/target fallback.
	private final Boolean sourceActorKnown;
	private final Boolean targetActorKnown;
	private final Boolean samplePlaneKnown;
	private final String collectionEvidence;
	private final Map<String, String> observationIssues;
	private final Boolean originKnown;
	private final Boolean destinationKnown;
	private final Double localX; // actual sampled projectile position, not its target
	private final Double localY;
	private final Double z;
	private final Integer targetLocalX;
	private final Integer targetLocalY;
	private final String positionEvidence; // TICK_SAMPLE | MOVED_CALLBACK
	private final Integer sourceNpcIndex; // source actor if an NPC, else null
	private final Integer sourceNpcId;
	private final Integer sourceBaseNpcId;
	private final String sourceDefinitionEvidence;
	private final Boolean sourceDefinitionHasTransforms;
	private final Integer sourceCombatLevel;
	private final Boolean sourceNormalWorldContextObserved;
	private final Boolean sourceModelOverridesPresent;
	private final String damageType; // Scoped source/projectile reference, not accuracy, status or a hit outcome.
	private final String damageTypeEvidence;
	private final String damageTypeRuleKey;
	private final String damageTypeCatalogId;
	private final String sourceName; // resolved source (NPC/player name), or "Unknown"
	private final String sourceObservedName;
	private final Boolean sourceNameKnown;
	private final Integer targetNpcIndex; // target actor if resolvable
	private final Boolean targetsPlayer;

	private final Integer startWorldX;
	private final Integer startWorldY;
	private final Integer endWorldX;
	private final Integer endWorldY;
	private final Integer plane;
	private final Integer originPlane;
	private final Integer destinationPlane;
	private final String destinationEvidence;
	private final String targetPositionEvidence;

	private final int spawnTick;
	private final String spawnTickEvidence; // FIRST_OBSERVED; this is not a hidden server attack-start tick
	private final Integer startCycle;
	private final Integer endCycle;
	private final Integer remainingCycles;
	private final Integer estimatedImpactTick;
	private final Integer slope;
	private final Integer startHeight;
	private final Integer endHeight;
	private final Integer startPos;
	private final Integer orientation;
	private final Integer animationId;
	private final Integer animationFrame;
}
