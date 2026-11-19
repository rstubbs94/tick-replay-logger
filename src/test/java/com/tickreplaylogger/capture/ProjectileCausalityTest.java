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
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.ProjectileSourceCandidate;
import com.tickreplaylogger.model.world.ProjectileSourceHistory;
import com.tickreplaylogger.model.world.ProjectileState;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProjectileCausalityTest
{
	private static final int NPC = 987654, SHOT = 876543;
	private static final String REF = "npc:unfamiliar:v0:g1", NAME = "Unfamiliar entity", SCENE = "native-scene";

	private static NpcState npc()
	{
		return NpcState.builder().npcId(NPC).baseNpcId(NPC).actorRef(REF).name(NAME).combatLevel(80)
			.worldViewId(0).plane(0).planeKnown(true).worldPositionKnown(true).worldX(100).worldY(199).size(1)
			.normalWorldContextObserved(true).modelOverridesPresent(false).definitionEvidence("NPC_API_ID")
			.definitionHasTransforms(false).build();
	}

	private static ProjectileState projectile()
	{
		ProjectileSourceHistory.SourceObservation first = ProjectileSourceHistory.SourceObservation.builder()
			.observationId(7L).projectileId(SHOT).observedTick(1).observedCycle(100).actorKind("NPC")
			.sourceActorKnown(true).sourceEvidence("ACTOR").sourceRef(REF).sourceName(NAME).sourceNpcId(NPC)
			.sourceNameKnown(true).sourceObservedName(NAME).sourceCombatLevel(80).sourceNormalWorldContextObserved(true)
			.sourceBaseNpcId(NPC).sourceDefinitionEvidence("NPC_API_ID").sourceDefinitionHasTransforms(false)
			.sourceWorldViewId(0).sourcePlane(0).build();
		return ProjectileState.builder().observationId(7L).projectileId(SHOT).spawnTick(1).observedTick(1)
			.observedCycle(100).sourceActorKnown(true).sourceEvidence("ACTOR").sourceRef(REF).sourceName(NAME)
			.sourceNpcId(NPC).sourceWorldViewId(0).sourcePlane(0).worldViewId(0).targetRef("player")
			.targetsPlayer(true).targetWorldViewId(0).targetPlane(0)
			.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(first).build()).build();
	}

	private static WorldSnapshot frame(int tick)
	{
		return WorldSnapshot.builder().tick(tick).gameCycle(tick * 100).gameCycleKnown(true).sceneKey(SCENE)
			.worldViewId(0).plane(0).nativeContextKnown(true).npcs(Collections.singletonList(npc()))
			.player(PlayerState.builder().worldViewId(0).plane(0).planeKnown(true).worldPositionKnown(true)
				.worldX(100).worldY(200).build()).hazards(Collections.emptyList()).build();
	}

	private static DamageRecord hit(Map<String, Object> raw)
	{
		return DamageRecord.builder().impactTick(2).observedCycle(200).observationSequence(9L).direction("RECEIVED")
			.defenderRef("player").amount(13).hitsplatType(16).hitsplatTypeKnown(true).attackerName("Unknown")
			.impactSceneKey(SCENE).impactWorldViewId(0).impactPlane(0).impactPositionKnown(true).impactX(100).impactY(200)
			.attributionCandidates(Collections.singletonList(raw)).build();
	}

	private static Map<String, Object> witness(ProjectileState shot)
	{
		Map<String, Object> raw = new LinkedHashMap<>();
		raw.put("kind", "PROJECTILE_CORRELATION"); raw.put("name", NAME); raw.put("projectileObservationId", 7L);
		raw.put("projectileId", SHOT); raw.put("arrivalGapCycles", 0);
		raw.put("matchBasis", "OBSERVED_CLIENT_CYCLE_WINDOW"); raw.put("sourceHistory", shot.getSourceHistory());
		return raw;
	}

	private static SourceResolution resolve(ProjectileState shot, DamageRecord hit, DamageMap map)
	{
		return new RecordedSourceResolver(Arrays.asList(frame(1).toBuilder()
			.projectiles(Collections.singletonList(shot)).build(), frame(2)), Collections.emptyList(), map).resolve(hit);
	}

	private static void uncertainNamed(SourceResolution result)
	{
		assertEquals("ALTERNATIVES", result.getStatus());
		assertTrue(result.isUnidentifiedPossible());
		assertEquals(1, result.getCandidates().size());
		assertEquals(NAME, result.getCandidates().get(0).getName());
		assertTrue(result.getCandidates().get(0).getEvidence().stream()
			.anyMatch(e -> e.startsWith("HIT_CAUSALITY_UNESTABLISHED:")));
	}

	@Test
	public void unfamiliarApiProjectileOwnerRemainsNamedWithoutBecomingCertainHpCause()
	{
		ProjectileState shot = projectile(); DamageRecord original = hit(witness(shot));
		SourceResolution result = resolve(shot, original, null);
		uncertainNamed(result);
		assertEquals(REF, result.getCandidates().get(0).getActorRef());
		assertEquals(Integer.valueOf(NPC), result.getCandidates().get(0).getNpcId());
		assertEquals("STRONG", result.getCandidates().get(0).getStrength());
		assertEquals(13, original.getAmount()); assertEquals("Unknown", original.getAttackerName());
	}

	@Test
	public void proximityAndTimingDoNotBecomeCertainProjectileOwnershipOrHpCausality()
	{
		ProjectileSourceCandidate nearest = ProjectileSourceCandidate.builder().npcId(NPC).actorRef(REF).name(NAME)
			.worldViewId(0).plane(0).sceneKey(SCENE).combatLevel(80).build();
		ProjectileState shot = projectile();
		ProjectileSourceHistory.SourceObservation first = shot.getSourceHistory().getFirstObservedSource().toBuilder()
			.sourceRef(null).sourceNpcId(null).actorKind(null).sourceEvidence("PROXIMITY")
			.proximitySampleRetained(true).proximityCandidate(nearest).build();
		shot = shot.toBuilder().sourceRef(null).sourceNpcId(null).sourceEvidence("PROXIMITY")
			.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(first).build()).build();
		SourceResolution result = resolve(shot, hit(witness(shot)), null);
		uncertainNamed(result);
		assertEquals(REF, result.getCandidates().get(0).getActorRef());
		assertTrue(result.getCandidates().get(0).getEvidence().contains("RECORDED_PROXIMITY_CANDIDATE; not API source actor"));
	}

	@Test
	public void namedProjectileWithoutRetainedSourceIdentityCannotBecomeCertainBySingletonConsensus()
	{
		Map<String, Object> raw = new LinkedHashMap<>();
		raw.put("kind", "PROJECTILE_CORRELATION"); raw.put("name", NAME);
		SourceResolution result = resolve(projectile(), hit(raw), null);
		uncertainNamed(result); assertNull(result.getCandidates().get(0).getActorRef());
	}

	@Test
	public void qualifiedHpComponentStillDoesNotUniquelyAssociateThisHpCallback() throws Exception
	{
		Gson gson = new Gson(); CombatCatalog catalog = new CombatCatalog();
		JsonObject root = gson.fromJson("{\"npcIdentities\":{\"987654\":{\"candidates\":[{\"name\":\"Unfamiliar entity\","
			+ "\"source_ref\":\"wiki:Unfamiliar#1\",\"combat_level\":\"80\"}]}},"
			+ "\"reviewedBindings\":{\"projectileStyleRules\":[{\"npcIds\":[987654],\"styles\":{\"876543\":\"ranged\"},"
			+ "\"outcomeMechanism\":\"ORDINARY_ATTACK\"}]},\"hitsplatRules\":{\"rules\":{\"16\":{\"surface\":\"HP\"}}},"
			+ "\"damageCompatibility\":{\"version\":1,\"knownHitsplatTypes\":[12,16],\"rules\":[{\"key\":\"ordinary\","
			+ "\"selector\":{\"kind\":\"NPC_ORDINARY_ATTACK\"},\"mechanism\":\"ORDINARY_ATTACK\",\"complete\":true,"
			+ "\"positiveHitsplatTypes\":[16],\"zeroHitsplatTypes\":[12],\"requiredObservations\":[\"QUALIFIED_ORDINARY_COMPONENT\"],"
			+ "\"sourceRefs\":[\"Primary fixture\"]}]}}", JsonObject.class);
		Field field = CombatCatalog.class.getDeclaredField("root"); field.setAccessible(true); field.set(catalog, root);
		DamageMap map = new DamageMap(); field = DamageMap.class.getDeclaredField("catalog");
		field.setAccessible(true); field.set(map, catalog);
		ProjectileState shot = projectile(); SourceResolution result = resolve(shot, hit(witness(shot)), map);
		uncertainNamed(result);
		assertTrue("Positive control must actually qualify its HP signature", result.getCandidates().get(0).getEvidence()
			.stream().anyMatch(e -> e.startsWith("HITSPLAT_SIGNATURE:ordinary:")));
		assertTrue(result.getExcludedCandidates() == null || result.getExcludedCandidates().isEmpty());
	}

	@Test
	public void typedGateSurvivesActorConsensusButExcludedAssociationsDoNotPoisonAnotherSource()
	{
		SourceResolution.Candidate a = SourceResolution.Candidate.builder().key("api-owner").name(NAME).actorRef(REF)
			.npcId(NPC).sceneKey(SCENE).worldViewId(0).plane(0).strength("STRONG").build();
		LegacyConstraintOracle.Hypothesis correlation = LegacyConstraintOracle.Hypothesis.builder().candidate(a)
			.causalAssociationOnly(true).build();
		LegacyConstraintOracle.Hypothesis independent = LegacyConstraintOracle.Hypothesis.builder()
			.candidate(a.toBuilder().key("separate-witness").build()).build();
		SourceResolution result = LegacyConstraintOracle.resolve(Arrays.asList(correlation, independent), 13, false, "fixture");
		assertEquals(1, result.getCandidates().size()); assertTrue(result.isUnidentifiedPossible());
		assertEquals("ALTERNATIVES", result.getStatus());
		SourceResolution other = LegacyConstraintOracle.resolve(Arrays.asList(correlation.toBuilder()
			.eligibilityExclusions(Collections.singletonList("QUALIFIED_OUTCOME_CONTRADICTION")).build(),
			independent.toBuilder().candidate(a.toBuilder().key("other").actorRef("npc:other:v0:g2").build()).build()),
			13, false, "fixture");
		assertEquals("IDENTIFIED", other.getStatus()); assertFalse(other.isUnidentifiedPossible());
		assertEquals("npc:other:v0:g2", other.getCandidates().get(0).getActorRef());
	}
}
