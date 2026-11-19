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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.SourceResolution.Candidate;
import com.tickreplaylogger.util.SourceResolutionLabels;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class SourceEvidenceResolverTest
{
	private static Candidate actor(String ref, String family, String strength)
	{
		return Candidate.builder().key("witness:" + ref).name("Unfamiliar entity").actorRef(ref).npcId(900001)
			.familyKey(family).sceneKey("scene-a").worldViewId(0).plane(0).strength(strength)
			.evidence(Collections.singletonList("RECORDED_HYPOTHESIS")).build();
	}

	private static LegacyConstraintOracle.Hypothesis hypothesis(Candidate c)
	{
		return LegacyConstraintOracle.Hypothesis.builder().candidate(c).build();
	}

	private static SourceResolution resolve(LegacyConstraintOracle.Hypothesis... h)
	{
		return LegacyConstraintOracle.resolve(Arrays.asList(h), 16, false, "test-catalog");
	}

	private static AttributionConstraint bound(String key, int max)
	{
		return AttributionConstraint.builder().key(key).maximum(max).qualification("HARD_BOUND")
			.mechanism("ORDINARY_ATTACK").damageComponent("SINGLE_HIT").worldMode("NORMAL")
			.requiredObservations(Arrays.asList("QUALIFIED_NPC_VARIANT", "NORMAL_WORLD_CONTEXT",
				"ORDINARY_ATTACK_COMPONENT", "SINGLE_HIT_COMPONENT", "STANDARD_DAMAGE_MODIFIERS"))
			.build();
	}

	private static LegacyConstraintOracle.Hypothesis bounded(Candidate c, AttributionConstraint... rules)
	{
		Map<String, Boolean> observed = new HashMap<>();
		for (String key : bound("", 1).getRequiredObservations())
			observed.put(key, true);
		return LegacyConstraintOracle.Hypothesis.builder().candidate(c).constraints(Arrays.asList(rules))
			.observations(observed).mechanism("ORDINARY_ATTACK").damageComponent("SINGLE_HIT").worldMode("NORMAL")
			.build();
	}

	@Test
	public void differentAttacksOfOneScopedActorHaveSourceConsensusWithoutInventingAttack()
	{
		Candidate a = actor("a", "family-a", "STRONG");
		SourceResolution r = resolve(hypothesis(a),
			hypothesis(a.toBuilder().key("animation:a").strength("POSSIBLE").build()));
		assertEquals("IDENTIFIED", r.getStatus());
		assertEquals("ACTOR", r.getIdentityLevel());
		assertEquals(1, r.getCandidates().size());
		assertEquals("a", r.getCandidates().get(0).getActorRef());
	}

	@Test
	public void scopedFamilyConsensusDoesNotSelectAnIndividualOrJoinSameNamesAcrossScenes()
	{
		Candidate a = actor("a", "family-a", "POSSIBLE"), b = actor("b", "family-a", "POSSIBLE");
		SourceResolution same = resolve(hypothesis(a), hypothesis(b));
		assertEquals("FAMILY", same.getIdentityLevel());
		assertNull(same.getCandidates().get(0).getActorRef());
		SourceResolution foreign = resolve(hypothesis(a), hypothesis(b.toBuilder().sceneKey("scene-b").build()));
		assertEquals("ALTERNATIVES", foreign.getStatus());
		assertEquals(2, foreign.getCandidates().size());
	}

	@Test
	public void unqualifiedSameNameWitnessesRemainSeparate()
	{
		Candidate a = actor(null, null, "POSSIBLE").toBuilder().key("projectile:7").build();
		Candidate b = a.toBuilder().key("animation:8").build();
		assertEquals(2, resolve(hypothesis(a), hypothesis(b)).getCandidates().size());
	}

	@Test
	public void aBoundRequiresEveryPredicateButStillReportsReferenceContradictions()
	{
		LegacyConstraintOracle.Hypothesis h = bounded(actor("a", "family-a", "POSSIBLE"), bound("max", 13));
		SourceResolution excluded = resolve(h);
		assertTrue(excluded.getCandidates().isEmpty());
		assertEquals(1, excluded.getExcludedCandidates().size());
		Map<String, Boolean> partial = new HashMap<>(h.getObservations());
		partial.remove("STANDARD_DAMAGE_MODIFIERS");
		SourceResolution kept = resolve(h.toBuilder().observations(partial).build());
		assertEquals(1, kept.getCandidates().size());
		assertNull(kept.getExcludedCandidates());
		assertTrue(kept.getCandidates().get(0).getEvidence().stream()
			.anyMatch(s -> s.contains("REFERENCE_MAXIMUM:max:16 > 13")));
	}

	@Test
	public void alternativeVariantBoundsAreOrCasesRatherThanConjunctiveCaps()
	{
		SourceResolution r = resolve(
			bounded(actor("a", "family-a", "POSSIBLE"), bound("lower", 13), bound("higher", 20)));
		assertEquals(1, r.getCandidates().size());
		assertNull(r.getExcludedCandidates());
	}

	@Test
	public void zeroMaximumAndZeroHitsRemainLiteralValues()
	{
		LegacyConstraintOracle.Hypothesis h = bounded(actor("a", "family-a", "POSSIBLE"), bound("zero", 0));
		assertEquals(1,
			LegacyConstraintOracle.resolve(Collections.singletonList(h), 0, false, "test").getCandidates().size());
		assertTrue(
			LegacyConstraintOracle.resolve(Collections.singletonList(h), 1, false, "test").getCandidates().isEmpty());
	}

	@Test
	public void canonicalOrderIsStrengthThenStableKeyAndPermutationIndependent()
	{
		List<LegacyConstraintOracle.Hypothesis> input = new ArrayList<>(
			Arrays.asList(hypothesis(actor("z", null, "STRONG")), hypothesis(actor("a", null, "POSSIBLE")),
				hypothesis(actor("b", null, "POSSIBLE"))));
		SourceResolution first = LegacyConstraintOracle.resolve(input, 0, false, "test");
		Collections.reverse(input);
		assertEquals(first, LegacyConstraintOracle.resolve(input, 0, false, "test"));
		assertEquals("z", first.getCandidates().get(0).getActorRef());
	}

	@Test
	public void sharedDisplayContractUsesExactFiveNormativeLabels() throws Exception
	{
		Gson gson = new Gson();
		try (InputStreamReader in = new InputStreamReader(
			getClass().getResourceAsStream("/source-resolution-conformance-v1.json"), StandardCharsets.UTF_8))
		{
			JsonObject root = gson.fromJson(in, JsonObject.class);
			for (JsonElement row : root.getAsJsonArray("cases"))
			{
				JsonObject c = row.getAsJsonObject();
				SourceResolution r = gson.fromJson(c.get("resolution"), SourceResolution.class);
				assertEquals(c.get("id").getAsString(), c.get("expectedLabel").getAsString(),
					SourceResolutionLabels.label(r));
			}
		}
	}

	@Test
	public void conciseSourceLabelsKeepUncertaintyAndEvidenceInTheResolution()
	{
		Candidate a = actor("a", null, "STRONG").toBuilder().name("<col=ffffff>Entity A</col>")
			.evidence(Collections.singletonList("RECORDED_ORIGIN_WITH_UNRESOLVED_HP_CAUSE")).build();
		SourceResolution single = SourceResolution.builder().version(1).status("ALTERNATIVES").identityLevel("ACTOR")
			.candidates(Collections.singletonList(a)).unidentifiedPossible(true).build();
		String before = new Gson().toJson(single);
		assertEquals("Entity A", SourceResolutionLabels.label(single));
		assertEquals(before, new Gson().toJson(single));
		assertTrue(single.isUnidentifiedPossible());
		assertEquals("STRONG", single.getCandidates().get(0).getStrength());
		assertEquals("RECORDED_ORIGIN_WITH_UNRESOLVED_HP_CAUSE", single.getCandidates().get(0).getEvidence().get(0));
		SourceResolution several = single.toBuilder().candidates(Arrays.asList(a,
			a.toBuilder().key("another-a").actorRef("other-a").build(),
			a.toBuilder().key("b").name("Entity B").actorRef("b").build())).build();
		assertEquals("Entity A or Entity B", SourceResolutionLabels.label(several));
		assertEquals(3, several.getCandidates().size());
		assertTrue(several.isUnidentifiedPossible());
	}

	@Test
	public void absentOrPlaceholderSourceNamesDisplayUnknownWithoutAddingAnUnidentifiedAlternative()
	{
		for (boolean unknown : Arrays.asList(true, false))
		{
			SourceResolution resolution = SourceResolution.builder().version(1).status("UNIDENTIFIED")
				.candidates(Collections.singletonList(actor("a", null, "POSSIBLE").toBuilder().name("Unknown").build()))
				.unidentifiedPossible(unknown).build();
			assertEquals("Unknown", SourceResolutionLabels.label(resolution));
			assertEquals(unknown, resolution.isUnidentifiedPossible());
			assertEquals("Unknown", resolution.getCandidates().get(0).getName());
			assertEquals("Unknown", SourceResolutionLabels.label(resolution.toBuilder().candidates(Collections.emptyList()).build()));
		}
	}

	@Test
	public void placeholderNamesCannotBecomeSourceNamesButObservedNumericIdentitySurvives()
	{
		for (String name : Arrays.asList("Unknown", " Unknown source ", "NULL", "Unavailable", "<col=ff0000>null</col>",
			""))
		{
			Candidate unnamed = actor("a", null, "POSSIBLE").toBuilder().name(name).build();
			SourceResolution resolution = resolve(hypothesis(unnamed));
			assertEquals("NPC #900001", resolution.getCandidates().get(0).getName());
			assertEquals("a", resolution.getCandidates().get(0).getActorRef());
			assertTrue(
				resolve(hypothesis(unnamed.toBuilder().actorRef(null).npcId(null).build())).getCandidates().isEmpty());
		}
		SourceResolution sameName = resolve(hypothesis(actor("a", null, "POSSIBLE")),
			hypothesis(actor("b", null, "POSSIBLE")));
		assertEquals(2, sameName.getCandidates().size());
		assertEquals("Unfamiliar entity", SourceResolutionLabels.label(sameName));
	}

	@Test
	public void allExcludedActualApiCorrelationRemainsPossibleWithReferenceConflict()
	{
		LegacyConstraintOracle.Hypothesis api = bounded(actor("a", "family-a", "STRONG"), bound("max", 13)).toBuilder()
			.observedApiSourceCorrelation(true).build();
		SourceResolution r = resolve(api);
		assertEquals("ALTERNATIVES", r.getStatus());
		assertTrue(r.isUnidentifiedPossible());
		assertEquals(1, r.getCandidates().size());
		assertEquals("a", r.getCandidates().get(0).getActorRef());
		assertEquals("POSSIBLE", r.getCandidates().get(0).getStrength());
		assertNull(r.getExcludedCandidates());
		assertTrue(
			r.getCandidates().get(0).getEvidence().stream().anyMatch(e -> e.startsWith("REFERENCE_PROFILE_CONFLICT:")));
		assertTrue(r.getCandidates().get(0).getEvidence().contains("QUALIFIED_MAXIMUM:max:16 > 13"));
		assertEquals("STRONG", api.getCandidate().getStrength());
	}

	@Test
	public void allExcludedProximityOrAnimationWithoutApiRelationshipDoesNotRevive()
	{
		LegacyConstraintOracle.Hypothesis proximity = bounded(actor("a", "family-a", "STRONG"), bound("max", 13));
		LegacyConstraintOracle.Hypothesis animation = bounded(actor("b", "family-b", "POSSIBLE"), bound("max", 13));
		SourceResolution r = resolve(proximity, animation);
		assertEquals("UNIDENTIFIED", r.getStatus());
		assertTrue(r.getCandidates().isEmpty());
		assertEquals(2, r.getExcludedCandidates().size());
	}

	@Test
	public void eligibleAlternativeKeepsNumericExclusionOfConflictingApiHypothesis()
	{
		LegacyConstraintOracle.Hypothesis api = bounded(actor("a", "family-a", "STRONG"), bound("max", 13)).toBuilder()
			.observedApiSourceCorrelation(true).build();
		SourceResolution r = resolve(api, bounded(actor("b", "family-b", "POSSIBLE"), bound("higher", 20)));
		assertEquals("IDENTIFIED", r.getStatus());
		assertFalse(r.isUnidentifiedPossible());
		assertEquals("b", r.getCandidates().get(0).getActorRef());
		assertEquals("a", r.getExcludedCandidates().get(0).getActorRef());
		assertFalse(
			r.getCandidates().get(0).getEvidence().stream().anyMatch(e -> e.startsWith("REFERENCE_PROFILE_CONFLICT:")));
	}

	@Test
	public void observedOutcomeContradictionCannotBeRevivedAsANumericReferenceConflict()
	{
		LegacyConstraintOracle.Hypothesis api = bounded(actor("a", "family-a", "STRONG"), bound("max", 13)).toBuilder()
			.observedApiSourceCorrelation(true).eligibilityEvidence(Collections.singletonList("OBSERVED_HITSPLAT:5"))
			.eligibilityExclusions(Collections.singletonList("HITSPLAT_TYPE_MISMATCH:ordinary:observed=5;allowed=[16]"))
			.build();
		SourceResolution r = resolve(api);
		assertTrue(r.getCandidates().isEmpty());
		assertEquals(1, r.getExcludedCandidates().size());
		assertTrue(r.getExcludedCandidates().get(0).getEvidence().contains("OBSERVED_HITSPLAT:5"));
		assertFalse(r.getExcludedCandidates().get(0).getEvidence().stream()
			.anyMatch(e -> e.startsWith("REFERENCE_PROFILE_CONFLICT:")));
	}

	@Test
	public void excludingOneMechanismDoesNotExcludeAnotherMechanismOfTheSameActor()
	{
		Candidate c = actor("a", "family-a", "POSSIBLE");
		LegacyConstraintOracle.Hypothesis ordinary = hypothesis(c.toBuilder().key("ordinary").build()).toBuilder()
			.eligibilityExclusions(Collections.singletonList("HITSPLAT_TYPE_MISMATCH:ordinary")).build();
		SourceResolution r = resolve(ordinary, hypothesis(c.toBuilder().key("unclassified-special").build()));
		assertEquals("IDENTIFIED", r.getStatus());
		assertEquals("a", r.getCandidates().get(0).getActorRef());
		assertEquals(1, r.getExcludedCandidates().size());
	}

	@Test
	public void duplicateExcludedWitnessesMergeEvidenceWithoutMergingDifferentActorsByName()
	{
		Candidate c = actor("a", null, "POSSIBLE");
		LegacyConstraintOracle.Hypothesis first = hypothesis(c).toBuilder()
			.eligibilityEvidence(Collections.singletonList("FIRST_OBSERVATION"))
			.eligibilityExclusions(Collections.singletonList("HITSPLAT_TYPE_MISMATCH:first")).build();
		LegacyConstraintOracle.Hypothesis second = first.toBuilder()
			.eligibilityEvidence(Collections.singletonList("SECOND_OBSERVATION"))
			.eligibilityExclusions(Collections.singletonList("HITSPLAT_TYPE_MISMATCH:second")).build();
		SourceResolution r = resolve(first, second);
		assertEquals(1, r.getExcludedCandidates().size());
		assertEquals(2, r.getExcludedCandidates().get(0).getReasons().size());
		assertTrue(r.getExcludedCandidates().get(0).getEvidence()
			.containsAll(Arrays.asList("FIRST_OBSERVATION", "SECOND_OBSERVATION")));
		r = resolve(first, second.toBuilder().candidate(c.toBuilder().actorRef("b").build()).build());
		assertEquals(2, r.getExcludedCandidates().size());
		assertNotEquals(r.getExcludedCandidates().get(0).getKey(), r.getExcludedCandidates().get(1).getKey());
	}

}
