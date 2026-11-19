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

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * A damage event in the rich export, carrying the full Â§3 lifecycle: both launch and impact ticks,
 * the attacker + attribution confidence (Â§5.4), damage type, reactability determination, PvP context
 * and the defender's prayer/position state at launch AND impact. Protection has source-specific rules.
 */
@Data
@Builder(toBuilder = true)
public class DamageRecord
{
	private final int launchTick;
	private final int impactTick;
	private final String direction; // RECEIVED | DEALT | NPC_RECEIVED; legacy absence means RECEIVED
	private final String ownership; // NPC hits only: LOCAL_PLAYER | OTHER | UNKNOWN; not a direct attacker identity or attack style
	private final String defenderRef;
	private final String defenderIdentityEvidence;
	private final String defenderName;
	private final Integer observedCycle;
	private final Long observationSequence;
	private final String attributionEvidence; // interpretation evidence, not an observed source-of-damage fact
	private final Boolean launchTickEstimated;
	private final String launchStateEvidence; // SNAPSHOT | IMPACT_FALLBACK | UNAVAILABLE
	private final List<String> captureIssues; // failed context/enrichment reads; the hitsplat value remains observed
	private final Boolean impactPositionKnown;
	private final Boolean launchPositionKnown;
	private final Boolean impactPrayerStateKnown;
	private final Boolean launchPrayerStateKnown; // false for fallback or partial launch-snapshot flags
	private final ActorGraphicsState defenderGraphicsAtImpact; // Callback sample; not later snapshot state or a protection predicate.
	private final PrayerState defenderPrayerStateAtImpact; // Shared original local callback sample; unavailable fields remain explicit.

	private final String attackerRef; // player | view/lifetime-scoped actor reference | null; legacy refs remain readable
	private final String attackerName;
	private final Integer attackerNpcId;
	private final Integer attackerCombatLevel;
	private final String attribution; // CONFIRMED | TIMED | PROXIMITY | ENVIRONMENTAL | UNKNOWN
	private final SourceResolution sourceResolution;
	private final String impactSceneKey;
	private final Integer impactWorldViewId;
	private final Integer impactPlane;
	private final java.util.List<java.util.Map<String, Object>> attributionCandidates;
	private final java.util.List<java.util.Map<String, Object>> observedHazardContacts; // concurrent spatial observations, not a causal assignment; also retained for status hits

	private final int amount; // observed hitsplat callback value; not a reconstructed hidden roll or per-hit net HP loss
	private final int hitsplatType;
	private final Boolean hitsplatTypeKnown; // original callback availability; absent type0 is not an observed category
	private final String damageTypeEvidence; // HITSPLAT_TYPE | LABELLED_FROM_MAP | UNKNOWN | LEGACY_JOURNAL
	private final String damageType; // melee|ranged|magic|typeless|poison|venom|dragonfire|...
	private final String damageRecurrence; // UNKNOWN: a typed hitsplat alone does not establish a recurring tick
	private final String damageRecurrenceEvidence;
	private final String npcDamageReductionStyle; // sourced NPC reduction category, not an accuracy roll
	private final String damageReferenceKey;
	private final String damagePropertyEvidence; // SOURCE_REFERENCE, not an observed mitigation outcome
	private final java.util.List<String> damageTypeCandidates; // reference hints, not proof of this hit's style
	private final String attackStyleName; // from gameval name semantics, or null

	private final String determination; // LAUNCH | IMPACT | UNKNOWN (reactability)
	private final String determinationEvidence;
	private final String determinationRuleKey;
	private final String damageTypeRuleKey;
	private final String damageTypeCatalogId;
	private final String prayerEffect; // NONE_REVIEWED for scoped source rule; null is unspecified, not full protection
	private final boolean pvp;

	// Defender state at the two lifecycle ticks (reactability analysis)
	private final String overheadAtLaunch;
	private final List<String> prayersAtLaunch;
	private final int launchX;
	private final int launchY;
	private final String overheadAtImpact;
	private final String overheadIconAtImpact;
	private final List<String> reportedPrayersAtImpact;
	private final Boolean prayerStateConflictAtImpact;
	private final String overheadAtImpactEvidence;
	private final List<String> prayersAtImpact;
	private final int impactX;
	private final int impactY;

	private final String mapRef; // sources.json / specials.json / generic rule that labelled it
}
