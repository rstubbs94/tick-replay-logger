/*
 * Copyright (c) 2026, rstubbs94
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original observation roles qualify reference facts independently of stronger numeric proof. */
public class CanonicalCuePolicyTest
{
    private final Gson gson=new Gson();
    @Test public void knownOriginalApiModelCanSupplyReferenceWithoutNativeNumericProof() throws Exception
    {
        JsonObject cue=api();
        object(cue,"source").add("scope",new JsonObject());
        assertFlag(interpret(cue),"staticReferenceEligible",true);
        assertFlag(interpret(cue),"numericEligible",false);
        assertEquals("Synthetic actor",interpret(cue).getAsJsonObject("source").get("name").getAsString());
    }
    @Test public void missingOriginalModelOrWorldDoesNotBecomeNormal() throws Exception
    {
        for(String field:new String[]{"sourceModelOverridesPresent","sourceNormalWorldContextObserved","sourceCombatLevel","sourceObservedName"})
        {
            JsonObject cue=api();object(cue,"originalSourceObservation").remove(field);
            assertFlag(interpret(cue),"staticReferenceEligible",false);assertFlag(interpret(cue),"numericEligible",false);
        }
    }
    @Test public void sourceMetadataMustComeFromItsActualFirstObservation() throws Exception
    {
        JsonObject cue=api();object(cue,"originalSourceObservation").remove("sourceSceneKey");
        assertFlag(interpret(cue),"staticReferenceEligible",true);assertFlag(interpret(cue),"numericEligible",false);
        cue=api();object(cue,"originalSourceObservation").addProperty("sourceNpcId",900002);
        assertFlag(interpret(cue),"staticReferenceEligible",false);assertFlag(interpret(cue),"numericEligible",false);
    }
    @Test public void stableKnownNativeOriginalApiRemainsNumericallyEligible() throws Exception
    {
        assertFlag(interpret(api()),"staticReferenceEligible",true);assertFlag(interpret(api()),"numericEligible",true);
        JsonObject cue=api();cue.addProperty("numericEligible",false);assertFlag(interpret(cue),"numericEligible",false);
    }
    @Test public void changedOrUnknownSourceHistoryCannotSupplyStyleOrCap() throws Exception
    {
        for(String field:new String[]{"actorChanged","referenceChanged","definitionChanged","scopeChanged","observationGap","projectileDefinitionChanged"})
        {
            JsonObject cue=api();object(object(cue,"witness"),"sourceHistory").addProperty(field,true);
            assertFlag(interpret(cue),"staticReferenceEligible",false);assertFlag(interpret(cue),"numericEligible",false);
            object(object(cue,"witness"),"sourceHistory").remove(field);
            assertFlag(interpret(cue),"staticReferenceEligible",false);assertFlag(interpret(cue),"numericEligible",false);
        }
    }
    @Test public void laterOriginalActorPoseCannotSupplyEarlierActualStyle() throws Exception
    {
        JsonObject cue=actor();cue.addProperty("cycle",131);cue.addProperty("sequence",21);
        assertFlag(interpret(cue),"staticReferenceEligible",false);assertFlag(interpret(cue),"numericEligible",false);
        cue=actor();cue.addProperty("observationKind","HP_CALLBACK_ACTOR_SAMPLE");cue.addProperty("cycle",130);cue.addProperty("sequence",20);cue.addProperty("numericEligible",false);
        assertFlag(interpret(cue),"staticReferenceEligible",true);assertFlag(interpret(cue),"numericEligible",false);
    }
    @Test public void onlyEarlierStatusSamplesCanAddAnIndependentStatusAlternative()
    {
        Integer[][] clocks={{2,null},{3,129},{3,130},{3,131},{3,null},{4,120}};
        for(int n=0;n<clocks.length;n++)
        {
            JsonObject input=gson.fromJson("{hit:{recipientRef:'player',amount:5,hitsplatType:2,family:'poison',tick:3,cycle:130,sequence:20,scope:{sceneKey:'native',worldViewId:0,plane:0}},cues:[{key:'status',pattern:'status_tick',evidenceMode:'STATUS_ENVELOPE',targetRef:'player',source:{kind:'status',statusFamily:'poison',familyKey:'status:poison_venom_client_state:poison',name:'Poison',scope:{sceneKey:'native',worldViewId:0,plane:0}},witness:{key:'poison_venom_client_state',state:'poison',valueObserved:true,active:true}}]}",JsonObject.class);
            input.getAsJsonObject("hit").addProperty("hitsplatType",net.runelite.api.HitsplatID.POISON);
            JsonObject cue=input.getAsJsonArray("cues").get(0).getAsJsonObject();cue.addProperty("tick",clocks[n][0]);
            if(clocks[n][1]!=null)cue.addProperty("cycle",clocks[n][1]);
            JsonObject result=ResolverTestCatalog.resolve(ResolverV1.load(gson),input);
            assertEquals("clock case "+n,n<2?1:0,result.getAsJsonArray("candidates").size());
            JsonObject typed=cue.deepCopy();typed.remove("evidenceMode");typed.addProperty("key","typed-fact");typed.addProperty("hitFamilyOnly",true);
            input.getAsJsonArray("cues").add(typed);
            assertTrue("Typed category remains despite missing original status timing",ResolverTestCatalog.resolve(ResolverV1.load(gson),input).getAsJsonArray("candidates").size()>0);
        }
    }

    @Test public void anUnreviewedOriginalLevelKeepsTheActorWithoutTheBaseOrdinaryCeiling()
    {
        for(int level:new int[]{230,999})
        {
            JsonObject cue=actor(),source=object(cue,"source"),state=object(cue,"originalActorState");
            source.addProperty("id",5779);source.addProperty("name","Giant Mole");source.addProperty("combatLevel",level);
            state.addProperty("npcId",5779);state.addProperty("name","Giant Mole");state.addProperty("combatLevel",level);
            cue.add("facts",gson.fromJson("{source_event:{world_mode:'NORMAL',source_model_overrides:false}}",JsonObject.class));
            JsonObject input=gson.fromJson("{hit:{recipientRef:'player',amount:22,hitsplatType:16,family:'ordinary',tick:3,cycle:130,sequence:20,scope:{sceneKey:'native',worldViewId:0,plane:0}}}",JsonObject.class);
            JsonArray cues=new JsonArray();cues.add(cue);input.add("cues",cues);
            JsonObject result=ResolverTestCatalog.resolve(ResolverV1.load(gson),input);boolean possible=false;
            for(JsonElement value:result.getAsJsonArray("candidates")) possible|="POSSIBLE".equals(value.getAsJsonObject().get("status").getAsString());
            assertEquals("Original combat level "+level,level==999,possible);
        }
    }

    private JsonObject api()
    {
        return gson.fromJson("{key:'api',pattern:'projectile_impact',evidenceMode:'API_PROJECTILE',targetRef:'player',apiSourceObserved:true,tick:3,cycle:130,sequence:10,source:{kind:'npc',id:900001,name:'Synthetic actor',combatLevel:100,actorRef:'npc:1',scope:{sceneKey:'native',worldViewId:0,plane:0}},signals:{projectileIds:[900001]},originalSourceObservation:{sourceEvidence:'ACTOR',actorKind:'NPC',sourceActorKnown:true,sourceNameKnown:true,sourceObservedName:'Synthetic actor',sourceNpcId:900001,sourceCombatLevel:100,sourceModelOverridesPresent:false,sourceNormalWorldContextObserved:true,sourceSceneKey:'native',sourceWorldViewId:0,sourcePlane:0,sourceRef:'npc:1',observedTick:2,observedCycle:100},witness:{observedCycle:100,sourceHistory:{version:1,actorChanged:false,referenceChanged:false,definitionChanged:false,scopeChanged:false,observationGap:false,projectileDefinitionChanged:false}}}",JsonObject.class);
    }
    private JsonObject actor()
    {
        JsonObject cue=api();cue.remove("apiSourceObserved");cue.remove("originalSourceObservation");cue.addProperty("evidenceMode","ACTOR_ACTIVITY");cue.addProperty("pattern","direct_attack");cue.addProperty("actorCallbackObserved",true);
        cue.add("originalActorState",gson.fromJson("{npcId:900001,name:'Synthetic actor',combatLevel:100,animationId:1,normalWorldContextObserved:true,modelOverridesPresent:false,worldViewId:0,plane:0}",JsonObject.class));return cue;
    }
    private JsonObject interpret(JsonObject cue) throws Exception
    {
        JsonObject hit=gson.fromJson("{recipientRef:'player',amount:5,hitsplatType:16,tick:3,cycle:130,sequence:20,scope:{sceneKey:'native',worldViewId:0,plane:0}}",JsonObject.class);
        Method method=ResolverV1.class.getDeclaredMethod("interpretedCue",JsonObject.class,JsonObject.class);method.setAccessible(true);
        return (JsonObject)method.invoke(ResolverV1.load(gson),cue,hit);
    }
    private static JsonObject object(JsonObject value,String field) { return value.getAsJsonObject(field); }
    private static void assertFlag(JsonObject value,String field,boolean expected) { assertEquals(field,expected,value.get(field).getAsBoolean()); }
}
