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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.DeathRecordingV2;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.SceneData;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResolverV1Test
{
	private final Gson gson = new Gson();

	@Test public void sharedCrossLanguageConformance() throws Exception
	{
		JsonObject fixture;
		try (InputStreamReader reader = new InputStreamReader(getClass().getResourceAsStream("/resolver-v1-conformance.json"),StandardCharsets.UTF_8))
		{ fixture = gson.fromJson(reader,JsonObject.class); }
		for (JsonElement value : fixture.getAsJsonArray("cases"))
		{
			JsonObject test = value.getAsJsonObject(), expected = test.getAsJsonObject("expected");
			JsonObject result = new ResolverV1(test.getAsJsonObject("catalog")).resolve(test.getAsJsonObject("input"));
			String id = test.get("id").getAsString(); JsonObject match = null;
			for (JsonElement row : result.getAsJsonArray("candidates"))
				if (expected.has("ruleId") && expected.get("ruleId").equals(row.getAsJsonObject().get("ruleId"))) match = row.getAsJsonObject();
			if (expected.has("ruleId"))
			{
				assertNotNull(id+": missing declared rule",match);
				assertEquals(id,expected.get("status"),match.get("status"));
				if (expected.has("maximum")) assertEquals(id,0,expected.get("maximum").getAsBigDecimal().compareTo(match.get("maximum").getAsBigDecimal()));
				if (expected.has("maximumAbsent")) assertFalse(id,match.has("maximum"));
				if (expected.has("exclusionScope"))
				{
					if (expected.get("exclusionScope").isJsonNull()) assertTrue(id,!match.has("exclusionScope") || match.get("exclusionScope").isJsonNull());
					else assertEquals(id,expected.get("exclusionScope"),match.get("exclusionScope"));
				}
			}
			if (expected.has("absentRuleIds")) for (JsonElement absent : expected.getAsJsonArray("absentRuleIds"))
				for (JsonElement row : result.getAsJsonArray("candidates")) assertNotEquals(id,absent,row.getAsJsonObject().get("ruleId"));
			assertTrue(id,result.get("unidentifiedPossible").getAsBoolean());
		}
	}

	@Test public void realBundledOrdinaryProfileReachesFrozenCallbackAndPreservesRawHp()
	{
		ResolverV1 engine = ResolverV1.load(gson); assertTrue(engine.isAvailable()); assertTrue(engine.ordinaryOnly(2045));
		DeathRecordingV2 before = recording(2045,90,"Snakeling",16,20,true,false,0);
		DeathRecordingV2 after = new ResolverV1Recording(engine,gson,before).enrich(before);
		DamageRecord hit = after.getEvents().getDamageEvents().get(0);
		assertEquals(20,hit.getAmount()); assertEquals(16,hit.getHitsplatType()); assertEquals(Long.valueOf(20),hit.getObservationSequence());
		assertEquals("minion",before.getEvents().getDamageEvents().get(0).getAttackerName());
		assertEquals("UNIDENTIFIED",hit.getSourceResolution().getStatus());
		assertTrue(hit.getSourceResolution().getCandidates().isEmpty());
		assertTrue(hit.getSourceResolution().getExcludedCandidates().stream().anyMatch(c -> c.getReasons().contains("MAXIMUM_EXCEEDED")));
		assertSame(before.getEvents().getObservations(),after.getEvents().getObservations());
	}

	@Test public void allBundledSourceBoundsHaveSyntheticApplicabilityWitnesses() throws Exception
	{
		JsonObject fixture;
		try (InputStreamReader reader = new InputStreamReader(getClass().getResourceAsStream("/resolver-source-bounds.synthetic.json"),StandardCharsets.UTF_8))
		{ fixture = gson.fromJson(reader,JsonObject.class); }
		ResolverV1 engine = ResolverV1.load(gson);
		assertEquals("synthetic_qualification_witnesses_not_gameplay_proof",fixture.get("purpose").getAsString());
		assertEquals("resolver-v1:sha256:"+fixture.get("rulesSha256").getAsString(),engine.id());
		assertTrue(fixture.get("sourceBoundCount").getAsInt()>0); assertTrue(fixture.getAsJsonArray("cases").size()>0);
		for (JsonElement value : fixture.getAsJsonArray("cases"))
		{
			JsonObject test = value.getAsJsonObject(); String id = test.get("id").getAsString();
			JsonObject result = engine.resolve(test.getAsJsonObject("input"));
			assertTrue(id,result.getAsJsonArray("candidates").size()>0);
			boolean possible = false;
			for (JsonElement candidate : result.getAsJsonArray("candidates"))
			{
				JsonObject row = candidate.getAsJsonObject(); possible |= "POSSIBLE".equals(row.get("status").getAsString());
				if ("whole_source_excluded".equals(test.get("expected").getAsString()))
				{
					assertEquals(id,"EXCLUDED",row.get("status").getAsString());
					assertEquals(id,"ALL_DIRECT_HP_COMPONENTS",row.get("exclusionScope").getAsString());
				}
				if ("missing_override_witness".equals(test.get("scenario").getAsString()) || "nonordinary_type".equals(test.get("scenario").getAsString()))
					for (JsonElement evidence : row.getAsJsonArray("evidence")) assertFalse(id,evidence.getAsString().startsWith("SOURCE_BOUND:"));
			}
			if ("possible".equals(test.get("expected").getAsString())) assertTrue(id,possible);
		}
	}

	@Test public void atCapAndUnknownModeOrOverridePreserveMinionPossibility()
	{
		ResolverV1 engine = ResolverV1.load(gson);
		for (DeathRecordingV2 input : Arrays.asList(recording(2045,90,"Snakeling",16,15,true,false,0),recording(2046,90,"Snakeling",16,13,true,false,0),
			recording(2045,90,"Snakeling",16,20,false,false,0),recording(2045,90,"Snakeling",16,20,true,true,0)))
		{
			SourceResolution resolution = new ResolverV1Recording(engine,gson,input).enrich(input).getEvents().getDamageEvents().get(0).getSourceResolution();
			assertFalse(resolution.getCandidates().isEmpty()); assertTrue(resolution.isUnidentifiedPossible());
		}
	}

	@Test public void arbitraryUnknownNpcStillUsesObservedActivityAndForeignScopeDoesNotBecomeContact()
	{
		ResolverV1 engine = ResolverV1.load(gson);
		DeathRecordingV2 unknown = recording(2000000000,77,"Unfamiliar actor",16,20,true,false,0);
		SourceResolution actual = new ResolverV1Recording(engine,gson,unknown).enrich(unknown).getEvents().getDamageEvents().get(0).getSourceResolution();
		assertTrue(actual.getCandidates().stream().anyMatch(c -> "Unfamiliar actor".equals(c.getName())));
		assertTrue(actual.isUnidentifiedPossible());
		DeathRecordingV2 foreign = recording(2045,90,"Snakeling",16,20,true,false,17);
		JsonObject normalized = new ResolverV1Recording(engine,gson,foreign).normalize(foreign.getEvents().getDamageEvents().get(0));
		JsonObject result = engine.resolve(normalized);
		assertTrue(result.getAsJsonArray("candidates").size() > 0);
		for (JsonElement row : result.getAsJsonArray("candidates")) assertEquals("EXCLUDED",row.getAsJsonObject().get("status").getAsString());
	}

	@Test public void sameObservationIsNotConsumedOrTurnedIntoExclusiveJointAssignment()
	{
		JsonObject catalog = gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[],specialRules:[]}",JsonObject.class);
		JsonObject input = gson.fromJson("{hit:{amount:9,recipientRef:'player'},cues:[{key:'one',pattern:'projectile_impact',source:{kind:'npc',id:123,actorRef:'actual'}}]}",JsonObject.class);
		java.util.List<JsonObject> results = new ResolverV1(catalog).resolveBatch(Arrays.asList(input,input));
		assertEquals(2,results.size());
		for (JsonObject result : results)
		{
			assertEquals(1,result.getAsJsonArray("candidates").size());
			assertTrue(result.toString().contains("SHARED_OBSERVATION_ASSIGNMENT_UNESTABLISHED"));
			assertTrue(result.get("unidentifiedPossible").getAsBoolean());
		}
	}

	@Test public void absentTypeFlagUsesOnlyExactOriginalCallbackWhileExplicitFalseRemainsUnknown()
	{
		ResolverV1 engine = ResolverV1.load(gson);
		DeathRecordingV2 original = recording(2045,90,"Snakeling",5,1,true,false,0);
		DamageRecord hit = original.getEvents().getDamageEvents().get(0);
		DeathRecordingV2 absent = replaceHit(original,hit.toBuilder().hitsplatTypeKnown(null).build());
		JsonObject normalized = new ResolverV1Recording(engine,gson,absent).normalize(absent.getEvents().getDamageEvents().get(0));
		assertEquals(5,normalized.getAsJsonObject("hit").get("hitsplatType").getAsInt());
		SourceResolution result = new ResolverV1Recording(engine,gson,absent).enrich(absent).getEvents().getDamageEvents().get(0).getSourceResolution();
		assertFalse(result.getCandidates().stream().anyMatch(c -> Integer.valueOf(2045).equals(c.getNpcId())));
		assertTrue(result.getCandidates().stream().anyMatch(c -> "Venom".equals(c.getName())));
		assertEquals(1,absent.getEvents().getDamageEvents().get(0).getAmount());
		DeathRecordingV2 unknown = replaceHit(original,hit.toBuilder().hitsplatTypeKnown(false).build());
		assertFalse(new ResolverV1Recording(engine,gson,unknown).normalize(unknown.getEvents().getDamageEvents().get(0)).getAsJsonObject("hit").has("hitsplatType"));
	}

	@Test public void originalContactMasksLegacyDefinitionFamilyWithoutBorrowingReceiverAsEmitter()
	{
		JsonObject catalog = gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[],specialRules:[{id:'contact',label:'Synthetic cloud',pattern:'contact_hazard',source:{kind:'object',ids:[900301]},timing:{anchor:'contact',authority:'unknown'},geometry:{shape:'unknown',anchor:'source_position',authority:'reference_only'},recipients:'within_area',calculationTick:'unknown',outcome:{surface:'hp_or_block',hitsplatTypeIds:[5],signatureComplete:true,signatureAuthority:'verified'},ordinaryAttacksContinue:true,scopePolicy:'same_native_scope',references:[{source:'synthetic',claim:'test'}]}]}",JsonObject.class);
		ResolverV1 engine = new ResolverV1(catalog);
		DeathRecordingV2 input = recording(900201,90,"Rival",12,0,true,false,0);
		DamageRecord original = input.getEvents().getDamageEvents().get(0);
		SourceResolution.Candidate cloud = SourceResolution.Candidate.builder().key("old-cloud").familyKey("object-definition:900301")
			.name("Synthetic cloud").sceneKey("room").worldViewId(0).plane(0).build();
		Map<String,Object> contact = map("kind","OBJECT_SURFACE","objectId",900301,"name","Synthetic cloud","worldViewId",0,"plane",0,
			"observedCycle",301,"observationStage","HITSPLAT_CALLBACK","contactWorldX",3200,"contactWorldY",3200,"damaging",true);
		DamageRecord hit = original.toBuilder().hitsplatTypeKnown(null).observedHazardContacts(Collections.singletonList(contact))
			.sourceResolution(original.getSourceResolution().toBuilder().candidates(Arrays.asList(original.getSourceResolution().getCandidates().get(0),cloud)).build()).build();
		input = replaceHit(input,hit);
		JsonObject normalized = new ResolverV1Recording(engine,gson,input).normalize(hit);
		for (JsonElement element : normalized.getAsJsonArray("cues"))
			if ("contact_hazard".equals(element.getAsJsonObject().get("pattern").getAsString())) assertFalse(element.getAsJsonObject().has("position"));
		SourceResolution result = new ResolverV1Recording(engine,gson,input).enrich(input).getEvents().getDamageEvents().get(0).getSourceResolution();
		assertFalse(result.getCandidates().stream().anyMatch(c -> "object-definition:900301".equals(c.getFamilyKey())));
		assertTrue(result.getCandidates().stream().anyMatch(c -> Integer.valueOf(900201).equals(c.getNpcId())));
		assertTrue(result.getExcludedCandidates().stream().anyMatch(c -> c.getReasons().contains("SIGNATURE_MISMATCH")));
	}

	@Test public void graphicCreatedThenGoneStillHasExactOriginalNativeAnchor()
	{
		ResolverV1 engine = new ResolverV1(gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[],specialRules:[{id:'graphic',label:'Transient impact',pattern:'area_impact',source:{kind:'graphic',ids:[900401]},signals:{graphicIds:[900401]},timing:{anchor:'effect_event',authority:'unknown'},geometry:{shape:'point',anchor:'effect_position',authority:'verified'},recipients:'within_area',calculationTick:'unknown',outcome:{surface:'hp_or_block'},ordinaryAttacksContinue:true,scopePolicy:'same_native_scope',references:[{source:'synthetic',claim:'test'}]}]}",JsonObject.class));
		DeathRecordingV2 input = recording(900201,77,"Rival",16,9,true,false,0);
		ObservedEvent creation = ObservedEvent.builder().type("GRAPHICS_OBJECT_CREATED").sequence(18).tick(10).gameCycle(299).sceneKey("room")
			.data(map("id",900401,"scopeKnown",true,"positionScopeKnown",true,"worldViewId",0,"localWorldViewId",0,"plane",0,"localX",1280,"localY",1280)).build();
		java.util.List<ObservedEvent> observations = new java.util.ArrayList<>(input.getEvents().getObservations()); observations.add(creation);
		input = input.toBuilder().scenes(Collections.singletonList(SceneData.builder().sceneKey("room").worldViewId(0).plane(0).baseX(3190).baseY(3190).width(104).height(104).build()))
			.events(DeathRecordingV2.Events.builder().damageEvents(input.getEvents().getDamageEvents()).observations(observations).build()).build();
		SourceResolution result = new ResolverV1Recording(engine,gson,input).enrich(input).getEvents().getDamageEvents().get(0).getSourceResolution();
		assertTrue(result.getCandidates().stream().anyMatch(c -> "Transient impact".equals(c.getName())));
		assertTrue(result.isUnidentifiedPossible()); assertNull(input.getTicks());
	}

	@Test public void retainedDeclaredDeathUsesOriginalClockAndDoesNotCreateUnlistedExplosions()
	{
		ResolverV1 engine = new ResolverV1(gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[],specialRules:[{id:'death',label:'Terminal clue',pattern:'delayed_effect',source:{kind:'npc',ids:[900201]},signals:{lifecycleEvents:['actor_death']},timing:{anchor:'lifecycle_event',clock:'game_tick',minimumOffset:0,maximumOffset:3,authority:'correlation_window'},geometry:{shape:'unknown',anchor:'source_position',authority:'reference_only'},recipients:'within_area',calculationTick:'unknown',outcome:{surface:'hp_or_block'},ordinaryAttacksContinue:true,scopePolicy:'same_native_scope',references:[{source:'synthetic',claim:'test'}]}]}",JsonObject.class));
		DeathRecordingV2 input = recording(900201,77,"Terminal source",16,9,true,false,0);
		Map<String,Object> originalActor = input.getEvents().getObservations().get(0).getData();
		ObservedEvent death = ObservedEvent.builder().type("ACTOR_DEATH").actorRef("terminal:actual").sequence(10).tick(8).gameCycle(240).sceneKey("room").data(originalActor).build();
		java.util.List<ObservedEvent> observations = new java.util.ArrayList<>(input.getEvents().getObservations()); observations.add(death);
		input = input.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(input.getEvents().getDamageEvents()).observations(observations).build()).build();
		JsonObject result = engine.resolve(new ResolverV1Recording(engine,gson,input).normalize(input.getEvents().getDamageEvents().get(0)));
		assertTrue(result.getAsJsonArray("candidates").toString().contains("\"ruleId\":\"death\""));
		assertTrue(result.get("unidentifiedPossible").getAsBoolean());
		JsonObject absentCatalog = gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[],specialRules:[]}",JsonObject.class);
		JsonObject unlisted = new ResolverV1(absentCatalog).resolve(new ResolverV1Recording(new ResolverV1(absentCatalog),gson,input).normalize(input.getEvents().getDamageEvents().get(0)));
		for (JsonElement row : unlisted.getAsJsonArray("candidates")) assertNotEquals("observed:delayed_effect",row.getAsJsonObject().get("ruleId").getAsString());
	}

	private static DeathRecordingV2 replaceHit(DeathRecordingV2 before,DamageRecord hit)
	{ return before.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(hit)).observations(before.getEvents().getObservations()).build()).build(); }

	@Test public void exactNpcContactUsesOriginalSourceAnchorAndDoesNotBorrowLaterPose()
	{
		ResolverV1 engine = new ResolverV1(gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[],specialRules:[{id:'contact.synthetic',label:'Contact component',pattern:'contact_hazard',source:{kind:'npc',ids:[900201],combatLevels:[77]},timing:{anchor:'contact',authority:'unknown'},geometry:{shape:'point',anchor:'source_position',authority:'verified'},recipients:'within_area',calculationTick:'unknown',outcome:{surface:'hp_or_block',maximum:{kind:'fixed_max',value:1,authority:'verified',when:[{fact:'world_mode',op:'equals',value:'NORMAL',at:'source_event'}]}},ordinaryAttacksContinue:true,scopePolicy:'same_native_scope',references:[{source:'synthetic',claim:'test'}]}]}",JsonObject.class));
		for (boolean original : new boolean[] {false,true})
		{
			DeathRecordingV2 input = recording(900201,77,"Synthetic hazard",16,9,true,false,0);
			DamageRecord hit = input.getEvents().getDamageEvents().get(0);
			Map<String,Object> contact = map("kind","NPC_CONTACT","npcId",900201,"combatLevel",77,"normalWorldContextObserved",true,
				"actorRef","actual:minion","observedName","Synthetic hazard","worldX",3200,"worldY",3200,"plane",0,"observedSize",1,
				"contactPredicate","SAME_SERVER_TILE","footprintWidth",1,"footprintHeight",1,"worldViewId",0,"sceneKey","room",
				"scopeEvidence","NPC_WORLD_VIEW_READ","observedCycle",301,"observationStage",original ? "HITSPLAT_CALLBACK" : "DEFERRED_RESOLUTION",
				"defenderPositionEvidence","SERVER_REPORTED_TILE","sourceEligibility","ELIGIBLE_REFERENCE_SURFACE","damaging",true);
			hit = hit.toBuilder().observedHazardContacts(Collections.singletonList(contact)).sourceResolution(hit.getSourceResolution().toBuilder().candidates(Collections.emptyList()).build()).build();
			NpcState later = NpcState.builder().actorRef("actual:minion").npcId(900201).name("Synthetic hazard").combatLevel(77)
				.worldViewId(0).planeKnown(true).plane(0).worldPositionKnown(true).worldX(3210).worldY(3210).worldAreaKnown(true)
				.worldAreaWorldViewKnown(true).worldAreaWorldViewId(0).worldArea(Collections.singletonMap("minX",3210)).build();
			input = input.toBuilder().ticks(Collections.singletonList(WorldSnapshot.builder().tick(10).sceneKey("room").worldViewId(0).plane(0)
				.nativeContextKnown(true).gameCycleKnown(true).gameCycle(302).npcs(Collections.singletonList(later)).build()))
				.events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(hit)).observations(Collections.singletonList(input.getEvents().getObservations().get(1))).build()).build();
			JsonObject normalized = new ResolverV1Recording(engine,gson,input).normalize(hit);
			boolean found = false;
			for (JsonElement value : normalized.getAsJsonArray("cues"))
			{
				JsonObject cue = value.getAsJsonObject();
				if (cue.get("key").getAsString().startsWith("original-npc-contact:")) { found = true; assertEquals(3200,cue.getAsJsonObject("position").get("x").getAsInt()); assertFalse(cue.has("contactObserved")); }
				if (cue.get("key").getAsString().startsWith("body:")) assertFalse(cue.get("componentBound").getAsBoolean());
			}
			assertEquals(original,found);
			JsonObject results = engine.resolve(normalized);
			assertEquals(original,results.getAsJsonArray("candidates").toString().contains("MAXIMUM_EXCEEDED"));
			assertSame(input.getEvents().getObservations(),new ResolverV1Recording(engine,gson,input).enrich(input).getEvents().getObservations());
		}
	}

	@Test public void encounterContextRequiresTheActualSourceClockAndNativeFrame()
	{
		ResolverV1 engine = referenceEngine(false,false);
		for (int frameCycle : new int[] {299,300,301})
		{
			DeathRecordingV2 input = recording(900201,77,"Synthetic actor",16,9,true,false,0);
			WorldSnapshot frame = WorldSnapshot.builder().tick(10).gameCycle(frameCycle).gameCycleKnown(true).nativeContextKnown(true)
				.sceneKey("room").worldViewId(0).plane(0).context(com.tickreplaylogger.model.world.ContextBlock.builder().activity("toa").build()).build();
			input = input.toBuilder().ticks(Collections.singletonList(frame)).build();
			JsonObject normalized = new ResolverV1Recording(engine,gson,input).normalize(input.getEvents().getDamageEvents().get(0));
			for (JsonElement value : normalized.getAsJsonArray("cues"))
			{
				JsonObject cue = value.getAsJsonObject();
				if ("callback:19".equals(cue.get("key").getAsString()))
				{
					assertEquals(frameCycle==300,cue.getAsJsonObject("facts").getAsJsonObject("source_event").has("encounter_activity"));
					assertFalse(cue.getAsJsonObject("facts").getAsJsonObject("source_event").has("encounter_mode"));
				}
			}
		}
	}

	@Test public void ownedApiProjectileKeepsItsSourceWhenNoNpcSnapshotExists()
	{
		ResolverV1 engine = new ResolverV1(gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[],specialRules:[]}",JsonObject.class));
		DeathRecordingV2 input = recording(900201,77,"Rival",16,9,true,false,0); DamageRecord hit = input.getEvents().getDamageEvents().get(0);
		com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation owner = com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation.builder()
			.sourceEvidence("ACTOR").sourceActorKnown(true).sourceNameKnown(true).sourceObservedName("Ephemeral actor").sourceRef("owned:ephemeral")
			.sourceNpcId(900202).sourceCombatLevel(77).sourceWorldViewId(0).sourcePlane(0).sourceNormalWorldContextObserved(true).observedTick(9).observedCycle(270).build();
		com.tickreplaylogger.model.world.ProjectileState projectile = com.tickreplaylogger.model.world.ProjectileState.builder().observationId(7L).projectileId(900501)
			.targetRef("player").endCycle(301).estimatedImpactTick(10).worldViewId(0).plane(0).samplePlaneKnown(true).destinationKnown(true)
			.endWorldX(3200).endWorldY(3200).targetWorldViewId(0).destinationPlane(0)
			.sourceHistory(com.tickreplaylogger.model.world.ProjectileSourceHistory.builder().firstObservedSource(owner).build()).build();
		ObservedEvent observed = ObservedEvent.builder().type("PROJECTILE_MOVED").sceneKey("room").tick(10).gameCycle(300).sequence(19).data(map("projectile",projectile)).build();
		hit = hit.toBuilder().sourceResolution(hit.getSourceResolution().toBuilder().candidates(Collections.emptyList()).build()).build();
		input = input.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(hit))
			.observations(Arrays.asList(observed,input.getEvents().getObservations().get(1))).build()).build();
		JsonObject normalized = new ResolverV1Recording(engine,gson,input).normalize(hit);
		for (JsonElement value : normalized.getAsJsonArray("cues"))
		{
			JsonObject cue = value.getAsJsonObject(); if (!cue.has("apiSourceObserved")) continue;
			assertTrue(cue.get("identityOnly").getAsBoolean()); assertFalse(cue.get("numericEligible").getAsBoolean()); assertFalse(cue.has("endpoint"));
			assertFalse(cue.getAsJsonObject("source").getAsJsonObject("scope").has("sceneKey"));
		}
		SourceResolution resolution = new ResolverV1Recording(engine,gson,input).enrich(input).getEvents().getDamageEvents().get(0).getSourceResolution();
		assertTrue(resolution.getCandidates().stream().anyMatch(c -> "owned:ephemeral".equals(c.getActorRef()) && "Ephemeral actor".equals(c.getName())));
		assertTrue(resolution.isUnidentifiedPossible()); assertEquals(9,input.getEvents().getDamageEvents().get(0).getAmount());
	}

	@Test public void originalApiMetadataAndObservedFlightSurviveWithoutAnNpcPose()
	{
		ResolverV1 engine=referenceEngine(false,false);
		for (boolean frameOnly : new boolean[] {false,true}) for (boolean knownOverride : new boolean[] {false,true})
		{
			DeathRecordingV2 input=apiRecording(frameOnly,false,knownOverride); DamageRecord hit=input.getEvents().getDamageEvents().get(0);
			JsonObject normalized=new ResolverV1Recording(engine,gson,input).normalize(hit); int count=0;
			for (JsonElement value : normalized.getAsJsonArray("cues"))
			{
				JsonObject cue=value.getAsJsonObject(); if (!cue.has("apiSourceObserved")) continue; count++;
				assertEquals("HP_DURING_OBSERVED_FLIGHT",cue.get("clockEvidence").getAsString());
				assertEquals("room",cue.getAsJsonObject("source").getAsJsonObject("scope").get("sceneKey").getAsString());
				assertFalse(cue.has("identityOnly"));
				assertEquals(!knownOverride,cue.has("numericEligible") && !cue.get("numericEligible").getAsBoolean());
				assertEquals(knownOverride,cue.getAsJsonObject("facts").getAsJsonObject("source_event").has("source_model_overrides"));
			}
			assertEquals(2,count); assertEquals(9,hit.getAmount());
		}
	}

	@Test public void changedLaterProjectileHistoryKeepsOnlyOriginalOwnerIdentity()
	{
		ResolverV1 engine=referenceEngine(false,false); DeathRecordingV2 input=apiRecording(false,true,true);
		DamageRecord hit=input.getEvents().getDamageEvents().get(0); JsonObject normalized=new ResolverV1Recording(engine,gson,input).normalize(hit);
		int count=0;
		for (JsonElement value : normalized.getAsJsonArray("cues"))
		{
			JsonObject cue=value.getAsJsonObject(); if (!cue.has("apiSourceObserved")) continue; count++;
			assertEquals("owned:original",cue.getAsJsonObject("source").get("actorRef").getAsString());
			assertTrue(cue.get("identityOnly").getAsBoolean()); assertFalse(cue.get("componentBound").getAsBoolean());
			assertFalse(cue.get("numericEligible").getAsBoolean()); assertFalse(cue.has("endpoint"));
		}
		assertEquals(2,count);
		SourceResolution resolution=new ResolverV1Recording(engine,gson,input).enrich(input).getEvents().getDamageEvents().get(0).getSourceResolution();
		assertTrue(resolution.getCandidates().stream().anyMatch(c -> "owned:original".equals(c.getActorRef())));
		assertFalse(resolution.getCandidates().stream().anyMatch(c -> "owned:later".equals(c.getActorRef())));
		assertTrue(resolution.getCandidates().stream().anyMatch(c -> c.getEvidence().contains("ORIGINAL_API_SOURCE_HISTORY_UNSTABLE")));
	}

	private DeathRecordingV2 apiRecording(boolean frameOnly,boolean unstable,boolean knownOverride)
	{
		DeathRecordingV2 base=recording(900201,77,"Rival",16,9,true,false,0);
		com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation first=com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation.builder()
			.sourceEvidence("ACTOR").sourceActorKnown(true).sourceRef("owned:original").sourceObservedName("Original actor").sourceNameKnown(true)
			.sourceNpcId(900202).sourceCombatLevel(77).sourceSceneKey("room").sourceWorldViewId(0).sourcePlane(0)
			.sourceNormalWorldContextObserved(true).sourceModelOverridesPresent(knownOverride ? false : null).observedTick(9).observedCycle(270).build();
		com.tickreplaylogger.model.world.ProjectileState projectile=com.tickreplaylogger.model.world.ProjectileState.builder()
			.observationId(77L).projectileId(900501).sourceRef("owned:later").sourceNpcId(900203).targetRef("player").endCycle(400)
			.estimatedImpactTick(14).worldViewId(0).plane(0).samplePlaneKnown(true).destinationKnown(true).endWorldX(3200).endWorldY(3200)
			.targetWorldViewId(0).destinationPlane(0).sourceHistory(com.tickreplaylogger.model.world.ProjectileSourceHistory.builder()
				.firstObservedSource(first).definitionChanged(unstable).referenceChanged(unstable).build()).build();
		DamageRecord hit=base.getEvents().getDamageEvents().get(0).toBuilder().sourceResolution(base.getEvents().getDamageEvents().get(0)
			.getSourceResolution().toBuilder().candidates(Collections.emptyList()).build()).build();
		java.util.List<ObservedEvent> observations=new java.util.ArrayList<>(); observations.add(base.getEvents().getObservations().get(1));
		if (!frameOnly) observations.add(ObservedEvent.builder().type("PROJECTILE_MOVED").tick(10).gameCycle(300).sequence(19).sceneKey("room").data(map("projectile",projectile)).build());
		WorldSnapshot frame=WorldSnapshot.builder().tick(10).gameCycle(300).gameCycleKnown(true).nativeContextKnown(true).worldViewId(0).plane(0)
			.sceneKey("room").projectiles(Collections.singletonList(projectile)).build();
		return base.toBuilder().ticks(Collections.singletonList(frame)).events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(hit)).observations(observations).build()).build();
	}

	@Test public void identifiedComponentRejectedByVerifiedTimingCannotReviveAsUnclassifiedActivity()
	{
		ResolverV1 engine=new ResolverV1(gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[],specialRules:[{id:'timed',pattern:'projectile_impact',source:{kind:'npc',ids:[900202]},signals:{projectileIds:[900501]},timing:{anchor:'projectile_event',clock:'game_tick',minimumOffset:0,maximumOffset:0,authority:'verified'},geometry:{shape:'unknown',anchor:'source_position',authority:'reference_only'},recipients:'direct_target',outcome:{surface:'hp_or_block'},scopePolicy:'same_native_scope'}]}",JsonObject.class));
		DeathRecordingV2 input=apiRecording(false,false,true); JsonObject result=engine.resolve(new ResolverV1Recording(engine,gson,input).normalize(input.getEvents().getDamageEvents().get(0)));
		boolean rejected=false;
		for (JsonElement value : result.getAsJsonArray("candidates"))
		{
			JsonObject row=value.getAsJsonObject(); assertNotEquals("observed:projectile_impact",row.get("ruleId").getAsString());
			if ("timed".equals(row.get("ruleId").getAsString())) { rejected=true; assertEquals("EXCLUDED",row.get("status").getAsString()); }
		}
		assertTrue(rejected);
	}

	@Test public void passiveEmitterUsesOnlyExactOriginalNativePresenceWithoutInventingAnAttack()
	{
		ResolverV1 engine = presenceEngine();
		for (String variation : Arrays.asList("original", "later", "foreign", "missing_actor", "wrong_cycle"))
		{
			DeathRecordingV2 input = emitterRecording(variation, null);
			DamageRecord hit = input.getEvents().getDamageEvents().get(0);
			JsonObject normalized = new ResolverV1Recording(engine, gson, input).normalize(hit);
			JsonObject result = engine.resolve(normalized);
			assertEquals(variation, "original".equals(variation) ? 1 : 0, result.getAsJsonArray("candidates").size());
			for (JsonElement value : result.getAsJsonArray("candidates"))
			{
				JsonObject row = value.getAsJsonObject();
				assertEquals("presence.synthetic", row.get("ruleId").getAsString());
				assertFalse(row.has("maximum"));
				assertFalse(row.has("label"));
				assertFalse(row.get("componentQualified").getAsBoolean());
				assertTrue(row.getAsJsonArray("evidence").toString().contains("PASSIVE_SOURCE_PRESENCE_ONLY"));
			}
			assertTrue(result.get("unidentifiedPossible").getAsBoolean());
			assertEquals(9, hit.getAmount());
		}
	}

	@Test public void independentPassivePresenceSurvivesOrdinaryOnlyButNotQualifiedWholeSourceExclusion()
	{
		ResolverV1 engine = presenceEngine();
		for (String scope : Arrays.asList("ORDINARY_ATTACK", "ALL_DIRECT_HP_COMPONENTS"))
		{
			DeathRecordingV2 input = emitterRecording("original", scope);
			SourceResolution resolution = new ResolverV1Recording(engine, gson, input).enrich(input).getEvents().getDamageEvents().get(0).getSourceResolution();
			assertEquals(scope, "ORDINARY_ATTACK".equals(scope), !resolution.getCandidates().isEmpty());
			assertTrue(resolution.isUnidentifiedPossible());
			assertFalse(resolution.getExcludedCandidates().isEmpty());
			for (SourceResolution.Candidate candidate : resolution.getCandidates())
			{
				assertEquals("Synthetic actor", candidate.getName());
			}
		}
	}

	@Test public void smallCaptureIndexContainsOnlyItsDeclaredNpcIds()
	{
		SourceAssociationNpcIndex index = new SourceAssociationNpcIndex(gson.fromJson(
			"{schemaVersion:1,rulesSha256:'synthetic',npcIds:[900201,900202]}", JsonObject.class));
		assertTrue(index.contains(900201));
		assertFalse(index.contains(900203));
		assertFalse(new SourceAssociationNpcIndex(null).contains(900201));
		assertFalse(new SourceAssociationNpcIndex(gson.fromJson("{schemaVersion:2,npcIds:[900201]}", JsonObject.class)).contains(900201));
	}

	@Test public void currentWholeSourceExclusionCannotBeRevivedByWeakPresenceButKeepsChangedIdentity()
	{
		ResolverV1 engine = presenceEngine(true);
		for (String variation : Arrays.asList("original", "changed_name", "changed_actor"))
		{
			DeathRecordingV2 input = emitterRecording(variation, null);
			DamageRecord hit = input.getEvents().getDamageEvents().get(0).toBuilder()
				.sourceResolution(input.getEvents().getDamageEvents().get(0).getSourceResolution().toBuilder().candidates(Collections.emptyList()).build()).build();
			ObservedEvent attack = recording(900201, 77, "Synthetic actor", 16, 9, true, false, 0).getEvents().getObservations().get(0);
			input = input.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(hit))
				.observations(Arrays.asList(attack, input.getEvents().getObservations().get(0))).build()).build();
			SourceResolution result = new ResolverV1Recording(engine, gson, input).enrich(input).getEvents().getDamageEvents().get(0).getSourceResolution();
			assertEquals(variation, !"original".equals(variation), !result.getCandidates().isEmpty());
			assertTrue(result.isUnidentifiedPossible());
			assertTrue(result.getExcludedCandidates().stream().anyMatch(candidate -> "ALL_DIRECT_HP_COMPONENTS".equals(candidate.getExclusionScope())));
		}
	}

	@Test public void bundledCaptureEligibilityIndexMatchesItsResolverCatalog() throws Exception
	{
		JsonObject index;
		try (InputStreamReader reader = new InputStreamReader(getClass().getResourceAsStream(
			"/com/tickreplaylogger/source-association-npc-ids.json"), StandardCharsets.UTF_8))
		{
			index = gson.fromJson(reader, JsonObject.class);
		}
		assertEquals("resolver-v1:sha256:" + index.get("rulesSha256").getAsString(), ResolverV1.load(gson).id());
	}

	private ResolverV1 presenceEngine()
	{
		return presenceEngine(false);
	}

	private ResolverV1 presenceEngine(boolean bounded)
	{
		JsonObject catalog = gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[],specialRules:[{id:'not_observed',pattern:'area_impact',source:{kind:'npc',ids:[900201]},outcome:{maximum:{kind:'fixed_max',value:1,authority:'verified'}}}],sourceAssociations:[{id:'presence.synthetic',association:'observed_native_presence',authority:'verified',source:{kind:'npc',ids:[900201]},references:[{source:'synthetic',claim:'test'}]}]}", JsonObject.class);
		if (bounded)
		{
			catalog.add("sourceBounds", gson.fromJson("[{id:'whole.synthetic',source:{kind:'npc',ids:[900201]},when:[{fact:'world_mode',op:'equals',value:'NORMAL',at:'source_event'},{fact:'source_model_overrides',op:'equals',value:false,at:'source_event'}],maximum:{kind:'fixed_max',value:1,authority:'verified'},outcome:{surface:'hp_or_block',signatureComplete:true,signatureAuthority:'verified',hitsplatTypeIds:[16]}}]", com.google.gson.JsonArray.class));
		}
		return new ResolverV1(catalog);
	}

	private DeathRecordingV2 emitterRecording(String variation, String priorScope)
	{
		DeathRecordingV2 input = recording(900201, 77, "Synthetic actor", 16, 9, true, false, 0);
		if (priorScope != null)
		{
			input = priorExcluded(input, priorScope);
		}
		DamageRecord hit = input.getEvents().getDamageEvents().get(0);
		Map<String, Object> witness = map("kind", "NPC_EMITTER", "npcId", 900201, "actorRef", "missing_actor".equals(variation) ? null : "changed_actor".equals(variation) ? "other:actor" : "actual:minion",
			"observedName", "changed_name".equals(variation) ? "Changed actor" : "Synthetic actor", "combatLevel", 77, "worldX", 3200, "worldY", 3200, "worldViewId", "foreign".equals(variation) ? 17 : 0,
			"plane", 0, "sceneKey", "room", "scopeEvidence", "NPC_WORLD_VIEW_READ", "sourceEligibility", "DECLARED_SOURCE_ASSOCIATION",
			"observedCycle", "wrong_cycle".equals(variation) ? 302 : 301, "observationStage", "later".equals(variation) ? "DEFERRED_RESOLUTION" : "HITSPLAT_CALLBACK");
		hit = hit.toBuilder().observedHazardContacts(Collections.singletonList(witness)).build();
		return input.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(hit))
			.observations(Collections.singletonList(input.getEvents().getObservations().get(1))).build()).build();
	}

	@Test public void priorQualifiedExclusionsCannotBeRevivedByReferenceOnlyActivity()
	{
		ResolverV1 engine = referenceEngine(false,false);
		for (String scope : Arrays.asList("ALL_DIRECT_HP_COMPONENTS","ORDINARY_ATTACK",null))
		{
			DeathRecordingV2 input = priorExcluded(recording(900201,77,"Synthetic actor",16,20,true,false,0),scope);
			DeathRecordingV2 output = new ResolverV1Recording(engine,gson,input).enrich(input);
			SourceResolution resolution = output.getEvents().getDamageEvents().get(0).getSourceResolution();
			assertTrue(resolution.getCandidates().isEmpty()); assertTrue(resolution.isUnidentifiedPossible());
			assertEquals(20,output.getEvents().getDamageEvents().get(0).getAmount());
			assertSame(input.getEvents().getObservations(),output.getEvents().getObservations());
			assertEquals(scope,resolution.getExcludedCandidates().get(0).getExclusionScope());
		}
	}

	@Test public void wholeActorExclusionSurvivesSignatureOnlySpecialButOrdinaryExclusionAllowsIndependentSpecial()
	{
		ResolverV1 engine = referenceEngine(true,false);
		for (String scope : Arrays.asList("ALL_DIRECT_HP_COMPONENTS","ORDINARY_ATTACK"))
		{
			DeathRecordingV2 input = priorExcluded(recording(900201,77,"Synthetic actor",16,20,true,false,0),scope);
			SourceResolution resolution = new ResolverV1Recording(engine,gson,input).enrich(input).getEvents().getDamageEvents().get(0).getSourceResolution();
			assertEquals("ORDINARY_ATTACK".equals(scope),!resolution.getCandidates().isEmpty());
			assertTrue(resolution.isUnidentifiedPossible());
		}
	}

	@Test public void priorExclusionDoesNotCrossActorFormOrNativeScope()
	{
		ResolverV1 engine = referenceEngine(false,false);
		DeathRecordingV2 base = priorExcluded(recording(900201,77,"Synthetic actor",16,20,true,false,0),"ALL_DIRECT_HP_COMPONENTS");
		DamageRecord hit = base.getEvents().getDamageEvents().get(0);
		SourceResolution.Candidate original = hit.getSourceResolution().getExcludedCandidates().get(0);
		for (SourceResolution.Candidate different : Arrays.asList(original.toBuilder().npcId(900202).build(),
			original.toBuilder().actorRef("previous-incarnation").build(),original.toBuilder().sceneKey("other-room").build(),
			original.toBuilder().worldViewId(17).build(),original.toBuilder().plane(1).build(),original.toBuilder().npcId(null).build()))
		{
			DeathRecordingV2 input = replaceHit(base,hit.toBuilder().sourceResolution(hit.getSourceResolution().toBuilder()
				.excludedCandidates(Collections.singletonList(different)).build()).build());
			assertFalse(new ResolverV1Recording(engine,gson,input).enrich(input).getEvents().getDamageEvents().get(0).getSourceResolution().getCandidates().isEmpty());
		}
	}

	@Test public void nonDamagingActivityRetiresOnlyItsLinkedOriginalActivitySupport()
	{
		ResolverV1 engine = referenceEngine(false,true);
		for (boolean linked : new boolean[] {false,true})
		{
			DeathRecordingV2 input = recording(900201,77,"Synthetic actor",16,9,true,false,0);
			DamageRecord hit = input.getEvents().getDamageEvents().get(0);
			SourceResolution.Candidate prior = hit.getSourceResolution().getCandidates().get(0).toBuilder()
				.evidence(Arrays.asList(linked ? "RECORDED_PRE_HIT_NPC_ACTIVITY;tick=10;sequence=19;cycle=300;animationId=777" : "INDEPENDENT_OBSERVED_PROJECTILE",
					"HITSPLAT_SIGNATURE_UNESTABLISHED:synthetic:ZERO_OUTCOME_DOMAIN_UNAVAILABLE")).build();
			input = replaceHit(input,hit.toBuilder().sourceResolution(hit.getSourceResolution().toBuilder().candidates(Collections.singletonList(prior)).build()).build());
			SourceResolution resolution = new ResolverV1Recording(engine,gson,input).enrich(input).getEvents().getDamageEvents().get(0).getSourceResolution();
			assertEquals(!linked,!resolution.getCandidates().isEmpty());
			assertTrue(resolution.getExcludedCandidates().stream().anyMatch(c -> c.getReasons().contains("NON_DAMAGING_ACTIVITY")));
			assertTrue(resolution.isUnidentifiedPossible());
		}
	}

	@Test public void classifiedCueDoesNotEraseAnotherDamagingActivityOfTheSameActor()
	{
		ResolverV1 engine = referenceEngine(false,true);
		DeathRecordingV2 input = recording(900201,77,"Synthetic actor",16,9,true,false,0);
		Map<String,Object> laterData = new LinkedHashMap<>(input.getEvents().getObservations().get(0).getData()); laterData.put("animationId",778);
		ObservedEvent other = ObservedEvent.builder().type("ANIMATION_CHANGED").actorRef("actual:minion").sceneKey("room")
			.tick(10).sequence(18).gameCycle(299).data(laterData).build();
		java.util.List<ObservedEvent> observations = new java.util.ArrayList<>(input.getEvents().getObservations()); observations.add(other);
		input = input.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(input.getEvents().getDamageEvents()).observations(observations).build()).build();
		SourceResolution resolution = new ResolverV1Recording(engine,gson,input).enrich(input).getEvents().getDamageEvents().get(0).getSourceResolution();
		assertFalse(resolution.getCandidates().isEmpty()); assertTrue(resolution.isUnidentifiedPossible());
	}

	@Test public void harmlessProjectileIdentityRejectsOnlyLinkedProjectileSupportWithoutInventingAnOwner()
	{
		JsonObject catalog = gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[],specialRules:[],activityClassifications:[{id:'harmless.projectile',source:{kind:'projectile',ids:[900501]},signals:{projectileIds:[900501]},role:'non_damaging',authority:'verified',references:[{source:'synthetic',claim:'test'}]}]}",JsonObject.class);
		ResolverV1 engine = new ResolverV1(catalog);
		for (boolean planeKnown : new boolean[] {false,true}) for (boolean proximity : new boolean[] {false,true}) for (int amount : new int[] {0,9})
		{
			DeathRecordingV2 input = recording(900201,77,"Synthetic actor",16,amount,true,false,0);
			DamageRecord hit = input.getEvents().getDamageEvents().get(0);
			SourceResolution.Candidate prior = hit.getSourceResolution().getCandidates().get(0).toBuilder()
				.evidence(Collections.singletonList("PROJECTILE_TIMING_CORRELATION")).build();
			Map<String,Object> raw = map("kind","PROJECTILE_CORRELATION","npcId",proximity ? null : 900201,
				"actorRef",proximity ? null : "actual:minion","projectileId",900501,"projectileObservationId",7);
			if (proximity) raw.put("sourceHistory",map("firstObservedSource",map("proximitySampleRetained",true,"proximityCandidate",
				map("actorRef","actual:minion","npcId",900201,"sceneKey","room","worldViewId",0,"plane",0))));
			hit = hit.toBuilder().attributionCandidates(Collections.singletonList(raw))
				.sourceResolution(hit.getSourceResolution().toBuilder().candidates(Collections.singletonList(prior)).build()).build();
			com.tickreplaylogger.model.world.ProjectileState projectile = com.tickreplaylogger.model.world.ProjectileState.builder()
				.projectileId(900501).observationId(7L).worldViewId(0).plane(0).samplePlaneKnown(planeKnown).endCycle(301).build();
			ObservedEvent observed = ObservedEvent.builder().type("PROJECTILE_MOVED").tick(10).sequence(19).gameCycle(300).sceneKey("room")
				.data(map("projectile",projectile)).build();
			input = input.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(hit))
				.observations(Arrays.asList(observed,input.getEvents().getObservations().get(1))).build()).build();
			SourceResolution resolution = new ResolverV1Recording(engine,gson,input).enrich(input).getEvents().getDamageEvents().get(0).getSourceResolution();
			assertEquals(!planeKnown || amount == 0,!resolution.getCandidates().isEmpty()); assertTrue(resolution.isUnidentifiedPossible());
			assertEquals(amount,input.getEvents().getDamageEvents().get(0).getAmount());
		}
	}

	@Test public void unsupportedNonNpcFamilyCannotShareAnExclusionThroughMissingSourceKeys()
	{
		ResolverV1 engine = referenceEngine(false,false);
		DeathRecordingV2 input = recording(900201,77,"Synthetic actor",net.runelite.api.HitsplatID.POISON,6,true,false,0);
		DamageRecord hit = input.getEvents().getDamageEvents().get(0);
		SourceResolution.Candidate unrelated = SourceResolution.Candidate.builder().key("unrelated-status").name("Unrelated status")
			.familyKey("unsupported-status-family").sceneKey("another-room").worldViewId(17).plane(1)
			.exclusionScope("ALL_DIRECT_HP_COMPONENTS").build();
		input = replaceHit(input,hit.toBuilder().sourceResolution(hit.getSourceResolution().toBuilder().candidates(Collections.emptyList())
			.excludedCandidates(Collections.singletonList(unrelated)).build()).build());
		SourceResolution resolution = new ResolverV1Recording(engine,gson,input).enrich(input).getEvents().getDamageEvents().get(0).getSourceResolution();
		assertTrue(resolution.getCandidates().stream().anyMatch(c -> "Poison".equals(c.getName())));
		assertTrue(resolution.isUnidentifiedPossible());
	}

	@Test public void bundledQualifiedProfilesPruneBossAndRaidInvariantWithoutBorrowingUnknownOriginalContext()
	{
		ResolverV1 engine = ResolverV1.load(gson);
		for (DeathRecordingV2 input : Arrays.asList(recording(8713,318,"Sarachnis",16,32,true,false,0),
			recording(11727,53,"Agile Scarab",16,6,true,false,0)))
		{
			SourceResolution resolution = new ResolverV1Recording(engine,gson,input).enrich(input).getEvents().getDamageEvents().get(0).getSourceResolution();
			assertTrue(resolution.getCandidates().isEmpty());
			assertTrue(resolution.getExcludedCandidates().stream().anyMatch(c -> c.getReasons().contains("MAXIMUM_EXCEEDED")));
		}
		for (DeathRecordingV2 input : Arrays.asList(recording(8713,318,"Sarachnis",16,32,false,false,0),
			recording(11727,53,"Agile Scarab",16,6,true,true,0),recording(8713,999,"Sarachnis",16,32,true,false,0)))
			assertFalse(new ResolverV1Recording(engine,gson,input).enrich(input).getEvents().getDamageEvents().get(0).getSourceResolution().getCandidates().isEmpty());
	}

	@Test public void bundledNonDamageProjectileRoleAndVerifiedExplosionEnvelopeAreExecutable()
	{
		ResolverV1 engine = ResolverV1.load(gson);
		JsonObject scope = gson.fromJson("{sceneKey:'room',worldViewId:0,plane:0}",JsonObject.class);
		JsonObject hit = gson.fromJson("{recipientRef:'player',amount:9,tick:10,cycle:301,family:'ordinary',hitsplatType:16,position:{x:3200,y:3200}}",JsonObject.class); hit.add("scope",scope);
		JsonObject projectile = gson.fromJson("{key:'actual-projectile',pattern:'projectile_impact',source:{kind:'projectile',id:1687},signals:{projectileIds:[1687]}}",JsonObject.class); projectile.getAsJsonObject("source").add("scope",scope);
		assertTrue(engine.isNonDamagingActivity(projectile,hit));
		JsonObject graphic = gson.fromJson("{key:'actual-graphic',pattern:'area_impact',source:{kind:'graphic',id:2148},signals:{graphicIds:[2148],lifecycleEvents:['graphic_spawn']},tick:10,cycle:300,position:{x:3202,y:3200}}",JsonObject.class); graphic.getAsJsonObject("source").add("scope",scope);
		JsonObject input = new JsonObject(); input.add("hit",hit); input.add("cues",gson.toJsonTree(Collections.singletonList(graphic)));
		JsonObject result = engine.resolve(input);
		assertTrue(result.getAsJsonArray("candidates").size()>0);
		assertTrue(result.getAsJsonArray("candidates").get(0).getAsJsonObject().getAsJsonArray("reasons").toString().contains("GEOMETRY_MISMATCH"));
	}

	private ResolverV1 referenceEngine(boolean special,boolean classification)
	{
		JsonObject catalog = gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[{id:'ordinary.synthetic',source:{kind:'npc',ids:[900201]},styles:['melee'],maximum:{kind:'fixed_max',value:10,authority:'reference_only'}}],specialRules:[]}",JsonObject.class);
		if (special) catalog.getAsJsonArray("specialRules").add(gson.fromJson("{id:'special.synthetic',label:'Independent component',pattern:'direct_attack',source:{kind:'npc',ids:[900201]},signals:{animationIds:[777]},timing:{anchor:'actor_activity',authority:'unknown'},geometry:{shape:'unknown',anchor:'source_position',authority:'reference_only'},recipients:'direct_target',calculationTick:'unknown',outcome:{surface:'hp_or_block',hitsplatTypeIds:[16],signatureComplete:true,signatureAuthority:'verified'},ordinaryAttacksContinue:true,scopePolicy:'same_native_scope',references:[{source:'synthetic',claim:'test'}]}",JsonObject.class));
		if (classification) catalog.add("activityClassifications",gson.fromJson("[{id:'activity.synthetic',source:{kind:'npc',ids:[900201]},signals:{animationIds:[777]},role:'non_damaging',authority:'verified',references:[{source:'synthetic',claim:'test'}]}]",com.google.gson.JsonArray.class));
		return new ResolverV1(catalog);
	}

	private static DeathRecordingV2 priorExcluded(DeathRecordingV2 recording,String scope)
	{
		DamageRecord hit = recording.getEvents().getDamageEvents().get(0);
		SourceResolution.Candidate candidate = hit.getSourceResolution().getCandidates().get(0).toBuilder().exclusionScope(scope)
			.evidence(Collections.singletonList("QUALIFIED_MAXIMUM:synthetic:20 > 10"))
			.reasons(Collections.singletonList("MAXIMUM_EXCEEDED:synthetic:20 > 10")).build();
		return replaceHit(recording,hit.toBuilder().sourceResolution(hit.getSourceResolution().toBuilder().candidates(Collections.emptyList())
			.excludedCandidates(Collections.singletonList(candidate)).build()).build());
	}

	@Test public void separateRetaliationSourceUsesOneActualLocalTriggerInEitherPacketOrder()
	{
		ResolverV1 engine = retaliationEngine();
		for (boolean later : new boolean[] {false,true})
		{
			DeathRecordingV2 input = retaliationRecording("LOCAL_PLAYER",0,900301,later,"");
			DamageRecord incoming = input.getEvents().getDamageEvents().get(1);
			JsonObject normalized = new ResolverV1Recording(engine,gson,input).normalize(incoming);
			JsonObject result = engine.resolve(normalized); JsonObject reflected = null;
			for (JsonElement row : result.getAsJsonArray("candidates")) if ("retal".equals(row.getAsJsonObject().get("ruleId").getAsString())) reflected=row.getAsJsonObject();
			assertNotNull(reflected); assertEquals("POSSIBLE",reflected.get("status").getAsString());
			assertFalse("Later co-present source sample cannot acquire even a1HP cap",reflected.has("maximum"));
			for (JsonElement value : normalized.getAsJsonArray("cues"))
			{
				JsonObject cue = value.getAsJsonObject(); if (!"retaliation".equals(cue.get("pattern").getAsString())) continue;
				assertEquals(30,cue.getAsJsonObject("facts").getAsJsonObject("source_event").get("observed_trigger_damage").getAsInt());
				assertEquals(900301,cue.getAsJsonObject("facts").getAsJsonObject("source_event").get("trigger_target_npc_id").getAsInt());
				assertFalse(cue.getAsJsonObject("facts").getAsJsonObject("source_event").has("trigger_attack_style"));
			}
			DeathRecordingV2 after = new ResolverV1Recording(engine,gson,input).enrich(input);
			assertSame(input.getEvents().getDamageEvents().get(0),after.getEvents().getDamageEvents().get(0));
			assertEquals(7,after.getEvents().getDamageEvents().get(1).getAmount());
			assertTrue(after.getEvents().getDamageEvents().get(1).getSourceResolution().getCandidates().stream().anyMatch(c -> "reflector".equals(c.getActorRef())));
		}
	}

	@Test public void separateRetaliationCannotBorrowOtherOwnershipForeignScopeWrongTargetOrLaterBirth()
	{
		ResolverV1 engine = retaliationEngine();
		for (DeathRecordingV2 input : Arrays.asList(retaliationRecording("OTHER",0,900301,false,""),
			retaliationRecording("UNKNOWN",0,900301,false,""),retaliationRecording("LOCAL_PLAYER",17,900301,false,""),
			retaliationRecording("LOCAL_PLAYER",0,900309,false,""),retaliationRecording("LOCAL_PLAYER",0,900301,false,"born_after"),
			retaliationRecording("LOCAL_PLAYER",0,900301,false,"prior_despawn")))
		{
			JsonObject result=engine.resolve(new ResolverV1Recording(engine,gson,input).normalize(input.getEvents().getDamageEvents().get(1)));
			for (JsonElement row : result.getAsJsonArray("candidates")) assertNotEquals("retal",row.getAsJsonObject().get("ruleId").getAsString());
			assertTrue(result.get("unidentifiedPossible").getAsBoolean());
		}
	}

	private ResolverV1 retaliationEngine()
	{
		return new ResolverV1(gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[],specialRules:[{id:'retal',label:'Reflected component',pattern:'retaliation',source:{kind:'npc',ids:[900302]},when:[{fact:'trigger_target_npc_id',op:'one_of',value:[900301],at:'source_event'}],timing:{anchor:'retaliation_event',authority:'unknown'},geometry:{shape:'target',anchor:'source_position',authority:'reference_only'},recipients:'direct_target',calculationTick:'unknown',outcome:{surface:'hp_or_block',maximum:{kind:'fixed_max',value:1,authority:'verified'}},ordinaryAttacksContinue:true,scopePolicy:'same_native_scope',references:[{source:'synthetic',claim:'test'}]}]}",JsonObject.class));
	}
	private DeathRecordingV2 retaliationRecording(String owner,int sourceView,int targetId,boolean later,String lifetime)
	{
		DeathRecordingV2 base=recording(900201,77,"Rival",16,7,true,false,0);
		int cycle=later?302:300; long sequence=later?21:10;
		Map<String,Object> target=map("npcId",targetId,"name","Actual target","actorKind","NPC","combatLevel",80,"worldViewId",0,"plane",0,"planeKnown",true);
		ObservedEvent actual=ObservedEvent.builder().type("HITSPLAT").tick(10).gameCycle(cycle).sequence(sequence).actorRef("target").sceneKey("room")
			.data(map("actorState",target,"receiverKind","NPC","isMine","LOCAL_PLAYER".equals(owner),"amount",30,"hitsplatType",17)).build();
		DamageRecord trigger=DamageRecord.builder().direction("NPC_RECEIVED").ownership(owner).defenderRef("target").impactTick(10).observedCycle(cycle)
			.observationSequence(sequence).amount(30).hitsplatType(17).hitsplatTypeKnown(true).build();
		java.util.List<ObservedEvent> observations=new java.util.ArrayList<>(base.getEvents().getObservations()); observations.add(actual);
		if (!lifetime.isEmpty()) observations.add(ObservedEvent.builder().type("born_after".equals(lifetime)?"NPC_SPAWNED":"NPC_DESPAWNED")
			.actorRef("reflector").tick("born_after".equals(lifetime)?10:9).sequence("born_after".equals(lifetime)?22:9).gameCycle("born_after".equals(lifetime)?303:270).sceneKey("room").data(Collections.emptyMap()).build());
		NpcState source=NpcState.builder().actorRef("reflector").npcId(900302).name("Recorded reflector").combatLevel(40)
			.worldViewId(sourceView).plane(0).planeKnown(true).normalWorldContextObserved(true).build();
		WorldSnapshot frame=WorldSnapshot.builder().tick(10).gameCycle(303).gameCycleKnown(true).nativeContextKnown(true)
			.sceneKey("room").worldViewId(0).plane(0).npcs(Collections.singletonList(source)).build();
		return base.toBuilder().ticks(Collections.singletonList(frame)).events(DeathRecordingV2.Events.builder()
			.damageEvents(Arrays.asList(trigger,base.getEvents().getDamageEvents().get(0))).observations(observations).build()).build();
	}

	private DeathRecordingV2 recording(int id,int level,String name,int type,int amount,boolean normal,boolean override,int sourceView)
	{
		Map<String,Object> interaction = map("reportedInteracting",true,"actorRef","player","actorKind","LOCAL_PLAYER","worldViewId",sourceView);
		Map<String,Object> npc = map("npcId",id,"name",name,"combatLevel",level,"worldViewId",sourceView,"plane",0,"planeKnown",true,
			"worldPositionKnown",true,"worldX",3200,"worldY",3200,"animationId",777,"normalWorldContextObserved",normal,"modelOverridesPresent",override,
			"interaction",interaction,"callbackMetadataEvidence","LOGGED_IN_ACTOR_FIELDS");
		ObservedEvent attack = ObservedEvent.builder().sequence(19).tick(10).gameCycle(300).type("ANIMATION_CHANGED").actorRef("actual:minion")
			.sceneKey("room").data(npc).build();
		ObservedEvent hp = ObservedEvent.builder().sequence(20).tick(10).gameCycle(301).type("HITSPLAT").actorRef("player").sceneKey("room")
			.data(map("amount",amount,"hitsplatType",type,"receiverKind","PLAYER","actorState",map("actorKind","LOCAL_PLAYER","worldViewId",0,
				"plane",0,"planeKnown",true,"worldPositionKnown",true,"worldX",3200,"worldY",3200))).build();
		SourceResolution before = SourceResolution.builder().version(1).status("ALTERNATIVES").unidentifiedPossible(true)
			.candidates(Collections.singletonList(SourceResolution.Candidate.builder().key("prior").name(name).npcId(id).actorRef("actual:minion")
				.sceneKey("room").worldViewId(0).plane(0).build())).build();
		DamageRecord hit = DamageRecord.builder().direction("RECEIVED").defenderRef("player").impactTick(10).observedCycle(301).observationSequence(20L)
			.impactSceneKey("room").impactWorldViewId(0).impactPlane(0).impactX(3200).impactY(3200).impactPositionKnown(true)
			.amount(amount).hitsplatType(type).hitsplatTypeKnown(true).attackerName("minion").attackerRef("actual:minion").sourceResolution(before).build();
		return DeathRecordingV2.builder().events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(hit))
			.observations(Arrays.asList(attack,hp)).build()).build();
	}
	private static Map<String,Object> map(Object... entries)
	{ Map<String,Object> result = new LinkedHashMap<>(); for (int i=0;i<entries.length;i+=2) result.put((String)entries[i],entries[i+1]); return result; }
}
