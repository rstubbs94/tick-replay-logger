/*
 * Copyright (c) 2026, rstubbs94
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.world.SourceResolution;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Refactoring parity over primitive facts, not a claim of complete game observation. */
public class CanonicalMechanicsProjectionTest
{
	private final Gson gson = new Gson();

	@Test public void projectionRetainsEveryOriginalSectionAndSeparatesLineageFromSemanticIdentity() throws Exception
	{
		RuntimeCatalog packaged = RuntimeCatalog.load(gson);
		JsonObject original = resource("combat_catalog.json");
		JsonObject authored=packaged.section("references").deepCopy();
		assertNotNull(authored.remove("inventorySourceCapture"));
		assertJsonSame("references",original,authored);
		assertJsonSame("capture",resource("damage_map.json"),packaged.section("capture"));
		JsonObject resolver = packaged.section("resolver");
		Map<String,JsonObject> compiled=new HashMap<>();
		for(JsonElement value:resolver.getAsJsonArray("resolutionRules"))
			compiled.put(value.getAsJsonObject().get("id").getAsString(),value.getAsJsonObject());
		for(JsonElement value:ResolverTestCatalog.lower(resource("resolver-v1.json")).getAsJsonArray("resolutionRules"))
		{
			JsonObject originalRule=value.getAsJsonObject();
			assertJsonSame("resolver:"+originalRule.get("id").getAsString(),originalRule,compiled.get(originalRule.get("id").getAsString()));
		}
		assertEquals(3269, ResolverTestCatalog.rows(resolver,"qualified_limit").size());
		assertEquals(55, ResolverTestCatalog.rows(resolver,"evidence").size());
		assertEquals(22, ResolverTestCatalog.rows(resolver,"contact_limit").size());
		for (JsonElement value : ResolverTestCatalog.rows(resolver,"evidence"))
		{
			JsonObject row = value.getAsJsonObject();
			if ("ENDPOINT_COOCCURRENCE".equals(text(row,"evidenceMode")))
				assertEquals(row.getAsJsonObject("declaration").get("sourceNames"),row.getAsJsonObject("source").get("names"));
			if ("RETAINED_LIFECYCLE".equals(text(row,"evidenceMode")))
			{
				assertTrue(row.get("originalHpPositionRequired").getAsBoolean());
				assertTrue(row.getAsJsonObject("contextPredicate").getAsJsonArray("requiredZeroVariables").size()>0);
			}
		}
		assertNotEquals("resolver-v1:sha256:" + packaged.section("sourceSnapshots").get("resolverSha256").getAsString(), ResolverV1.load(gson).id());
	}

	@Test public void everyCompiledNumericDeclarationMatchesTheOriginalEvaluator()
	{
		LegacyCombatCatalog original = LegacyCombatCatalog.load(gson);
		int checked = 0;
		for (JsonElement value : ResolverTestCatalog.rows(RuntimeCatalog.load(gson).section("resolver"),"qualified_limit"))
		{
			JsonObject declaration = value.getAsJsonObject();
			Integer npc = declaration.getAsJsonObject("source").getAsJsonArray("ids").get(0).getAsInt();
			AttributionConstraint prior = original.attributionConstraints(npc).stream()
				.filter(row -> row.getKey().equals(declaration.get("id").getAsString())).findFirst().orElse(null);
			assertNotNull(declaration.get("id").getAsString(), prior);
			JsonObject input = qualified(declaration, npc);
			JsonObject cue = input.getAsJsonObject("cue"), hit = input.getAsJsonObject("hit");
			int maximum = declaration.get("maximum").isJsonNull() ? 0 : declaration.get("maximum").getAsInt();
			for (int amount : new int[]{0, maximum, maximum + 1})
			{
				hit.addProperty("amount", amount);
				compare(declaration, prior, cue, hit, "qualified");
				JsonObject missing = cue.deepCopy(); missing.add("observations", new JsonObject());
				compare(declaration, prior, missing, hit, "missing-observations");
				JsonObject falseFacts = cue.deepCopy();
				for (String key : new ArrayList<>(falseFacts.getAsJsonObject("observations").keySet()))
					falseFacts.getAsJsonObject("observations").addProperty(key, false);
				compare(declaration, prior, falseFacts, hit, "false-observations");
				JsonObject noNumbers = cue.deepCopy(); noNumbers.add("numericFacts", new JsonObject());
				compare(declaration, prior, noNumbers, hit, "missing-numbers");
				if ("ALL_DIRECT_HP_COMPONENTS".equals(text(declaration,"exclusionScope")))
				{
					JsonObject unscoped = cue.deepCopy(); unscoped.getAsJsonObject("source").remove("scope");
					compare(declaration,prior,unscoped,hit,"missing-native-scope");
					JsonObject unnamed = cue.deepCopy(); unnamed.getAsJsonObject("source").remove("actorRef");
					compare(declaration,prior,unnamed,hit,"missing-actor-reference");
				}
			}
			checked++;
		}
		assertEquals(3269, checked);
	}

	@Test public void originalProjectileOutcomeCanExcludeHpWithoutAnOrdinaryProfileQualification()
	{
		JsonObject input = gson.fromJson("{hit:{recipientRef:'player',amount:10,hitsplatType:16,family:'ordinary',tick:2,cycle:60,sequence:10,scope:{sceneKey:'synthetic',worldViewId:0,plane:0}},cues:[{key:'actual-api-projectile',pattern:'projectile_impact',evidenceMode:'API_PROJECTILE',apiSourceObserved:true,targetRef:'player',sourceObservedBeforeImpact:true,tick:2,cycle:60,sequence:1,source:{kind:'npc',id:12226,name:\"Vardorvis' Head\",combatLevel:1,actorRef:'npc:head',scope:{sceneKey:'synthetic',worldViewId:0,plane:0}},signals:{projectileIds:[2520]},originalSourceObservation:{actorKind:'NPC',sourceEvidence:'ACTOR',sourceActorKnown:true,sourceNameKnown:true,sourceNormalWorldContextObserved:true,sourceModelOverridesPresent:false,sourceDefinitionEvidence:'NPC_API_ID',sourceNpcId:12226,sourceObservedName:\"Vardorvis' Head\",sourceCombatLevel:1,sourceRef:'npc:head',sourceWorldViewId:0,sourcePlane:0,projectileId:2520,observedTick:1,observedCycle:30},witness:{projectileId:2520,sourceHistory:{version:1,actorChanged:false,referenceChanged:false,definitionChanged:false,scopeChanged:false,observationGap:false,projectileDefinitionChanged:false}}}]}", JsonObject.class);
		JsonObject result = ResolverTestCatalog.resolve(ResolverV1.load(gson),input);
		assertTrue(result.getAsJsonArray("candidates").size() > 0);
		for (JsonElement value : result.getAsJsonArray("candidates"))
		{
			assertEquals("EXCLUDED",value.getAsJsonObject().get("status").getAsString());
			assertTrue(value.getAsJsonObject().getAsJsonArray("reasons").toString().contains("HITSPLAT_TYPE_MISMATCH"));
		}
		input.getAsJsonArray("cues").get(0).getAsJsonObject().getAsJsonObject("originalSourceObservation")
			.addProperty("sourceModelOverridesPresent",true);
		JsonObject uncertain = ResolverTestCatalog.resolve(ResolverV1.load(gson),input);
		boolean possible = false;
		for (JsonElement value : uncertain.getAsJsonArray("candidates")) possible |= "POSSIBLE".equals(value.getAsJsonObject().get("status").getAsString());
		assertTrue(possible);
	}

	@Test public void duplicateRowsWithinOneHitDoNotBecomeCrossHitAssignments()
	{
		ResolverV1 engine = ResolverTestCatalog.engine(ResolverTestCatalog.lower(gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution'}",JsonObject.class)));
		JsonObject input = gson.fromJson("{hit:{recipientRef:'player',amount:1},cues:[{key:'observed-projectile:1',pattern:'projectile_impact',apiSourceObserved:true,source:{kind:'npc',id:900000,actorRef:'synthetic:1'}}]}",JsonObject.class);
		input.getAsJsonArray("cues").add(input.getAsJsonArray("cues").get(0).deepCopy());
		assertFalse(ResolverTestCatalog.resolveBatch(engine,Collections.singletonList(input)).get(0).has("scalarAssociation"));
		for (JsonObject result : ResolverTestCatalog.resolveBatch(engine,Arrays.asList(input,input.deepCopy())))
			assertEquals("AMBIGUOUS",result.get("scalarAssociation").getAsString());
	}

	@Test public void redactedOwnersRetainObservationIdentityWithoutInventingActorIdentity()
	{
		JsonObject first = gson.fromJson("{kind:'player',name:'Other player',observationKey:'projectile-observation:1'}",JsonObject.class);
		JsonObject second = first.deepCopy(); second.addProperty("observationKey","projectile-observation:2");
		assertNotEquals(identityKey(first),identityKey(second));
		first.addProperty("kind","npc"); second.addProperty("kind","npc"); first.addProperty("id",900001); second.addProperty("id",900001);
		assertNotEquals(identityKey(first),identityKey(second));
		first.remove("id");
		first.remove("observationKey"); assertNull(identityKey(first));
	}

	@Test public void terminalSourceHintRequiresOriginalHpPositionAndOriginalHpMode()
	{
		JsonObject input = gson.fromJson("{hit:{recipientRef:'player',direction:'RECEIVED',amount:1,hitsplatType:16,tick:10,cycle:30,sequence:2,scope:{sceneKey:'synthetic',worldViewId:0,plane:0},position:{x:5,y:5},originalHpCallback:{receiverKind:'PLAYER',amount:1,hitsplatType:16,actorState:{actorKind:'LOCAL_PLAYER',worldPositionKnown:true,planeKnown:true,worldViewId:0,plane:0,worldX:5,worldY:5}},numericContext:{worldTypes:[],leagueType:0,evidence:{worldTypes:'OBSERVED_CLIENT_WORLD_TYPES',LEAGUE_TYPE:'OBSERVED_CLIENT_VARBIT'}}},cues:[{key:'actual-death',pattern:'delayed_effect',evidenceMode:'RETAINED_LIFECYCLE',allowFallback:false,tick:10,cycle:30,sequence:1,position:{x:5,y:5},source:{kind:'npc',id:2910,name:'Chinchompa',combatLevel:1,actorRef:'npc:synthetic',scope:{sceneKey:'synthetic',worldViewId:0,plane:0}},signals:{lifecycleEvents:['actor_death']},originalActorState:{npcId:2910,name:'Chinchompa',combatLevel:1,normalWorldContextObserved:true,modelOverridesPresent:false,definitionEvidence:'NPC_API_ID'}}]}",JsonObject.class);
		ResolverV1 engine = ResolverV1.load(gson);
		assertTrue(hasRule(ResolverTestCatalog.resolve(engine,input),"lifecycle_hint:grey-chinchompa:death"));
		JsonObject missingMode = input.deepCopy(); missingMode.getAsJsonObject("hit").remove("numericContext");
		assertFalse(hasRule(ResolverTestCatalog.resolve(engine,missingMode),"lifecycle_hint:grey-chinchompa:death"));
		JsonObject wrongPosition = input.deepCopy(); wrongPosition.getAsJsonObject("hit").getAsJsonObject("position").addProperty("x",6);
		assertFalse(hasRule(ResolverTestCatalog.resolve(engine,wrongPosition),"lifecycle_hint:grey-chinchompa:death"));
	}

	@Test public void globalAnnotationsCannotCrossNpcDefinitionOrActorKind()
	{
		JsonObject document=gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',resolutionRules:["
			+"{id:'reference.first',role:'annotation',domain:'cue',source:{kind:'npc',ids:[900001]},effects:{annotations:{damageType:'ranged',determination:'attack'}},merge:{group:'base',priority:1,mode:'consensus'}},"
			+"{id:'reference.second',role:'annotation',domain:'cue',source:{kind:'npc',ids:[900002]},effects:{annotations:{damageType:'magic',determination:'hit'}},merge:{group:'base',priority:1,mode:'consensus'}}]}",JsonObject.class);
		ResolverV1 engine=new ResolverV1(document,gson,CombatCatalog.load(gson),DamageMap.load(gson));
		for(int id:new int[]{900001,900002,900003}) for(String kind:Arrays.asList("npc","object"))
		{
			JsonObject input=gson.fromJson("{hit:{recipientRef:'player',amount:1,hitsplatType:16,family:'ordinary',tick:10,cycle:30,sequence:2,scope:{sceneKey:'synthetic',worldViewId:0,plane:0}},cues:[{key:'actual-source',pattern:'direct_attack',targetRef:'player',source:{name:'Observed source',actorRef:'actual-source',scope:{sceneKey:'synthetic',worldViewId:0,plane:0}}}]}",JsonObject.class);
			JsonObject source=input.getAsJsonArray("cues").get(0).getAsJsonObject().getAsJsonObject("source");source.addProperty("kind",kind);source.addProperty("id",id);
			JsonObject result=ResolverTestCatalog.resolve(engine,input).getAsJsonArray("candidates").get(0).getAsJsonObject().getAsJsonObject("derivedAnnotations");
			boolean matched="npc".equals(kind) && id!=900003;
			assertEquals(kind+":"+id,matched,result.has("damageType"));assertEquals(matched,result.has("determination"));
			if(matched) { assertEquals(id==900001?"ranged":"magic",text(result,"damageType"));assertEquals(id==900001?"attack":"hit",text(result,"determination")); }
		}
	}

	private static boolean hasRule(JsonObject result, String id)
	{
		for (JsonElement row : result.getAsJsonArray("candidates")) if (id.equals(text(row.getAsJsonObject(),"ruleId"))) return true;
		return false;
	}

	@Test public void observedItemLabelAliasesOnlyItsExactDataBoundInputReceipt()
	{
		JsonObject declaration=null;
		for (JsonElement value : RuntimeCatalog.load(gson).section("references").getAsJsonObject("selfDamageInputs").getAsJsonArray("records"))
			if ("Divine super combat potion".equals(text(value.getAsJsonObject(),"page"))) declaration=value.getAsJsonObject();
		assertNotNull(declaration);
		JsonObject request=gson.fromJson("{key:'request',pattern:'interaction_damage',evidenceMode:'INPUT_REQUEST',allowFallback:false,tick:10,cycle:30,sequence:1,source:{kind:'item',id:23685,scope:{sceneKey:'synthetic',worldViewId:0,plane:0}},witness:{kind:'SELF_DAMAGE_INPUT_CANDIDATE',itemId:23685,inputTick:10,inputSequence:1,option:'Drink',evidence:'INPUT_CORRELATION_NOT_EXECUTION_PROOF'}}",JsonObject.class);
		request.getAsJsonObject("source").addProperty("name",text(declaration,"page"));
		request.getAsJsonObject("witness").addProperty("sourceRef","wiki:"+text(declaration,"page"));
		request.getAsJsonObject("witness").add("pageHash",declaration.get("pageHash"));
		JsonObject consumed=request.deepCopy(); consumed.addProperty("key","decrease"); consumed.addProperty("evidenceMode","CONSUMPTION");
		consumed.getAsJsonObject("source").addProperty("name","Divine super combat potion(4)");
		JsonObject receipt=consumed.getAsJsonObject("witness"); receipt.addProperty("kind","ITEM_CONSUMPTION_CANDIDATE");
		receipt.addProperty("evidence","OBSERVED_INVENTORY_DECREASE_NOT_CAUSAL_PROOF"); receipt.addProperty("slot",0);
		receipt.addProperty("quantityBefore",1);receipt.addProperty("quantityAfter",0);
		JsonObject input=gson.fromJson("{hit:{recipientRef:'player',direction:'RECEIVED',amount:10,hitsplatType:16,family:'ordinary',tick:10,cycle:30,sequence:2,scope:{sceneKey:'synthetic',worldViewId:0,plane:0}},cues:[]}",JsonObject.class);
		input.getAsJsonArray("cues").add(request);input.getAsJsonArray("cues").add(consumed);
		JsonObject result=ResolverTestCatalog.resolve(ResolverV1.load(gson),input); boolean decrease=false;
		for (JsonElement value : result.getAsJsonArray("candidates"))
		{
			JsonObject row=value.getAsJsonObject();
			assertEquals("Divine super combat potion(4)",text(row.getAsJsonObject("source"),"name"));
			decrease |= "ITEM_CONSUMPTION_CORRELATION".equals(text(row,"sourceAssociation"));
		}
		assertTrue(decrease); assertEquals(text(declaration,"page"),text(request.getAsJsonObject("source"),"name"));
		receipt.addProperty("inputSequence",3);
		for (JsonElement value : ResolverTestCatalog.resolve(ResolverV1.load(gson),input).getAsJsonArray("candidates"))
			if ("INPUT_REQUEST".equals(text(value.getAsJsonObject(),"evidenceMode")))
				assertEquals(text(declaration,"page"),text(value.getAsJsonObject().getAsJsonObject("source"),"name"));
	}

	@Test public void physicalContactAgreementNeverJoinsAReplacementAtTheSameTile()
	{
		ResolverV1 engine=ResolverTestCatalog.engine(ResolverTestCatalog.lower(gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',specialRules:[{id:'synthetic.contact',pattern:'contact_hazard',source:{kind:'object',ids:[900001]},recipients:'within_area',geometry:{shape:'point',anchor:'source_position',authority:'verified'}}]}",JsonObject.class)));
		JsonObject input=gson.fromJson("{hit:{recipientRef:'player',amount:1,hitsplatType:16,tick:10,cycle:30,sequence:2,scope:{sceneKey:'synthetic',worldViewId:0,plane:0},position:{x:5,y:5},impactFrame:{tick:10,nativeContextKnown:true,sceneKey:'synthetic',worldViewId:0,plane:0},originalHpCallback:{receiverKind:'PLAYER',amount:1,hitsplatType:16,actorState:{actorKind:'LOCAL_PLAYER',worldPositionKnown:true,planeKnown:true,worldViewId:0,plane:0,worldX:5,worldY:5}},observedHazardContacts:[{kind:'OBJECT_SURFACE',objectId:900001,objectObservationId:7,objectKind:'gameObject',worldViewId:0,plane:0,sceneKey:'synthetic',observedCycle:30,observationStage:'HITSPLAT_CALLBACK',defenderPositionEvidence:'SERVER_REPORTED_TILE',sourceEligibility:'ELIGIBLE_REFERENCE_SURFACE',damaging:true}],impactSurfaces:[{kind:'gameObject',id:900001,objectKey:123,objectObservationId:7,worldViewId:0,plane:0,damaging:true,sourceEligibility:'ELIGIBLE_REFERENCE_SURFACE'}]},cues:[{key:'contact',pattern:'contact_hazard',evidenceMode:'ORIGINAL_CONTACT',contactObserved:true,allowFallback:false,source:{kind:'object',id:900001,scope:{sceneKey:'synthetic',worldViewId:0,plane:0}},position:{x:5,y:5}}]}",JsonObject.class);
		JsonObject actualCue=input.getAsJsonArray("cues").get(0).getAsJsonObject();
		actualCue.add("witness",input.getAsJsonObject("hit").getAsJsonArray("observedHazardContacts").get(0).deepCopy());
		actualCue.addProperty("tick",10);actualCue.addProperty("cycle",30);actualCue.addProperty("sequence",2);
		assertEquals("ORIGINAL_CONTACT_AGREEMENT",text(ResolverTestCatalog.resolve(engine,input).getAsJsonArray("candidates").get(0).getAsJsonObject(),"sourceAssociation"));
		input.getAsJsonObject("hit").getAsJsonArray("impactSurfaces").get(0).getAsJsonObject().addProperty("objectObservationId",8);
		input.getAsJsonObject("hit").getAsJsonArray("impactSurfaces").get(0).getAsJsonObject().addProperty("objectKey",7);
		JsonObject replacement=ResolverTestCatalog.resolve(engine,input).getAsJsonArray("candidates").get(0).getAsJsonObject();
		assertEquals("POSSIBLE",text(replacement,"status")); assertNull(text(replacement,"sourceAssociation"));
	}

	private void compare(JsonObject declaration, AttributionConstraint prior, JsonObject cue, JsonObject hit, String scenario)
	{
		JsonObject document = gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution'}", JsonObject.class);
		JsonArray limits = new JsonArray(); limits.add(declaration); document.add("qualifiedLimits", limits);
		JsonObject source = cue.getAsJsonObject("source");
		JsonObject scope = source.has("scope") ? source.getAsJsonObject("scope") : new JsonObject();
		Map<String, Boolean> observations = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> field : cue.getAsJsonObject("observations").entrySet()) observations.put(field.getKey(), field.getValue().getAsBoolean());
		Map<String, Number> numbers = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> field : cue.getAsJsonObject("numericFacts").entrySet()) numbers.put(field.getKey(), field.getValue().getAsBigDecimal());
		SourceResolution.Candidate candidate = SourceResolution.Candidate.builder().key("primitive-fixture").name("Synthetic source")
			.npcId(source.get("id").getAsInt()).actorRef(text(source,"actorRef")).familyKey(text(source,"familyKey"))
			.sceneKey(text(scope,"sceneKey")).worldViewId(scope.has("worldViewId") ? scope.get("worldViewId").getAsInt() : null)
			.plane(scope.has("plane") ? scope.get("plane").getAsInt() : null).strength("POSSIBLE").build();
		LegacyConstraintOracle.Hypothesis hypothesis = LegacyConstraintOracle.Hypothesis.builder().candidate(candidate)
			.constraints(Collections.singletonList(prior)).observations(observations).numericObservations(numbers)
			.mechanism(text(cue,"mechanism")).damageComponent(text(cue,"damageComponent")).worldMode(text(cue,"worldMode"))
			.observedAttackStyle(text(cue,"observedAttackStyle")).observedHitsplatType(hit.get("hitsplatType").getAsInt()).build();
		SourceResolution baseline = LegacyConstraintOracle.resolve(Collections.singletonList(hypothesis), hit.get("amount").getAsInt(), true, "synthetic");
		ResolverV1.BoundCases cases = new ResolverV1.BoundCases();
		boolean applicable = "verified".equals(text(declaration,"authority"))
			&& declaration.getAsJsonArray("staticMissing").size()==0
			&& ResolverV1.conditions(declaration.get("when"),(domain,fact)-> {
				JsonObject values="hit".equals(domain) ? hit : "cue".equals(domain) ? cue
					: "source".equals(domain) ? source : cue.getAsJsonObject(domain);
				return values==null ? null : ResolverV1.path(values,fact);
			})==ResolverEvidence.Truth.TRUE;
        if("ALL_DIRECT_HP_COMPONENTS".equals(text(declaration,"exclusionScope")))
        {
            ResolverEvidence.Identity identity=DirectFixtureEvidence.source(cue).identity();
            applicable &= identity.actorRef!=null && !identity.actorRef.trim().isEmpty()
                && OriginalSourceProof.scope(identity.scope,identity.scope)==ResolverEvidence.Truth.TRUE;
        }
		cases.add("independent_guard".equals(text(declaration,"composition")),applicable,
			ResolverEvidence.number(declaration.get("maximum")),hit.get("amount").getAsBigDecimal());
		assertEquals(text(declaration,"id") + ":" + scenario + ":" + hit.get("amount"), baseline.getExcludedCandidates()!=null && !baseline.getExcludedCandidates().isEmpty(), cases.excluded());
	}

	private String identityKey(JsonObject source)
	{
		JsonObject cue=new JsonObject();cue.add("source",source);
		return ResolverEvidence.sourceKey(DirectFixtureEvidence.source(cue).identity());
	}

	private JsonObject qualified(JsonObject declaration, int npc)
	{
		JsonObject input = gson.fromJson("{cue:{source:{kind:'npc',actorRef:'npc:primitive-fixture',scope:{sceneKey:'synthetic-scene',worldViewId:0,plane:0}},observations:{},numericFacts:{}},hit:{hitsplatType:16}}", JsonObject.class);
		JsonObject cue = input.getAsJsonObject("cue"), hit = input.getAsJsonObject("hit"); cue.getAsJsonObject("source").addProperty("id", npc);
		for (JsonElement value : declaration.getAsJsonArray("when"))
		{
			JsonObject predicate = value.getAsJsonObject(); String domain = predicate.get("at").getAsString();
			JsonObject target = "hit".equals(domain) ? hit : "cue".equals(domain) ? cue : cue.getAsJsonObject(domain);
			JsonElement expected = predicate.get("value");
			target.add(predicate.get("fact").getAsString(), "one_of".equals(text(predicate,"op")) ? expected.getAsJsonArray().get(0) : expected);
		}
		return input;
	}

	private JsonObject resource(String name) throws Exception
	{
		try (Reader reader = new InputStreamReader(getClass().getResourceAsStream("/com/tickreplaylogger/" + name), StandardCharsets.UTF_8))
		{ return gson.fromJson(reader, JsonObject.class); }
	}
	private static void assertJsonSame(String path, JsonElement expected, JsonElement actual)
	{
		if (expected==null || actual==null || expected.isJsonNull() || actual.isJsonNull())
		{ assertEquals(path,expected,actual); return; }
		if (expected.isJsonObject() && actual.isJsonObject())
		{
			assertEquals(path+":keys",expected.getAsJsonObject().keySet(),actual.getAsJsonObject().keySet());
			for (String key : expected.getAsJsonObject().keySet()) assertJsonSame(path+"."+key,expected.getAsJsonObject().get(key),actual.getAsJsonObject().get(key));
			return;
		}
		if (expected.isJsonArray() && actual.isJsonArray())
		{
			assertEquals(path+":length",expected.getAsJsonArray().size(),actual.getAsJsonArray().size());
			for (int index=0;index<expected.getAsJsonArray().size();index++) assertJsonSame(path+"["+index+"]",expected.getAsJsonArray().get(index),actual.getAsJsonArray().get(index));
			return;
		}
		assertTrue(path+":value-or-type",expected.equals(actual));
	}
	private static String text(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : null; }
}
