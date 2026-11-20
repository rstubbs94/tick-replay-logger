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
    @Test public void onePhysicalSourceMatchesEveryApplicableDeclaredRelationship()
    {
        JsonObject input=input();
        ResolverEvidence.Hit hit=DirectFixtureEvidence.hit(input);
        java.util.List<ResolverV1.Facts> facts=facts(engine(),input);
        ResolutionResult result=engine().evaluate(hit,facts);
        java.util.Set<String> rules=new java.util.LinkedHashSet<>();
        ResolutionCandidate endpoint=null;
        for(ResolutionCandidate row:result.candidates)
        {
            assertTrue("One physical source must not emit duplicate component rows",rules.add(row.ruleId));
            assertSame(hit.sources().get(0).raw(),row.source.raw());
            if("endpoint".equals(row.ruleId)) endpoint=row;
        }
        assertTrue(rules.contains("area"));assertNotNull(endpoint);
        assertFalse(Boolean.TRUE.equals(endpoint.componentQualified));assertNull(endpoint.maximum);
        assertTrue(endpoint.evidence.toString().contains("PROJECTILE_OBSERVATION_ID:7"));
    }
    @Test public void alternativeRelationshipsDoNotMutateOrCloneTheOriginalPayload()
    {
        JsonObject input=input();String before=input.toString();
        ResolverEvidence.Hit hit=DirectFixtureEvidence.hit(input);
        ResolutionResult result=engine().evaluate(hit,facts(engine(),input));
        assertTrue(result.candidates.size()>1);
        for(ResolutionCandidate row:result.candidates)
            assertSame(hit.sources().get(0).raw(),row.source.raw());
        assertEquals(before,input.toString());
    }
    @Test public void boundedOriginalSourcesCannotBeReportedAsCompleteWhenTruncated()
    {
        JsonObject input=input(),base=input.getAsJsonArray("cues").get(0).getAsJsonObject();JsonArray sources=new JsonArray();
        for(int n=0;n<ResolverV1.MAX_CUES+4;n++)
        {
            JsonObject source=base.deepCopy();source.addProperty("key","source:"+n);sources.add(source);
        }
        input.add("cues",sources);
        assertTrue(ResolverTestCatalog.resolve(engine(),input).get("truncated").getAsBoolean());
    }
    private java.util.List<ResolverV1.Facts> facts(ResolverV1 engine,JsonObject input)
    {
        java.util.List<ResolverV1.Facts> facts=new java.util.ArrayList<>();
        for(JsonElement cue:input.getAsJsonArray("cues")) facts.add(new DeclaredMechanicFacts(engine,input,cue.getAsJsonObject()));
        return facts;
    }
    private ResolverV1 engine()
    {
        return ResolverTestCatalog.engine(ResolverTestCatalog.lower(gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[{id:'ordinary',source:{kind:'npc',ids:[900001]},styles:['melee'],maximum:{kind:'fixed_max',value:10,authority:'verified'}}],specialRules:[{id:'area',source:{kind:'npc',ids:[900001]},pattern:'area_impact',signals:{projectileIds:[1001]},geometry:{shape:'point',anchor:'source_position',authority:'verified'},recipients:'within_area',outcome:{maximum:{kind:'fixed_max',value:20,authority:'verified'}}}],evidenceRules:[{id:'endpoint',evidenceMode:'ENDPOINT_COOCCURRENCE',source:{kind:'npc',ids:[900001]},declaration:{},admissionWhen:{all:[]},geometryRequired:true,geometry:{shape:'point',anchor:'source_position',authority:'reference_only'},clockPolicy:'SAME_RECORDED_TICK'}]}",JsonObject.class)));
    }
    private JsonObject input()
    {
        return gson.fromJson("{hit:{recipientRef:'player',amount:5,hitsplatType:16,family:'ordinary',tick:3,cycle:130,sequence:20,position:{x:3200,y:3200},scope:{sceneKey:'native',worldViewId:0,plane:0}},cues:[{key:'physical:7',cohortKey:'physical:7',pattern:'projectile_impact',evidenceMode:'API_PROJECTILE',ordinary:true,targetRef:'player',tick:3,cycle:130,sequence:19,position:{x:3200,y:3200},signals:{projectileIds:[1001]},source:{kind:'npc',id:900001,name:'Synthetic actor',actorRef:'synthetic:1',scope:{sceneKey:'native',worldViewId:0,plane:0}},witness:{projectileId:1001,observationId:7},channels:[{pattern:'projectile_impact',evidenceMode:'API_PROJECTILE'},{pattern:'area_impact',evidenceMode:'API_PROJECTILE',allowFallback:false},{key:'endpoint:7',pattern:'area_impact',evidenceMode:'ENDPOINT_COOCCURRENCE',numericEligible:false,componentBound:false,allowFallback:false}]}]}",JsonObject.class);
    }
}
