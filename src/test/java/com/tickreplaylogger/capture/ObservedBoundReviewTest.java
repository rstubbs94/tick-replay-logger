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
import com.tickreplaylogger.model.world.ContextBlock;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Independent review cases: component qualification is separate from a compatible source identity. */
public class ObservedBoundReviewTest
{
	private static AttributionConstraint bound(String expression)
	{
		return bound(expression, "PER_HIT_MAXIMUM");
	}

	private static AttributionConstraint bound(String expression, String numericScope)
	{
		return AttributionConstraint.builder().key("review:conditional-component").familyKey("review:source")
			.maximum(1).maximumExpression(ObservedExpressions.compileNumeric(new JsonParser().parse(expression)))
			.conditions(ObservedExpressions.compilePredicate(new JsonParser().parse(
				"{\"op\":\"EQ\",\"left\":{\"op\":\"FACT\",\"name\":\"context.activity.review\"},\"right\":{\"op\":\"CONST\",\"value\":1}}")))
			.composition("INDEPENDENT_COMPONENT_GUARD").qualification("HARD_BOUND")
			.numericScope(numericScope)
			.mechanism("ORDINARY_ATTACK").damageComponent("SINGLE_HIT").worldMode("NORMAL")
			.requiredObservations(Arrays.asList("QUALIFIED_NPC_VARIANT", "NORMAL_WORLD_CONTEXT", "NO_MODEL_OVERRIDES_OBSERVED",
				"SINGLE_HIT_COMPONENT", "ORDINARY_ATTACK_COMPONENT")).build();
	}

	private static SourceEvidenceResolver.Hypothesis hypothesis(AttributionConstraint bound, Boolean componentKnown,
		Map<String, Number> facts, boolean api)
	{
		Map<String, Boolean> observations = new LinkedHashMap<>();
		for (String key : bound.getRequiredObservations()) observations.put(key, true);
		if (componentKnown == null) observations.remove("ORDINARY_ATTACK_COMPONENT");
		else observations.put("ORDINARY_ATTACK_COMPONENT", componentKnown);
		return SourceEvidenceResolver.Hypothesis.builder().candidate(SourceResolution.Candidate.builder()
			.key("review:source").name("Recorded source").actorRef("npc:lifetime:1").familyKey("review:source")
			.sceneKey("room").worldViewId(0).plane(0).strength(api ? "STRONG" : "POSSIBLE").build())
			.mechanism("ORDINARY_ATTACK").damageComponent("SINGLE_HIT").worldMode("NORMAL")
			.observations(observations).numericObservations(facts).constraints(Collections.singletonList(bound))
			.observedApiSourceCorrelation(api).build();
	}

	private static SourceEvidenceResolver.Hypothesis other()
	{
		return SourceEvidenceResolver.Hypothesis.builder().candidate(SourceResolution.Candidate.builder()
			.key("review:other").name("Other source").actorRef("npc:lifetime:2").familyKey("review:other")
			.sceneKey("room").worldViewId(0).plane(0).strength("POSSIBLE").build()).build();
	}

	@Test
	public void ordinaryMaximumCannotEraseAnUnqualifiedPossibleSpecialFromSameActor()
	{
		for (Boolean componentKnown : Arrays.asList(null, false))
		{
			SourceResolution result = SourceEvidenceResolver.resolve(Arrays.asList(
				hypothesis(bound("{\"op\":\"CONST\",\"value\":5}"), componentKnown,
					Collections.singletonMap("context.activity.review", 1), false), other()), 20, false, "review");
			assertEquals("ALTERNATIVES", result.getStatus());
			assertEquals(2, result.getCandidates().size());
		}
	}

	@Test
	public void positivelyObservedComponentCanUseTheSameBound()
	{
		SourceResolution result = SourceEvidenceResolver.resolve(Arrays.asList(
			hypothesis(bound("{\"op\":\"CONST\",\"value\":5}"), true,
				Collections.singletonMap("context.activity.review", 1), false), other()), 20, false, "review");
		assertEquals(1, result.getCandidates().size());
		assertEquals("Other source", result.getCandidates().get(0).getName());
	}

	@Test
	public void missingScaleDoesNotUseTheScalarFallbackOrDropTheActor()
	{
		SourceResolution result = SourceEvidenceResolver.resolve(Arrays.asList(
			hypothesis(bound("{\"op\":\"MULTIPLY\",\"args\":[{\"op\":\"CONST\",\"value\":5},{\"op\":\"FACT\",\"name\":\"observed.scale\"}]}"),
				true, Collections.singletonMap("context.activity.review", 1), false), other()), 20, false, "review");
		assertEquals("ALTERNATIVES", result.getStatus());
		assertEquals(2, result.getCandidates().size());
	}

	@Test
	public void unrecognizedOrAbsentNumericScopeCannotBeInterpretedAsPerHitMaximum()
	{
		for (String scope : Arrays.asList(null, "TOTAL_ATTACK_DAMAGE", "PER_PHASE_MAXIMUM"))
		{
			SourceResolution result = SourceEvidenceResolver.resolve(Arrays.asList(
				hypothesis(bound("{\"op\":\"CONST\",\"value\":5}", scope), true,
					Collections.singletonMap("context.activity.review", 1), false), other()), 20, false, "review");
			assertEquals("ALTERNATIVES", result.getStatus());
			assertEquals(2, result.getCandidates().size());
		}
	}

	@Test
	public void explicitApiCorrelationSurvivesReferenceConflictButNotIncompatibleObservedOutcome()
	{
		SourceEvidenceResolver.Hypothesis source = hypothesis(bound("{\"op\":\"CONST\",\"value\":5}"), true,
			Collections.singletonMap("context.activity.review", 1), true);
		SourceResolution conflict = SourceEvidenceResolver.resolve(Collections.singletonList(source), 20, false, "review");
		assertEquals(1, conflict.getCandidates().size());
		assertEquals("POSSIBLE", conflict.getCandidates().get(0).getStrength());
		assertTrue(conflict.isUnidentifiedPossible());
		SourceResolution contradicted = SourceEvidenceResolver.resolve(Collections.singletonList(source.toBuilder()
			.eligibilityExclusions(Collections.singletonList("HITSPLAT_TYPE_MISMATCH:review")).build()), 20, false, "review");
		assertTrue(contradicted.getCandidates().isEmpty());
	}

	@SuppressWarnings("unchecked")
	@Test
	public void factReaderDoesNotFillAbsentContextScaleWithZeroOrDeriveApiAbsence() throws Exception
	{
		AttributionConstraint requested = bound("{\"op\":\"ADD\",\"args\":[{\"op\":\"FACT\",\"name\":\"snapshot.impact.context.raidLevel\"},"
			+ "{\"op\":\"FACT\",\"name\":\"snapshot.impact.context.teamSize\"},{\"op\":\"FACT\",\"name\":\"source.apiProjectileCorrelated\"}]}" );
		Method method = RecordedSourceResolver.class.getDeclaredMethod("numericFacts", List.class, NpcState.class,
			DamageRecord.class, WorldSnapshot.class, boolean.class); method.setAccessible(true);
		Map<String, Number> unavailable = (Map<String, Number>) method.invoke(null, Collections.singletonList(requested), null,
			DamageRecord.builder().impactTick(37).build(), null, false);
		assertTrue(unavailable.isEmpty());
		WorldSnapshot frame = WorldSnapshot.builder().context(ContextBlock.builder().activity("review").raidLevel(0).build()).build();
		Map<String, Number> observed = (Map<String, Number>) method.invoke(null, Collections.singletonList(requested), null,
			DamageRecord.builder().impactTick(37).build(), frame, false);
		assertEquals(0, observed.get("snapshot.impact.context.raidLevel"));
		assertEquals(1, observed.get("context.activity.review"));
		assertFalse(observed.containsKey("snapshot.impact.context.teamSize"));
		assertFalse(observed.containsKey("source.apiProjectileCorrelated"));
	}
}
