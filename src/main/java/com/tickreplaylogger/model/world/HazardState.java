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

import java.util.Map;
import lombok.*;

/**
 * An environmental hazard observed this tick (acid pool, falling-rock shadow, blood splat, fire,
 * etc.) — a graphics object, spawned game/ground object, or AoE. Used for environmental damage
 * attribution (§5.3) and explicit hazard tile observations.
 */
@Data
@Builder
public class HazardState
{
	private final String kind; // graphicsObject | gameObject | groundObject | wallObject | decorativeObject | projectileAoe
	private final int id;
	private final Integer baseId; // original TileObject id when resolving a dynamic definition
	private final Long objectKey; // same tracker identity as the neutral scene-object channel, when available
	private final Long objectObservationId; // shared API lifetime identity used by callback contact witnesses
	private final Long graphicObservationId; // recorder-local graphics API lifetime identity; distinct from scene objectKey
	private final String state; // inactive | warning | active | unknown; null if unclassified
	private final Boolean damaging; // null means unclassified, not a safe tile
	private final String stateEvidence;
	private final String sourceEligibility; // reference candidate, unresolved condition or non-HP reference
	private final String surfaceRuleKey;
	private final String mapRef;
	private final Integer minSceneX; // observed object footprint, inclusive scene tile bounds
	private final Integer minSceneY;
	private final Integer maxSceneX;
	private final Integer maxSceneY;
	private final Boolean objectNativeScopeKnown; // independent TileObject API scope for interpreting object bounds
	private final Integer objectWorldViewId;
	private final Integer objectPlane;
	private final int worldX;
	private final Boolean localPositionKnown;
	private final Boolean worldPositionKnown;
	private final int worldY;
	private final int plane;
	private final int localX;
	private final int localY;
	private final String mappedName; // from the bundled hazard name index, or null
	private final Integer startCycle;
	private final Integer worldViewId;
	private final Integer z;
	private final Integer animationId;
	private final Integer animationFrame;
	private final Boolean finished;
	private final Map<String, Boolean> fieldAvailability;
	private final Map<String, String> observationIssues;
	private final java.util.List<String> nullFields;
}
