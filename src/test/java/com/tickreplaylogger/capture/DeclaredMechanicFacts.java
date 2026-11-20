/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import java.lang.reflect.Method;
import java.util.*;
import static com.tickreplaylogger.capture.ResolverEvidence.*;

/**
 * Declared assumptions for synthetic matcher units, separate from captured-source proofs.
 * The original fixture dialect supplies scope/clock/component/numeric assumptions directly.
 * It does not claim that an API owner, history, model or original HP callback was captured.
 * Raw-window tests use production RawFacts and never pass through this provider.
 */
final class DeclaredMechanicFacts implements ResolverV1.Facts
{
    private final JsonObject cue, hitJson;
    private final Hit hit;
    private final Source source;
    private final ResolverV1.Facts actual;
    final List<String> assumptions = new ArrayList<>();

    DeclaredMechanicFacts(ResolverV1 engine, JsonObject input, JsonObject cue)
    {
        this.cue = cue;
        hitJson = object(input.get("hit"));
        hit = DirectFixtureEvidence.hit(input);
        source = DirectFixtureEvidence.source(cue);
        actual = engine.new RawFacts(hit, source);
        if (!cue.has("numericEligible")) assumptions.add("Matcher unit assumes numeric eligibility; raw proof is tested separately.");
        if (Boolean.TRUE.equals(bool(cue.get("componentBound")))) assumptions.add("Matcher unit explicitly supplies component binding.");
    }

    public Hit hit() { return hit; }
    public Source source() { return source; }
    public Identity identity() { return source.identity(); }
    public JsonObject raw() { return source.raw(); }
    public JsonObject actor() { return source.actor(); }
    public JsonObject first() { return source.first(); }
    public Truth nativeScope() { return OriginalSourceProof.scope(identity().scope, hit.callback().scope); }
    public boolean owned() { return Boolean.TRUE.equals(bool(cue.get("apiSourceObserved"))) || Boolean.TRUE.equals(bool(cue.get("actorCallbackObserved"))); }
    public boolean numeric() { return (!cue.has("numericEligible") || Boolean.TRUE.equals(bool(cue.get("numericEligible")))) && nativeScope() == Truth.TRUE; }
    public boolean stable() { return !Boolean.TRUE.equals(bool(cue.get("unstableOriginalSource"))); }
    public boolean activityEligible() { return nativeScope()==Truth.TRUE && stable(); }
    public String family() { String value=text(object(cue.get("source")),"familyKey");return value==null ? actual.family() : value; }
    public String mechanism() { String value=text(cue,"mechanism");return value==null ? actual.mechanism() : value; }
    public String pattern() { return text(cue,"pattern"); }
    public boolean acceptsPattern(String requested)
    {
        if(Objects.equals(pattern(),requested))return true;
        for(JsonElement channel:array(cue.get("channels")))
            if(Objects.equals(text(object(channel),"pattern"),requested))return true;
        return false;
    }
    public String mode() { return text(cue,"evidenceMode"); }
    public String familyOfHit()
    {
        String declared=text(hitJson,"family");
        return declared==null ? actual.familyOfHit() : declared;
    }
    public boolean ordinary() { return cue.has("ordinary") ? Boolean.TRUE.equals(bool(cue.get("ordinary"))) : actual.ordinary(); }
    public boolean weak() { return Boolean.FALSE.equals(bool(cue.get("numericEligible"))) && Boolean.FALSE.equals(bool(cue.get("componentBound"))); }
    public boolean direct() { return Arrays.asList("direct_attack","projectile_impact").contains(pattern()); }
    public boolean targeted() { return text(cue,"targetRef") != null && Objects.equals(text(cue,"targetRef"),text(hitJson,"recipientRef")); }
    public boolean nativeConflict()
    {
        return ResolverV1.nativeConflict(hit,source,Boolean.TRUE.equals(bool(cue.get("apiSourceObserved"))));
    }
    public boolean contactRejected() { return Boolean.FALSE.equals(bool(cue.get("contactObserved"))); }
    public boolean fallback() { return !Boolean.FALSE.equals(bool(cue.get("allowFallback"))) && !nativeConflict()
        && (direct() || "area_impact".equals(pattern())); }

    public boolean before(boolean strict)
    {
        Boolean explicit = bool(cue.get("sourceObservedBeforeImpact"));
        return explicit != null ? explicit : OriginalSourceProof.before(source.receipt().clock,hit.callback().clock,strict);
    }

    public boolean sampleBefore() { return before(false); }
    public Truth contact()
    {
        return Boolean.TRUE.equals(bool(cue.get("contactObserved"))) ? Truth.TRUE : contactRejected() ? Truth.FALSE
            : "contact_hazard".equals(pattern()) && Boolean.TRUE.equals(bool(cue.get("componentBound"))) ? Truth.TRUE : Truth.UNKNOWN;
    }
    public Truth sourceActive()
    {
        Boolean active = bool(object(object(cue.get("facts")).get("impact")).get("source_active"));
        return active == null ? Truth.UNKNOWN : active ? Truth.TRUE : Truth.FALSE;
    }
    public boolean statusEnvelope() { return actual.statusEnvelope(); }
    public boolean owner(boolean nativeSource, boolean noOverrides) { return actual.owner(nativeSource,noOverrides); }
    public void scalar(ResolutionCandidate row) { actual.scalar(row); }
    public boolean normalContext(JsonObject predicate) { return actual.normalContext(predicate); }
    public boolean input(JsonObject rule) { return actual.input(rule); }
    public JsonElement signal(String key)
    {
        JsonObject declared=object(cue.get("signals"));
        return declared.has(key) ? declared.get(key) : actual.signal(key);
    }

    public Truth test(JsonElement predicate) { return ResolverV1.conditions(predicate,this::value); }
    public Truth test(JsonElement predicate,ResolutionCandidate row)
    {
        return ResolverV1.conditions(predicate,(domain,fact) -> "row".equals(domain) && "label".equals(fact)
            ? row.label == null ? null : new JsonPrimitive(row.label) : value(domain,fact));
    }

    public JsonElement value(String domain,String fact)
    {
        if("observations".equals(domain) && "QUALIFIED_NPC_VARIANT".equals(fact)
            && !cue.has("observations"))
            return cue.has("originalActorState") || cue.has("originalSourceObservation")
                ? actual.value(domain,fact) : new JsonPrimitive(numeric());
        if ("source".equals(domain)) return ResolverV1.path(object(cue.get("source")),fact);
        if ("hit".equals(domain)) return ResolverV1.path(hitJson,fact);
        if("cue".equals(domain) && "actorAnimationObserved".equals(fact) && !cue.has(fact))
            return cue.get("actorCallbackObserved");
        if ("cue".equals(domain)) return ResolverV1.path(cue,"arrival_cycle".equals(fact) ? "cycle" : "arrival_tick".equals(fact) ? "tick" : fact);
        if ("impact".equals(domain))
        {
            JsonObject owner = fact.startsWith("source_") ? cue : hitJson;
            return ResolverV1.path(object(object(owner.get("facts")).get("impact")),fact);
        }
        if (Arrays.asList("source_event","attack","interaction").contains(domain))
            return ResolverV1.path(object(object(cue.get("facts")).get(domain)),fact);
        return ResolverV1.path(object(cue.get(domain)),fact);
    }

    public Truth geometry(JsonObject declaration)
    {
        // The authored unit explicitly withholds component binding: its private footprint cannot prune the source.
        if(Boolean.FALSE.equals(bool(cue.get("componentBound"))))return Truth.UNKNOWN;
        // Only the frozen mathematical operation over explicitly declared fixture positions.
        // Actual original receipt/native/position eligibility is exercised by raw-window tests.
        try
        {
            Method method = PreRuleIrResolverV1.class.getDeclaredMethod("geometry",JsonObject.class,JsonObject.class,JsonObject.class);
            method.setAccessible(true);
            return Truth.valueOf(method.invoke(null,declaration,cue,hitJson).toString());
        }
        catch (ReflectiveOperationException failure)
        {
            throw new AssertionError("Frozen synthetic geometry oracle unavailable",failure);
        }
    }
}
