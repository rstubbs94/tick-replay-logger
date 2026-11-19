/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class WholeActorBoundTest
{
	private static final List<String> REQUIRED = Arrays.asList("QUALIFIED_NPC_VARIANT", "NORMAL_WORLD_CONTEXT",
		"NO_MODEL_OVERRIDES_OBSERVED", "SINGLE_HIT_COMPONENT", "NPC_DIRECT_SOURCE_HYPOTHESIS");

	private static AttributionConstraint guard(String basis, List<Integer> allowed, boolean witness)
	{
		return AttributionConstraint.builder().key("fixture:whole-source").familyKey("fixture:source")
			.boundBasis(basis).allowedHitsplatTypes(allowed).wholeActorWitnessQualified(witness)
			.maximumExpression(ObservedExpressions.compileNumeric(new JsonParser().parse("{\"op\":\"CONST\",\"value\":4}")))
			.conditions(ObservedExpressions.compilePredicate(new JsonParser().parse(
				"{\"op\":\"EQ\",\"left\":{\"op\":\"FACT\",\"name\":\"source.sample.combatLevel\"},\"right\":{\"op\":\"CONST\",\"value\":10}}")))
			.composition("INDEPENDENT_COMPONENT_GUARD").qualification("HARD_BOUND").numericScope("PER_HIT_MAXIMUM")
			.mechanism("ALL_DIRECT_HP_COMPONENTS").damageComponent("SINGLE_HIT").worldMode("NORMAL")
			.requiredObservations(REQUIRED).build();
	}

	private static LegacyConstraintOracle.Hypothesis source(AttributionConstraint guard)
	{
		Map<String, Boolean> facts = new HashMap<>(); for (String fact : REQUIRED) facts.put(fact, true);
		facts.put("ORDINARY_ATTACK_COMPONENT", false); facts.put("STANDARD_DAMAGE_MODIFIERS", false);
		SourceResolution.Candidate candidate = SourceResolution.Candidate.builder().key("source").name("Recorded source")
			.actorRef("npc:actual-lifetime").npcId(999950).familyKey("fixture:source").sceneKey("room").worldViewId(0).plane(0)
			.strength("POSSIBLE").build();
		return LegacyConstraintOracle.Hypothesis.builder().candidate(candidate).constraints(Collections.singletonList(guard))
			.observations(facts).numericObservations(Collections.singletonMap("source.sample.combatLevel", 10))
			.mechanism("NPC_DIRECT_SOURCE").damageComponent("SINGLE_HIT").worldMode("NORMAL").observedHitsplatType(16).build();
	}

	private static SourceResolution resolve(LegacyConstraintOracle.Hypothesis source)
	{
		LegacyConstraintOracle.Hypothesis other = LegacyConstraintOracle.Hypothesis.builder().candidate(SourceResolution.Candidate.builder()
			.key("other").name("Other source").actorRef("npc:other").sceneKey("room").worldViewId(0).plane(0).build()).build();
		return LegacyConstraintOracle.resolve(Arrays.asList(source, other), 5, false, "fixture");
	}

	@Test
	public void qualifiedWholeSourceEnvelopeDoesNotRequireOrdinaryComponentStyleOrModifiers()
	{
		SourceResolution result = resolve(source(guard("QUALIFIED_WHOLE_ACTOR_ENVELOPE", Collections.singletonList(16), true)));
		assertEquals(1, result.getCandidates().size()); assertEquals("Other source", result.getCandidates().get(0).getName());
		assertEquals(1, result.getExcludedCandidates().size());
		assertEquals("ALL_DIRECT_HP_COMPONENTS",result.getExcludedCandidates().get(0).getExclusionScope());
	}

	@Test
	public void qualifiedWholeSourceEnvelopeIsNotRevivedByApiCorrelationFallback()
	{
		LegacyConstraintOracle.Hypothesis original = source(guard("QUALIFIED_WHOLE_ACTOR_ENVELOPE",Collections.singletonList(16),true));
		SourceResolution result = LegacyConstraintOracle.resolve(Collections.singletonList(original.toBuilder()
			.candidate(original.getCandidate().toBuilder().strength("STRONG").build()).observedApiSourceCorrelation(true).build()),5,true,"fixture");
		assertTrue(result.getCandidates().isEmpty());
		assertEquals("ALL_DIRECT_HP_COMPONENTS",result.getExcludedCandidates().get(0).getExclusionScope());
		assertTrue(result.isUnidentifiedPossible());
	}

	@Test
	public void duplicateExclusionWitnessRetainsQualifiedWholeSourceScopeInEitherOrder()
	{
		LegacyConstraintOracle.Hypothesis whole = source(guard("QUALIFIED_WHOLE_ACTOR_ENVELOPE",Collections.singletonList(16),true));
		LegacyConstraintOracle.Hypothesis other = whole.toBuilder().constraints(Collections.emptyList())
			.eligibilityExclusions(Collections.singletonList("OTHER_COMPONENT_EXCLUDED")).build();
		for (List<LegacyConstraintOracle.Hypothesis> order : Arrays.asList(Arrays.asList(other,whole),Arrays.asList(whole,other)))
		{
			SourceResolution result = LegacyConstraintOracle.resolve(order,5,true,"fixture");
			assertTrue(result.getCandidates().isEmpty()); assertEquals(1,result.getExcludedCandidates().size());
			assertEquals("ALL_DIRECT_HP_COMPONENTS",result.getExcludedCandidates().get(0).getExclusionScope());
		}
	}

	@Test
	public void unlistedOrUnknownHitsplatOnlyMakesTheBoundInapplicable()
	{
		LegacyConstraintOracle.Hypothesis original = source(guard("QUALIFIED_WHOLE_ACTOR_ENVELOPE", Collections.singletonList(16), true));
		for (Integer type : Arrays.asList(null, 5, 74))
		{
			SourceResolution result = resolve(original.toBuilder().observedHitsplatType(type).build());
			assertEquals(2, result.getCandidates().size());
			assertTrue(result.getExcludedCandidates() == null || result.getExcludedCandidates().isEmpty());
		}
	}

	@Test
	public void malformedAuthoredApplicabilityCannotWidenThePinnedV1DirectHpDomain()
	{
		for (List<Integer> invalid : Arrays.asList(Collections.singletonList(5), Collections.singletonList(999999),
			Arrays.asList(16, 5), Arrays.asList(16, 16)))
		{
			LegacyConstraintOracle.Hypothesis source = source(guard("QUALIFIED_WHOLE_ACTOR_ENVELOPE", invalid, true));
			for (int observed : Arrays.asList(5, 16, 999999))
				assertEquals(2, resolve(source.toBuilder().observedHitsplatType(observed).build()).getCandidates().size());
		}
	}

	@Test
	public void everyRequiredObservedFactAndOriginalNpcIdentityIsNecessary()
	{
		LegacyConstraintOracle.Hypothesis original = source(guard("QUALIFIED_WHOLE_ACTOR_ENVELOPE", Collections.singletonList(16), true));
		for (String fact : REQUIRED)
		{
			Map<String, Boolean> facts = new HashMap<>(original.getObservations()); facts.remove(fact);
			assertEquals(fact, 2, resolve(original.toBuilder().observations(facts).build()).getCandidates().size());
		}
		for (SourceResolution.Candidate invalid : Arrays.asList(original.getCandidate().toBuilder().npcId(null).build(),
			original.getCandidate().toBuilder().actorRef(null).build(), original.getCandidate().toBuilder().worldViewId(null).build()))
			assertEquals(2, resolve(original.toBuilder().candidate(invalid).build()).getCandidates().size());
	}

	@Test
	public void areasContactsStatusAndDifferentResourceComponentsCannotInheritTheEnvelope()
	{
		LegacyConstraintOracle.Hypothesis original = source(guard("QUALIFIED_WHOLE_ACTOR_ENVELOPE", Collections.singletonList(16), true));
		for (String mechanism : Arrays.asList("AREA_IMPACT", "TERMINAL_AREA", "CONTACT", "STATUS"))
			assertEquals(mechanism, 2, resolve(original.toBuilder().mechanism(mechanism).build()).getCandidates().size());
		assertEquals(2, resolve(original.toBuilder().damageComponent("PERIODIC_STATUS").build()).getCandidates().size());
		assertEquals(2, resolve(original.toBuilder().worldMode("UNESTABLISHED").build()).getCandidates().size());
	}

	@Test
	public void missingLevelUnknownBasisOrUnqualifiedMetadataCannotBecomeDefaults()
	{
		for (AttributionConstraint invalid : Arrays.asList(guard("UNKNOWN_FUTURE_BASIS", Collections.singletonList(16), true),
			guard("QUALIFIED_WHOLE_ACTOR_ENVELOPE", null, true), guard("QUALIFIED_WHOLE_ACTOR_ENVELOPE", Collections.emptyList(), true),
			guard("QUALIFIED_WHOLE_ACTOR_ENVELOPE", Collections.singletonList(16), false)))
			assertEquals(2, resolve(source(invalid)).getCandidates().size());
		LegacyConstraintOracle.Hypothesis original = source(guard("QUALIFIED_WHOLE_ACTOR_ENVELOPE", Collections.singletonList(16), true));
		assertEquals(2, resolve(original.toBuilder().numericObservations(Collections.emptyMap()).build()).getCandidates().size());
		assertEquals(2, resolve(original.toBuilder().numericObservations(Collections.singletonMap("source.sample.combatLevel", 11)).build()).getCandidates().size());
	}

	@Test
	public void compiledActualWholeActorRowsUseTypedMetadataAndClosedWitnesses() throws Exception
	{
		DamageMap map = DamageMap.load(new Gson());
		List<AttributionConstraint> whole = new ArrayList<>();
		for (int id : Arrays.asList(5779, 11705))
			for (AttributionConstraint rule : LegacyCombatCatalog.from(map.catalog()).attributionConstraints(id))
				if ("QUALIFIED_WHOLE_ACTOR_ENVELOPE".equals(rule.getBoundBasis())) whole.add(rule);
		assertEquals("Both independently reviewed current declarations must reach the runtime", 2, whole.size());
		for (AttributionConstraint rule : whole)
		{
			assertTrue(rule.isWholeActorWitnessQualified()); assertFalse(rule.getAllowedHitsplatTypes().isEmpty());
			assertFalse(rule.getRequiredObservations().contains("ORDINARY_ATTACK_COMPONENT"));
		}
		Field field = CombatCatalog.class.getDeclaredField("root"); field.setAccessible(true);
		JsonObject root = (JsonObject) field.get(map.catalog());
		for (com.google.gson.JsonElement value : root.getAsJsonObject("attributionConstraints").getAsJsonArray("rules"))
		{
			JsonObject rule = value.getAsJsonObject();
			if (!rule.has("boundBasis") || !"QUALIFIED_WHOLE_ACTOR_ENVELOPE".equals(rule.get("boundBasis").getAsString())) continue;
			com.google.gson.JsonArray invalid = new com.google.gson.JsonArray(); invalid.add(5); rule.add("allowedHitsplatTypes", invalid);
		}
		Field index = LegacyCombatCatalog.class.getDeclaredField("attributionConstraintIndex"); index.setAccessible(true); index.set(LegacyCombatCatalog.from(map.catalog()), null);
		for (AttributionConstraint rule : LegacyCombatCatalog.from(map.catalog()).attributionConstraints(5779))
			if ("QUALIFIED_WHOLE_ACTOR_ENVELOPE".equals(rule.getBoundBasis())) assertNull(rule.getAllowedHitsplatTypes());
		root.getAsJsonObject("attributionConstraints").addProperty("version", 2);
		index.set(LegacyCombatCatalog.from(map.catalog()), null);
		assertTrue(LegacyCombatCatalog.from(map.catalog()).attributionConstraints(5779).isEmpty());
	}

	@Test
	public void actualDirectNpcAdapterCanUseSourceEnvelopeWithoutAnOrdinaryCue()
	{
		DamageMap map = DamageMap.load(new Gson());
		NpcState source = NpcState.builder().actorRef("npc:actual-mole").npcId(5779).name("Giant Mole")
			.combatLevel(230).normalWorldContextObserved(true).modelOverridesPresent(false).planeKnown(true).plane(0).worldViewId(0).build();
		NpcState rival = NpcState.builder().actorRef("npc:rival").npcId(999949).name("Unreviewed rival")
			.combatLevel(10).planeKnown(true).plane(0).worldViewId(0).build();
		WorldSnapshot frame = WorldSnapshot.builder().tick(5).sceneKey("room").worldViewId(0).plane(0).nativeContextKnown(true)
			.player(PlayerState.builder().worldPositionKnown(true).worldViewId(0).worldX(100).worldY(200).plane(0).planeKnown(true).build())
			.npcs(Arrays.asList(source, rival)).build();
		DamageRecord hit = DamageRecord.builder().impactTick(5).direction("RECEIVED").defenderRef("player")
			.impactSceneKey("room").impactWorldViewId(0).impactPlane(0).impactPositionKnown(true).impactX(100).impactY(200)
			.amount(22).hitsplatTypeKnown(true).hitsplatType(16).observationSequence(50L).attackerName("Unknown")
			.attributionCandidates(Arrays.asList(direct(source), direct(rival))).build();
		SourceResolution resolved = new RecordedSourceResolver(Collections.singletonList(frame), Collections.emptyList(), map).resolve(hit);
		assertTrue(resolved.getExcludedCandidates() != null
			&& resolved.getExcludedCandidates().stream().anyMatch(c -> "Giant Mole".equals(c.getName())));
		assertTrue(resolved.getCandidates().stream().anyMatch(c -> "Unreviewed rival".equals(c.getName())));
		for (DamageRecord incomplete : Arrays.asList(hit.toBuilder().defenderRef(null).build(),
			hit.toBuilder().direction("DEALT").build(), hit.toBuilder().hitsplatTypeKnown(false).build()))
		{
			SourceResolution kept = new RecordedSourceResolver(Collections.singletonList(frame), Collections.emptyList(), map).resolve(incomplete);
			assertTrue(kept.getCandidates().stream().anyMatch(c -> "Giant Mole".equals(c.getName())));
		}
	}

	private static Map<String, Object> direct(NpcState npc)
	{
		Map<String, Object> raw = new HashMap<>(); raw.put("kind", "NPC_ANIMATION_CORRELATION");
		raw.put("actorRef", npc.getActorRef()); raw.put("npcId", npc.getNpcId()); raw.put("name", npc.getName());
		raw.put("animationId", 999948); return raw;
	}
}
