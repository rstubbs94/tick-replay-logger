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
import lombok.Builder;
import lombok.Value;
import net.runelite.api.HitsplatID;

/** Frozen legacy evaluator used only to prove canonical resolver parity. Never shipped in the plugin. */
public final class LegacyConstraintOracle
{
	public static final int MAX_HYPOTHESES = 256;
	private static final Set<Integer> WHOLE_ACTOR_V1_HITSPLATS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
		HitsplatID.DAMAGE_ME, HitsplatID.DAMAGE_OTHER, HitsplatID.DAMAGE_MAX_ME, HitsplatID.BLOCK_ME, HitsplatID.BLOCK_OTHER)));

	/** Contract domain only: membership never identifies an attacker or makes other outcomes impossible. */
	static boolean wholeActorHitsplatDomain(List<Integer> selected)
	{
		return selected != null && !selected.isEmpty() && selected.size() <= WHOLE_ACTOR_V1_HITSPLATS.size()
			&& new LinkedHashSet<>(selected).size() == selected.size() && WHOLE_ACTOR_V1_HITSPLATS.containsAll(selected);
	}

	private LegacyConstraintOracle()
	{
	}

	@Value
	@Builder(toBuilder = true)
	public static class Hypothesis
	{
		Candidate candidate;
		List<AttributionConstraint> constraints;
		Map<String, Boolean> observations;
		Map<String, Number> numericObservations;
		String mechanism;
		String damageComponent;
		String worldMode;
		String observedAttackStyle;
		Integer observedHitsplatType;
		boolean observedApiSourceCorrelation;
		/** The witness associates an observed source, but does not uniquely link it to this impact callback. */
		boolean causalAssociationOnly;
		List<String> eligibilityEvidence;
		List<String> eligibilityExclusions;
	}

	public static SourceResolution resolve(List<Hypothesis> hypotheses, int amount, boolean unidentifiedPossible,
		String catalogId)
	{
		Map<String, List<Candidate>> retained = new LinkedHashMap<>();
		List<Candidate> excluded = new ArrayList<>();
		List<Candidate> apiReferenceConflicts = new ArrayList<>();
		int index = 0;
		List<Hypothesis> ordered = hypotheses == null ? new ArrayList<>() : new ArrayList<>(hypotheses);
		ordered.sort(java.util.Comparator.comparing(
			(Hypothesis h) -> h == null || h.getCandidate() == null ? "" : String.valueOf(h.getCandidate().getKey())));
		for (Hypothesis hypothesis : ordered)
		{
			if (index++ >= MAX_HYPOTHESES)
			{
				unidentifiedPossible = true;
				break;
			}
			Candidate candidate = hypothesis == null ? null : hypothesis.getCandidate();
			if (candidate != null)
			{
				String name = com.tickreplaylogger.util.SourceResolutionLabels.usableName(candidate.getName());
				if (name == null && candidate.getNpcId() != null)
					name = "NPC #" + candidate.getNpcId();
				if (name == null && !blank(candidate.getActorRef()) && scoped(candidate))
					name = "Recorded actor";
				candidate = candidate.toBuilder().name(name).build();
			}
			if (candidate == null || blank(candidate.getKey()) || blank(candidate.getName())
				|| "Unknown".equalsIgnoreCase(candidate.getName()))
			{
				unidentifiedPossible = true;
				continue;
			}
			List<String> evidence = copy(candidate.getEvidence());
			evidence.addAll(copy(hypothesis.getEligibilityEvidence()));
			if (hypothesis.isCausalAssociationOnly())
				evidence.add("HIT_CAUSALITY_UNESTABLISHED: observed source association does not uniquely link this impact callback");
			if (hypothesis.getEligibilityExclusions() != null && !hypothesis.getEligibilityExclusions().isEmpty())
			{
				// Contradictory observed outcomes are not a source-relative numeric reference conflict.
				// In particular, the later API-link fallback must never revive this mechanism.
				excluded.add(candidate.toBuilder().evidence(immutable(evidence))
					.reasons(immutable(hypothesis.getEligibilityExclusions())).build());
				continue;
			}
			List<String> reasons = new ArrayList<>();
			boolean allCasesExcluded = true, hasAlternativeCase = false, independentGuardExcluded = false;
			boolean wholeActorExcluded = false, ordinaryAttackExcluded = false;
			if (hypothesis.getConstraints() != null)
				for (AttributionConstraint constraint : hypothesis.getConstraints())
				{
					boolean independent = constraint != null && "INDEPENDENT_COMPONENT_GUARD".equals(constraint.getComposition());
					if (!independent) hasAlternativeCase = true;
					java.math.BigInteger maximum = null;
					if (constraint != null && constraint.getMaximumExpression() != null)
					{
						ObservedExpressions.NumericResult evaluated = constraint.getMaximumExpression().evaluate(hypothesis.getNumericObservations());
						if (evaluated.getStatus() == ObservedExpressions.NumericStatus.KNOWN)
							maximum = evaluated.getValue();
						else
							evidence.add("BOUND_EXPRESSION_UNESTABLISHED:" + constraint.getKey() + ":" + evaluated.getReason()
								+ ":" + String.join(",", evaluated.getMissingFacts()));
					}
					else if (constraint != null && constraint.getMaximum() != null)
						maximum = java.math.BigInteger.valueOf(constraint.getMaximum());
					if (constraint == null || maximum == null || maximum.signum() < 0)
					{
						if (!independent) allCasesExcluded = false;
						continue;
					}
					List<String> missing = new ArrayList<>();
					boolean wholeActor = "QUALIFIED_WHOLE_ACTOR_ENVELOPE".equals(constraint.getBoundBasis());
					if (constraint.getBoundBasis() != null && !Arrays.asList("VARIANT_LITERAL_MAXIMUM", "QUALIFIED_MECHANIC_EXPRESSION",
						"QUALIFIED_WHOLE_ACTOR_ENVELOPE").contains(constraint.getBoundBasis())) missing.add("BOUND_BASIS_UNAVAILABLE");
					boolean conditional = constraint.getMaximumExpression() != null || constraint.getConditions() != null || independent;
					if (conditional)
					{
						if (!"PER_HIT_MAXIMUM".equals(constraint.getNumericScope()))
							missing.add("NUMERIC_SCOPE_NOT_QUALIFIED");
						if (blank(constraint.getFamilyKey()) || blank(constraint.getMechanism())
							|| blank(constraint.getDamageComponent()) || blank(constraint.getWorldMode()))
							missing.add("CONDITIONAL_BOUND_SELECTOR_UNAVAILABLE");
					}
					if (constraint.getComposition() != null && !Arrays.asList("ALTERNATIVE_CASE", "INDEPENDENT_COMPONENT_GUARD").contains(constraint.getComposition()))
						missing.add("BOUND_COMPOSITION_UNESTABLISHED");
					if (independent && (constraint.getMaximumExpression() == null || constraint.getConditions() == null))
						missing.add("INDEPENDENT_COMPONENT_QUALIFICATION_UNAVAILABLE");
					if (independent && (constraint.getRequiredObservations() == null
						|| !constraint.getRequiredObservations().containsAll(Arrays.asList("QUALIFIED_NPC_VARIANT",
							"NORMAL_WORLD_CONTEXT", "NO_MODEL_OVERRIDES_OBSERVED", "SINGLE_HIT_COMPONENT",
							wholeActor ? "NPC_DIRECT_SOURCE_HYPOTHESIS" : "ORDINARY_ATTACK_COMPONENT"))))
						missing.add("INDEPENDENT_COMPONENT_OBSERVATIONS_UNAVAILABLE");
					if (wholeActor)
					{
						if (!independent || !constraint.isWholeActorWitnessQualified()
							|| !"ALL_DIRECT_HP_COMPONENTS".equals(constraint.getMechanism())
							|| !"SINGLE_HIT".equals(constraint.getDamageComponent()) || !"NORMAL".equals(constraint.getWorldMode()))
							missing.add("WHOLE_ACTOR_ENVELOPE_SCOPE_UNESTABLISHED");
						if (hypothesis.getObservations() == null
							|| !Boolean.TRUE.equals(hypothesis.getObservations().get("NPC_DIRECT_SOURCE_HYPOTHESIS"))
							|| blank(candidate.getActorRef()) || candidate.getNpcId() == null || candidate.getNpcId() < 0 || !scoped(candidate)
							|| !Arrays.asList("ORDINARY_ATTACK", "NPC_DIRECT_SOURCE").contains(hypothesis.getMechanism()))
							missing.add("NPC_DIRECT_SOURCE_HYPOTHESIS");
						if (hypothesis.getObservedHitsplatType() == null || !wholeActorHitsplatDomain(constraint.getAllowedHitsplatTypes())
							|| !constraint.getAllowedHitsplatTypes().contains(hypothesis.getObservedHitsplatType()))
							missing.add("WHOLE_ACTOR_HITSPLAT_APPLICABILITY_UNESTABLISHED");
					}
					if ("BASE_PROFILE_ENVELOPE".equals(constraint.getEnvelopeOperator()))
					{
						if (hypothesis.getObservations() == null
							|| !Boolean.TRUE.equals(hypothesis.getObservations().get("STANDARD_DAMAGE_MODIFIERS")))
							missing.add("STANDARD_DAMAGE_MODIFIERS");
						if (constraint.getAttackStyles() == null || constraint.getAttackStyles().isEmpty()
							|| blank(hypothesis.getObservedAttackStyle())
							|| !constraint.getAttackStyles().contains(hypothesis.getObservedAttackStyle()))
							missing.add("BASE_PROFILE_ATTACK_STYLE_NOT_QUALIFIED");
					}
					if (constraint.getConditions() != null)
					{
						ObservedExpressions.PredicateResult condition = constraint.getConditions().evaluate(hypothesis.getNumericObservations());
						if (condition.getStatus() != ObservedExpressions.PredicateStatus.MATCH)
						{
							missing.add(condition.getStatus() == ObservedExpressions.PredicateStatus.CONTRADICTED
								? "BOUND_CONDITION_NOT_APPLICABLE" : "BOUND_CONDITION_UNESTABLISHED");
							if (!condition.getMissingFacts().isEmpty())
								evidence.add("BOUND_CONDITION_MISSING_FACTS:" + String.join(",", condition.getMissingFacts()));
						}
					}
					if (constraint.getFamilyKey() != null
						&& !Objects.equals(constraint.getFamilyKey(), candidate.getFamilyKey()))
						missing.add("SOURCE_FAMILY_NOT_QUALIFIED");
					if (!wholeActor && !Objects.equals(constraint.getMechanism(), hypothesis.getMechanism())
						|| !Objects.equals(constraint.getDamageComponent(), hypothesis.getDamageComponent())
						|| !Objects.equals(constraint.getWorldMode(), hypothesis.getWorldMode()))
						missing.add("COMPONENT_OR_MODE_NOT_QUALIFIED");
					if (constraint.getRequiredObservations() == null || constraint.getRequiredObservations().isEmpty())
						missing.add("REQUIRED_OBSERVATIONS_UNAVAILABLE");
					else
						for (String required : constraint.getRequiredObservations())
							if (hypothesis.getObservations() == null
								|| !Boolean.TRUE.equals(hypothesis.getObservations().get(required)))
								missing.add(required);
					boolean qualified = "HARD_BOUND".equals(constraint.getQualification()) && missing.isEmpty();
					if (qualified && "BASE_PROFILE_ENVELOPE".equals(constraint.getEnvelopeOperator()))
						evidence
							.add("BASE_PROFILE_ENVELOPE:" + constraint.getKey() + ":" + constraint.getEnvelopeEvidence()
								+ "; source-relative ordinary model, not proof of hit mechanism");
					boolean exceeds = java.math.BigInteger.valueOf(amount).compareTo(maximum) > 0;
					String comparison = amount + (exceeds ? " > " : " <= ") + maximum;
					evidence.add((qualified ? "QUALIFIED_MAXIMUM:" : "REFERENCE_MAXIMUM:") + constraint.getKey() + ":"
						+ comparison);
					if (!missing.isEmpty())
						evidence.add("BOUND_CONDITIONS_UNESTABLISHED:" + String.join(",", missing));
					if (!independent && (!qualified || !exceeds))
						allCasesExcluded = false;
					if (qualified && exceeds)
					{
						reasons.add("MAXIMUM_EXCEEDED:" + constraint.getKey() + ":" + comparison);
						if (independent) independentGuardExcluded = true;
						if (wholeActor) wholeActorExcluded = true;
						else if ("ORDINARY_ATTACK".equals(constraint.getMechanism())) ordinaryAttackExcluded = true;
					}
				}
			Candidate evaluated = candidate.toBuilder().evidence(immutable(evidence)).build();
			if ((independentGuardExcluded || hasAlternativeCase && allCasesExcluded) && !reasons.isEmpty())
			{
				Candidate rejected = evaluated.toBuilder().reasons(immutable(reasons))
					.exclusionScope(wholeActorExcluded ? "ALL_DIRECT_HP_COMPONENTS" : ordinaryAttackExcluded ? "ORDINARY_ATTACK" : null).build();
				excluded.add(rejected);
				if (hypothesis.isObservedApiSourceCorrelation() && "STRONG".equals(rejected.getStrength())
					&& !"ALL_DIRECT_HP_COMPONENTS".equals(rejected.getExclusionScope())
					&& !blank(rejected.getActorRef()) && scoped(rejected))
					apiReferenceConflicts.add(rejected);
				continue;
			}
			if (hypothesis.isCausalAssociationOnly())
				unidentifiedPossible = true;
			retained.computeIfAbsent(actorKey(evaluated), ignored -> new ArrayList<>()).add(evaluated);
		}
		// A source-relative ordinary bound cannot erase the last explicit observed API link.
		// This fallback is inactive whenever any eligible hypothesis survived normal filtering.
		if (retained.isEmpty() && !apiReferenceConflicts.isEmpty())
		{
			for (Candidate conflict : apiReferenceConflicts)
			{
				List<String> evidence = copy(conflict.getEvidence());
				evidence.add(
					"REFERENCE_PROFILE_CONFLICT: observed API source actor, target and arrival correlation retained; ordinary profile exceeded");
				for (String reason : copy(conflict.getReasons()))
					evidence.add("ORDINARY_COMPONENT_EXCLUDED:" + reason);
				Candidate possible = conflict.toBuilder().strength("POSSIBLE").evidence(immutable(evidence))
					.reasons(null).exclusionScope(null).build();
				retained.computeIfAbsent(actorKey(possible), ignored -> new ArrayList<>()).add(possible);
				excluded.remove(conflict);
			}
			unidentifiedPossible = true;
		}
		List<Candidate> evaluated = new ArrayList<>();
		for (List<Candidate> values : retained.values())
			evaluated.addAll(values);
		return project(evaluated, excluded, unidentifiedPossible, catalogId, "GENERIC_EVIDENCE_V1");
	}

	/** Canonical presentation projection. All eligibility and numeric decisions have already been evaluated. */
	public static SourceResolution project(List<Candidate> evaluated, List<Candidate> exclusions,
		boolean unidentifiedPossible, String catalogId, String method)
	{
		Map<String, List<Candidate>> retained = new LinkedHashMap<>();
		int count = 0;
		List<Candidate> ordered = evaluated == null ? new ArrayList<>() : new ArrayList<>(evaluated);
		ordered.sort(java.util.Comparator.comparing(candidate -> candidate == null || candidate.getKey() == null ? "" : candidate.getKey()));
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
			if (name == null && original.getNpcId() != null) name = "NPC #" + original.getNpcId();
			if (name == null && !blank(original.getActorRef()) && scoped(original)) name = "Recorded actor";
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
			actors.add(merge(values, false));
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
			candidates.add(merge(values, values.size() > 1));
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
			.unidentifiedPossible(unidentifiedPossible || candidates.isEmpty()).catalogId(catalogId)
			.method(method).build();
	}

	private static List<Candidate> distinctExclusions(List<Candidate> input, List<Candidate> retained)
	{
		Map<String, Candidate> result = new LinkedHashMap<>();
		Map<String, List<String>> originalKeys = new LinkedHashMap<>();
		Set<String> retainedKeys = new LinkedHashSet<>();
		for (Candidate candidate : retained)
			retainedKeys.add(candidate.getKey());
		for (Candidate candidate : input)
		{
			String key = candidate.getKey();
			String matching = null;
			for (String previousKey : originalKeys.getOrDefault(key, Collections.emptyList()))
				if (sameWitnessIdentity(result.get(previousKey), candidate))
				{
					matching = previousKey;
					break;
				}
			if (matching != null)
			{
				Candidate previous = result.get(matching);
				List<String> evidence = copy(previous.getEvidence()), reasons = copy(previous.getReasons());
				evidence.addAll(copy(candidate.getEvidence()));
				reasons.addAll(copy(candidate.getReasons()));
				result.put(matching,
					previous.toBuilder().evidence(immutable(evidence)).reasons(immutable(reasons))
						.exclusionScope(mergeExclusionScope(previous.getExclusionScope(),candidate.getExclusionScope())).build());
				continue;
			}
			int collision = 0;
			while (retainedKeys.contains(key) || result.containsKey(key))
				key = candidate.getKey() + ":excluded-witness:" + ++collision;
			result.put(key,
				Objects.equals(key, candidate.getKey()) ? candidate : candidate.toBuilder().key(key).build());
			originalKeys.computeIfAbsent(candidate.getKey(), ignored -> new ArrayList<>()).add(key);
		}
		return new ArrayList<>(result.values());
	}

	private static String mergeExclusionScope(String first,String second)
	{
		return "ALL_DIRECT_HP_COMPONENTS".equals(first) || "ALL_DIRECT_HP_COMPONENTS".equals(second) ? "ALL_DIRECT_HP_COMPONENTS"
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
				npcId = null;
			if (family && !blank(candidate.getActorRef()))
				evidence.add("ALTERNATIVE_ACTOR:" + candidate.getActorRef());
		}
		if (family)
			evidence.add("SOURCE_FAMILY_CONSENSUS; exact actor and attack remain unresolved");
		else if (values.size() > 1)
			evidence.add("SOURCE_IDENTITY_CONSENSUS; exact attack remains unresolved");
		return first.toBuilder().key(family ? scope(first) + "family:" + first.getFamilyKey() : actorKey(first))
			.name(String.join(" / ", names)).actorRef(family ? null : first.getActorRef()).npcId(npcId)
			.strength(strong ? "STRONG" : "POSSIBLE").evidence(immutable(evidence)).reasons(null).exclusionScope(null).build();
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
