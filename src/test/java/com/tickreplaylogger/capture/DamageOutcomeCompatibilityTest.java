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
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.HazardState;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.ObservedStatusState;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.SceneData;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Arbitrary identities exercise observed signatures before source consensus. */
public class DamageOutcomeCompatibilityTest
{
	private static final Gson GSON = new Gson();

	private static JsonObject root(DamageMap map) throws Exception
	{
		java.lang.reflect.Field field = CombatCatalog.class.getDeclaredField("root");
		field.setAccessible(true);
		return (JsonObject) field.get(map.catalog());
	}

	private static void reset(DamageMap map) throws Exception
	{
		java.lang.reflect.Field field = CombatCatalog.class.getDeclaredField("damageCompatibilityIndex");
		field.setAccessible(true);
		field.set(map.catalog(), null);
	}

	private static DamageMap map() throws Exception
	{
		DamageMap map = DamageMap.load(GSON);
		JsonObject root = root(map);
		root.getAsJsonObject("npcIdentities").add("900001", GSON.fromJson(
			"{\"candidates\":[{\"name\":\"Unfamiliar entity\",\"source_ref\":\"wiki:Unfamiliar_entity#infobox-1\",\"combat_level\":\"80\"}]}",
			JsonObject.class));
		root.getAsJsonObject("reviewedBindings").getAsJsonObject("npcOverrides").add("900001",
			GSON.fromJson(
				"{\"animations\":{\"777777\":{\"kind\":\"attack\",\"outcomeMechanism\":\"ORDINARY_ATTACK\"}}}",
				JsonObject.class));
		root.add("damageCompatibility",
			GSON.fromJson("{\"version\":1,\"knownHitsplatTypes\":[0,5,12,13,16,17,18,43,65,74],\"rules\":["
				+ "{\"key\":\"ordinary-outcome\",\"selector\":{\"kind\":\"NPC_ORDINARY_ATTACK\"},\"mechanism\":\"ORDINARY_ATTACK\",\"positiveHitsplatTypes\":[16,17,43],\"zeroHitsplatTypes\":[12,13,16,17,43],\"complete\":true,\"requiredObservations\":[\"QUALIFIED_ORDINARY_COMPONENT\"],\"sourceRefs\":[\"synthetic-ordinary\"]},"
				+ "{\"key\":\"typed-surface-outcome\",\"selector\":{\"kind\":\"OBJECT\",\"ids\":[900010]},\"mechanism\":\"ENVIRONMENT_CONTACT\",\"positiveHitsplatTypes\":[5],\"zeroHitsplatTypes\":[5],\"complete\":true,\"requiredObservations\":[\"SOURCE_DEFINITION_BOUND\"],\"sourceRefs\":[\"synthetic-surface\"]}]}",
				JsonObject.class));
		reset(map);
		return map;
	}

	private static ObservedStatusState status(boolean active)
	{
		return ObservedStatusState.builder().key("poison_venom_client_state").ruleKey("poison_venom_client_state")
			.state(active ? "venom" : "inactive").stateLabel("Venom").active(active).valueObserved(true).build();
	}

	private static WorldSnapshot frame(int tick, boolean active)
	{
		NpcState npc = NpcState.builder().npcId(900001).actorRef("npc:unfamiliar:v0:g1").name("Unfamiliar entity")
			.combatLevel(80).worldViewId(0).plane(0).normalWorldContextObserved(true).modelOverridesPresent(false)
			.animationId(777777).build();
		HazardState hazard = HazardState.builder().kind("gameObject").id(900010).objectKey(1L).mappedName("Fume")
			.damaging(true).sourceEligibility("ELIGIBLE_REFERENCE_SURFACE").worldViewId(0).plane(0).worldX(102)
			.worldY(203).localPositionKnown(true).worldPositionKnown(true).minSceneX(2).maxSceneX(4).minSceneY(3)
			.maxSceneY(5).startCycle(1).build();
		return WorldSnapshot.builder().tick(tick).sceneKey("scene-a").worldViewId(0).plane(0).nativeContextKnown(true)
			.player(PlayerState.builder().worldX(102).worldY(203).build()).npcs(Collections.singletonList(npc))
			.hazards(Collections.singletonList(hazard)).localStatuses(Collections.singletonList(status(active)))
			.build();
	}

	private static DamageRecord hit(int amount, int type, long sequence)
	{
		Map<String, Object> npc = new HashMap<>();
		npc.put("kind", "NPC_ANIMATION_CORRELATION");
		npc.put("name", "Unfamiliar entity");
		npc.put("actorRef", "npc:unfamiliar:v0:g1");
		npc.put("npcId", 900001);
		npc.put("animationId", 777777);
		return DamageRecord.builder().impactTick(2).observationSequence(sequence).observedCycle(100)
			.direction("RECEIVED").impactSceneKey("scene-a").impactWorldViewId(0).impactPlane(0).impactX(102)
			.impactY(203).impactPositionKnown(true).amount(amount).hitsplatType(type).damageType("unknown")
			.damageTypeEvidence("UNKNOWN").attackerName("Unknown").attributionCandidates(Collections.singletonList(npc))
			.build();
	}

	private static SourceResolution resolve(DamageMap map, DamageRecord hit)
	{
		return new RecordedSourceResolver(Arrays.asList(frame(1, false), frame(2, false)),
			Collections.singletonList(
				SceneData.builder().sceneKey("scene-a").worldViewId(0).plane(0).baseX(100).baseY(200).build()),
			map).resolve(hit);
	}

	private static void only(SourceResolution result, String name, String excluded)
	{
		assertEquals("ALTERNATIVES", result.getStatus());
		assertTrue("An outcome signature filters causes but does not prove the surviving contact caused this hit",
			result.isUnidentifiedPossible());
		assertTrue(result.getCandidates().get(0).getEvidence().stream()
			.anyMatch(e -> e.startsWith("HIT_CAUSALITY_UNESTABLISHED:")));
		assertEquals(1, result.getCandidates().size());
		assertEquals(name, result.getCandidates().get(0).getName());
		assertTrue(result.getExcludedCandidates().stream().anyMatch(c -> excluded.equals(c.getName())));
		assertTrue(result.getExcludedCandidates().stream().flatMap(c -> c.getReasons().stream())
			.anyMatch(s -> s.startsWith("HITSPLAT_TYPE_MISMATCH:")));
	}

	@Test
	public void sameTickSeparateBlockAndTypedCallbacksSelectDifferentMechanismsWithoutChangingFacts() throws Exception
	{
		DamageMap map = map();
		DamageRecord block = hit(0, 12, 7L), typed = hit(1, 5, 8L);
		String originalBlock = GSON.toJson(block), originalTyped = GSON.toJson(typed);
		only(resolve(map, block), "Unfamiliar entity", "Fume");
		only(resolve(map, typed), "Fume", "Unfamiliar entity");
		assertEquals(originalBlock, GSON.toJson(block));
		assertEquals(originalTyped, GSON.toJson(typed));
		assertEquals("unknown", block.getDamageType());
		assertEquals("UNKNOWN", block.getDamageTypeEvidence());
	}

	@Test
	public void zeroAmountDoesNotInventABlockAndNormalRedDoesNotInventAnAttackStyle() throws Exception
	{
		DamageMap map = map();
		only(resolve(map, hit(0, 5, 7L)), "Fume", "Unfamiliar entity");
		DamageRecord red = hit(3, 16, 8L);
		only(resolve(map, red), "Unfamiliar entity", "Fume");
		assertEquals("unknown", red.getDamageType());
	}

	@Test
	public void unclassifiedAttackAnimationDoesNotBecomeOrdinaryByKindOrInfoboxStyle() throws Exception
	{
		DamageMap map = map();
		root(map).getAsJsonObject("reviewedBindings").getAsJsonObject("npcOverrides").getAsJsonObject("900001")
			.getAsJsonObject("animations").getAsJsonObject("777777").remove("outcomeMechanism");
		assertEquals(2, resolve(map, hit(1, 5, 7L)).getCandidates().size());
	}

	@Test
	public void missingUnknownAndAmbiguousOutcomeObservationsCannotExclude() throws Exception
	{
		DamageMap map = map();
		for (DamageRecord h : Arrays.asList(hit(1, 999991, 7L), hit(1, 18, 7L), hit(1, 0, 7L),
			hit(1, 12, 7L).toBuilder().hitsplatTypeKnown(false).build(),
			hit(1, 12, 7L).toBuilder().captureIssues(Collections.singletonList("hitsplatType: unavailable")).build()))
			assertEquals(2, resolve(map, h).getCandidates().size());
		assertTrue(resolve(map, hit(1, 0, 7L).toBuilder().hitsplatTypeKnown(true).build()).getCandidates().isEmpty());
	}

	@Test
	public void incompleteOrUnboundSurfaceSignatureKeepsPossibility() throws Exception
	{
		DamageMap map = map();
		root(map).getAsJsonObject("damageCompatibility").getAsJsonArray("rules").get(1).getAsJsonObject()
			.addProperty("complete", false);
		reset(map);
		assertEquals(2, resolve(map, hit(0, 12, 7L)).getCandidates().size());
		map = map();
		WorldSnapshot impact = frame(2, false).toBuilder()
			.hazards(Collections.singletonList(HazardState.builder().kind("gameObject").id(900010).mappedName("Fume")
				.damaging(true).worldViewId(0).plane(0).worldPositionKnown(true).worldX(102).worldY(203).build()))
			.build();
		SourceResolution r = new RecordedSourceResolver(Collections.singletonList(impact), Collections.emptyList(), map)
			.resolve(hit(0, 12, 7L));
		assertEquals(2, r.getCandidates().size());
	}

	@Test
	public void completeAlternativeSignaturesAreUnionedRatherThanAppliedAsConjunctiveCaps() throws Exception
	{
		DamageMap map = map();
		JsonArray rules = root(map).getAsJsonObject("damageCompatibility").getAsJsonArray("rules");
		JsonObject alternate = rules.get(1).getAsJsonObject().deepCopy();
		alternate.addProperty("key", "second-surface-outcome");
		alternate.add("zeroHitsplatTypes", GSON.toJsonTree(Collections.singletonList(12)));
		rules.add(alternate);
		reset(map);
		assertEquals(2, resolve(map, hit(0, 12, 7L)).getCandidates().size());
	}

	@Test
	public void malformedNamespaceOrIdArrayCannotPartiallySupplyAnExclusion() throws Exception
	{
		DamageMap map = map();
		root(map).getAsJsonObject("damageCompatibility").add("version", new JsonObject());
		reset(map);
		assertEquals(2, resolve(map, hit(0, 12, 7L)).getCandidates().size());
		map = map();
		root(map).getAsJsonObject("damageCompatibility").getAsJsonArray("rules").get(1).getAsJsonObject()
			.getAsJsonObject("selector").getAsJsonArray("ids").add("not-an-id");
		reset(map);
		assertEquals(2, resolve(map, hit(0, 12, 7L)).getCandidates().size());
	}

	@Test
	public void knownObservedBlockCannotBecomeStatusDamageFromAnInferredStyleString() throws Exception
	{
		DamageMap map = map();
		DamageRecord h = hit(0, 12, 7L).toBuilder().damageType("venom").build();
		SourceResolution r = new RecordedSourceResolver(Arrays.asList(frame(1, true), frame(2, true)),
			Collections.singletonList(
				SceneData.builder().sceneKey("scene-a").worldViewId(0).plane(0).baseX(100).baseY(200).build()),
			map).resolve(h);
		only(r, "Unfamiliar entity", "Fume");
		assertEquals("venom", h.getDamageType());
	}
}
