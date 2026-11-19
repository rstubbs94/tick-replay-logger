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
package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.util.SourceResolutionLabels;
import static com.tickreplaylogger.capture.ResolverV1.*;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.SourceResolution.Candidate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.runelite.api.HitsplatID;

/** Groups already evaluated source alternatives for consistent retrospective presentation. */
public final class SourceEvidenceResolver
{
	public static final int MAX_HYPOTHESES = 256;
	private static final Set<Integer> WHOLE_ACTOR_V1_HITSPLATS = Collections
		.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(HitsplatID.DAMAGE_ME, HitsplatID.DAMAGE_OTHER,
			HitsplatID.DAMAGE_MAX_ME, HitsplatID.BLOCK_ME, HitsplatID.BLOCK_OTHER)));

	/** Contract domain only: membership never identifies an attacker or makes other outcomes impossible. */
	static boolean wholeActorHitsplatDomain(List<Integer> selected)
	{
		return selected != null && !selected.isEmpty() && selected.size() <= WHOLE_ACTOR_V1_HITSPLATS.size()
			&& new LinkedHashSet<>(selected).size() == selected.size()
			&& WHOLE_ACTOR_V1_HITSPLATS.containsAll(selected);
	}

	private SourceEvidenceResolver()
	{
	}

	/** Direct typed conversion; eligibility has already been evaluated by the interpreter. */
	static SourceResolution project(ResolutionResult result, String catalogId)
	{
		List<Candidate> candidates = new ArrayList<>(), excluded = new ArrayList<>();
		List<Candidate> apiReferenceConflicts = new ArrayList<>();
		for (ResolutionCandidate row : result.candidates)
		{
			JsonObject source = object(row.source), scope = object(source.get("scope"));
			String name = text(source, "name");
			if (SourceResolutionLabels.usableName(name) == null && !"npc".equals(text(source, "kind")))
			{
				name = row.label;
			}
			if (SourceResolutionLabels.usableName(name) == null)
			{
				name = integer(source.get("id")) == null
					? "player".equals(text(source, "kind")) ? "Recorded player" : "Recorded NPC"
					: text(source, "kind") + " #" + integer(source.get("id"));
			}
			List<String> evidence = strings(row.evidence);
			evidence.add("RESOLVER_V1:" + row.ruleId);
			evidence.add("RESOLVER_V1_RULESET:" + catalogId);
			if (row.maximum != null)
			{
				evidence.add("COMPONENT_MAXIMUM:" + row.maximum);
			}
			Candidate candidate = Candidate.builder().key(presentationKey(row)).name(name)
				.actorRef(text(source, "actorRef"))
				.npcId("npc".equals(text(source, "kind")) ? integer(source.get("id")) : null)
				.familyKey(text(source, "familyKey") != null ? text(source, "familyKey")
					: "status".equals(text(source, "kind")) && text(source, "statusFamily") != null
						? "observed-hit-kind:" + text(source, "statusFamily")
						: text(source, "actorRef") == null && integer(source.get("id")) != null
							? text(source, "kind") + "-definition:" + integer(source.get("id"))
							: text(source, "actorRef") == null && "status".equals(text(source, "kind")) ? row.sourceKey
								: null)
				.sceneKey(text(scope, "sceneKey")).worldViewId(integer(scope.get("worldViewId")))
				.plane(integer(scope.get("plane")))
				.strength("STRONG".equals(row.candidateStrength) ? "STRONG" : "POSSIBLE").evidence(evidence)
				.reasons(strings(row.reasons)).exclusionScope(row.exclusionScope).build();
			if ("EXCLUDED".equals(row.status))
			{
				excluded.add(candidate);
				if (Boolean.TRUE.equals(row.legacyApiReferenceConflictEligible)
					&& !"ALL_DIRECT_HP_COMPONENTS".equals(candidate.getExclusionScope()))
				{
					apiReferenceConflicts.add(candidate);
				}
			}
			else
			{
				candidates.add(candidate);
			}
		}
		if (candidates.isEmpty())
		{
			for (Candidate conflict : apiReferenceConflicts)
			{
				List<String> evidence = conflict.getEvidence() == null ? new ArrayList<>()
					: new ArrayList<>(conflict.getEvidence());
				evidence.add(
					"REFERENCE_PROFILE_CONFLICT: observed API source actor, target and arrival correlation retained; ordinary profile exceeded");
				if (conflict.getReasons() != null)
				{
					for (String reason : conflict.getReasons())
					{
						evidence.add("ORDINARY_COMPONENT_EXCLUDED:" + reason);
					}
				}
				candidates.add(conflict.toBuilder().strength("POSSIBLE").evidence(evidence).reasons(null)
					.exclusionScope(null).build());
				excluded.remove(conflict);
			}
		}
		if (candidates.stream().anyMatch(candidate -> !familyOnly(candidate)))
		{
			List<String> familyEvidence = new ArrayList<>();
			for (Candidate candidate : candidates)
			{
				if (familyOnly(candidate))
				{
					familyEvidence.add(
						"OBSERVED_HIT_FAMILY:" + candidate.getFamilyKey().substring("observed-hit-kind:".length()));
				}
			}
			candidates.removeIf(SourceEvidenceResolver::familyOnly);
			if (!familyEvidence.isEmpty())
			{
				for (int index = 0; index < candidates.size(); index++)
				{
					Candidate candidate = candidates.get(index);
					List<String> evidence = new ArrayList<>();
					if (candidate.getEvidence() != null)
					{
						evidence.addAll(candidate.getEvidence());
					}
					evidence.addAll(familyEvidence);
					candidates.set(index, candidate.toBuilder().evidence(evidence).build());
				}
			}
		}
		if (excluded.size() > 256)
		{
			excluded = new ArrayList<>(excluded.subList(0, 256));
		}
		for (Candidate candidate : candidates)
		{
			// These lists are newly owned by this projection, not caller/frozen model lists.
			List<String> evidence = candidate.getEvidence();
			evidence.add(
				"HIT_CAUSALITY_UNESTABLISHED: observed source association does not uniquely link this impact callback");
			Set<String> unique = new LinkedHashSet<>(evidence);
			evidence.clear();
			evidence.addAll(unique);
		}
		return project(candidates, excluded, true, catalogId, "RECORDED_RESOLVER_V1_COMPONENTS");
	}

	static SourceResolution projectAssociations(List<Candidate> candidates, List<Candidate> excluded, String catalogId,
		String method)
	{
		List<Candidate> associations = new ArrayList<>();
		for (Candidate candidate : candidates)
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

	private static String presentationKey(ResolutionCandidate row)
	{
		JsonObject source = object(row.source), scope = object(source.get("scope"));
		if (text(scope, "sceneKey") != null && integer(scope.get("worldViewId")) != null
			&& integer(scope.get("plane")) != null || row.key == null || row.ruleId == null || row.cue == null)
		{
			return row.key;
		}
		return "witness:" + String.valueOf(text(row.cue, "key")) + "|" + row.sourceKey + "|" + source.get("id");
	}

	private static boolean familyOnly(Candidate candidate)
	{
		return candidate.getActorRef() == null && candidate.getNpcId() == null && candidate.getFamilyKey() != null
			&& candidate.getFamilyKey().startsWith("observed-hit-kind:") && candidate.getEvidence() != null
			&& candidate.getEvidence().contains("OBSERVED_HIT_FAMILY_ONLY");
	}

	private static List<String> strings(JsonElement value)
	{
		List<String> result = new ArrayList<>();
		for (JsonElement element : array(value))
		{
			if (element.isJsonPrimitive())
			{
				result.add(element.getAsString());
			}
		}
		return result;
	}

	/** Canonical presentation projection. All eligibility and numeric decisions have already been evaluated. */
	public static SourceResolution project(List<Candidate> evaluated, List<Candidate> exclusions,
		boolean unidentifiedPossible, String catalogId, String method)
	{
		Map<String, List<Candidate>> retained = new LinkedHashMap<>();
		int count = 0;
		List<Candidate> ordered = evaluated == null ? new ArrayList<>() : new ArrayList<>(evaluated);
		ordered.sort(java.util.Comparator
			.comparing(candidate -> candidate == null || candidate.getKey() == null ? "" : candidate.getKey()));
		for (Candidate original : ordered)
		{
			if (++count > MAX_HYPOTHESES)
			{
				unidentifiedPossible = true;
				break;
			}
			if (original == null || blank(original.getKey()))
			{
				unidentifiedPossible = true;
				continue;
			}
			String name = com.tickreplaylogger.util.SourceResolutionLabels.usableName(original.getName());
			if (name == null && original.getNpcId() != null)
			{
				name = "NPC #" + original.getNpcId();
			}
			if (name == null && !blank(original.getActorRef()) && scoped(original))
			{
				name = "Recorded actor";
			}
			if (name == null)
			{
				unidentifiedPossible = true;
				continue;
			}
			Candidate candidate = original.toBuilder().name(name).build();
			retained.computeIfAbsent(actorKey(candidate), ignored -> new ArrayList<>()).add(candidate);
		}
		List<Candidate> excluded = exclusions == null ? new ArrayList<>() : new ArrayList<>(exclusions);
		List<Candidate> actors = new ArrayList<>();
		for (List<Candidate> values : retained.values())
		{
			actors.add(merge(values, false));
		}
		Map<String, List<Candidate>> families = new LinkedHashMap<>();
		for (Candidate candidate : actors)
		{
			String key = !blank(candidate.getFamilyKey()) && scoped(candidate)
				? scope(candidate) + "family:" + candidate.getFamilyKey()
				: "single:" + candidate.getKey();
			families.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
		}
		List<Candidate> candidates = new ArrayList<>();
		for (List<Candidate> values : families.values())
		{
			candidates.add(merge(values, values.size() > 1));
		}
		java.util.Comparator<Candidate> canonicalOrder = java.util.Comparator
			.comparingInt((Candidate c) -> "STRONG".equals(c.getStrength()) ? 0 : 1).thenComparing(Candidate::getKey);
		candidates.sort(canonicalOrder);
		excluded = distinctExclusions(excluded, candidates);
		excluded.sort(canonicalOrder);
		String level = candidates.isEmpty() ? "NONE"
			: candidates.stream().allMatch(value -> !blank(value.getActorRef()) && scoped(value)) ? "ACTOR"
				: candidates.stream().allMatch(value -> !blank(value.getFamilyKey())) ? "FAMILY" : "NONE";
		return SourceResolution.builder().version(1)
			.status(candidates.isEmpty() ? "UNIDENTIFIED"
				: candidates.size() == 1 && !unidentifiedPossible ? "IDENTIFIED" : "ALTERNATIVES")
			.identityLevel(level).candidates(Collections.unmodifiableList(candidates))
			.excludedCandidates(excluded.isEmpty() ? null : Collections.unmodifiableList(excluded))
			.unidentifiedPossible(unidentifiedPossible || candidates.isEmpty()).catalogId(catalogId).method(method)
			.build();
	}

	private static List<Candidate> distinctExclusions(List<Candidate> input, List<Candidate> retained)
	{
		Map<String, Candidate> result = new LinkedHashMap<>();
		Map<String, List<String>> originalKeys = new LinkedHashMap<>();
		Set<String> retainedKeys = new LinkedHashSet<>();
		for (Candidate candidate : retained)
		{
			retainedKeys.add(candidate.getKey());
		}
		for (Candidate candidate : input)
		{
			String key = candidate.getKey();
			String matching = null;
			for (String previousKey : originalKeys.getOrDefault(key, Collections.emptyList()))
			{
				if (sameWitnessIdentity(result.get(previousKey), candidate))
				{
					matching = previousKey;
					break;
				}
			}
			if (matching != null)
			{
				Candidate previous = result.get(matching);
				List<String> evidence = copy(previous.getEvidence()), reasons = copy(previous.getReasons());
				evidence.addAll(copy(candidate.getEvidence()));
				reasons.addAll(copy(candidate.getReasons()));
				result.put(matching, previous.toBuilder().evidence(immutable(evidence)).reasons(immutable(reasons))
					.exclusionScope(mergeExclusionScope(previous.getExclusionScope(), candidate.getExclusionScope()))
					.build());
				continue;
			}
			int collision = 0;
			while (retainedKeys.contains(key) || result.containsKey(key))
			{
				key = candidate.getKey() + ":excluded-witness:" + ++collision;
			}
			result.put(key,
				Objects.equals(key, candidate.getKey()) ? candidate : candidate.toBuilder().key(key).build());
			originalKeys.computeIfAbsent(candidate.getKey(), ignored -> new ArrayList<>()).add(key);
		}
		return new ArrayList<>(result.values());
	}

	private static String mergeExclusionScope(String first, String second)
	{
		return "ALL_DIRECT_HP_COMPONENTS".equals(first) || "ALL_DIRECT_HP_COMPONENTS".equals(second)
			? "ALL_DIRECT_HP_COMPONENTS"
			: "ORDINARY_ATTACK".equals(first) || "ORDINARY_ATTACK".equals(second) ? "ORDINARY_ATTACK" : null;
	}

	private static boolean sameWitnessIdentity(Candidate a, Candidate b)
	{
		return Objects.equals(a.getName(), b.getName()) && Objects.equals(a.getActorRef(), b.getActorRef())
			&& Objects.equals(a.getNpcId(), b.getNpcId()) && Objects.equals(a.getFamilyKey(), b.getFamilyKey())
			&& Objects.equals(a.getSceneKey(), b.getSceneKey())
			&& Objects.equals(a.getWorldViewId(), b.getWorldViewId()) && Objects.equals(a.getPlane(), b.getPlane());
	}

	private static Candidate merge(List<Candidate> values, boolean family)
	{
		Candidate first = values.get(0);
		Set<String> evidence = new LinkedHashSet<>(), names = new LinkedHashSet<>();
		boolean strong = false;
		Integer npcId = first.getNpcId();
		values.sort(java.util.Comparator.comparing(Candidate::getKey).thenComparing(Candidate::getName));
		first = values.get(0);
		for (Candidate candidate : values)
		{
			evidence.addAll(copy(candidate.getEvidence()));
			names.add(candidate.getName());
			strong |= "STRONG".equals(candidate.getStrength());
			if (!Objects.equals(npcId, candidate.getNpcId()))
			{
				npcId = null;
			}
			if (family && !blank(candidate.getActorRef()))
			{
				evidence.add("ALTERNATIVE_ACTOR:" + candidate.getActorRef());
			}
		}
		if (family)
		{
			evidence.add("SOURCE_FAMILY_CONSENSUS; exact actor and attack remain unresolved");
		}
		else if (values.size() > 1)
		{
			evidence.add("SOURCE_IDENTITY_CONSENSUS; exact attack remains unresolved");
		}
		return first.toBuilder().key(family ? scope(first) + "family:" + first.getFamilyKey() : actorKey(first))
			.name(String.join(" / ", names)).actorRef(family ? null : first.getActorRef()).npcId(npcId)
			.strength(strong ? "STRONG" : "POSSIBLE").evidence(immutable(evidence)).reasons(null).exclusionScope(null)
			.build();
	}

	private static String actorKey(Candidate candidate)
	{
		return !blank(candidate.getActorRef()) && scoped(candidate)
			? scope(candidate) + "actor:" + candidate.getActorRef()
			: "hypothesis:" + candidate.getKey();
	}

	private static boolean scoped(Candidate candidate)
	{
		return !blank(candidate.getSceneKey()) && candidate.getWorldViewId() != null && candidate.getPlane() != null;
	}

	private static String scope(Candidate candidate)
	{
		return candidate.getSceneKey().length() + ":" + candidate.getSceneKey() + ":" + candidate.getWorldViewId() + ":"
			+ candidate.getPlane() + ":";
	}

	private static boolean blank(String value)
	{
		return value == null || value.trim().isEmpty();
	}

	private static List<String> copy(List<String> values)
	{
		return values == null ? new ArrayList<>() : new ArrayList<>(values);
	}

	private static List<String> immutable(java.util.Collection<String> values)
	{
		return Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(values)));
	}
}
