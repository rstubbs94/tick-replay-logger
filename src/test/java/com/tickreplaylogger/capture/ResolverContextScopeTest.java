/*
 * Copyright (c) 2026, rstubbs94
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Known native lifetime contradictions cannot become causal alternatives through another evidence channel. */
public class ResolverContextScopeTest
{
    private final Gson gson=new Gson();
    private final ResolverV1 engine=new ResolverV1(ResolverTestCatalog.lower(gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution'}",JsonObject.class)));
    @Test public void everyCausalEvidenceModeHonorsKnownOriginalNativeConflicts()
    {
        for(String mode:Arrays.asList("ACTOR_ACTIVITY","API_PROJECTILE","RETAINED_NAMED","ORIGINAL_CONTACT",
            "STATUS_ENVELOPE","INPUT_REQUEST","INVENTORY_POSSESSION","SYSTEM_RESPONSE","CONSUMPTION"))
            for(String field:Arrays.asList("sceneKey","worldViewId","plane","contextEpoch"))
            {
                JsonObject input=input(),cue=cue(input),scope=cue.getAsJsonObject("source").getAsJsonObject("scope");cue.addProperty("evidenceMode",mode);
                if("sceneKey".equals(field))scope.addProperty(field,"other");else scope.addProperty(field,9);
                if("contextEpoch".equals(field))input.getAsJsonObject("hit").getAsJsonObject("scope").addProperty(field,1);
                assertConflict(mode+":"+field,ResolverTestCatalog.resolve(engine,input));
            }
    }
    @Test public void originalMatchedSourceAndHpSurviveALaterFinalizationReload()
    {
        JsonObject input=input(),hit=input.getAsJsonObject("hit");hit.addProperty("contextEpoch",1);hit.addProperty("finalizationContextEpoch",2);
        cue(input).getAsJsonObject("source").getAsJsonObject("scope").addProperty("contextEpoch",1);
        JsonObject result=ResolverTestCatalog.resolve(engine,input);assertEquals(1,result.getAsJsonArray("candidates").size());
        JsonObject row=result.getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertEquals("POSSIBLE",row.get("status").getAsString());assertEquals("Recorded actor",row.getAsJsonObject("source").get("name").getAsString());
        assertTrue(result.get("unidentifiedPossible").getAsBoolean());
    }
    @Test public void originalCallbackAndImpactEpochContradictionRejectsCausalRows()
    {
        JsonObject input=input(),hit=input.getAsJsonObject("hit");hit.addProperty("contextEpoch",1);
        hit.add("originalHpCallback",gson.fromJson("{causalSceneEpoch:2}",JsonObject.class));assertConflict("original callback",ResolverTestCatalog.resolve(engine,input));
    }
    @Test public void absentOptionalEpochDoesNotInventAConflictOrDeleteObservedIdentity()
    {
        JsonObject input=input();cue(input).getAsJsonObject("source").getAsJsonObject("scope").addProperty("contextEpoch",1);
        JsonObject result=ResolverTestCatalog.resolve(engine,input);assertEquals(1,result.getAsJsonArray("candidates").size());
        assertEquals("POSSIBLE",result.getAsJsonArray("candidates").get(0).getAsJsonObject().get("status").getAsString());
        assertTrue(result.get("unidentifiedPossible").getAsBoolean());
    }
    @Test public void observedTypedCategorySurvivesLaterContextChange()
    {
        JsonObject input=input(),hit=input.getAsJsonObject("hit"),cue=cue(input);hit.addProperty("contextEpoch",1);hit.addProperty("finalizationContextEpoch",2);
        hit.addProperty("hitsplatType",net.runelite.api.HitsplatID.POISON);hit.addProperty("family","poison");
        cue.addProperty("pattern","status_tick");cue.addProperty("hitFamilyOnly",true);
        cue.add("source",gson.fromJson("{kind:'status',statusFamily:'poison',name:'Poison',scope:{sceneKey:'native',worldViewId:0,plane:0}}",JsonObject.class));
        JsonObject result=ResolverTestCatalog.resolve(engine,input);assertEquals(1,result.getAsJsonArray("candidates").size());
        assertEquals("POSSIBLE",result.getAsJsonArray("candidates").get(0).getAsJsonObject().get("status").getAsString());
    }
    private JsonObject input()
    {
        return gson.fromJson("{hit:{recipientRef:'player',amount:5,hitsplatType:16,family:'ordinary',tick:3,cycle:130,sequence:20,scope:{sceneKey:'native',worldViewId:0,plane:0}},cues:[{key:'original',pattern:'direct_attack',targetRef:'player',source:{kind:'npc',id:900001,name:'Recorded actor',actorRef:'npc:original',scope:{sceneKey:'native',worldViewId:0,plane:0}}}]}",JsonObject.class);
    }
    private static JsonObject cue(JsonObject input) { return input.getAsJsonArray("cues").get(0).getAsJsonObject(); }
    private static void assertConflict(String description,JsonObject result)
    {
        assertEquals(description,1,result.getAsJsonArray("candidates").size());JsonObject row=result.getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertEquals(description,"EXCLUDED",row.get("status").getAsString());
        assertTrue(description,row.getAsJsonArray("reasons").toString().contains("RECORDED_NATIVE_CONTEXT_CONFLICT"));
        assertEquals("Recorded actor",row.getAsJsonObject("source").get("name").getAsString());
        assertTrue(result.get("unidentifiedPossible").getAsBoolean());
    }
}
