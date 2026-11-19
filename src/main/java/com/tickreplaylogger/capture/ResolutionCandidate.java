/*
 * Copyright (c) 2026, rstubbs94
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import java.math.BigDecimal;
import java.util.Arrays;
import static com.tickreplaylogger.capture.ResolverV1.*;

/** One interpreted component shared directly with the recording projector. */
final class ResolutionCandidate
{
	String key, ruleId, sourceKey;
	String status = "POSSIBLE";
	String confidence, sourceAssociation, attributionEvidence, label, evidenceMode;
	String candidateStrength, exclusionScope, observedLabel;
	boolean ordinary;
	transient JsonObject source, referenceAnnotations, derivedAnnotations;
	Boolean componentQualified, componentIdentified, identityQualified, causalAssociationOnly;
	Boolean legacyApiReferenceConflictEligible, scalarEligible;
	transient JsonArray evidence = new JsonArray(), reasons = new JsonArray(), references;
	transient JsonElement maximum, hitMultiplicity;
	transient JsonObject cue;
	transient boolean sourceOnly;

	static ResolutionCandidate observed(String ruleId, JsonObject cue)
	{
		ResolutionCandidate row = new ResolutionCandidate();
		JsonObject source = object(cue.get("source"));
		row.cue = cue;
		row.key = String.valueOf(text(cue, "key")) + ":" + ruleId;
		row.ruleId = ruleId;
		row.sourceKey = sourceKey(source);
		row.ordinary = Boolean.TRUE.equals(bool(cue.get("ordinary")));
		row.source = source.deepCopy();
		row.evidenceMode = text(cue, "evidenceMode");
		row.sourceAssociation = text(cue, "sourceAssociation");
		row.observedLabel = text(cue, "observedLabel");
		row.candidateStrength = text(cue, "candidateStrength");
		row.evidence.add("RECORDED_ASSOCIATION_ONLY");
		String sequence = recordedIdentifier(cue.get("sequence"));
		if (sequence != null)
		{
			row.evidence.add("OBSERVATION_SEQUENCE:" + sequence);
		}
		JsonObject witness = object(cue.get("witness"));
		String projectile = integer(witness.get("projectileId")) == null ? null
			: recordedIdentifier(witness.get("observationId"));
		if (projectile != null)
		{
			row.evidence.add("PROJECTILE_OBSERVATION_ID:" + projectile);
		}
		if (Boolean.TRUE.equals(bool(cue.get("originalNpcContact"))))
		{
			row.evidence.add("ORIGINAL_NPC_CONTACT_OBSERVATION");
		}
		if (Boolean.TRUE.equals(bool(cue.get("hitFamilyOnly"))))
		{
			row.evidence.add("OBSERVED_HIT_FAMILY_ONLY");
		}
		if (Boolean.TRUE.equals(bool(cue.get("unstableOriginalSource"))))
		{
			row.evidence.add("ORIGINAL_API_SOURCE_HISTORY_UNSTABLE");
		}
		String clock = text(cue, "clockEvidence");
		if (Arrays.asList("HP_DURING_OBSERVED_FLIGHT", "CLIENT_END_CYCLE_CORRELATION").contains(clock))
		{
			row.evidence.add(clock);
		}
		return row;
	}

	static ResolutionCandidate hint(String ruleId, JsonObject cue)
	{
		ResolutionCandidate row = observed(ruleId, cue);
		row.componentQualified = false;
		row.componentIdentified = false;
		row.causalAssociationOnly = true;
		row.evidence.add("COMPONENT_UNKNOWN");
		row.evidence.add(
			"HIT_CAUSALITY_UNESTABLISHED: observed source association does not uniquely link this impact callback");
		for (JsonElement evidence : array(cue.get("candidateEvidence")))
		{
			row.evidence.add(evidence);
		}
		return row;
	}

	void applyMaximum(JsonElement maximum, JsonObject hit, String key)
	{
		this.maximum = maximum;
		evidence.add("QUALIFIED_MAXIMUM:" + key);
		if (number(hit.get("amount")).compareTo(number(maximum)) > 0)
		{
			status = "EXCLUDED";
			reasons.add("MAXIMUM_EXCEEDED:" + key);
		}
	}

	private static String recordedIdentifier(JsonElement value)
	{
		BigDecimal number = number(value);
		return number != null && number.signum() > 0 && number.stripTrailingZeros().scale() <= 0
			? number.toBigIntegerExact().toString()
			: null;
	}
}
