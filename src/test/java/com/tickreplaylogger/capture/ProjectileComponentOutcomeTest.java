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

/** Held-out source IDs demonstrate that one non-HP projectile is not a harmless-actor rule. */
public class ProjectileComponentOutcomeTest
{
	private static final Gson GSON = new Gson();
	private static final int NPC = 987654, SHOT = 876543;
	private static final String REF = "npc:unfamiliar:v0:g1", NAME = "Unfamiliar entity", SCENE = "native-scene";

	private static NpcState npc()
	{
		return npc(0, 0, false);
	}

	private static NpcState npc(int view, int plane, boolean modelOverrides)
	{
		return NpcState.builder().npcId(NPC).baseNpcId(NPC).actorRef(REF).name(NAME).combatLevel(80)
			.worldViewId(view).plane(plane).planeKnown(true).worldPositionKnown(true).worldX(100).worldY(199)
			.normalWorldContextObserved(true).modelOverridesPresent(modelOverrides).definitionEvidence("NPC_API_ID")
			.definitionHasTransforms(false).build();
	}

	private static ProjectileState shot()
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

	private static WorldSnapshot frame(int tick, NpcState actor)
	{
		return WorldSnapshot.builder().tick(tick).gameCycle(tick * 100).gameCycleKnown(true).sceneKey(SCENE)
			.worldViewId(0).plane(0).nativeContextKnown(true).npcs(Collections.singletonList(actor))
			.player(PlayerState.builder().worldViewId(0).plane(0).planeKnown(true).worldPositionKnown(true)
				.worldX(100).worldY(200).build()).hazards(Collections.emptyList()).build();
	}

	private static Map<String, Object> witness(ProjectileState shot)
	{
		Map<String, Object> raw = new LinkedHashMap<>();
		raw.put("kind", "PROJECTILE_CORRELATION"); raw.put("name", NAME); raw.put("projectileObservationId", 7L);
		raw.put("projectileId", shot.getProjectileId()); raw.put("arrivalGapCycles", 0);
		raw.put("matchBasis", "OBSERVED_CLIENT_CYCLE_WINDOW"); raw.put("sourceHistory", shot.getSourceHistory());
		return raw;
	}

	private static DamageRecord hit(ProjectileState shot, int amount, int type)
	{
		return DamageRecord.builder().impactTick(2).observedCycle(200).observationSequence(9L).direction("RECEIVED")
			.defenderRef("player").amount(amount).hitsplatType(type).hitsplatTypeKnown(true).attackerName("Unknown")
			.impactSceneKey(SCENE).impactWorldViewId(0).impactPlane(0).impactPositionKnown(true).impactX(100).impactY(200)
			.attributionCandidates(Collections.singletonList(witness(shot))).build();
	}

	private static JsonObject namespace()
	{
		String hash = String.join("", Collections.nCopies(64, "a"));
		return GSON.fromJson("{\"version\":1,\"knownHitsplatTypes\":[12,16,18,65],\"rules\":[{\"key\":\"held-out-control\","
			+ "\"selector\":{\"kind\":\"NPC_PROJECTILE_COMPONENT\",\"ids\":[987654],\"projectileIds\":[876543],\"observedNames\":[\"Unfamiliar entity\"]},"
			+ "\"mechanism\":\"PROJECTILE_COMPONENT\",\"positiveHitsplatTypes\":[],\"zeroHitsplatTypes\":[],\"complete\":true,"
			+ "\"outcomeDomain\":\"NON_HP_COMPONENT\",\"outcomeSetEvidence\":\"REVIEWED_EXHAUSTIVE_COMPONENT_OUTCOMES\","
			+ "\"nonHpOutcomeKinds\":[\"PRAYER_RESOURCE\"],\"requiredObservations\":[\"QUALIFIED_ORIGINAL_PROJECTILE_COMPONENT\"],"
			+ "\"sourceRefs\":[\"Primary fixture\"],\"provenance\":[{\"page\":\"Primary fixture\",\"bodySha256\":\""+hash+"\","
			+ "\"evidence\":[{\"start\":0,\"end\":10,\"sha256\":\""+hash+"\"}]}]}]}", JsonObject.class);
	}

	private static DamageMap map(JsonObject namespace) throws Exception
	{
		CombatCatalog catalog = new CombatCatalog();
		JsonObject root = GSON.fromJson("{\"hitsplatRules\":{\"rules\":{\"12\":{\"surface\":\"BLOCK\"},\"16\":{\"surface\":\"HP\"},"
			+ "\"18\":{\"surface\":\"HP_OR_COMBAT_RESOURCE\"},\"65\":{\"surface\":\"HP\",\"damageType\":\"poison\"}}}}", JsonObject.class);
		root.add("damageCompatibility", namespace);
		Field field = CombatCatalog.class.getDeclaredField("root"); field.setAccessible(true); field.set(catalog, root);
		DamageMap map = new DamageMap(); field = DamageMap.class.getDeclaredField("catalog");
		field.setAccessible(true); field.set(map, catalog); return map;
	}

	private static SourceResolution resolve(DamageMap map, ProjectileState shot, DamageRecord hit, NpcState actor)
	{
		return new RecordedSourceResolver(Arrays.asList(frame(1, actor).toBuilder()
			.projectiles(Collections.singletonList(shot)).build(), frame(2, actor)), Collections.emptyList(), map).resolve(hit);
	}

	private static boolean excluded(SourceResolution result)
	{
		return result.getExcludedCandidates() != null && result.getExcludedCandidates().stream()
			.anyMatch(c -> c.getReasons().stream().anyMatch(r -> r.startsWith("HITSPLAT_TYPE_MISMATCH:held-out-control:")));
	}

	@Test public void exactReviewedNonHpProjectileCannotCauseKnownHpOrBlockButRawFactsRemain() throws Exception
	{
		DamageMap map = map(namespace()); ProjectileState shot = shot();
		for (int[] outcome : new int[][]{{13,16},{0,12},{4,65}})
		{
			DamageRecord hit = hit(shot, outcome[0], outcome[1]); String original = GSON.toJson(hit);
			SourceResolution result = resolve(map, shot, hit, npc());
			assertTrue(excluded(result)); assertTrue(result.getCandidates().isEmpty());
			assertEquals("UNIDENTIFIED", result.getStatus()); assertEquals(original, GSON.toJson(hit));
		}
	}

	@Test public void allThreeSelectorDimensionsMustMatchTogether() throws Exception
	{
		for (String field : Arrays.asList("ids", "projectileIds", "observedNames"))
		{
			JsonObject config = namespace(); JsonObject selector = config.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("selector");
			selector.add(field, GSON.fromJson("observedNames".equals(field) ? "[\"Different actor\"]" : "[999999]", com.google.gson.JsonArray.class));
			ProjectileState shot = shot(); SourceResolution result = resolve(map(config), shot, hit(shot,13,16), npc());
			assertFalse(field, excluded(result)); assertFalse(field, result.getCandidates().isEmpty());
		}
	}

	@Test public void missingOriginalNameDefinitionContextScopeAndSourceReadsRemainUnqualified() throws Exception
	{
		ProjectileState original = shot(); ProjectileSourceHistory.SourceObservation first = original.getSourceHistory().getFirstObservedSource();
		for (ProjectileSourceHistory.SourceObservation incomplete : Arrays.asList(first.toBuilder().sourceNameKnown(null).build(),
			first.toBuilder().sourceDefinitionEvidence(null).build(), first.toBuilder().sourceNormalWorldContextObserved(null).build(),
			first.toBuilder().sourceWorldViewId(null).build(), first.toBuilder().sourceActorKnown(false).build(),
			first.toBuilder().observedCycle(null).build(), first.toBuilder().observedCycle(201).build(),
			first.toBuilder().observedCycle(-1).build(), first.toBuilder().observedTick(-1).build(),
			first.toBuilder().observationIssues(Collections.singletonMap("source.modelOverrides", "UNAVAILABLE")).build()))
		{
			ProjectileState shot = original.toBuilder().sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(incomplete).build()).build();
			assertFalse(excluded(resolve(map(namespace()), shot, hit(shot,13,16), npc())));
		}
	}

	@Test public void negativeImpactClockCannotQualifyAnOriginalComponent() throws Exception
	{
		ProjectileState shot = shot();
		assertFalse(excluded(resolve(map(namespace()), shot, hit(shot,13,16).toBuilder().observedCycle(-1).build(), npc())));
		assertFalse(excluded(resolve(map(namespace()), shot, hit(shot,13,16).toBuilder().impactTick(-1).build(), npc())));
	}

	@Test public void matchingInvalidNativeSentinelsCannotQualifyAComponent() throws Exception
	{
		for (int[] scope : new int[][]{{-1,0},{0,-1},{0,4}})
		{
			ProjectileState original = shot();
			ProjectileSourceHistory.SourceObservation first = original.getSourceHistory().getFirstObservedSource().toBuilder()
				.sourceWorldViewId(scope[0]).sourcePlane(scope[1]).build();
			ProjectileState shot = original.toBuilder().sourceWorldViewId(scope[0]).sourcePlane(scope[1])
				.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(first).build()).build();
			NpcState actor = npc(scope[0], scope[1], false);
			WorldSnapshot origin = frame(1,actor).toBuilder().worldViewId(scope[0]).plane(scope[1])
				.projectiles(Collections.singletonList(shot)).build();
			SourceResolution result = new RecordedSourceResolver(Arrays.asList(origin,frame(2,npc())),Collections.emptyList(),map(namespace()))
				.resolve(hit(shot,13,16));
			assertFalse(Arrays.toString(scope),excluded(result));
		}
	}

	@Test public void changedHistoryProximityAndModelOverridesCannotBecomeAComponentExclusion() throws Exception
	{
		ProjectileState original = shot();
		ProjectileState gap = original.toBuilder().sourceHistory(ProjectileSourceHistory.builder().version(1)
			.firstObservedSource(original.getSourceHistory().getFirstObservedSource()).observationGap(true).build()).build();
		assertFalse(excluded(resolve(map(namespace()), gap, hit(gap,13,16), npc())));
		ProjectileState proximity = original.toBuilder().sourceHistory(ProjectileSourceHistory.builder().version(1)
			.firstObservedSource(original.getSourceHistory().getFirstObservedSource().toBuilder().sourceEvidence("PROXIMITY").build()).build()).build();
		assertFalse(excluded(resolve(map(namespace()), proximity, hit(proximity,13,16), npc())));
		assertFalse(excluded(resolve(map(namespace()), original, hit(original,13,16), npc(0,0,true))));
	}

	@Test public void mixedAndUnknownOutcomeSurfacesRemainPossibleRatherThanBeingDeclaredHp() throws Exception
	{
		for (int type : new int[]{18,999999})
		{
			ProjectileState shot = shot(); SourceResolution result = resolve(map(namespace()),shot,hit(shot,13,type),npc());
			assertFalse(excluded(result)); assertFalse(result.getCandidates().isEmpty()); assertTrue(result.isUnidentifiedPossible());
		}
	}

	@Test public void aDifferentActivityComponentOfTheSameActorSurvivesProjectileExclusion() throws Exception
	{
		ProjectileState shot = shot(); Map<String,Object> activity = new LinkedHashMap<>();
		activity.put("kind","NPC_ANIMATION_CORRELATION"); activity.put("name",NAME); activity.put("actorRef",REF);
		activity.put("npcId",NPC); activity.put("animationId",444444);
		DamageRecord hit = hit(shot,13,16).toBuilder().attributionCandidates(Arrays.asList(witness(shot),activity)).build();
		SourceResolution result = resolve(map(namespace()),shot,hit,npc());
		assertTrue(excluded(result)); assertEquals(1,result.getCandidates().size());
		assertEquals(REF,result.getCandidates().get(0).getActorRef()); assertTrue(result.isUnidentifiedPossible());
	}

	@Test public void incompleteOrMalformedReviewCannotRegisterAnExclusion() throws Exception
	{
		for (String field : Arrays.asList("outcomeDomain","outcomeSetEvidence","provenance","requiredObservations","nonHpOutcomeKinds"))
		{
			JsonObject config=namespace(); config.getAsJsonArray("rules").get(0).getAsJsonObject().remove(field);
			assertTrue(field,map(config).catalog().damageOutcomeRules("NPC_PROJECTILE_COMPONENT",NPC).isEmpty());
		}
		JsonObject config=namespace(); config.getAsJsonArray("rules").get(0).getAsJsonObject()
			.add("positiveHitsplatTypes",GSON.fromJson("[16]",com.google.gson.JsonArray.class));
		assertTrue(map(config).catalog().damageOutcomeRules("NPC_PROJECTILE_COMPONENT",NPC).isEmpty());
	}
}
