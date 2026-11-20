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
 * Observed encounter context and named client values. Nullable fields are unavailable or
 * unestablished, not defaults. Reserved invocations/mode/phase fields do not establish active
 * modifiers: interpreting them requires separate source and observation evidence.
 */
@Data
@Builder(toBuilder = true)
public class ContextBlock
{
	private final String activity; // colosseum|toa|cox|tob|inferno|wilderness|... or null
	private final Integer worldId;
	private final Integer gameRevision;
	private final List<String> worldTypes;
	private final Integer raidLevel;
	private final List<Integer> invocations;
	private final String challengeMode;
	private final ModeReference modeByRaidLevel; // Qualified reference, separate from observed/active settings.
	private final EncounterWaveCue encounterWaveCue; // Last observed cue; not a current damage predicate.
	private final Integer teamSize;
	private final Integer scaledTeamSize;
	private final String room;
	private final Integer templateRegionId;
	private final Map<String, Integer> observedVariables;
	private final Map<String, String> evidence;
	private final List<ObservedResource> resources;
	private final String bossPhase;
	private final Integer bossHpRatio;
	private final Boolean inInstance;
	private final Boolean inWilderness;

	public Boolean isInInstance()
	{
		return inInstance;
	}

	public Boolean isInWilderness()
	{
		return inWilderness;
	}

	@Data
	@Builder
	public static class ModeReference
	{
		private final String ruleKey;
		private final String namespace;
		private final int variableId;
		private final String variableSymbol;
		private final String sourceHash;
		private final String referenceCatalogId;
		private final Integer rawValue;
		private final boolean known;
		private final String state;
		private final String label;
		private final String evidence;
	}

	@Data
	@Builder
	public static class ObservedResource
	{
		private final String key;
		private final String label;
		private final int rawValue;
		private final int divisor;
		private final Integer maxRaw;
		private final String unit;
		private final String evidence;
	}
}
