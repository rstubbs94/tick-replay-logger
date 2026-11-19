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
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.tickreplaylogger.capture;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Held-out item identities exercise existing outcome filtering without proving an input executed. */
public class ItemInputOutcomeCompatibilityTest
{
	private static final Gson GSON = new Gson();
	private static final int ITEM = 990123;
	private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	static final class Fixture
	{
		final CombatCatalog catalog = new CombatCatalog();
		final JsonObject root = GSON.fromJson("{\"hitsplatRules\":{\"rules\":{\"16\":{\"surface\":\"HP\"},\"12\":{\"surface\":\"BLOCK\"},\"65\":{\"surface\":\"HP\",\"damageType\":\"poison\"},\"5\":{\"surface\":\"HP\",\"damageType\":\"venom\"},\"3\":{\"surface\":\"SKILL_OR_HP\"}}},\"selfDamageInputs\":{\"records\":[{\"page\":\"Unfamiliar item\",\"itemIds\":[990123],\"candidateOperations\":[\"Eat\",\"Crush\"],\"candidateWindowTicks\":2}]},\"damageCompatibility\":{\"version\":1,\"knownHitsplatTypes\":[3,5,12,16,65],\"rules\":[{\"key\":\"fixture:typed-self-input\",\"selector\":{\"kind\":\"ITEM\",\"ids\":[990123],\"inputSourceRef\":\"wiki:Unfamiliar item\",\"operations\":[\"Eat\"]},\"mechanism\":\"SELF_INPUT\",\"positiveHitsplatTypes\":[65],\"zeroHitsplatTypes\":null,\"complete\":true,\"requiredObservations\":[\"QUALIFIED_SELF_INPUT\"],\"sourceRefs\":[\"Unfamiliar item\"]}]}}", JsonObject.class);
		final DamageMap map = new DamageMap();
		Fixture() throws Exception
		{
			root.getAsJsonObject("selfDamageInputs").getAsJsonArray("records").get(0).getAsJsonObject().addProperty("pageHash", HASH);
			Field field = CombatCatalog.class.getDeclaredField("root"); field.setAccessible(true); field.set(catalog, root);
			field = DamageMap.class.getDeclaredField("catalog"); field.setAccessible(true); field.set(map, catalog);
		}
		JsonObject rule() { return root.getAsJsonObject("damageCompatibility").getAsJsonArray("rules").get(0).getAsJsonObject(); }
		Map<String, Object> input()
		{
			SelfDamageInputs inputs = new SelfDamageInputs(); inputs.observe(catalog, ITEM, "Eat", 5, 10L);
			return new LinkedHashMap<>(inputs.candidates(5).get(0));
		}
		RecordedSourceResolver resolver()
		{
			WorldSnapshot frame = WorldSnapshot.builder().tick(5).sceneKey("native").worldViewId(0).plane(0).nativeContextKnown(true)
				.player(PlayerState.builder().worldViewId(0).plane(0).planeKnown(true).worldPositionKnown(true).worldX(100).worldY(200).build()).build();
			return new RecordedSourceResolver(Collections.singletonList(frame), Collections.emptyList(), map);
		}
		SourceResolution resolve(Map<String, Object> input, int type, int amount)
		{
			return resolver().resolve(hit(type, amount).toBuilder().attributionCandidates(Collections.singletonList(input)).build());
		}
	}
	private static DamageRecord hit(int type, int amount)
	{
		return DamageRecord.builder().direction("RECEIVED").defenderRef("player").impactTick(5).observationSequence(20L)
			.observedCycle(100).impactSceneKey("native").impactWorldViewId(0).impactPlane(0).impactPositionKnown(true)
			.impactX(100).impactY(200).hitsplatTypeKnown(true).hitsplatType(type).amount(amount).attackerName("Unknown").build();
	}
	private static boolean excluded(SourceResolution result)
	{
		return result.getExcludedCandidates() != null && result.getExcludedCandidates().stream().anyMatch(candidate ->
			candidate.getReasons().stream().anyMatch(reason -> reason.startsWith("HITSPLAT_TYPE_MISMATCH:")));
	}

	@Test public void actualInputCandidateKeepsCompatibleTypedHitPossibleAndExcludesKnownPositiveRed() throws Exception
	{
		Fixture fixture = new Fixture(); Map<String, Object> input = fixture.input();
		SourceResolution compatible = fixture.resolve(input, 65, 5);
		assertEquals(1, compatible.getCandidates().size()); assertTrue(compatible.isUnidentifiedPossible());
		assertEquals("POSSIBLE", compatible.getCandidates().get(0).getStrength()); assertFalse(excluded(compatible));
		assertTrue(compatible.getCandidates().get(0).getEvidence().stream().anyMatch(e -> e.startsWith("HITSPLAT_SIGNATURE:")));
		SourceResolution red = fixture.resolve(input, 16, 5);
		assertTrue(red.getCandidates().isEmpty()); assertTrue(excluded(red));
		assertTrue(red.isUnidentifiedPossible()); assertEquals(5, hit(16, 5).getAmount());
	}

	@Test public void unprovedZeroMixedAndUnknownOutcomeDomainsDoNotExcludeAnInput() throws Exception
	{
		Fixture fixture = new Fixture();
		for (int type : new int[] { 12, 16, 65, 3, 999999 })
		{
			int amount = type == 3 || type == 999999 ? 5 : 0;
			SourceResolution result = fixture.resolve(fixture.input(), type, amount);
			assertEquals(1, result.getCandidates().size()); assertFalse(excluded(result)); assertTrue(result.isUnidentifiedPossible());
			assertTrue(result.getCandidates().get(0).getEvidence().stream().anyMatch(e -> e.startsWith("HITSPLAT_SIGNATURE_UNESTABLISHED:")));
		}
	}

	@Test public void missingOrContradictoryOperationReferenceHashAndInputClockStayUnqualified() throws Exception
	{
		Fixture fixture = new Fixture();
		String[] fields = { "itemId", "option", "sourceRef", "pageHash", "inputTick", "inputSequence", "evidence" };
		for (String field : fields)
		{
			Map<String, Object> input = fixture.input(); input.remove(field);
			SourceResolution result = fixture.resolve(input, 16, 5);
			assertEquals(field, 1, result.getCandidates().size()); assertFalse(field, excluded(result));
			assertTrue(result.isUnidentifiedPossible());
		}
		for (Object[] bad : new Object[][] { { "option", "Crush" }, { "sourceRef", "wiki:Other item" },
			{ "pageHash", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" },
			{ "inputTick", 1 }, { "inputTick", 5.5 }, { "inputSequence", 20L }, { "inputSequence", 10.5 },
			{ "itemId", ITEM + 0.5 }, { "itemId", -4293977173L } })
		{
			Map<String, Object> input = fixture.input(); input.put((String) bad[0], bad[1]);
			assertFalse(String.valueOf(bad[0]) + "=" + bad[1], excluded(fixture.resolve(input, 16, 5)));
		}
		DamageRecord missingSubtype = hit(16, 5).toBuilder().hitsplatTypeKnown(null)
			.attributionCandidates(Collections.singletonList(fixture.input())).build();
		assertFalse(excluded(fixture.resolver().resolve(missingSubtype)));
	}

	@Test public void resourceAndOtherMechanismCandidatesCannotBorrowTheItemInputSignature() throws Exception
	{
		Fixture fixture = new Fixture();
		for (String kind : new String[] { "SELF_DAMAGE_RESOURCE_CANDIDATE", "PICKPOCKET_FAILURE_CORRELATION" })
		{
			Map<String, Object> input = fixture.input(); input.put("kind", kind);
			assertFalse(excluded(fixture.resolve(input, 16, 5)));
		}
	}

	@Test public void anotherOperationOfTheSameItemDoesNotDiluteTheMatchingSignature() throws Exception
	{
		Fixture fixture = new Fixture(); JsonObject other = fixture.rule().deepCopy();
		other.addProperty("key", "fixture:other-operation");
		other.getAsJsonObject("selector").add("operations", GSON.toJsonTree(Collections.singletonList("Crush")));
		other.add("positiveHitsplatTypes", GSON.toJsonTree(Collections.singletonList(16)));
		fixture.root.getAsJsonObject("damageCompatibility").getAsJsonArray("rules").add(other);
		assertTrue(excluded(fixture.resolve(fixture.input(), 16, 5)));
		Map<String, Object> input = fixture.input(); input.put("option", "Crush");
		assertFalse(excluded(fixture.resolve(input, 16, 5)));
	}

	@Test public void malformedItemSelectorOrMissingZeroDomainCannotGainAnExclusion() throws Exception
	{
		for (int variant = 0; variant < 4; variant++)
		{
			Fixture fixture = new Fixture(); JsonObject rule = fixture.rule();
			if (variant == 0) rule.getAsJsonObject("selector").addProperty("futureCondition", true);
			if (variant == 1) rule.remove("zeroHitsplatTypes");
			if (variant == 2) rule.addProperty("mechanism", "RESOURCE_COST");
			if (variant == 3) rule.add("requiredObservations", GSON.toJsonTree(Collections.singletonList("SOURCE_DEFINITION_BOUND")));
			assertFalse("variant " + variant, excluded(fixture.resolve(fixture.input(), 16, 5)));
		}
	}

	@Test public void independentPriorExclusionsAndEvidenceSurviveCompatibleUnknownAndMismatchingSignatures() throws Exception
	{
		Fixture fixture = new Fixture();
		Method method = RecordedSourceResolver.class.getDeclaredMethod("outcomeCompatibility", LegacyConstraintOracle.Hypothesis.class,
			DamageRecord.class, String.class, Integer.class, Map.class, String.class, Map.class); method.setAccessible(true);
		LegacyConstraintOracle.Hypothesis previous = LegacyConstraintOracle.Hypothesis.builder()
			.eligibilityEvidence(Collections.singletonList("PRIOR_INDEPENDENT_EVIDENCE"))
			.eligibilityExclusions(Collections.singletonList("PRIOR_INDEPENDENT_EXCLUSION")).build();
		for (int type : new int[] { 65, 16, 3 })
		{
			LegacyConstraintOracle.Hypothesis result = (LegacyConstraintOracle.Hypothesis) method.invoke(fixture.resolver(), previous,
				hit(type, 5), "ITEM", ITEM, Collections.emptyMap(), "SELF_INPUT", fixture.input());
			assertTrue(result.getEligibilityEvidence().contains("PRIOR_INDEPENDENT_EVIDENCE"));
			assertTrue(result.getEligibilityExclusions().contains("PRIOR_INDEPENDENT_EXCLUSION"));
			assertEquals(type == 16 ? 2 : 1, result.getEligibilityExclusions().size());
		}
	}
}
