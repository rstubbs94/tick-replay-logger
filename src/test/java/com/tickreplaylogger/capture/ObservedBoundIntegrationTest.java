/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
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

import com.google.gson.JsonParser;
import com.tickreplaylogger.model.world.SourceResolution;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObservedBoundIntegrationTest
{
	private static SourceResolution.Candidate candidate(String key)
	{
		return SourceResolution.Candidate.builder().key(key).name(key).actorRef(key).familyKey("fixture:ordinary")
			.sceneKey("room").worldViewId(0).plane(0).strength("POSSIBLE").build();
	}

	private static AttributionConstraint guard(String maximum)
	{
		return AttributionConstraint.builder().key("fixture:independent-bound").familyKey("fixture:ordinary")
			.maximumExpression(ObservedExpressions.compileNumeric(new JsonParser().parse(maximum)))
			.conditions(ObservedExpressions.compilePredicate(new JsonParser().parse(
				"{\"op\":\"EQ\",\"left\":{\"op\":\"FACT\",\"name\":\"context.activity.fixture\"},\"right\":{\"op\":\"CONST\",\"value\":1}}")))
			.composition("INDEPENDENT_COMPONENT_GUARD").qualification("HARD_BOUND")
			.numericScope("PER_HIT_MAXIMUM")
			.mechanism("ORDINARY_ATTACK").damageComponent("SINGLE_HIT").worldMode("NORMAL")
			.requiredObservations(Arrays.asList("QUALIFIED_NPC_VARIANT", "NORMAL_WORLD_CONTEXT",
				"NO_MODEL_OVERRIDES_OBSERVED", "SINGLE_HIT_COMPONENT", "ORDINARY_ATTACK_COMPONENT")).build();
	}

	private static AttributionConstraint unrelatedReference()
	{
		return AttributionConstraint.builder().key("fixture:unqualified-profile").familyKey("fixture:ordinary")
			.maximum(5).qualification("REFERENCE_ONLY").mechanism("ORDINARY_ATTACK")
			.damageComponent("SINGLE_HIT").worldMode("NORMAL")
			.requiredObservations(Collections.singletonList("MISSING_ORDINARY_CUE")).build();
	}

	private static SourceResolution resolve(AttributionConstraint guard, Map<String, Number> numbers, int amount,
		boolean includeOther)
	{
		LegacyConstraintOracle.Hypothesis source = LegacyConstraintOracle.Hypothesis.builder()
			.candidate(candidate("Recorded source")).constraints(Arrays.asList(unrelatedReference(), guard))
			.observations(observations()).numericObservations(numbers)
			.mechanism("ORDINARY_ATTACK").damageComponent("SINGLE_HIT").worldMode("NORMAL").build();
		LegacyConstraintOracle.Hypothesis other = LegacyConstraintOracle.Hypothesis.builder()
			.candidate(candidate("Other source").toBuilder().familyKey("fixture:other").build()).build();
		return LegacyConstraintOracle.resolve(includeOther ? Arrays.asList(source, other) : Collections.singletonList(source),
			amount, false, "fixture");
	}

	private static Map<String, Boolean> observations()
	{
		Map<String, Boolean> observations = new java.util.HashMap<>();
		for (String fact : Arrays.asList("QUALIFIED_NPC_VARIANT", "NORMAL_WORLD_CONTEXT",
			"NO_MODEL_OVERRIDES_OBSERVED", "SINGLE_HIT_COMPONENT", "ORDINARY_ATTACK_COMPONENT", "QUALIFIED_SOURCE"))
			observations.put(fact, true);
		return observations;
	}

	@Test
	public void qualifiedIndependentBoundCanExcludeEvenWhenAnotherProfileRemainsUnqualified()
	{
		SourceResolution result = resolve(guard("{\"op\":\"CONST\",\"value\":5}"),
			Collections.singletonMap("context.activity.fixture", 1), 6, true);
		assertEquals("IDENTIFIED", result.getStatus());
		assertEquals("Other source", result.getCandidates().get(0).getName());
		assertEquals(1, result.getExcludedCandidates().size());
		assertTrue(result.getExcludedCandidates().get(0).getReasons().get(0).contains("6 > 5"));
	}

	@Test
	public void missingAndContradictoryConditionsDoNotTurnIntoDefaultApplicableLimits()
	{
		for (Map<String, Number> facts : Arrays.<Map<String, Number>>asList(Collections.emptyMap(),
			Collections.singletonMap("context.activity.fixture", 0)))
		{
			SourceResolution result = resolve(guard("{\"op\":\"CONST\",\"value\":5}"), facts, 6, true);
			assertEquals("ALTERNATIVES", result.getStatus());
			assertEquals(2, result.getCandidates().size());
			assertTrue(result.getExcludedCandidates() == null || result.getExcludedCandidates().isEmpty());
		}
	}

	@Test
	public void invalidProvidedExpressionNeverFallsBackToItsOldScalarMaximum()
	{
		AttributionConstraint malformed = AttributionConstraint.builder().key("fixture:malformed")
			.familyKey("fixture:ordinary").maximum(1)
			.maximumExpression(ObservedExpressions.compileNumeric(new JsonParser().parse("{\"op\":\"MISSING_OPERATOR\"}")))
			.composition("ALTERNATIVE_CASE").qualification("HARD_BOUND").mechanism("ORDINARY_ATTACK")
			.numericScope("PER_HIT_MAXIMUM")
			.damageComponent("SINGLE_HIT").worldMode("NORMAL")
			.requiredObservations(Collections.singletonList("QUALIFIED_SOURCE")).build();
		SourceResolution result = resolve(malformed, Collections.emptyMap(), 6, false);
		assertEquals(1, result.getCandidates().size());
		assertTrue(result.getCandidates().get(0).getEvidence().stream().anyMatch(e -> e.startsWith("BOUND_EXPRESSION_UNESTABLISHED")));
	}

	@Test
	public void largeKnownMaximumDoesNotOverflowAnIntegerComparison()
	{
		SourceResolution result = resolve(guard("{\"op\":\"CONST\",\"value\":6000000000}"),
			Collections.singletonMap("context.activity.fixture", 1), Integer.MAX_VALUE, false);
		assertEquals(1, result.getCandidates().size());
		assertTrue(result.getCandidates().get(0).getEvidence().stream().anyMatch(e -> e.contains("2147483647 <= 6000000000")));
	}
}
