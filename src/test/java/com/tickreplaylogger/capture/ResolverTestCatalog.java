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
    public static ResolverV1 engine(JsonObject document)
    {
        return new ResolverV1(document,JSON,null,null);
    }
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
    /** JSON exists only at the retained synthetic/conformance test boundary. */
    public static JsonObject render(ResolutionCandidate candidate)
    {
        JsonObject result = new JsonObject();
        put(result,"key",candidate.key);put(result,"ruleId",candidate.ruleId);put(result,"sourceKey",candidate.sourceKey);
        put(result,"status",candidate.status);put(result,"confidence",candidate.confidence);
        put(result,"sourceAssociation",candidate.sourceAssociation);put(result,"attributionEvidence",candidate.attributionEvidence);
        put(result,"label",candidate.label);put(result,"evidenceMode",candidate.evidenceMode);
        put(result,"candidateStrength",candidate.candidateStrength);put(result,"exclusionScope",candidate.exclusionScope);
        put(result,"observedLabel",candidate.observedLabel);result.addProperty("ordinary",candidate.ordinary);
        put(result,"componentQualified",candidate.componentQualified);put(result,"componentIdentified",candidate.componentIdentified);
        put(result,"identityQualified",candidate.identityQualified);put(result,"causalAssociationOnly",candidate.causalAssociationOnly);
        put(result,"legacyApiReferenceConflictEligible",candidate.legacyApiReferenceConflictEligible);put(result,"scalarEligible",candidate.scalarEligible);
        ResolverEvidence.Identity identity=candidate.identityOverride==null ? candidate.source.identity() : candidate.identityOverride;
        JsonObject source=new JsonObject();
        put(source,"kind",identity.kind);put(source,"id",identity.id);put(source,"actorRef",identity.actorRef);
        put(source,"observationKey",identity.observationKey);put(source,"name",identity.name);put(source,"combatLevel",identity.combatLevel);
        put(source,"familyKey",candidate.familyKey);
        if(identity.observationKey!=null && identity.observationKey.startsWith("observed-hit-kind:"))
            put(source,"statusFamily",identity.observationKey.substring("observed-hit-kind:".length()));
        if(identity.scope!=null)
        {
            JsonObject scope=new JsonObject();put(scope,"sceneKey",identity.scope.sceneKey);put(scope,"worldViewId",identity.scope.worldViewId);
            put(scope,"plane",identity.scope.plane);source.add("scope",scope);
        }
        result.add("source",source);result.add("evidence",candidate.evidence);result.add("reasons",candidate.reasons);
        put(result,"referenceAnnotations",candidate.referenceAnnotations);put(result,"derivedAnnotations",candidate.derivedAnnotations);
        put(result,"references",candidate.references);put(result,"maximum",candidate.maximum);put(result,"hitMultiplicity",candidate.hitMultiplicity);
        return result;
    }
    private static void put(JsonObject target,String key,Object value)
    {
        if(value instanceof JsonElement) target.add(key,(JsonElement)value);
        else if(value instanceof String) target.addProperty(key,(String)value);
        else if(value instanceof Number) target.addProperty(key,(Number)value);
        else if(value instanceof Boolean) target.addProperty(key,(Boolean)value);
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
        ResolverEvidence.Hit hit=DirectFixtureEvidence.hit(input);
        List<ResolverV1.Facts> facts=new ArrayList<>();
        for(JsonElement value:ResolverEvidence.array(input.get("cues"))) facts.add(new DeclaredMechanicFacts(engine,input,value.getAsJsonObject()));
        return render(engine.evaluate(hit,facts));
    }
    public static List<JsonObject> resolveBatch(ResolverV1 engine,List<JsonObject> inputs)
    {
        List<ResolverEvidence.Hit> hits=new ArrayList<>();
        List<ResolutionResult> interpreted=new ArrayList<>();
        for(JsonObject input:inputs)
        {
            ResolverEvidence.Hit hit=DirectFixtureEvidence.hit(input);hits.add(hit);
            List<ResolverV1.Facts> facts=new ArrayList<>();
            for(JsonElement value:ResolverEvidence.array(input.get("cues"))) facts.add(new DeclaredMechanicFacts(engine,input,value.getAsJsonObject()));
            interpreted.add(engine.evaluate(hit,facts));
        }
        ResolverV1.reconcileBatch(hits,interpreted);
        List<JsonObject> results=new ArrayList<>();
        for(ResolutionResult result:interpreted) results.add(render(result));
        return results;
    }
}
