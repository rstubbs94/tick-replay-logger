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
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObservedNpcContactHintResolverTest
{
	private static final Gson GSON = new Gson();
	private static final int ID = 999971;
	private static final String REF = "npc:body:v0:g1", HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	public static JsonObject namespace(int id, String name)
	{
		JsonObject document = GSON.fromJson("{\"version\":1,\"rules\":[{\"key\":\"fixture:body\",\"name\":\"Unseen body\",\"npcIds\":[],\"referenceKeys\":[],\"observationKind\":\"RECORDED_NPC_SNAPSHOT\",\"temporalRelation\":\"SAME_RECORDED_TICK\",\"qualification\":\"POSSIBLE_SOURCE_HINT\",\"unidentifiedPossible\":true,\"geometry\":{\"operator\":\"OBSERVED_API_OCCUPIED_BOUNDS\",\"scope\":\"POSSIBLE_BODY_CONTACT_ASSOCIATION\"},\"recipients\":\"OBSERVED_LOCAL_PLAYER\",\"observedOutcomeDomain\":\"ORDINARY_HP_OR_BLOCK_CALLBACK_ONLY\",\"observedHitsplatTypes\":[12,13,16,17,43],\"unestablishedConditions\":[\"PHASE_OR_ACTIVATION\",\"ATTACK_OR_CONTACT_OCCURRENCE\"]}]}", JsonObject.class);
		JsonObject rule = document.getAsJsonArray("rules").get(0).getAsJsonObject(); rule.addProperty("name", name);
		rule.add("npcIds", GSON.toJsonTree(Collections.singletonList(id)));
		Map<String, Object> span = new LinkedHashMap<>(); span.put("start", 0); span.put("end", 1); span.put("sha256", HASH);
		Map<String, Object> page = new LinkedHashMap<>(); page.put("page", "Unseen primary page"); page.put("bodySha256", HASH); page.put("evidence", Collections.singletonList(span));
		Map<String, Object> variant = new LinkedHashMap<>(); variant.put("sourceRef", "wiki:Unseen_body#infobox-1"); variant.put("sha256", HASH);
		Map<String, Object> asset = new LinkedHashMap<>(); asset.put("id", id); asset.put("symbol", "NpcID.UNSEEN_BODY"); asset.put("definitionSha256", HASH); asset.put("variantReferences", Collections.singletonList(variant));
		Map<String, Object> symbols = new LinkedHashMap<>(); symbols.put("BLOCK_ME", 12); symbols.put("BLOCK_OTHER", 13); symbols.put("DAMAGE_ME", 16); symbols.put("DAMAGE_OTHER", 17); symbols.put("DAMAGE_MAX_ME", 43);
		Map<String, Object> outcome = new LinkedHashMap<>(); outcome.put("path", "fixture/api.java"); outcome.put("sha256", HASH); outcome.put("symbols", symbols);
		Map<String, Object> proof = new LinkedHashMap<>(); proof.put("pages", Collections.singletonList(page)); proof.put("assets", Collections.singletonList(asset)); proof.put("reviewedClaimSha256", HASH); proof.put("outcomeCategoryProof", outcome);
		rule.add("provenance", GSON.toJsonTree(proof)); return document;
	}

	private static CombatCatalog catalog(JsonObject namespace) throws Exception
	{
		CombatCatalog catalog = new CombatCatalog(); JsonObject root = GSON.fromJson("{\"hitsplatRules\":{\"rules\":{\"16\":{\"surface\":\"HP\"},\"12\":{\"surface\":\"BLOCK\"},\"5\":{\"surface\":\"HP\",\"damageType\":\"venom\"},\"3\":{\"surface\":\"SKILL_OR_HP\"}}},\"npcIdentities\":{},\"reviewedBindings\":{\"npcOverrides\":{}}}", JsonObject.class);
		root.add("observedNpcContactHints", namespace);
		Field field = CombatCatalog.class.getDeclaredField("root"); field.setAccessible(true); field.set(catalog, root); return catalog;
	}
	private static Map<String, Integer> area()
	{
		Map<String, Integer> area = new LinkedHashMap<>(); area.put("minX", 100); area.put("minY", 200);
		area.put("maxX", 104); area.put("maxY", 204); area.put("plane", 0); return area;
	}
	private static NpcState.NpcStateBuilder npc()
	{
		return NpcState.builder().actorRef(REF).npcId(ID).name("Unseen body").definitionEvidence("NPC_API_ID")
			.normalWorldContextObserved(true).modelOverridesPresent(false).combatLevel(50).animationId(777777)
			.worldViewId(0).planeKnown(true).plane(0).worldPositionKnown(true).worldX(100).worldY(200).size(5)
			.worldArea(area()).worldAreaKnown(true).worldAreaWorldViewKnown(true).worldAreaWorldViewId(0).worldAreaEvidence("ACTOR_GET_WORLD_AREA");
	}
	private static WorldSnapshot frame(NpcState npc)
	{
		return WorldSnapshot.builder().tick(5).gameCycle(110).gameCycleKnown(true).sceneKey("native").worldViewId(0)
			.plane(0).nativeContextKnown(true).npcs(Collections.singletonList(npc)).hazards(Collections.emptyList()).build();
	}
	private static DamageRecord hit()
	{
		return DamageRecord.builder().impactTick(5).observationSequence(20L).observedCycle(100).direction("RECEIVED")
			.defenderRef("player").impactSceneKey("native").impactWorldViewId(0).impactPlane(0).impactPositionKnown(true)
			.impactX(102).impactY(202).hitsplatType(16).hitsplatTypeKnown(true).amount(7).attackerName("Unknown").build();
	}
	private static ObservedNpcContactHintResolver resolver(JsonObject namespace, WorldSnapshot frame) throws Exception
	{
		return new ObservedNpcContactHintResolver(namespace, Collections.singletonMap(frame.getTick(), Collections.singletonList(frame)), catalog(namespace));
	}

	@Test
	public void heldOutIdUsesDirectInclusiveApiBoundsAndKeepsEveryConditionUnestablished() throws Exception
	{
		ObservedNpcContactHintResolver resolver = resolver(namespace(ID, "Unseen body"), frame(npc().build()));
		for (int point : Arrays.asList(100, 102, 104))
		{
			ObservedMechanismResolver.Result result = resolver.resolve(hit().toBuilder().impactX(point).impactY(202).build());
			assertEquals(1, result.getContributions().size()); assertTrue(result.isUnidentifiedPossible());
			SourceEvidenceResolver.Hypothesis body = result.getContributions().get(0).getHypothesis();
			assertEquals(REF, body.getCandidate().getActorRef()); assertEquals("POSSIBLE", body.getCandidate().getStrength());
			assertNull(body.getCandidate().getFamilyKey()); assertNull(body.getConstraints()); assertNull(body.getNumericObservations());
			assertEquals("NPC_OCCUPIED_BODY_CONTACT", body.getMechanism());
		}
		assertTrue(resolver.resolve(hit().toBuilder().impactX(105).build()).getContributions().isEmpty());
		assertTrue(resolver.resolve(hit().toBuilder().impactTick(6).build()).getContributions().isEmpty());
	}

	@Test
	public void absentApiAreaCannotUseRenderedCoordinatesOrDefinitionSizeAndForeignScopeCannotProject() throws Exception
	{
		for (NpcState npc : Arrays.asList(npc().worldArea(null).worldAreaKnown(false).build(),
			npc().worldAreaWorldViewKnown(false).build(), npc().worldAreaWorldViewId(8).build(),
			npc().worldAreaEvidence("DERIVED_FROM_SIZE").build(), npc().worldViewId(8).build(),
			npc().name("Different creature").build(), npc().definitionEvidence("DEFINITION_CONTEXT_UNAVAILABLE").build()))
		{
			ObservedMechanismResolver.Result result = resolver(namespace(ID, "Unseen body"), frame(npc)).resolve(hit());
			assertTrue(result.getContributions().isEmpty()); assertTrue(result.isUnidentifiedPossible());
		}
		assertTrue(resolver(namespace(ID, "Unseen body"), frame(npc().build()).toBuilder().worldViewId(8).build()).resolve(hit()).getContributions().isEmpty());
	}

	@Test
	public void independentAreaStillWorksWhenTheRenderedPointReadFailsAndParsedBoundsAreFrozenPerTick() throws Exception
	{
		Map<String, Integer> actual = area(); NpcState npc = npc().worldArea(actual).worldPositionKnown(false).planeKnown(false)
			.observationIssues(Collections.singletonMap("worldPosition", "UNAVAILABLE")).build();
		ObservedNpcContactHintResolver resolver = resolver(namespace(ID, "Unseen body"), frame(npc));
		assertEquals(1, resolver.resolve(hit()).getContributions().size()); actual.put("minX", 999999);
		assertEquals(1, resolver.resolve(hit()).getContributions().size());
	}

	@Test
	public void oversizedAreaRemainsRawButCannotBecomeAWholeMapContact() throws Exception
	{
		Map<String, Integer> raw = area(); raw.put("minX", Integer.MIN_VALUE); raw.put("maxX", Integer.MAX_VALUE);
		NpcState npc = npc().worldArea(raw).build();
		ObservedMechanismResolver.Result result = resolver(namespace(ID, "Unseen body"), frame(npc)).resolve(hit());
		assertTrue(result.getContributions().isEmpty()); assertTrue(result.isUnidentifiedPossible());
		assertEquals(Integer.valueOf(Integer.MIN_VALUE), npc.getWorldArea().get("minX"));
	}

	@Test
	public void typedStatusMixedUnknownAndMissingHpCallbacksCannotAcquireBodyHints() throws Exception
	{
		ObservedNpcContactHintResolver resolver = resolver(namespace(ID, "Unseen body"), frame(npc().build()));
		for (int type : Arrays.asList(5, 3, 999999)) assertTrue(resolver.resolve(hit().toBuilder().hitsplatType(type).build()).getContributions().isEmpty());
		assertTrue(resolver.resolve(hit().toBuilder().hitsplatTypeKnown(false).build()).getContributions().isEmpty());
		assertTrue(resolver.resolve(hit().toBuilder().observationSequence(null).build()).getContributions().isEmpty());
		assertEquals(1, resolver.resolve(hit().toBuilder().hitsplatType(12).amount(0).build()).getContributions().size());
	}

	@Test
	public void unknownSemanticFieldsAndIncompleteSourceProofCannotActivateEvenAnExactNpc() throws Exception
	{
		for (int variant = 0; variant < 5; variant++)
		{
			JsonObject namespace = namespace(ID, "Unseen body"), row = namespace.getAsJsonArray("rules").get(0).getAsJsonObject();
			if (variant == 0) row.getAsJsonObject("geometry").addProperty("radius", 3);
			if (variant == 1) row.addProperty("qualification", "CONFIRMED");
			if (variant == 2) row.getAsJsonArray("observedHitsplatTypes").add(5);
			if (variant == 3) row.getAsJsonObject("provenance").remove("assets");
			if (variant == 4) row.getAsJsonObject("provenance").getAsJsonObject("outcomeCategoryProof").getAsJsonObject("symbols").addProperty("DAMAGE_ME", 5);
			assertTrue("variant " + variant, resolver(namespace, frame(npc().build())).resolve(hit()).getContributions().isEmpty());
		}
	}

	@Test
	public void anOrdinaryComponentExclusionCannotEraseTheIndependentBodyPossibility() throws Exception
	{
		JsonObject namespace = namespace(ID, "Unseen body"); CombatCatalog catalog = catalog(namespace);
		Field rootField = CombatCatalog.class.getDeclaredField("root"); rootField.setAccessible(true); JsonObject root = (JsonObject) rootField.get(catalog);
		root.getAsJsonObject("npcIdentities").add(String.valueOf(ID), GSON.fromJson("{\"candidates\":[{\"name\":\"Unseen body\",\"source_ref\":\"wiki:Unseen_body#infobox-1\",\"combat_level\":\"50\"}]}", JsonObject.class));
		root.getAsJsonObject("reviewedBindings").getAsJsonObject("npcOverrides").add(String.valueOf(ID), GSON.fromJson("{\"animations\":{\"777777\":{\"kind\":\"attack\",\"outcomeMechanism\":\"ORDINARY_ATTACK\"}}}", JsonObject.class));
		AttributionConstraint guard = AttributionConstraint.builder().key("fixture:ordinary").familyKey("wiki:Unseen_body#infobox-1")
			.maximum(4).qualification("HARD_BOUND").mechanism("ORDINARY_ATTACK").damageComponent("SINGLE_HIT").worldMode("NORMAL")
			.requiredObservations(Arrays.asList("QUALIFIED_NPC_VARIANT", "NORMAL_WORLD_CONTEXT", "NO_MODEL_OVERRIDES_OBSERVED", "ORDINARY_ATTACK_COMPONENT")).build();
		Field index = CombatCatalog.class.getDeclaredField("attributionConstraintIndex"); index.setAccessible(true); index.set(catalog, Collections.singletonMap(ID, Collections.singletonList(guard)));
		DamageMap map = new DamageMap(); Field catalogField = DamageMap.class.getDeclaredField("catalog"); catalogField.setAccessible(true); catalogField.set(map, catalog);
		Map<String, Object> ordinary = new LinkedHashMap<>(); ordinary.put("kind", "NPC_ANIMATION_CORRELATION"); ordinary.put("name", "Unseen body");
		ordinary.put("npcId", ID); ordinary.put("actorRef", REF); ordinary.put("animationId", 777777);
		WorldSnapshot impact = frame(npc().build()).toBuilder().player(PlayerState.builder().worldViewId(0)
			.worldPositionKnown(true).planeKnown(true).worldX(102).worldY(202).plane(0).build()).build();
		SourceResolution result = new RecordedSourceResolver(Collections.singletonList(impact), Collections.emptyList(), map)
			.resolve(hit().toBuilder().attributionCandidates(Collections.singletonList(ordinary)).build());
		assertEquals(1, result.getCandidates().size()); assertEquals(REF, result.getCandidates().get(0).getActorRef());
		assertTrue(result.getCandidates().get(0).getEvidence().stream().anyMatch(e -> e.startsWith("NPC_BODY_SOURCE_RULE:")));
		assertTrue(result.isUnidentifiedPossible()); assertNotNull(result.getExcludedCandidates());
		assertTrue(result.getExcludedCandidates().stream().anyMatch(c -> c.getReasons().stream().anyMatch(r -> r.contains("MAXIMUM_EXCEEDED:fixture:ordinary"))));
	}

	@Test
	public void explicitHeldOutProximityUsesInclusiveEdgeDistanceAndNeverInventsAnOverlapDamageFootprint() throws Exception
	{
		JsonObject ns=namespace(ID,"Unseen body");
		ns.getAsJsonArray("rules").get(0).getAsJsonObject().add("geometry",GSON.fromJson("{\"operator\":\"OBSERVED_API_OCCUPIED_BOUNDS_PROXIMITY\",\"scope\":\"POSSIBLE_PROXIMITY_ASSOCIATION\",\"minChebyshevTiles\":1,\"maxChebyshevTiles\":1}",JsonObject.class));
		ObservedNpcContactHintResolver resolver=resolver(ns,frame(npc().name("<col=00ffff>Unseen body</col>").build()));
		for (int[] point:new int[][]{{99,200},{105,205},{100,199},{104,205}})
		{
			ObservedMechanismResolver.Result result=resolver.resolve(hit().toBuilder().impactX(point[0]).impactY(point[1]).build());
			assertEquals(1,result.getContributions().size()); assertTrue(result.isUnidentifiedPossible());
			SourceEvidenceResolver.Hypothesis hypothesis=result.getContributions().get(0).getHypothesis();
			assertEquals("NPC_PROXIMITY_ASSOCIATION",hypothesis.getMechanism()); assertNull(hypothesis.getConstraints());
			assertTrue(hypothesis.isCausalAssociationOnly()); assertEquals("Unseen body",hypothesis.getCandidate().getName());
		}
		assertTrue(resolver.resolve(hit()).getContributions().isEmpty());
		assertTrue(resolver.resolve(hit().toBuilder().impactX(106).impactY(205).build()).getContributions().isEmpty());
		for (int bad:new int[]{-1,17})
		{
			JsonObject invalid=ns.deepCopy(); invalid.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("geometry").addProperty(bad<0?"minChebyshevTiles":"maxChebyshevTiles",bad);
			assertTrue(resolver(invalid,frame(npc().build())).resolve(hit().toBuilder().impactX(105).build()).getContributions().isEmpty());
		}
	}

	@Test
	public void zeroMinimumIncludesActualBodyAndAdjacentTilesAsOneUncertainSourceClue() throws Exception
	{
		JsonObject namespace = namespace(ID, "Unseen body");
		namespace.getAsJsonArray("rules").get(0).getAsJsonObject().add("geometry", GSON.fromJson(
			"{\"operator\":\"OBSERVED_API_OCCUPIED_BOUNDS_PROXIMITY\",\"scope\":\"POSSIBLE_PROXIMITY_ASSOCIATION\",\"minChebyshevTiles\":0,\"maxChebyshevTiles\":1}", JsonObject.class));
		ObservedNpcContactHintResolver resolver = resolver(namespace, frame(npc().build()));
		for (int[] point : new int[][]{{100,200},{102,202},{104,204},{99,199},{105,205},{100,205}})
		{
			ObservedMechanismResolver.Result result = resolver.resolve(hit().toBuilder().impactX(point[0]).impactY(point[1]).build());
			assertEquals(1, result.getContributions().size());
			assertTrue(result.isUnidentifiedPossible());
			SourceEvidenceResolver.Hypothesis hint = result.getContributions().get(0).getHypothesis();
			assertEquals("NPC_PROXIMITY_ASSOCIATION", hint.getMechanism());
			assertTrue(hint.isCausalAssociationOnly());
			assertEquals("POSSIBLE", hint.getCandidate().getStrength());
			assertEquals(REF, hint.getCandidate().getActorRef());
			assertNull(hint.getConstraints());
			assertNull(hint.getNumericObservations());
			assertNull(hint.getCandidate().getFamilyKey());
		}
		assertTrue(resolver.resolve(hit().toBuilder().impactX(106).impactY(205).build()).getContributions().isEmpty());
		assertTrue(resolver.resolve(hit().toBuilder().impactTick(6).build()).getContributions().isEmpty());
	}

	@Test
	public void zeroMinimumDoesNotRelaxNativeScopeObservedOutcomeOrSourceAreaRequirements() throws Exception
	{
		JsonObject namespace = namespace(ID, "Unseen body");
		namespace.getAsJsonArray("rules").get(0).getAsJsonObject().add("geometry", GSON.fromJson(
			"{\"operator\":\"OBSERVED_API_OCCUPIED_BOUNDS_PROXIMITY\",\"scope\":\"POSSIBLE_PROXIMITY_ASSOCIATION\",\"minChebyshevTiles\":0,\"maxChebyshevTiles\":1}", JsonObject.class));
		for (NpcState source : Arrays.asList(npc().worldAreaKnown(false).build(),
			npc().worldAreaWorldViewKnown(false).build(), npc().worldAreaWorldViewId(8).build(),
			npc().normalWorldContextObserved(false).build(), npc().modelOverridesPresent(true).build()))
		{
			ObservedMechanismResolver.Result result = resolver(namespace, frame(source)).resolve(hit());
			assertTrue(result.getContributions().isEmpty());
			assertTrue(result.isUnidentifiedPossible());
		}
		ObservedNpcContactHintResolver resolver = resolver(namespace, frame(npc().build()));
		for (DamageRecord impact : Arrays.asList(hit().toBuilder().impactWorldViewId(8).build(),
			hit().toBuilder().impactPlane(1).build(), hit().toBuilder().impactSceneKey("foreign").build(),
			hit().toBuilder().impactPositionKnown(false).build(), hit().toBuilder().impactWorldViewId(null).build(),
			hit().toBuilder().hitsplatType(5).build(), hit().toBuilder().hitsplatType(3).build(),
			hit().toBuilder().hitsplatType(999999).build(), hit().toBuilder().hitsplatTypeKnown(false).build(),
			hit().toBuilder().defenderRef("npc:rival").build()))
			assertTrue(resolver.resolve(impact).getContributions().isEmpty());
	}

	@Test
	public void zeroMinimumKeepsClosedRangeLimitsAndDoesNotCreateAWholeSceneFootprint() throws Exception
	{
		for (int[] range : new int[][]{{0,0},{0,16}})
		{
			JsonObject namespace = namespace(ID, "Unseen body");
			JsonObject geometry = GSON.fromJson("{\"operator\":\"OBSERVED_API_OCCUPIED_BOUNDS_PROXIMITY\",\"scope\":\"POSSIBLE_PROXIMITY_ASSOCIATION\"}", JsonObject.class);
			geometry.addProperty("minChebyshevTiles", range[0]); geometry.addProperty("maxChebyshevTiles", range[1]);
			namespace.getAsJsonArray("rules").get(0).getAsJsonObject().add("geometry", geometry);
			ObservedNpcContactHintResolver resolver = resolver(namespace, frame(npc().build()));
			assertEquals(1, resolver.resolve(hit()).getContributions().size());
			assertEquals(1, resolver.resolve(hit().toBuilder().impactX(104 + range[1]).build()).getContributions().size());
			assertTrue(resolver.resolve(hit().toBuilder().impactX(105 + range[1]).build()).getContributions().isEmpty());
		}
		for (int[] range : new int[][]{{-1,1},{0,-1},{2,1},{0,17}})
		{
			JsonObject namespace = namespace(ID, "Unseen body");
			JsonObject geometry = GSON.fromJson("{\"operator\":\"OBSERVED_API_OCCUPIED_BOUNDS_PROXIMITY\",\"scope\":\"POSSIBLE_PROXIMITY_ASSOCIATION\"}", JsonObject.class);
			geometry.addProperty("minChebyshevTiles", range[0]); geometry.addProperty("maxChebyshevTiles", range[1]);
			namespace.getAsJsonArray("rules").get(0).getAsJsonObject().add("geometry", geometry);
			ObservedMechanismResolver.Result result = resolver(namespace, frame(npc().build())).resolve(hit());
			assertTrue(result.getContributions().isEmpty());
			assertTrue(result.isUnidentifiedPossible());
		}
	}

	@Test
	public void everyCompiledCurrentBodyRuleReachesTheSameRuntimeAdapter() throws Exception
	{
		CombatCatalog catalog = DamageMap.load(GSON).catalog(); JsonObject namespace = catalog.observedNpcContactHints();
		assertEquals(17, namespace.getAsJsonArray("rules").size()); int ids = 0;
		for (JsonElement value : namespace.getAsJsonArray("rules"))
		{
			JsonObject rule = value.getAsJsonObject();
			for (JsonElement id : rule.getAsJsonArray("npcIds"))
			{
				NpcState npc = npc().npcId(id.getAsInt()).name(rule.get("name").getAsString()).build();
				ObservedNpcContactHintResolver adapter = new ObservedNpcContactHintResolver(namespace, Collections.singletonMap(5, Collections.singletonList(frame(npc))), catalog);
				DamageRecord probe = "OBSERVED_API_OCCUPIED_BOUNDS_PROXIMITY".equals(rule.getAsJsonObject("geometry").get("operator").getAsString())
					? hit().toBuilder().impactX(105).impactY(205).build() : hit();
				assertEquals(rule.get("key").getAsString(), 1, adapter.resolve(probe).getContributions().size()); ids++;
			}
		}
		assertEquals(28, ids);
	}
}
