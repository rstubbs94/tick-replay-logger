package com.tickreplaylogger.capture;
import com.google.gson.*;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.util.SourceResolutionLabels;
import java.util.*;
import static com.tickreplaylogger.capture.PreRuleIrResolverV1.*;
/** Frozen pre-typed presentation conversion; no runtime call sites. */
final class PreTypedSourceProjection
{
 private final String catalogId;
 PreTypedSourceProjection(String catalogId) { this.catalogId=catalogId; }
	SourceResolution projectResult(JsonObject result)
	{
		List<SourceResolution.Candidate> candidates = new ArrayList<>(), excluded = new ArrayList<>();
		List<SourceResolution.Candidate> apiReferenceConflicts = new ArrayList<>();
		for (JsonElement element : array(result.get("candidates")))
		{
			JsonObject row = object(element), source = object(row.get("source")), scope = object(source.get("scope"));
			String name = text(source, "name");
			if (SourceResolutionLabels.usableName(name) == null && !"npc".equals(text(source, "kind")))
				name = text(row, "label");
			if (SourceResolutionLabels.usableName(name) == null)
				name = integer(source.get("id")) == null
					? "player".equals(text(source, "kind")) ? "Recorded player" : "Recorded NPC"
					: text(source, "kind") + " #" + integer(source.get("id"));
			List<String> evidence = strings(row.get("evidence"));
			evidence.add("RESOLVER_V1:" + text(row, "ruleId"));
			evidence.add("RESOLVER_V1_RULESET:" + catalogId);
			if (row.has("maximum"))
				evidence.add("COMPONENT_MAXIMUM:" + row.get("maximum"));
			SourceResolution.Candidate candidate = SourceResolution.Candidate.builder().key(presentationKey(row))
				.name(name).actorRef(text(source, "actorRef"))
				.npcId("npc".equals(text(source, "kind")) ? integer(source.get("id")) : null)
				.familyKey(text(source, "familyKey") != null ? text(source, "familyKey")
					: "status".equals(text(source, "kind")) && text(source, "statusFamily") != null
						? "observed-hit-kind:" + text(source, "statusFamily")
						: text(source, "actorRef") == null && integer(source.get("id")) != null
							? text(source, "kind") + "-definition:" + integer(source.get("id"))
							: text(source, "actorRef") == null && "status".equals(text(source, "kind"))
								? text(row, "sourceKey")
								: null)
				.sceneKey(text(scope, "sceneKey")).worldViewId(integer(scope.get("worldViewId")))
				.plane(integer(scope.get("plane")))
				.strength("STRONG".equals(text(row, "candidateStrength")) ? "STRONG" : "POSSIBLE").evidence(evidence)
				.reasons(strings(row.get("reasons"))).exclusionScope(text(row, "exclusionScope")).build();
			if ("EXCLUDED".equals(text(row, "status")))
			{
				excluded.add(candidate);
				if (Boolean.TRUE.equals(bool(row.get("legacyApiReferenceConflictEligible")))
					&& !"ALL_DIRECT_HP_COMPONENTS".equals(candidate.getExclusionScope()))
					apiReferenceConflicts.add(candidate);
			}
			else
				candidates.add(candidate);
		}
		if (candidates.isEmpty())
			for (SourceResolution.Candidate conflict : apiReferenceConflicts)
			{
				List<String> evidence = conflict.getEvidence() == null ? new ArrayList<>()
					: new ArrayList<>(conflict.getEvidence());
				evidence.add(
					"REFERENCE_PROFILE_CONFLICT: observed API source actor, target and arrival correlation retained; ordinary profile exceeded");
				if (conflict.getReasons() != null)
					for (String reason : conflict.getReasons())
						evidence.add("ORDINARY_COMPONENT_EXCLUDED:" + reason);
				candidates.add(conflict.toBuilder().strength("POSSIBLE").evidence(evidence).reasons(null)
					.exclusionScope(null).build());
				excluded.remove(conflict);
			}
		if (candidates.stream().anyMatch(candidate -> !familyOnly(candidate)))
		{
			List<String> familyEvidence = new ArrayList<>();
			for (SourceResolution.Candidate candidate : candidates)
				if (familyOnly(candidate))
					familyEvidence.add(
						"OBSERVED_HIT_FAMILY:" + candidate.getFamilyKey().substring("observed-hit-kind:".length()));
			candidates.removeIf(PreTypedSourceProjection::familyOnly);
			if (!familyEvidence.isEmpty())
				for (int index = 0; index < candidates.size(); index++)
				{
					SourceResolution.Candidate candidate = candidates.get(index);
					List<String> evidence = new ArrayList<>();
					if (candidate.getEvidence() != null)
						evidence.addAll(candidate.getEvidence());
					evidence.addAll(familyEvidence);
					candidates.set(index, candidate.toBuilder().evidence(evidence).build());
				}
		}
		if (excluded.size() > 256)
			excluded = new ArrayList<>(excluded.subList(0, 256));
		return project(candidates, excluded, "RECORDED_RESOLVER_V1_COMPONENTS");
	}
	private SourceResolution project(List<SourceResolution.Candidate> candidates,
		List<SourceResolution.Candidate> excluded, String method)
	{
		List<SourceResolution.Candidate> associations = new ArrayList<>();
		for (SourceResolution.Candidate candidate : candidates)
		{
			List<String> evidence = candidate.getEvidence() == null ? new ArrayList<>()
				: new ArrayList<>(candidate.getEvidence());
			evidence.add(
				"HIT_CAUSALITY_UNESTABLISHED: observed source association does not uniquely link this impact callback");
			associations
				.add(candidate.toBuilder().evidence(new ArrayList<>(new java.util.LinkedHashSet<>(evidence))).build());
		}
		return SourceEvidenceResolver.project(associations, excluded, true, catalogId, method);
	}
	private static String presentationKey(JsonObject row)
	{
		JsonObject source = object(row.get("source")), scope = object(source.get("scope"));
		String key = text(row, "key"), rule = text(row, "ruleId");
		if (text(scope, "sceneKey") != null && integer(scope.get("worldViewId")) != null
			&& integer(scope.get("plane")) != null || key == null || rule == null
			|| !key.endsWith(":" + rule))
			return key;
		return "witness:" + key.substring(0, key.length() - rule.length() - 1) + "|" + text(row, "sourceKey") + "|"
			+ source.get("id");
	}
	private static boolean familyOnly(SourceResolution.Candidate candidate)
	{
		return candidate.getActorRef() == null && candidate.getNpcId() == null && candidate.getFamilyKey() != null
			&& candidate.getFamilyKey().startsWith("observed-hit-kind:") && candidate.getEvidence() != null
			&& candidate.getEvidence().contains("OBSERVED_HIT_FAMILY_ONLY");
	}
	private static List<String> strings(JsonElement value)
	{
		List<String> result = new ArrayList<>();
		for (JsonElement element : array(value))
			if (element.isJsonPrimitive())
				result.add(element.getAsString());
		return result;
	}
}
