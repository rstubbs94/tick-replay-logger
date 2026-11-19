/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Differential checks against the frozen evaluator, in addition to independently pinned outcomes. */
public class ResolverIrParityTest
{
    private final Gson gson=new Gson();
    @Test public void bundledSourceBoundInputsKeepEveryCandidateAndQualification() throws Exception
    {
        ResolverV1 current=ResolverV1.load(gson);
        PreRuleIrResolverV1 prior=PreRuleIrResolverV1.load(gson);
        int checked=0;
        for(JsonElement value:resource("resolver-source-bounds.synthetic.json").getAsJsonArray("cases"))
        {
            JsonObject test=value.getAsJsonObject(),input=test.getAsJsonObject("input");
            assertEquals(test.get("id").getAsString(),signature(prior.resolve(input.deepCopy())),signature(ResolverTestCatalog.resolve(current,input.deepCopy())));
            checked++;
        }
        assertEquals(528,checked);
    }
    @Test public void syntheticComponentsUseTheSameFoldAfterExplicitAuthoringLowering() throws Exception
    {
        int checked=0;
        for(JsonElement value:resource("resolver-v1-conformance.json").getAsJsonArray("cases"))
        {
            JsonObject test=value.getAsJsonObject(),catalog=test.getAsJsonObject("catalog"),input=test.getAsJsonObject("input");
            String id=test.get("id").getAsString();
            // This case independently pins the documented correction: an actual API owner survives foreign sender coordinates.
            if("owned_foreign_native_source_keeps_identity_without_spatial_or_numeric_proof".equals(id)) continue;
            assertEquals(id,signature(new PreRuleIrResolverV1(catalog).resolve(input.deepCopy())),
                signature(ResolverTestCatalog.resolve(new ResolverV1(ResolverTestCatalog.lower(catalog)),input.deepCopy())));
            checked++;
        }
        assertEquals(108,checked);
    }
    @Test public void authoringTablesAreNotASecondRuntimeDialect()
    {
        JsonObject old=gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[]}",JsonObject.class);
        assertFalse(new ResolverV1(old).isAvailable());
        assertTrue(new ResolverV1(ResolverTestCatalog.lower(old)).isAvailable());
        JsonObject packaged=RuntimeCatalog.load(gson).section("resolver");
        assertTrue(packaged.has("resolutionRules"));
        for(String key:Arrays.asList("ordinaryProfiles","specialRules","qualifiedLimits","evidenceRules","inputLimits",
            "sourceBounds","sourceAssociations","activityClassifications","contactPolicies","outcomeBindings")) assertFalse(key,packaged.has(key));
    }
    private List<String> signature(JsonObject output)
    {
        List<String> rows=new ArrayList<>();
        for(JsonElement value:output.getAsJsonArray("candidates"))
        {
            JsonObject row=value.getAsJsonObject(),picked=new JsonObject();
            for(String field:Arrays.asList("ruleId","sourceKey","source","status","maximum","exclusionScope","ordinary",
                "componentIdentified","componentQualified","identityQualified","sourceAssociation"))
                if(row.has(field)) picked.add(field,row.get(field));
            // A foreign passive sample rejects observed provenance, not a component that was never admitted.
            if(row.has("reasons") && row.getAsJsonArray("reasons").toString().contains("RECORDED_NATIVE_CONTEXT_CONFLICT"))
                picked.addProperty("ruleId","observed:native_context_conflict");
            rows.add(picked.toString());
        }
        Collections.sort(rows);return rows;
    }
    private JsonObject resource(String path) throws Exception
    {
        try(Reader input=new InputStreamReader(getClass().getResourceAsStream("/"+path),StandardCharsets.UTF_8))
        { return gson.fromJson(input,JsonObject.class); }
    }
}
