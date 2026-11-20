/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import org.junit.runners.model.MultipleFailureException;
import static org.junit.Assert.*;

/** Differential checks against the frozen evaluator, in addition to independently pinned outcomes. */
public class ResolverIrParityTest
{
    private final Gson gson=new Gson();
    @Test public void bundledSourceBoundInputsKeepEveryCandidateAndQualification() throws Exception
    {
        ResolverV1 current=ResolverV1.load(gson);
        PreRuleIrResolverV1 prior=PreRuleIrResolverV1.load(gson);
        JsonObject declarations=RuntimeCatalog.load(gson).section("resolver");
        int checked=0;List<Throwable> failures=new ArrayList<>();
        for(JsonElement value:resource("resolver-source-bounds.synthetic.json").getAsJsonArray("cases"))
        {
            JsonObject test=value.getAsJsonObject(),input=test.getAsJsonObject("input");
            try
            {
                JsonObject oldResult=prior.resolve(input.deepCopy()),newResult=ResolverTestCatalog.resolve(current,input.deepCopy());
                assertBoundDiagnostics(test,oldResult,newResult);
                assertEquals(test.get("id").getAsString(),signature(oldResult,declarations),signature(newResult,declarations));
            }
            catch(AssertionError | RuntimeException failure)
            {
                failures.add(new AssertionError(test.get("id").getAsString(),failure));
            }
            checked++;
        }
        assertEquals(528,checked);
        MultipleFailureException.assertEmpty(failures);
    }
    @Test public void syntheticComponentsUseTheSameFoldAfterExplicitAuthoringLowering() throws Exception
    {
        int checked=0;List<Throwable> failures=new ArrayList<>();
        for(JsonElement value:resource("resolver-v1-conformance.json").getAsJsonArray("cases"))
        {
            JsonObject test=value.getAsJsonObject(),catalog=test.getAsJsonObject("catalog"),input=test.getAsJsonObject("input");
            String id=test.get("id").getAsString();
            // This case independently pins the documented correction: an actual API owner survives foreign sender coordinates.
            if("owned_foreign_native_source_keeps_identity_without_spatial_or_numeric_proof".equals(id)) continue;
            try
            {
                if("pattern_status_tick".equals(id))
                {
                    JsonObject original=input.deepCopy();
                    JsonObject result=ResolverTestCatalog.resolve(ResolverTestCatalog.engine(ResolverTestCatalog.lower(catalog)),input);
                    assertEquals(1,result.getAsJsonArray("candidates").size());
                    JsonObject row=result.getAsJsonArray("candidates").get(0).getAsJsonObject();
                    assertEquals("synthetic:component",row.get("ruleId").getAsString());
                    assertEquals("POSSIBLE",row.get("status").getAsString());
                    assertEquals("status",row.getAsJsonObject("source").get("kind").getAsString());
                    assertEquals("venom",row.getAsJsonObject("source").get("statusFamily").getAsString());
                    assertFalse(row.getAsJsonObject("source").has("actorRef"));
                    assertFalse(row.get("componentIdentified").getAsBoolean());
                    assertFalse(row.get("componentQualified").getAsBoolean());
                    assertTrue(result.get("unidentifiedPossible").getAsBoolean());
                    assertEquals(original,input);
                    continue;
                }
                JsonObject declarations=ResolverTestCatalog.lower(catalog);
                if(Arrays.asList("passive_source_requires_same_native_view",
                    "passive_source_other_instance_does_not_generate_alternative").contains(id))
                {
                    JsonObject original=input.deepCopy();
                    ResolverEvidence.Hit declared=DirectFixtureEvidence.hit(input);
                    assertEquals(1,declared.sources().size());
                    ResolverEvidence.Source source=declared.sources().get(0);
                    assertEquals(ResolverEvidence.Channel.NPC_EMITTER,source.channel());
                    assertEquals(ResolverEvidence.Truth.FALSE,
                        OriginalSourceProof.scope(source.identity().scope,declared.callback().scope));
                    assertTrue(input.getAsJsonArray("cues").get(0).getAsJsonObject()
                        .get("nativePresenceObserved").getAsBoolean());
                    JsonObject prior=new PreRuleIrResolverV1(catalog).resolve(input.deepCopy());
                    assertEquals(1,prior.getAsJsonArray("candidates").size());
                    JsonObject inactive=prior.getAsJsonArray("candidates").get(0).getAsJsonObject();
                    assertEquals("EXCLUDED",inactive.get("status").getAsString());
                    assertTrue(inactive.getAsJsonArray("reasons").contains(
                        new JsonPrimitive("RECORDED_NATIVE_CONTEXT_CONFLICT")));
                    // Presence in a foreign scope is not a matched attack, including an excluded component.
                    // No candidate means no component, identity, maximum or scalar-source claim is emitted.
                    JsonObject result=ResolverTestCatalog.resolve(ResolverTestCatalog.engine(declarations),input);
                    assertEquals(0,result.getAsJsonArray("candidates").size());
                    assertFalse(result.has("scalarAssociation"));
                    assertTrue(result.get("unidentifiedPossible").getAsBoolean());
                    assertEquals(original,input);
                    continue;
                }
                if("owned_unknown_variant_is_not_erased_by_another_named_component".equals(id))
                {
                    JsonObject original=input.deepCopy(),prior=new PreRuleIrResolverV1(catalog).resolve(input.deepCopy());
                    JsonObject result=ResolverTestCatalog.resolve(ResolverTestCatalog.engine(declarations),input);
                    assertEquals(1,result.getAsJsonArray("candidates").size());
                    JsonArray priorOwners=new JsonArray();boolean rejectedName=false;
                    for(JsonElement previous:prior.getAsJsonArray("candidates"))
                    {
                        JsonObject row=previous.getAsJsonObject();
                        if("observed:projectile_impact".equals(row.get("ruleId").getAsString()))priorOwners.add(row);
                        else
                        {
                            assertEquals("synthetic:component",row.get("ruleId").getAsString());
                            assertEquals("EXCLUDED",row.get("status").getAsString());
                            assertFalse(row.get("identityQualified").getAsBoolean());rejectedName=true;
                        }
                    }
                    assertTrue(rejectedName);assertEquals(1,priorOwners.size());
                    JsonObject priorOwnerOnly=prior.deepCopy();priorOwnerOnly.add("candidates",priorOwners);
                    assertEquals(signature(priorOwnerOnly,declarations),signature(result,declarations));
                    JsonObject owner=result.getAsJsonArray("candidates").get(0).getAsJsonObject();
                    assertEquals("observed:projectile_impact",owner.get("ruleId").getAsString());
                    assertEquals("POSSIBLE",owner.get("status").getAsString());
                    assertFalse(owner.get("componentIdentified").getAsBoolean());
                    assertFalse(owner.get("componentQualified").getAsBoolean());assertFalse(owner.has("maximum"));
                    assertEquals("UNKNOWN",owner.get("confidence").getAsString());
                    assertTrue(result.get("unidentifiedPossible").getAsBoolean());assertEquals(original,input);
                    continue;
                }
                if(Arrays.asList("false_rule_condition_excludes_only_component",
                    "source_impact_cannot_override_original_receiver_prayers").contains(id))
                {
                    JsonObject original=input.deepCopy(),prior=new PreRuleIrResolverV1(catalog).resolve(input.deepCopy());
                    JsonObject result=ResolverTestCatalog.resolve(ResolverTestCatalog.engine(declarations),input);
                    assertEquals(2,result.getAsJsonArray("candidates").size());
                    JsonArray components=new JsonArray();JsonObject fallback=null;
                    for(JsonElement candidate:result.getAsJsonArray("candidates"))
                        if(candidate.getAsJsonObject().get("ruleId").getAsString().startsWith("observed:"))fallback=candidate.getAsJsonObject();
                        else components.add(candidate);
                    JsonObject componentOnly=result.deepCopy();componentOnly.add("candidates",components);
                    assertEquals(signature(prior,declarations),signature(componentOnly,declarations));
                    JsonObject component=components.get(0).getAsJsonObject();
                    assertEquals("EXCLUDED",component.get("status").getAsString());
                    assertTrue(component.getAsJsonArray("reasons").contains(new JsonPrimitive("CONDITION_MISMATCH")));
                    assertNotNull(fallback);assertEquals("POSSIBLE",fallback.get("status").getAsString());
                    assertEquals(component.get("source"),fallback.get("source"));
                    assertEquals(component.get("sourceKey"),fallback.get("sourceKey"));
                    assertFalse(fallback.get("componentIdentified").getAsBoolean());
                    assertFalse(fallback.get("componentQualified").getAsBoolean());
                    assertFalse(fallback.has("maximum"));
                    assertEquals("UNKNOWN",fallback.get("confidence").getAsString());
                    assertTrue(result.get("unidentifiedPossible").getAsBoolean());assertEquals(original,input);
                    continue;
                }
                assertEquals(id,signature(new PreRuleIrResolverV1(catalog).resolve(input.deepCopy()),declarations),
                    signature(ResolverTestCatalog.resolve(ResolverTestCatalog.engine(declarations),input.deepCopy()),declarations));
            }
            catch(AssertionError | RuntimeException failure)
            {
                failures.add(new AssertionError(id,failure));
            }
            finally
            {
                checked++;
            }
        }
        assertEquals(108,checked);
        MultipleFailureException.assertEmpty(failures);
    }
    @Test public void authoringTablesAreNotASecondRuntimeDialect()
    {
        JsonObject old=gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[]}",JsonObject.class);
        assertFalse(ResolverTestCatalog.engine(old).isAvailable());
        assertTrue(ResolverTestCatalog.engine(ResolverTestCatalog.lower(old)).isAvailable());
        JsonObject packaged=RuntimeCatalog.load(gson).section("resolver");
        assertTrue(packaged.has("resolutionRules"));
        for(String key:Arrays.asList("ordinaryProfiles","specialRules","qualifiedLimits","evidenceRules","inputLimits",
            "sourceBounds","sourceAssociations","activityClassifications","contactPolicies","outcomeBindings")) assertFalse(key,packaged.has(key));
    }
    private List<String> signature(JsonObject output,JsonObject declarations)
    {
        Map<String,String> families=new HashMap<>();
        for(JsonElement value:output.getAsJsonArray("candidates"))
        {
            JsonObject row=value.getAsJsonObject(),source=row.getAsJsonObject("source");
            String key=row.get("sourceKey").getAsString();
            for(String alias:Arrays.asList("familyKey","referenceFamilyKey"))
                if(source.has(alias) && !source.get(alias).isJsonNull())
                {
                    String family=source.get(alias).getAsString(),previous=families.putIfAbsent(key,family);
                    assertTrue("Differing non-null family aliases cannot be discarded",previous==null || previous.equals(family));
                }
        }
        List<String> rows=new ArrayList<>();
        for(JsonElement value:output.getAsJsonArray("candidates"))
        {
            if(redundantFallback(value.getAsJsonObject(),output.getAsJsonArray("candidates"),declarations))continue;
            JsonObject row=value.getAsJsonObject().deepCopy(),picked=new JsonObject(),source=row.getAsJsonObject("source");
            String family=families.get(row.get("sourceKey").getAsString());
            source.remove("referenceFamilyKey");if(family!=null)source.addProperty("familyKey",family);
            boolean fallback=row.get("ruleId").getAsString().startsWith("observed:")
                && (!row.has("componentIdentified") || !row.get("componentIdentified").getAsBoolean());
            if(fallback)
            {
                // An unknown component may retain a qualified whole-source ceiling without becoming an ordinary attack.
                assertFalse(row.has("componentQualified") && row.get("componentQualified").getAsBoolean());
                row.addProperty("ordinary",false);row.addProperty("componentIdentified",false);row.addProperty("componentQualified",false);
                if(row.has("maximum"))assertTrue(row.get("maximum").isJsonPrimitive() && row.get("maximum").getAsJsonPrimitive().isNumber());
            }
            for(String field:Arrays.asList("ruleId","sourceKey","source","status","maximum","exclusionScope","ordinary",
                "componentIdentified","componentQualified","identityQualified","sourceAssociation"))
                if(row.has(field))picked.add(field,row.get(field));
            if(row.has("reasons") && row.getAsJsonArray("reasons").toString().contains("RECORDED_NATIVE_CONTEXT_CONFLICT"))
            {
                assertEquals("EXCLUDED",row.get("status").getAsString());
                assertTrue(row.getAsJsonArray("reasons").contains(new JsonPrimitive("RECORDED_NATIVE_CONTEXT_CONFLICT")));
                picked.addProperty("ruleId","observed:native_context_conflict");
                // Older conflict-only diagnostics omitted these false flags; they never qualify an identity/component.
                for(String flag:Arrays.asList("componentIdentified","componentQualified","identityQualified"))
                {
                    assertFalse(Boolean.TRUE.equals(ResolverEvidence.bool(row.get(flag))));
                    picked.addProperty(flag,false);
                }
                picked.add("reasons",row.get("reasons"));
            }
            rows.add(canonical(picked).toString());
        }
        Collections.sort(rows);return rows;
    }
    private boolean redundantFallback(JsonObject fallback,JsonArray rows,JsonObject declarations)
    {
        String id=fallback.get("ruleId").getAsString();
        if(!id.startsWith("observed:") || Boolean.TRUE.equals(ResolverEvidence.bool(fallback.get("componentIdentified"))))return false;
        String pattern=id.substring("observed:".length()),key=fallback.get("key").getAsString();
        String physical=key.substring(0,key.length()-id.length());
        for(JsonElement value:rows)
        {
            JsonObject other=value.getAsJsonObject();String otherId=other.get("ruleId").getAsString();
            if(otherId.startsWith("observed:") || !other.get("key").getAsString().equals(physical+otherId)
                || !Objects.equals(fallback.get("sourceKey"),other.get("sourceKey"))
                || Boolean.TRUE.equals(ResolverEvidence.bool(other.get("componentIdentified"))))continue;
            boolean same=true;
            for(String field:Arrays.asList("status","maximum","exclusionScope","reasons"))
                same&=Objects.equals(fallback.get(field),other.get(field));
            if(!same)continue;
            for(JsonElement rule:declarations.getAsJsonArray("resolutionRules"))
            {
                JsonObject declaration=rule.getAsJsonObject();
                if(!otherId.equals(ResolverEvidence.text(declaration,"id"))
                    || !(Boolean.TRUE.equals(ResolverEvidence.bool(declaration.get("profile")))
                        || pattern.equals(ResolverEvidence.text(declaration,"pattern"))))continue;
                for(JsonElement evidence:fallback.getAsJsonArray("evidence"))
                {
                    String token=evidence.getAsString();
                    if(token.startsWith("OBSERVATION_SEQUENCE:") || token.startsWith("PROJECTILE_OBSERVATION_ID:"))
                        assertTrue(other.getAsJsonArray("evidence").contains(evidence));
                }
                return true;
            }
        }
        return false;
    }
    private void assertBoundDiagnostics(JsonObject test,JsonObject oldResult,JsonObject newResult)
    {
        String scenario=test.get("scenario").getAsString(),bound=test.get("boundId").getAsString();
        boolean applied="at_limit".equals(scenario) || "above_limit".equals(scenario);
        assertTrue(newResult.getAsJsonArray("candidates").size()>0);
        for(JsonElement value:newResult.getAsJsonArray("candidates"))
        {
            JsonObject row=value.getAsJsonObject();
            assertEquals(applied,contains(row.getAsJsonArray("evidence"),"SOURCE_BOUND:"+bound));
            if(applied)
            {
                assertEquals(0,test.get("maximum").getAsBigDecimal().compareTo(row.get("maximum").getAsBigDecimal()));
                if(row.get("ruleId").getAsString().startsWith("observed:"))
                {
                    assertFalse(row.get("componentIdentified").getAsBoolean());
                    assertFalse(row.get("componentQualified").getAsBoolean());
                    assertFalse(row.get("ordinary").getAsBoolean());
                }
            }
            if("above_limit".equals(scenario))
            {
                assertEquals("EXCLUDED",row.get("status").getAsString());
                assertEquals("ALL_DIRECT_HP_COMPONENTS",row.get("exclusionScope").getAsString());
                assertTrue(contains(row.getAsJsonArray("reasons"),"MAXIMUM_EXCEEDED"));
                assertTrue(contains(row.getAsJsonArray("reasons"),"MAXIMUM_EXCEEDED:"+bound));
            }
            else if(!"nonordinary_type".equals(scenario))assertEquals(0,row.getAsJsonArray("reasons").size());
            else
            {
                // The new detailed mask receipt supplements, rather than replaces, the old family rejection.
                JsonObject previous=null;
                for(JsonElement prior:oldResult.getAsJsonArray("candidates"))
                    if(prior.getAsJsonObject().get("ruleId").equals(row.get("ruleId")))previous=prior.getAsJsonObject();
                if(previous!=null)for(JsonElement reason:previous.getAsJsonArray("reasons"))
                    assertTrue(row.getAsJsonArray("reasons").contains(reason));
            }
        }
        if("above_limit".equals(scenario))for(JsonElement value:oldResult.getAsJsonArray("candidates"))
            assertTrue(contains(value.getAsJsonObject().getAsJsonArray("reasons"),"SOURCE_MAXIMUM_EXCEEDED"));
    }
    private boolean contains(JsonArray values,String expected)
    {
        return values.contains(new JsonPrimitive(expected));
    }
    private JsonElement canonical(JsonElement value)
    {
        if(value instanceof JsonObject)
        {
            JsonObject result=new JsonObject();
            for(String key:new TreeSet<>(value.getAsJsonObject().keySet())) result.add(key,canonical(value.getAsJsonObject().get(key)));
            return result;
        }
        if(value instanceof JsonArray)
        {
            JsonArray result=new JsonArray();for(JsonElement child:value.getAsJsonArray())result.add(canonical(child));return result;
        }
        return value;
    }
    private JsonObject resource(String path) throws Exception
    {
        try(Reader input=new InputStreamReader(getClass().getResourceAsStream("/"+path),StandardCharsets.UTF_8))
        { return gson.fromJson(input,JsonObject.class); }
    }
}
