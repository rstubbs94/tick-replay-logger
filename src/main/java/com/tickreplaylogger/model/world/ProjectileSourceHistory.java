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
import lombok.Builder;
import lombok.Data;

/** Retained observations of one registered API object, not a hidden launch or causal hit ID. */
@Data
@Builder
public class ProjectileSourceHistory
{
	private final int version;
	private final int observedTick;
	private final long contextEpoch;
	private final SourceObservation firstObservedSource;
	private final SourceObservation firstActorSource; // Only present when an actor was first reported later.
	private final boolean actorChanged, referenceChanged, definitionChanged, scopeChanged, observationGap,
		projectileDefinitionChanged;

	public boolean isAttributionUnestablished()
	{
		return actorChanged || scopeChanged || observationGap || projectileDefinitionChanged;
	}

	public SourceObservation firstActor()
	{
		return firstActorSource != null ? firstActorSource
			: firstObservedSource != null && "ACTOR".equals(firstObservedSource.getSourceEvidence())
				? firstObservedSource
				: null;
	}

	@Data
	@Builder(toBuilder = true)
	public static class SourceObservation
	{
		private final Long observationId;
		private final Integer projectileId, observedTick, observedCycle;
		private final long contextEpoch;
		private final String sampleEvidence, actorKind, sourceRef, sourceEvidence, sourceName, sourceObservedName;
		private final String sourceSceneKey;
		private final Boolean sourceActorKnown, sourceNameKnown;
		private final Boolean proximitySampleRetained; // True also retains an original null/unavailable result.
		private final ProjectileSourceCandidate proximityCandidate; // Original cohort; current candidate stays on ProjectileState.
		private final Integer sourceNpcIndex, sourceNpcId, sourceBaseNpcId, sourceCombatLevel, sourceWorldViewId,
			sourcePlane;
		private final String sourceDefinitionEvidence;
		private final Boolean sourceDefinitionHasTransforms, sourceNormalWorldContextObserved, sourceModelOverridesPresent;
		private final Map<String, String> observationIssues;
	}
}
