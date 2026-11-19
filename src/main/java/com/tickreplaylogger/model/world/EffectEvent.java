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
 * A non-damaging effect observation or scoped graphic/state reference, parallel to
 * {@link DamageRecord}. A graphic sighting does not establish an applied effect.
 */
@Data
@Builder
public class EffectEvent
{
	// freeze_bind|teleblock|stun|knockback|prayer_disable|prayer_pierce|disarm|stat_drain|
	// prayer_drain|run_energy_drain|spec_drain|dot|lifesteal|guaranteed_hit|multi_hit
	private final String type;

	private final String sourceRef; // actor ref, or null
	private final String sourceName;
	private final String targetRef; // Observed target, independent of an unknown inflictor.
	private final Integer observedGraphicId; // Registered graphic identity; visibility/application unproven.
	private final String attribution; // CONFIRMED | TIMED | PROXIMITY | ENVIRONMENTAL | UNKNOWN

	private final int startTick; // Observation/transition tick, not universally a cast/application onset.
	private final Integer durationTicks; // null if unknown
	private final String magnitude; // e.g. "-30% Defence", "16 ticks", or null

	private final String observedVia; // spotanim:<id> | varbit:<id> | skillDelta | equipmentDiff | movementDelta
	private final String mapRef; // effects.json key
	private final String evidence; // Observed state versus reference/name candidate.
	private final String referenceRuleKey;
	private final String referenceCatalogId;
	private final Integer observedRawValue;
	private final Integer previousRawValue;
	private final String journalNote; // Exact parallel journal note, for evidence-preserving deduplication.
	private final String linkedActionDetail; // Exact parallel derived action; input requests remain separate facts.
	private final String linkedActionType; // Typed link; legacy prayer aliases remain readable by consumers.
}
