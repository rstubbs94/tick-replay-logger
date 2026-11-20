/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import java.math.BigDecimal;
import static com.tickreplaylogger.capture.ResolverEvidence.*;

/** One evaluated component. Source facts remain on their original recorded receipt. */
final class ResolutionCandidate
{
	String key, ruleId, sourceKey, status = "POSSIBLE";
	String confidence, sourceAssociation, attributionEvidence, label, evidenceMode;
	String candidateStrength, exclusionScope, observedLabel;
	boolean ordinary, sourceOnly;
	Source source;
	Identity identityOverride;
	String familyKey;
	JsonObject rule, referenceAnnotations, derivedAnnotations;
	Boolean componentQualified, componentIdentified, identityQualified, causalAssociationOnly;
	Boolean legacyApiReferenceConflictEligible, scalarEligible;
	JsonArray evidence = new JsonArray(), reasons = new JsonArray(), references;
	JsonElement maximum, hitMultiplicity;

	static ResolutionCandidate observed(String ruleId, Source source)
	{
		ResolutionCandidate row = new ResolutionCandidate();
		row.ruleId = ruleId;
		row.source = source;
		row.sourceKey = sourceKey(source.identity());
		Identity identity = source.identity();
		row.familyKey = identity.actorRef == null && identity.id != null ? identity.kind + "-definition:" + identity.id
			: "status".equals(identity.kind) ? identity.observationKey : null;
		row.key = source.key() + ":" + ruleId;
		row.evidence.add("RECORDED_ASSOCIATION_ONLY");
		Receipt receipt = source.receipt();
		if (receipt != null && receipt.clock.sequence != null && receipt.clock.sequence > 0)
		{
			row.evidence.add("OBSERVATION_SEQUENCE:" + receipt.clock.sequence);
		}
		JsonObject sample = source.sample() == null ? null : source.sample().raw;
		if (sample != null && integer(sample.get("projectileId")) != null)
		{
			BigDecimal id = number(sample.get("observationId"));
			if (id != null && id.signum() > 0 && id.stripTrailingZeros().scale() <= 0)
			{
				row.evidence.add("PROJECTILE_OBSERVATION_ID:" + id.toBigIntegerExact());
			}
		}
		if (source.channel() == Channel.TYPED_STATUS)
		{
			row.evidence.add("OBSERVED_HIT_FAMILY_ONLY");
		}
		if (source.channel() == Channel.FRAME_NPC || source.channel() == Channel.FRAME_SURFACE)
		{
			row.evidence.add("SETTLED_REFERENCE_ONLY");
		}
		if ("NPC_CONTACT".equals(text(source.raw(), "kind")))
		{
			row.evidence.add("ORIGINAL_NPC_CONTACT_OBSERVATION");
		}
		return row;
	}

	static ResolutionCandidate hint(String ruleId, Source source)
	{
		ResolutionCandidate row = observed(ruleId, source);
		row.componentQualified = false;
		row.componentIdentified = false;
		row.causalAssociationOnly = true;
		row.evidence.add("COMPONENT_UNKNOWN");
		row.evidence.add(
			"HIT_CAUSALITY_UNESTABLISHED: observed source association does not uniquely link this impact callback");
		return row;
	}

	void applyMaximum(JsonElement maximum, Hit hit, String key)
	{
		this.maximum = maximum;
		evidence.add("QUALIFIED_MAXIMUM:" + key);
		if (BigDecimal.valueOf(hit.record().getAmount()).compareTo(number(maximum)) > 0)
		{
			status = "EXCLUDED";
			reasons.add("MAXIMUM_EXCEEDED:" + key);
		}
	}
}
