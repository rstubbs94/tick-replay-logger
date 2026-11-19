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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.ContextBlock;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** The existing constraint engine filters each input operation before source-family consensus. */
public class SelfInputConstraintsTest
{
	private static final Gson GSON = new Gson();
	private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final class Fixture
	{
		final ItemInputOutcomeCompatibilityTest.Fixture shared = new ItemInputOutcomeCompatibilityTest.Fixture();
		final JsonObject namespace = GSON.fromJson("{\"version\":1,\"rules\":[{\"key\":\"fixture:constant-input\",\"selector\":{\"kind\":\"ITEM\",\"ids\":[990123],\"inputSourceRef\":\"wiki:Unfamiliar item\",\"operations\":[\"Eat\"],\"pageHash\":\"" + HASH + "\"},\"maximum\":4,\"numericScope\":\"PER_CALLBACK_MAXIMUM\",\"contextPredicate\":{\"requiredZeroVariables\":[\"LEAGUE_TYPE\"],\"excludedWorldTypeFragments\":[\"SEASONAL\",\"BETA\"],\"requireObservedWorldTypes\":true},\"provenance\":{\"reviewedClaimSha256\":\"" + HASH + "\"}}]}", JsonObject.class);
		Fixture() throws Exception
		{
			shared.root.getAsJsonObject("damageCompatibility").add("rules", new JsonArray());
			shared.root.add("selfInputConstraints", namespace);
		}
		JsonObject rule() { return namespace.getAsJsonArray("rules").get(0).getAsJsonObject(); }
		SourceResolution resolve(List<Map<String, Object>> inputs, int amount, int type, ContextBlock context)
		{
			return resolve(inputs, amount, type, context, context, true);
		}
		SourceResolution resolve(List<Map<String, Object>> inputs, int amount, int type, ContextBlock original, ContextBlock settled, boolean captured)
		{
			WorldSnapshot frame = WorldSnapshot.builder().tick(5).sceneKey("native").worldViewId(0).plane(0).nativeContextKnown(true)
				.player(PlayerState.builder().worldViewId(0).plane(0).planeKnown(true).worldPositionKnown(true).worldX(100).worldY(200).build())
				.context(settled).build();
			DamageRecord hit = DamageRecord.builder().direction("RECEIVED").defenderRef("player").impactTick(5)
				.observationSequence(20L).observedCycle(100).impactSceneKey("native").impactWorldViewId(0).impactPlane(0)
				.impactPositionKnown(true).hitsplatTypeKnown(true).hitsplatType(type).amount(amount).attributionCandidates(inputs).build();
			return new RecordedSourceResolver(Collections.singletonList(frame), Collections.emptyList(), shared.map,
				captured ? Collections.singletonList(ObservedHpCallbackContextTest.observation(hit, original)) : Collections.emptyList(), Collections.emptyList()).resolve(hit);
		}
		SourceResolution resolve(int amount) { return resolve(Collections.singletonList(shared.input()), amount, 16, normal()); }
	}
	private static ContextBlock normal()
	{
		Map<String, String> evidence = new LinkedHashMap<>(); evidence.put("worldTypes", "OBSERVED_CLIENT_WORLD_TYPES"); evidence.put("LEAGUE_TYPE", "OBSERVED_CLIENT_VARIABLE");
		return ContextBlock.builder().worldTypes(Collections.emptyList()).evidence(evidence)
			.observedVariables(Collections.singletonMap("LEAGUE_TYPE", 0)).build();
	}
	private static boolean excluded(SourceResolution result)
	{
		return result.getExcludedCandidates() != null && result.getExcludedCandidates().stream().anyMatch(candidate ->
			candidate.getReasons().stream().anyMatch(reason -> reason.startsWith("MAXIMUM_EXCEEDED:fixture:constant-input")));
	}

	@Test public void constantPerCallbackMaximumUsesExistingEngineAndNeverProvesExecution() throws Exception
	{
		Fixture fixture = new Fixture(); SourceResolution atMaximum = fixture.resolve(4);
		assertEquals(1, atMaximum.getCandidates().size()); assertFalse(excluded(atMaximum)); assertTrue(atMaximum.isUnidentifiedPossible());
		assertEquals("POSSIBLE", atMaximum.getCandidates().get(0).getStrength());
		assertTrue(atMaximum.getCandidates().get(0).getEvidence().stream().anyMatch(e -> e.startsWith("QUALIFIED_MAXIMUM:fixture:constant-input")));
		SourceResolution above = fixture.resolve(5); assertTrue(above.getCandidates().isEmpty()); assertTrue(excluded(above));
		assertTrue(above.isUnidentifiedPossible());
	}

	@Test public void typedHpCanUseAnIndependentBoundButMixedAndUnknownOutcomesCannot() throws Exception
	{
		Fixture fixture = new Fixture();
		assertTrue(excluded(fixture.resolve(Collections.singletonList(fixture.shared.input()), 5, 65, normal())));
		for (int type : new int[] { 3, 999999 })
		{
			SourceResolution result = fixture.resolve(Collections.singletonList(fixture.shared.input()), 99, type, normal());
			assertEquals(1, result.getCandidates().size()); assertFalse(excluded(result)); assertTrue(result.isUnidentifiedPossible());
		}
	}

	@Test public void unknownModifiedAndUnobservedWorldContextCannotSupplyABound() throws Exception
	{
		Fixture fixture = new Fixture();
		for (ContextBlock context : Arrays.asList(null, ContextBlock.builder().worldTypes(Collections.emptyList()).build(),
			normal().toBuilder().observedVariables(Collections.singletonMap("LEAGUE_TYPE", 1)).build(),
			normal().toBuilder().worldTypes(Collections.singletonList("SEASONAL" )).build()))
		{
			SourceResolution result = fixture.resolve(Collections.singletonList(fixture.shared.input()), 99, 16, context);
			assertFalse(excluded(result)); assertEquals(1, result.getCandidates().size());
		}
	}

	@Test public void onlyTheOriginalHpCallbackContextCanQualifyTheBound() throws Exception
	{
		Fixture fixture = new Fixture(); List<Map<String, Object>> inputs = Collections.singletonList(fixture.shared.input());
		ContextBlock seasonal = normal().toBuilder().worldTypes(Collections.singletonList("SEASONAL")).build();
		assertTrue(excluded(fixture.resolve(inputs, 5, 16, normal(), seasonal, true)));
		assertFalse(excluded(fixture.resolve(inputs, 5, 16, seasonal, normal(), true)));
		assertFalse(excluded(fixture.resolve(inputs, 5, 16, null, normal(), true)));
		assertFalse(excluded(fixture.resolve(inputs, 5, 16, normal(), normal(), false)));
		ContextBlock failed = normal().toBuilder().evidence(Collections.singletonMap("worldTypes", "UNAVAILABLE")).build();
		assertFalse(excluded(fixture.resolve(inputs, 5, 16, failed, normal(), true)));
	}

	@Test public void cappedAndUncappedOperationsSurviveAsSeparateWitnessesBeforeFamilyMerge() throws Exception
	{
		Fixture fixture = new Fixture();
		JsonObject inputRule = fixture.shared.root.getAsJsonObject("selfDamageInputs").getAsJsonArray("records").get(0).getAsJsonObject();
		inputRule.getAsJsonArray("candidateOperations").add("Guzzle");
		SelfDamageInputs inputs = new SelfDamageInputs(); inputs.observe(fixture.shared.catalog, 990123, "Eat", 5, 10L);
		inputs.observe(fixture.shared.catalog, 990123, "Guzzle", 5, 11L);
		List<Map<String, Object>> witnesses = inputs.candidates(5); assertEquals(2, witnesses.size());
		SourceResolution result = fixture.resolve(witnesses, 7, 16, normal());
		assertTrue(excluded(result)); assertEquals(1, result.getCandidates().size()); assertTrue(result.isUnidentifiedPossible());
		assertEquals("input-rule:wiki:Unfamiliar item", result.getCandidates().get(0).getFamilyKey());
	}

	@Test public void missingInputProofAndStaleBoundPageCannotBecomeAMaximum() throws Exception
	{
		Fixture fixture = new Fixture(); Map<String, Object> input = fixture.shared.input(); input.remove("inputSequence");
		assertFalse(excluded(fixture.resolve(Collections.singletonList(input), 99, 16, normal())));
		input = fixture.shared.input(); input.put("pageHash", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
		assertFalse(excluded(fixture.resolve(Collections.singletonList(input), 99, 16, normal())));
		Fixture stale = new Fixture();
		stale.rule().getAsJsonObject("selector").addProperty("pageHash", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
		assertFalse(excluded(stale.resolve(99)));
	}

	@Test public void closedDeclarationsAndImmutableParsedRulesCannotGainNewSemantics() throws Exception
	{
		for (int variant = 0; variant < 3; variant++)
		{
			Fixture fixture = new Fixture();
			if (variant == 0) fixture.rule().getAsJsonObject("selector").addProperty("futureCondition", true);
			if (variant == 1) fixture.rule().addProperty("numericScope", "TOTAL_SEQUENCE_DAMAGE");
			if (variant == 2) fixture.rule().getAsJsonObject("contextPredicate").addProperty("unobservedModifier", false);
			assertFalse(excluded(fixture.resolve(99)));
		}
		Fixture fixture = new Fixture(); assertSame(fixture.shared.catalog.selfInputConstraints(), fixture.shared.catalog.selfInputConstraints());
		fixture.rule().addProperty("maximum", 0); assertFalse(excluded(fixture.resolve(4)));
	}
}
