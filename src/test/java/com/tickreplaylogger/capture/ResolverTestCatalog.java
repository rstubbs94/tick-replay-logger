/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import java.util.*;

/** Authoring-dialect lowering for frozen synthetic fixtures; never shipped in the plugin. */
public final class ResolverTestCatalog
{
    private static final Gson JSON=new Gson();
    private static final String[][] TABLES={{"ordinaryProfiles","component"},{"specialRules","component"},
        {"activityClassifications","activity"},{"sourceBounds","source_bound"},{"sourceAssociations","presence"},
        {"qualifiedLimits","qualified_limit"},{"evidenceRules","evidence"},{"inputLimits","input_limit"},{"contactPolicies","contact_limit"}};
    private ResolverTestCatalog() {}
    public static JsonObject lower(JsonObject document)
    {
        if(document==null) return null;
        JsonObject result=document.deepCopy();
        JsonArray rules=result.has("resolutionRules") ? result.getAsJsonArray("resolutionRules") : new JsonArray();
        result.add("resolutionRules",rules);
        if(!document.has("resolutionRules"))
            for(JsonElement value:RuntimeCatalog.load(new Gson()).section("resolver").getAsJsonArray("resolutionRules"))
                if("fact".equals(value.getAsJsonObject().get("role").getAsString())) rules.add(value.deepCopy());
        for(String[] table:TABLES)
        {
            if(result.has(table[0])) for(JsonElement value:result.getAsJsonArray(table[0]))
                append(rules,value.getAsJsonObject(),table[1],table[0]);
            result.remove(table[0]);
        }
        if(result.has("outcomeBindings"))
        {
            JsonObject metadata=result.getAsJsonObject("outcomeBindings");
            if(metadata.has("rules")) for(JsonElement value:metadata.getAsJsonArray("rules")) append(rules,value.getAsJsonObject(),"outcome",null);
            metadata.remove("rules");
            if(metadata.has("knownHitsplatTypes")) result.add("knownHitsplatTypes",metadata.remove("knownHitsplatTypes"));
            result.add("outcomeMetadata",metadata);result.remove("outcomeBindings");
        }
        return result;
    }
    private static void append(JsonArray rules,JsonObject original,String role,String table)
    {
        JsonObject row=original.deepCopy();
        if(row.has("role")) row.add("classificationRole",row.get("role"));
        row.addProperty("role",role);
        if("component".equals(role)) row.addProperty("profile","ordinaryProfiles".equals(table));
        if(!row.has("id")) row.add("id",row.has("key") ? row.get("key") : new JsonPrimitive(role+":"+rules.size()));
        if(("input_limit".equals(role)||"outcome".equals(role)) && row.has("selector"))
        {
            JsonObject selector=row.getAsJsonObject("selector"),source=new JsonObject();
            String kind=selector.get("kind").getAsString();
            source.addProperty("kind",kind.startsWith("NPC_") ? "npc" : kind.toLowerCase(Locale.ROOT));
            if(selector.has("ids")) source.add("ids",selector.get("ids"));
            if(selector.has("observedNames")) source.add("names",selector.get("observedNames"));
            row.add("source",source);
        }
        rules.add(row);
    }
    public static JsonArray rows(JsonObject document,String role)
    {
        JsonArray result=new JsonArray();
        for(JsonElement value:lower(document).getAsJsonArray("resolutionRules"))
            if(role.equals(value.getAsJsonObject().get("role").getAsString())) result.add(value);
        return result;
    }
    /** The shared JSON protocol is a test/conformance boundary, not an active replay intermediate. */
    public static JsonObject render(ResolutionCandidate candidate)
    {
        JsonObject result=JSON.toJsonTree(candidate).getAsJsonObject();
        result.addProperty("ruleId",candidate.ruleId);
        result.addProperty("sourceKey",candidate.sourceKey);
        result.add("source",candidate.source);
        result.add("evidence",candidate.evidence);
        result.add("reasons",candidate.reasons);
        if(candidate.referenceAnnotations!=null) result.add("referenceAnnotations",candidate.referenceAnnotations);
        if(candidate.derivedAnnotations!=null) result.add("derivedAnnotations",candidate.derivedAnnotations);
        if(candidate.references!=null) result.add("references",candidate.references);
        if(candidate.maximum!=null) result.add("maximum",candidate.maximum);
        if(candidate.hitMultiplicity!=null) result.add("hitMultiplicity",candidate.hitMultiplicity);
        return result;
    }
    public static JsonObject render(ResolutionResult evaluated)
    {
        JsonArray candidates=new JsonArray();
        for(ResolutionCandidate candidate:evaluated.candidates) candidates.add(render(candidate));
        JsonObject result=new JsonObject();result.add("candidates",candidates);
        result.addProperty("unidentifiedPossible",evaluated.unidentifiedPossible);
        result.add("hitAnnotations",evaluated.hitAnnotations);
        if(evaluated.truncated) result.addProperty("truncated",true);
        if(evaluated.scalarAssociation!=null) result.addProperty("scalarAssociation",evaluated.scalarAssociation);
        return result;
    }
    public static JsonObject resolve(ResolverV1 engine,JsonObject input)
    {
        return render(engine.evaluate(input));
    }
    public static List<JsonObject> resolveBatch(ResolverV1 engine,List<JsonObject> inputs)
    {
        List<JsonObject> results=new ArrayList<>();
        for(ResolutionResult result:engine.evaluateBatch(inputs)) results.add(render(result));
        return results;
    }

}
