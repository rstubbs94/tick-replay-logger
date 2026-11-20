/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import static com.tickreplaylogger.capture.ResolverEvidence.*;

/** Declared synthetic fixture values, never a runtime recording/compatibility path. */
final class DirectFixtureEvidence
{
    private static final Gson GSON = new Gson();

    private DirectFixtureEvidence()
    {
    }

    static Source source(JsonObject cue)
    {
        return new FixtureSource(cue);
    }

    static Hit hit(JsonObject input)
    {
        return new FixtureHit(input);
    }

    private static final class FixtureSource implements Source
    {
        final JsonObject cue;
        final Identity identity;

        FixtureSource(JsonObject cue)
        {
            this.cue = cue;
            JsonObject source = object(cue.get("source"));
            String observation = text(source, "observationKey");
            if (observation == null && text(source, "statusFamily") != null)
            {
                observation = "observed-hit-kind:" + text(source, "statusFamily");
            }
            identity = new Identity(text(source, "kind"), integer(source.get("id")), text(source, "actorRef"),
                observation, text(source, "name"), integer(source.get("combatLevel")),
                scope(object(source.get("scope"))));
        }

        public Channel channel()
        {
            if("FRAME_SURFACE".equals(text(cue,"observationKind"))) return Channel.FRAME_SURFACE;
            if("FRAME_NPC".equals(text(cue,"observationKind"))) return Channel.FRAME_NPC;
            if("HP_CALLBACK_ACTOR_SAMPLE".equals(text(cue,"observationKind"))) return Channel.HP_ACTOR_POSE;
            String mode = text(cue, "evidenceMode");
            if ("INPUT_REQUEST".equals(mode)) return Channel.INPUT;
            if ("CONSUMPTION".equals(mode) || "ITEM_CONSUMPTION".equals(mode)) return Channel.CONSUMPTION;
            if ("INVENTORY_POSSESSION".equals(mode)) return Channel.INVENTORY;
            if ("SYSTEM_RESPONSE".equals(mode)) return Channel.MESSAGE;
            if ("RESOURCE_INPUT".equals(mode)) return Channel.RESOURCE_INPUT;
            if (Boolean.TRUE.equals(bool(cue.get("hitFamilyOnly"))))
            {
                return Channel.TYPED_STATUS;
            }
            if (Boolean.TRUE.equals(bool(cue.get("nativePresenceObserved"))))
            {
                return Channel.NPC_EMITTER;
            }
            if (Boolean.TRUE.equals(bool(cue.get("apiSourceObserved"))))
            {
                return Channel.PROJECTILE;
            }
            switch (String.valueOf(text(cue, "pattern")))
            {
                case "projectile_impact": return Channel.PROJECTILE;
                case "contact_hazard": return Channel.HP_CONTACT;
                case "area_impact": return Channel.GRAPHIC_BIRTH;
                case "status_tick": case "status": return !"status".equals(identity.kind) ? Channel.ACTOR_CALLBACK
                    : "STATUS_ENVELOPE".equals(mode) ? Channel.STATUS : Channel.TYPED_STATUS;
                case "retaliation": return Channel.OUTGOING_TRIGGER;
                case "interaction_damage": case "interaction": return Channel.INPUT;
                case "delayed_effect": return Channel.ACTOR_LIFECYCLE;
                default: return Boolean.TRUE.equals(bool(cue.get("actorCallbackObserved")))
                    ? Channel.ACTOR_CALLBACK : Channel.HP_ACTOR_POSE;
            }
        }

        public String key() { return text(cue, "key"); }
        public Identity identity() { return identity; }
        public JsonObject raw() { return cue.get("witness") instanceof JsonObject ? object(cue.get("witness")) : cue; }
        public JsonObject first() { return object(cue.get("originalSourceObservation")); }

        public JsonObject actor()
        {
            JsonObject witness = object(cue.get("witness"));
            if (witness.get("originalActorState") instanceof JsonObject)
            {
                return object(witness.get("originalActorState"));
            }
            if (witness.get("actorState") instanceof JsonObject)
            {
                return object(witness.get("actorState"));
            }
            return object(cue.get("originalActorState"));
        }

        public Receipt receipt()
        {
            JsonObject first = first();
            Clock clock = first.size() == 0 ? clock(cue)
                : new Clock(integer(first.get("observedTick")), integer(first.get("observedCycle")),
                    longNumber(cue.has("sourceObservationSequence") ? cue.get("sourceObservationSequence") : cue.get("sequence")));
            return new Receipt(null, clock, identity.scope, cue);
        }

        public Receipt sample()
        {
            return new Receipt(null, clock(cue), identity.scope, object(cue.get("witness")));
        }

        public Receipt related() { return null; }

        public Receipt launch()
        {
            JsonObject raw = object(cue.get("launchObservation"));
            return raw.size() == 0 ? null : new Receipt(null,
                new Clock(integer(raw.get("firstSeenTick")), integer(object(raw.get("originalProjectileObservation")).get("observedCycle")), null),
                new Scope(text(raw, "launchSceneKey"), integer(raw.get("launchWorldViewId")), integer(raw.get("launchPlane")), null), raw);
        }

        public WorldSnapshot frame()
        {
            return GSON.fromJson(cue.get("originalSourceFrame"), WorldSnapshot.class);
        }
    }

    private static final class FixtureHit implements Hit
    {
        final JsonObject input, hit;
        final DamageRecord record;
        final Receipt callback;
        final List<Source> sources = new ArrayList<>();

        FixtureHit(JsonObject input)
        {
            this.input = input;
            hit = object(input.get("hit"));
            JsonObject scope = object(hit.get("scope")), position = object(hit.get("position"));
            record = DamageRecord.builder().impactTick(orZero(integer(hit.get("tick")))).observedCycle(integer(hit.get("cycle")))
                .observationSequence(longNumber(hit.get("sequence"))).amount(orZero(integer(hit.get("amount"))))
                .hitsplatType(orZero(integer(hit.get("hitsplatType")))).hitsplatTypeKnown(integer(hit.get("hitsplatType")) != null)
                .impactSceneKey(text(scope, "sceneKey")).impactWorldViewId(integer(scope.get("worldViewId"))).impactPlane(integer(scope.get("plane")))
                .impactContextEpoch(hit.has("contextEpoch") ? longNumber(hit.get("contextEpoch")) : scope(scope).epoch)
                .finalizationContextEpoch(longNumber(hit.get("finalizationContextEpoch"))).impactX(orZero(integer(position.get("x"))))
                .impactY(orZero(integer(position.get("y")))).impactPositionKnown(position.has("x") && position.has("y"))
                .defenderRef(text(hit, "recipientRef")).direction(text(hit, "direction")).build();
            JsonObject raw = object(hit.get("originalHpCallback"));
            if(hit.get("numericContext") instanceof JsonObject && !raw.has("numericContext"))
            {
                raw=raw.deepCopy();raw.add("numericContext",hit.get("numericContext"));
            }
            Scope nativeScope=scope(scope);
            Long callbackEpoch=raw.has("causalSceneEpoch") ? longNumber(raw.get("causalSceneEpoch")) : record.getImpactContextEpoch();
            callback = new Receipt(null, clock(hit), new Scope(nativeScope.sceneKey,nativeScope.worldViewId,nativeScope.plane,callbackEpoch), raw);
            for (JsonElement cue : array(input.get("cues")))
            {
                sources.add(source(object(cue)));
            }
            frameSources("impactSurfaces",false);
            frameSources("impactNpcs",true);
        }

        private void frameSources(String table,boolean npc)
        {
            JsonObject frame=object(hit.get("impactFrame"));
            for(JsonElement value:array(hit.get(table)))
            {
                JsonObject raw=object(value),cue=new JsonObject(),identity=new JsonObject(),scope=new JsonObject();
                cue.addProperty("key",table+":"+sources.size());cue.addProperty("observationKind",npc ? "FRAME_NPC" : "FRAME_SURFACE");
                cue.add("witness",raw);cue.add("originalSourceFrame",frame);cue.add("tick",frame.get("tick"));
                cue.add("cycle",frame.get("gameCycle"));
                identity.addProperty("kind",npc ? "npc" : "graphicsObject".equals(text(raw,"kind")) ? "graphic" : "object");
                identity.add("id",raw.get(npc ? "npcId" : "id"));identity.add("name",raw.get(npc ? "name" : "mappedName"));
                identity.add("actorRef",raw.get("actorRef"));identity.add("combatLevel",raw.get("combatLevel"));
                scope.add("sceneKey",frame.get("sceneKey"));scope.add("worldViewId",raw.get("worldViewId"));scope.add("plane",raw.get("plane"));
                identity.add("scope",scope);cue.add("source",identity);
                if(npc)cue.add("originalActorState",raw);
                sources.add(source(cue));
            }
        }

        public DamageRecord record() { return record; }
        public Receipt callback() { return callback; }
        public List<Source> sources() { return sources; }
        public boolean complete() { return Boolean.TRUE.equals(bool(input.get("complete"))); }
        public WorldSnapshot impact() { return GSON.fromJson(hit.get("impactFrame"), WorldSnapshot.class); }
        public SceneData scene() { return GSON.fromJson(hit.get("scene"), SceneData.class); }
        public WorldSnapshot exactFrame(Clock clock, Scope scope) { return null; }
        public List<Receipt> lifecycles() { return Collections.emptyList(); }
        public List<DamageRecord> peers() { return Collections.singletonList(record); }
    }

    private static Scope scope(JsonObject raw)
    {
        return new Scope(text(raw, "sceneKey"), integer(raw.get("worldViewId")), integer(raw.get("plane")),
            longNumber(raw.has("epoch") ? raw.get("epoch") : raw.get("contextEpoch")));
    }

    private static Clock clock(JsonObject raw)
    {
        return new Clock(integer(raw.get("tick")), integer(raw.get("cycle")), longNumber(raw.get("sequence")));
    }

    private static Long longNumber(JsonElement value)
    {
        try
        {
            return number(value) == null ? null : number(value).longValueExact();
        }
        catch (ArithmeticException invalid)
        {
            return null;
        }
    }

    private static int orZero(Integer value)
    {
        return value == null ? 0 : value;
    }
}
