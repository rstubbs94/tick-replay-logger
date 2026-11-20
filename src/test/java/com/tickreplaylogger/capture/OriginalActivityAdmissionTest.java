/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Generic correlations require original activity; retained raw membership is not causal admission. */
public class OriginalActivityAdmissionTest
{
    private final Gson gson = new Gson();

    @Test
    public void idleUntargetedEmptyAndStaleCallbacksNeverBecomeGenericAttackSources()
    {
        assertGeneric(true, "ANIMATION_CHANGED", 9, 777, true, "room");
        assertGeneric(false, "ANIMATION_CHANGED", 9, -1, true, "room");
        assertGeneric(false, "ANIMATION_CHANGED", 9, 777, false, "room");
        assertGeneric(false, "ACTOR_GRAPHICS_CHANGED", 9, 777, true, "room");
        assertGeneric(false, "ANIMATION_CHANGED", 1, 777, true, "room");
        assertGeneric(false, "ANIMATION_CHANGED", 9, 777, true, "other-room");
    }

    @Test
    public void explicitReviewedDelayStillUsesTheOriginalReceiptAfterGenericActivityExpires()
    {
        Fixture fixture = fixture("OVERHEAD_TEXT_CHANGED", 1, -1, false, "room");
        JsonObject catalog = gson.fromJson("{schemaVersion:1,purpose:'retrospective_damage_resolution',specialRules:["
            + "{id:'declared-delay',pattern:'direct_attack',source:{kind:'npc',ids:[990001]},"
            + "timing:{anchor:'actor_activity',clock:'game_tick',minimumOffset:9,maximumOffset:9,authority:'observed'}}]}", JsonObject.class);
        ResolverV1 engine = ResolverTestCatalog.engine(ResolverTestCatalog.lower(catalog));
        ResolutionResult result = engine.evaluate(fixture.hit);
        assertTrue(result.candidates.stream().anyMatch(row -> "declared-delay".equals(row.ruleId) && "POSSIBLE".equals(row.status)));
        assertTrue(result.candidates.stream().noneMatch(row -> row.ruleId.startsWith("observed:")));
        assertEquals(fixture.before, gson.toJson(fixture.recording));
    }

    @Test
    public void redactedOriginalHpTargetRemainsAnUncertainRivalWithoutInventingActivity()
    {
        Fixture base = fixture("ANIMATION_CHANGED", 9, 777, true, "room");
        Map<String, Object> witness = gson.fromJson("{kind:'PLAYER_ACTIVITY_OBSERVATION',actorState:{},name:'Other player',"
            + "targetRef:'player',sourceCollectionEvidence:'NATIVE_ACTOR_COLLECTION_MEMBERSHIP',observedTick:10,observedCycle:301,"
            + "impactSequence:2,observationStage:'HITSPLAT_CALLBACK',evidence:'CURRENT_ENGAGED_POSE_NOT_ATTACK_PROOF'}", Map.class);
        DamageRecord hit = base.recording.getEvents().getDamageEvents().get(0).toBuilder()
            .attributionCandidates(Collections.singletonList(witness)).build();
        DeathRecordingV2 recording = base.recording.toBuilder().events(DeathRecordingV2.Events.builder()
            .damageEvents(Collections.singletonList(hit)).observations(Collections.singletonList(base.recording.getEvents().getObservations().get(1))).build()).build();
        String before = gson.toJson(recording);
        ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(null, null,
            recording.getEvents().getObservations(), Collections.singletonList(hit), null, 0, 10, Long.MAX_VALUE, 0);
        ResolverV1 engine = ResolverTestCatalog.engine(ResolverTestCatalog.lower(gson.fromJson(
            "{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[],specialRules:[]}", JsonObject.class)));
        ResolutionResult result = engine.evaluate(new RawEvidenceWindow(window, gson).hit(hit));
        assertEquals(1, result.candidates.size());
        ResolutionCandidate candidate = result.candidates.get(0);
        assertEquals("POSSIBLE", candidate.status);
        assertEquals("Other player", candidate.source.identity().name);
        assertNull(candidate.source.identity().actorRef);
        assertNull(candidate.source.identity().id);
        assertFalse(Boolean.TRUE.equals(candidate.scalarEligible));
        assertFalse(Boolean.TRUE.equals(candidate.componentQualified));
        assertNull(candidate.maximum);
        assertEquals(before, gson.toJson(recording));
    }

    private void assertGeneric(boolean expected, String type, int tick, int animation, boolean targeted, String scene)
    {
        Fixture fixture = fixture(type, tick, animation, targeted, scene);
        ResolverV1 engine = ResolverTestCatalog.engine(ResolverTestCatalog.lower(gson.fromJson(
            "{schemaVersion:1,purpose:'retrospective_damage_resolution',ordinaryProfiles:[],specialRules:[]}", JsonObject.class)));
        ResolutionResult result = engine.evaluate(fixture.hit);
        assertEquals(type + ":" + tick + ":" + animation + ":" + targeted + ":" + scene, expected,
            result.candidates.stream().anyMatch(row -> "POSSIBLE".equals(row.status)));
        assertEquals(1, fixture.hit.sources().stream().filter(source -> source.channel() == ResolverEvidence.Channel.ACTOR_CALLBACK).count());
        assertEquals(fixture.before, gson.toJson(fixture.recording));
    }

    private Fixture fixture(String type, int tick, int animation, boolean targeted, String scene)
    {
        JsonObject raw = gson.fromJson("{npcId:990001,name:'Recorded actor',combatLevel:10,worldViewId:0,plane:0,planeKnown:true,"
            + "interaction:{actorRef:'player'},normalWorldContextObserved:true,modelOverridesPresent:false}", JsonObject.class);
        raw.addProperty("animationId", animation);
        raw.getAsJsonObject("interaction").addProperty("reportedInteracting", targeted);
        if (!targeted) raw.getAsJsonObject("interaction").remove("actorRef");
        ObservedEvent callback = ObservedEvent.builder().sequence(1).tick(tick).gameCycle(tick * 30).type(type)
            .actorRef("npc:1:v0:g1").sceneKey(scene).data(gson.fromJson(raw, Map.class)).build();
        ObservedEvent hp = ObservedEvent.builder().sequence(2).tick(10).gameCycle(301).type("HITSPLAT").actorRef("player")
            .sceneKey("room").data(gson.fromJson("{amount:8,hitsplatType:16,receiverKind:'PLAYER',actorState:"
                + "{actorKind:'LOCAL_PLAYER',worldViewId:0,plane:0,planeKnown:true}}", Map.class)).build();
        DamageRecord damage = DamageRecord.builder().direction("RECEIVED").defenderRef("player").impactTick(10)
            .observedCycle(301).observationSequence(2L).amount(8).hitsplatType(16).hitsplatTypeKnown(true)
            .impactSceneKey("room").impactWorldViewId(0).impactPlane(0).build();
        DeathRecordingV2 recording = DeathRecordingV2.builder().events(DeathRecordingV2.Events.builder()
            .damageEvents(Collections.singletonList(damage)).observations(Arrays.asList(callback, hp)).build()).build();
        ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(null, null,
            recording.getEvents().getObservations(), recording.getEvents().getDamageEvents(), null, 0, 10, Long.MAX_VALUE, 0);
        return new Fixture(recording, new RawEvidenceWindow(window, gson).hit(damage), gson.toJson(recording));
    }

    private static final class Fixture
    {
        final DeathRecordingV2 recording;
        final ResolverEvidence.Hit hit;
        final String before;

        Fixture(DeathRecordingV2 recording, ResolverEvidence.Hit hit, String before)
        {
            this.recording = recording;
            this.hit = hit;
            this.before = before;
        }
    }
}
