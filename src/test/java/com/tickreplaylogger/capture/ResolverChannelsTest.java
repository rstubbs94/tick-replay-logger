/*
 * Copyright (c) 2026, rstubbs94
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic relationship facets preserve one physical witness and separate declaration diagnostics. */
public class ResolverChannelsTest
{
    private final Gson gson=new Gson();
    @Test public void onePhysicalCueMatchesTheSameExplicitLegacyFacets()
    {
        JsonObject one=input(),base=one.getAsJsonArray("cues").get(0).getAsJsonObject();
        JsonObject legacy=one.deepCopy();JsonArray separate=new JsonArray();
        for(JsonElement value:base.getAsJsonArray("channels"))
        {
            JsonObject view=base.deepCopy();view.remove("channels");
            for(java.util.Map.Entry<String,JsonElement> field:value.getAsJsonObject().entrySet())view.add(field.getKey(),field.getValue());
            view.addProperty("cohortKey","physical:7");separate.add(view);
        }
        legacy.add("cues",separate);ResolverV1 engine=engine();
        assertEquals(ResolverTestCatalog.resolve(engine,legacy),ResolverTestCatalog.resolve(engine,one));
        JsonObject endpoint=null;
        for(JsonElement value:ResolverTestCatalog.resolve(engine,one).getAsJsonArray("candidates"))
            if("endpoint".equals(value.getAsJsonObject().get("ruleId").getAsString()))endpoint=value.getAsJsonObject();
        assertNotNull(endpoint);assertFalse(endpoint.get("componentQualified").getAsBoolean());assertFalse(endpoint.has("maximum"));
        assertTrue(endpoint.getAsJsonArray("evidence").toString().contains("PROJECTILE_OBSERVATION_ID:7"));
    }
    @Test public void endpointOverridesDoNotMutateOrCopyTheOriginalPayload()
    {
        JsonObject input=input(),base=input.getAsJsonArray("cues").get(0).getAsJsonObject();String before=input.toString();
        JsonArray views=ResolverV1.expandChannels(input.getAsJsonArray("cues"));assertEquals(3,views.size());
        JsonObject endpoint=views.get(2).getAsJsonObject();assertSame(base.get("witness"),endpoint.get("witness"));
        assertEquals("physical:7",endpoint.get("cohortKey").getAsString());assertEquals("endpoint:7",endpoint.get("key").getAsString());
        assertFalse(endpoint.get("numericEligible").getAsBoolean());assertFalse(endpoint.get("componentBound").getAsBoolean());
        assertFalse(endpoint.get("allowFallback").getAsBoolean());assertEquals(before,input.toString());
    }
    @Test public void evaluatedFacetLimitCannotBeReportedAsComplete()
    {
        JsonObject input=input(),base=input.getAsJsonArray("cues").get(0).getAsJsonObject();JsonArray facets=new JsonArray();
        for(int n=0;n<ResolverV1.MAX_CUES+4;n++)facets.add(gson.fromJson("{pattern:'projectile_impact',evidenceMode:'API_PROJECTILE'}",JsonObject.class));
        base.add("channels",facets);assertTrue(ResolverTestCatalog.resolve(engine(),input).get("truncated").getAsBoolean());
    }
    private ResolverV1 engine()
    {
        return new ResolverV1(ResolverTestCatalog.lower(gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[{id:'ordinary',source:{kind:'npc',ids:[900001]},styles:['melee'],maximum:{kind:'fixed_max',value:10,authority:'verified'}}],specialRules:[{id:'area',source:{kind:'npc',ids:[900001]},pattern:'area_impact',signals:{projectileIds:[1001]},geometry:{shape:'point',anchor:'source_position',authority:'verified'},recipients:'within_area',outcome:{maximum:{kind:'fixed_max',value:20,authority:'verified'}}}],evidenceRules:[{id:'endpoint',evidenceMode:'ENDPOINT_COOCCURRENCE',source:{kind:'npc',ids:[900001]},declaration:{},admissionWhen:{all:[]},geometryRequired:true,geometry:{shape:'point',anchor:'source_position',authority:'reference_only'},clockPolicy:'SAME_RECORDED_TICK'}]}",JsonObject.class)));
    }
    private JsonObject input()
    {
        return gson.fromJson("{hit:{recipientRef:'player',amount:5,hitsplatType:16,family:'ordinary',tick:3,cycle:130,sequence:20,position:{x:3200,y:3200},scope:{sceneKey:'native',worldViewId:0,plane:0}},cues:[{key:'physical:7',cohortKey:'physical:7',pattern:'projectile_impact',evidenceMode:'API_PROJECTILE',ordinary:true,targetRef:'player',tick:3,cycle:130,sequence:19,position:{x:3200,y:3200},signals:{projectileIds:[1001]},source:{kind:'npc',id:900001,name:'Synthetic actor',actorRef:'synthetic:1',scope:{sceneKey:'native',worldViewId:0,plane:0}},witness:{projectileId:1001,observationId:7},channels:[{pattern:'projectile_impact',evidenceMode:'API_PROJECTILE'},{pattern:'area_impact',evidenceMode:'API_PROJECTILE',allowFallback:false},{key:'endpoint:7',pattern:'area_impact',evidenceMode:'ENDPOINT_COOCCURRENCE',numericEligible:false,componentBound:false,allowFallback:false}]}]}",JsonObject.class);
    }
}
