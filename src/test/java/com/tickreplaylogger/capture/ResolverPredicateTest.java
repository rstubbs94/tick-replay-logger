/*
 * Copyright (c) 2026, rstubbs94
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Closed predicate truth tables; these are not game recordings or damage-source coverage claims. */
public class ResolverPredicateTest
{
	private final Gson gson=new Gson();
	@Test public void nullableFlagsHaveExplicitThreeStateSemantics()
	{
		for(String op:Arrays.asList("not_true","not_false"))
		{
			boolean inverse="not_true".equals(op);
			check(op,null,null,"TRUE");check(op,JsonNull.INSTANCE,null,"TRUE");
			check(op,new JsonPrimitive(true),null,inverse?"FALSE":"TRUE");
			check(op,new JsonPrimitive(false),null,inverse?"TRUE":"FALSE");
			check(op,new JsonPrimitive("false"),null,"UNKNOWN");
		}
	}
	@Test public void numericComparisonsNeverDefaultMissingOrCoerceStrings()
	{
		for(String op:Arrays.asList("gt","gte","lt","lte"))
		{
			for(int value:new int[]{-1,0,1})
			{
				boolean match="gt".equals(op)?value>0:"gte".equals(op)?value>=0:"lt".equals(op)?value<0:value<=0;
				check(op,new JsonPrimitive(value),new JsonPrimitive(0),match?"TRUE":"FALSE");
			}
			check(op,null,new JsonPrimitive(0),"UNKNOWN");
			check(op,new JsonPrimitive("0"),new JsonPrimitive(0),"UNKNOWN");
		}
	}
	@Test public void recordedFactComparisonRetainsNamespaceAndMissingDistinctions()
	{
		JsonObject input=input(),when=condition("equals",null);when.add("valueFrom",gson.fromJson("{at:'hit',fact:'recorded.value'}",JsonObject.class));
		input.getAsJsonObject("hit").add("recorded",gson.fromJson("{value:7}",JsonObject.class));input.getAsJsonArray("cues").get(0).getAsJsonObject().addProperty("probe",7);
		assertTruth(input,when,"TRUE");input.getAsJsonObject("hit").getAsJsonObject("recorded").addProperty("value",8);assertTruth(input,when,"FALSE");
		input.getAsJsonObject("hit").remove("recorded");assertTruth(input,when,"UNKNOWN");
	}
	@Test public void errorPrefixesAreBoundedAndWrongShapesRemainUnknown()
	{
		JsonArray prefixes=gson.fromJson("['source.']",JsonArray.class);
		check("no_issue",null,prefixes,"TRUE");check("no_issue",new JsonObject(),prefixes,"TRUE");
		check("no_issue",gson.fromJson("{other:'failed'}",JsonObject.class),prefixes,"TRUE");
		check("no_issue",gson.fromJson("{'source.name':'failed'}",JsonObject.class),prefixes,"FALSE");
		check("no_issue",gson.fromJson("['source.name']",JsonArray.class),prefixes,"FALSE");
		check("no_issue",new JsonPrimitive("source.name"),prefixes,"UNKNOWN");
		check("no_issue",gson.fromJson("[7]",JsonArray.class),prefixes,"UNKNOWN");
		JsonArray excessive=new JsonArray();for(int n=0;n<=ResolverV1.MAX_CUES;n++)excessive.add("x");check("no_issue",excessive,prefixes,"UNKNOWN");
	}
	@Test public void permittedNullableMarkerCannotSupplyUnknownNormalModelOrNativeProof()
	{
		JsonArray predicates=new JsonArray();predicates.add(condition("not_true",null));
		JsonObject model=condition("equals",new JsonPrimitive(false));model.addProperty("fact","original.modelOverridesPresent");predicates.add(model);
		JsonObject normal=condition("equals",new JsonPrimitive(true));normal.addProperty("fact","original.normalWorldContextObserved");predicates.add(normal);
		assertTruth(input(),predicates,"UNKNOWN");
		JsonObject observed=input();observed.getAsJsonArray("cues").get(0).getAsJsonObject().add("original",gson.fromJson("{modelOverridesPresent:false,normalWorldContextObserved:true}",JsonObject.class));
		assertTruth(observed,predicates,"TRUE");
	}
	@Test public void pathsCannotEscapeBoundedRecordedJson()
	{
		JsonObject predicate=condition("not_true",null);predicate.addProperty("fact",String.join(".",Collections.nCopies(14,"a")));assertTruth(input(),predicate,"UNKNOWN");
		predicate.addProperty("fact",String.join("",Collections.nCopies(257,"a")));assertTruth(input(),predicate,"UNKNOWN");
	}
	@Test public void allAndAnyPreserveThreeStateTruthTables()
	{
		for(String operator:Arrays.asList("all","any"))
			for(int left=0;left<3;left++) for(int right=0;right<3;right++)
			{
				JsonObject input=input(),cue=input.getAsJsonArray("cues").get(0).getAsJsonObject();
				if(left<2) cue.addProperty("left",left==1);if(right<2) cue.addProperty("right",right==1);
				JsonObject group=gson.fromJson("{"+operator+":[{at:'cue',fact:'left',op:'equals',value:true},{at:'cue',fact:'right',op:'equals',value:true}]}",JsonObject.class);
				String expected="all".equals(operator)?left==0||right==0?"FALSE":left==2||right==2?"UNKNOWN":"TRUE"
					:left==1||right==1?"TRUE":left==2||right==2?"UNKNOWN":"FALSE";
				assertTruth(input,group,expected);
			}
	}
	@Test public void groupedPredicatesCannotTurnMissingFactsOrExcessDepthIntoProof()
	{
		JsonObject condition=condition("equals",new JsonPrimitive(true));
		for(int depth=0;depth<15;depth++) { JsonArray children=new JsonArray();children.add(condition);JsonObject group=new JsonObject();group.add("all",children);condition=group; }
		assertTruth(input(),condition,"UNKNOWN");
		JsonObject malformed=gson.fromJson("{all:[],any:[]}",JsonObject.class);assertTruth(input(),malformed,"UNKNOWN");
	}
	@Test public void valueFromUsesTheSameOriginalSourceNamespaceAsTheCondition()
	{
		JsonObject input=input(),cue=input.getAsJsonArray("cues").get(0).getAsJsonObject();
		cue.addProperty("probe",7);cue.add("facts",gson.fromJson("{source_event:{original_id:7},impact:{source_id:7}}",JsonObject.class));
		for(String domain:Arrays.asList("source_event","impact"))
		{
			JsonObject condition=condition("equals",null),from=new JsonObject();from.addProperty("at",domain);from.addProperty("fact","impact".equals(domain)?"source_id":"original_id");condition.add("valueFrom",from);
			assertTruth(input,condition,"TRUE");
		}
	}

	@Test public void closedTypesNeverCoerceOrTreatMissingAsProof()
	{
		Map<String,JsonElement> examples=new LinkedHashMap<>();
		examples.put("string",new JsonPrimitive("7"));examples.put("integer",new JsonPrimitive(7));
		examples.put("number",new JsonPrimitive(1.5));examples.put("boolean",new JsonPrimitive(true));
		examples.put("nonempty_object",gson.fromJson("{observed:true}",JsonObject.class));
		for(Map.Entry<String,JsonElement> type:examples.entrySet())
		{
			check("type_is",type.getValue(),new JsonPrimitive(type.getKey()),"TRUE");
			check("type_is",null,new JsonPrimitive(type.getKey()),"UNKNOWN");
			check("type_is",JsonNull.INSTANCE,new JsonPrimitive(type.getKey()),"UNKNOWN");
			check("type_is",new JsonArray(),new JsonPrimitive(type.getKey()),"FALSE");
		}
		check("type_is",new JsonPrimitive("7"),new JsonPrimitive("number"),"FALSE");
		check("type_is",new JsonPrimitive("7"),new JsonPrimitive("integer"),"FALSE");
		check("type_is",new JsonPrimitive(1.5),new JsonPrimitive("integer"),"FALSE");
		check("type_is",new JsonPrimitive("true"),new JsonPrimitive("boolean"),"FALSE");
		check("type_is",new JsonObject(),new JsonPrimitive("nonempty_object"),"FALSE");
		check("type_is",new JsonPrimitive(7),new JsonPrimitive("arbitrary_type"),"UNKNOWN");
	}

	private void check(String op,JsonElement actual,JsonElement expected,String truth)
	{
		JsonObject input=input();if(actual!=null)input.getAsJsonArray("cues").get(0).getAsJsonObject().add("probe",actual);assertTruth(input,condition(op,expected),truth);
	}
	private JsonObject condition(String op,JsonElement value)
	{
		JsonObject result=new JsonObject();result.addProperty("at","cue");result.addProperty("fact","probe");result.addProperty("op",op);if(value!=null)result.add("value",value);return result;
	}
	private JsonObject input()
	{
		return gson.fromJson("{hit:{recipientRef:'player',amount:1,family:'ordinary',hitsplatType:16,scope:{sceneKey:'synthetic',worldViewId:0,plane:0}},cues:[{key:'predicate',ordinary:true,pattern:'direct_attack',targetRef:'player',source:{kind:'npc',id:900001,actorRef:'synthetic:1',scope:{sceneKey:'synthetic',worldViewId:0,plane:0}}}]}",JsonObject.class);
	}
	private void assertTruth(JsonObject input,JsonElement predicates,String truth)
	{
		JsonObject document=gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[{id:'predicate.profile',source:{kind:'npc',ids:[900001]},styles:['melee']}]} " ,JsonObject.class);
		if(!(predicates instanceof JsonArray)){JsonArray values=new JsonArray();values.add(predicates);predicates=values;}
		document.getAsJsonArray("ordinaryProfiles").get(0).getAsJsonObject().add("when",predicates);
		JsonObject row=ResolverTestCatalog.resolve(new ResolverV1(ResolverTestCatalog.lower(document)),input).getAsJsonArray("candidates").get(0).getAsJsonObject();
		assertEquals(truth,"FALSE".equals(truth)?"EXCLUDED":"POSSIBLE",row.get("status").getAsString());
		assertEquals(truth,"TRUE".equals(truth),row.get("componentQualified").getAsBoolean());
	}
}
