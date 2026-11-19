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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;
import static org.junit.Assert.*;

/** Held-out identities exercise shared semantics without an encounter reference or NPC switch. */
public class GenericEvidenceHeldOutTest
{
	private static Candidate candidate(String key, String name, String ref, String family, String scope)
	{
		return Candidate.builder().key(key).name(name).actorRef(ref).familyKey(family).sceneKey(scope).worldViewId(0)
			.plane(0).strength("POSSIBLE").evidence(Collections.singletonList("OBSERVED_ATTACK_CUE")).build();
	}

	private static SourceEvidenceResolver.Hypothesis hypothesis(Candidate c)
	{
		return SourceEvidenceResolver.Hypothesis.builder().candidate(c).constraints(Collections.emptyList())
			.observations(Collections.emptyMap()).mechanism("ORDINARY_ATTACK").damageComponent("SINGLE_HIT")
			.worldMode("NORMAL").build();
	}

	private static Set<String> refs(SourceResolution result)
	{
		return result.getCandidates().stream().map(Candidate::getActorRef).collect(Collectors.toSet());
	}

	@Test
	public void sameActorDifferentAttackCuesAgreeWithoutIdentifyingOneAttack()
	{
		Candidate projectile = candidate("projectile:arbitrary", "Unfamiliar A", "actor:a", null, "room-a");
		Candidate animation = candidate("animation:arbitrary", "Unfamiliar A", "actor:a", null, "room-a");
		SourceResolution result = SourceEvidenceResolver
			.resolve(Arrays.asList(hypothesis(projectile), hypothesis(animation)), 34, false, null);
		assertEquals("IDENTIFIED", result.getStatus());
		assertEquals("ACTOR", result.getIdentityLevel());
		assertEquals(Collections.singleton("actor:a"), refs(result));
		assertTrue(result.getCandidates().get(0).getEvidence().stream()
			.anyMatch(e -> e.contains("SOURCE_IDENTITY_CONSENSUS")));
	}

	@Test
	public void identicalNamesDoNotJoinActorsOrNativeAreas()
	{
		Candidate a = candidate("a", "Same displayed name", "actor:a", null, "room-a");
		Candidate b = candidate("b", "Same displayed name", "actor:b", null, "room-a");
		SourceResolution result = SourceEvidenceResolver.resolve(Arrays.asList(hypothesis(a), hypothesis(b)), 7, false,
			null);
		assertEquals("ALTERNATIVES", result.getStatus());
		assertEquals(2, result.getCandidates().size());
		Candidate otherArea = candidate("other-area", "Same displayed name", "actor:a", null, "room-b");
		result = SourceEvidenceResolver.resolve(Arrays.asList(hypothesis(a), hypothesis(otherArea)), 7, false, null);
		assertEquals(2, result.getCandidates().size());
	}

	@Test
	public void arbitraryIdentityRenamingAndCandidatePermutationDoNotChangeResolution()
	{
		for (int seed = 0; seed < 24; seed++)
		{
			Candidate a = candidate("origin:" + seed, "Novel entity " + seed, "actor:" + seed, null,
				"unknown-area:" + seed);
			Candidate b = a.toBuilder().key("animation:" + seed).build();
			List<SourceEvidenceResolver.Hypothesis> input = seed % 2 == 0 ? Arrays.asList(hypothesis(a), hypothesis(b))
				: Arrays.asList(hypothesis(b), hypothesis(a));
			SourceResolution result = SourceEvidenceResolver.resolve(input, seed, false, null);
			assertEquals("IDENTIFIED", result.getStatus());
			assertEquals(1, result.getCandidates().size());
			assertEquals(a.getActorRef(), result.getCandidates().get(0).getActorRef());
			assertEquals(a.getName(), result.getCandidates().get(0).getName());
		}
	}

	@Test
	public void maximumCannotExcludeAHitWhenItsRequiredModifierObservationIsMissing()
	{
		Candidate a = candidate("a", "Novel low-cap source", "actor:a", null, "room-a");
		AttributionConstraint limit = AttributionConstraint.builder().key("synthetic-limit").maximum(13)
			.qualification("HARD_BOUND").mechanism("ORDINARY_ATTACK").damageComponent("SINGLE_HIT").worldMode("NORMAL")
			.requiredObservations(Arrays.asList("OBSERVED_VARIANT", "STANDARD_MODIFIERS")).build();
		Map<String, Boolean> partial = new LinkedHashMap<>();
		partial.put("OBSERVED_VARIANT", true);
		SourceEvidenceResolver.Hypothesis uncertain = SourceEvidenceResolver.Hypothesis.builder().candidate(a)
			.constraints(Collections.singletonList(limit)).observations(partial).mechanism("ORDINARY_ATTACK")
			.damageComponent("SINGLE_HIT").worldMode("NORMAL").build();
		SourceResolution result = SourceEvidenceResolver.resolve(Collections.singletonList(uncertain), 22, false, null);
		assertEquals(1, result.getCandidates().size());
		assertTrue(result.getExcludedCandidates() == null || result.getExcludedCandidates().isEmpty());
		assertTrue(result.getCandidates().get(0).getEvidence().stream()
			.anyMatch(e -> e.contains("BOUND_CONDITIONS_UNESTABLISHED")));
	}

	@Test
	public void anObservationGapKeepsTheKnownAlternativeWithoutClaimingUniqueIdentity()
	{
		Candidate a = candidate("a", "Novel entity", "actor:a", null, "room-a");
		SourceResolution result = SourceEvidenceResolver.resolve(Collections.singletonList(hypothesis(a)), 0, true,
			null);
		assertEquals("ALTERNATIVES", result.getStatus());
		assertTrue(result.isUnidentifiedPossible());
		assertEquals("Novel entity", result.getCandidates().get(0).getName());
	}
}
