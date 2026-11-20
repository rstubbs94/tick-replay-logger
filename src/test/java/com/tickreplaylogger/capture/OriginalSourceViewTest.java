/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.tickreplaylogger.capture.ResolverEvidence.*;

/** Boundary controls use the actual raw-window path rather than a second normalized representation. */
public class OriginalSourceViewTest
{
    private final Gson gson = new Gson();

    @Test
    public void explicitPlayerNamespaceCannotBecomeAnNpcFromAConflictingDefinitionNumber()
    {
        for (String kind : new String[] {"PLAYER", "OTHER_PLAYER", "LOCAL_PLAYER"})
        {
            Map<String,Object> actor = map("{actorKind:'" + kind + "',npcId:9036,name:'Observed player',worldViewId:0,plane:0,planeKnown:true}");
            String original = gson.toJson(actor);
            ObservedEvent activity = ObservedEvent.builder().type("ANIMATION_CHANGED").actorRef("player:observed")
                .tick(10).gameCycle(299).sequence(19).sceneKey("room").data(Collections.singletonMap("actorState",actor)).build();
            Hit hit = window(Collections.singletonList(activity),Collections.emptyList());
            List<Source> activitySources = new ArrayList<>();
            for (Source source : hit.sources())
            {
                if (source.channel() == Channel.ACTOR_CALLBACK)
                {
                    activitySources.add(source);
                }
            }
            if ("LOCAL_PLAYER".equals(kind))
            {
                assertTrue(activitySources.isEmpty());
            }
            else
            {
                assertEquals(1,activitySources.size());
                assertEquals("player",activitySources.get(0).identity().kind);
                assertNull(activitySources.get(0).identity().id);
            }
            assertEquals(original,gson.toJson(actor));
        }
    }

    @Test
    public void anExactHpWitnessKeepsItsActualReferenceWhenItsActorFieldsAreUnavailable()
    {
        Map<String,Object> witness = map("{kind:'NPC_ACTIVITY_OBSERVATION',actorRef:'npc:original',name:'Observed NPC',"
            + "sceneKey:'room',worldViewId:0,plane:0,observedTick:10,observedCycle:300,impactSequence:20,"
            + "observationStage:'HITSPLAT_CALLBACK',actorState:{}}");
        String original = gson.toJson(witness);
        Hit hit = window(Collections.emptyList(),Collections.singletonList(witness));
        Source source = hit.sources().stream().filter(value -> value.channel() == Channel.HP_ACTOR_POSE).findFirst().get();
        assertEquals("npc:original",source.identity().actorRef);
        assertEquals("npc",source.identity().kind);
        assertEquals("Observed NPC",source.identity().name);
        assertNull(source.identity().id);
        assertEquals(0,source.actor().size());
        assertEquals(Long.valueOf(20),source.receipt().clock.sequence);
        assertEquals(original,gson.toJson(witness));
        assertFalse(OriginalSourceProof.numericReference(source,hit));
        assertFalse(OriginalSourceProof.staticReference(source,hit));
        ResolutionCandidate row = ResolutionCandidate.hint("synthetic",source);
        OriginalSourceProof.scalar(source,hit,row);
        assertFalse(row.scalarEligible);
        assertEquals("UNKNOWN",row.confidence);
    }

    private Hit window(List<ObservedEvent> observed,List<Map<String,Object>> witnesses)
    {
        DamageRecord hit = DamageRecord.builder().impactTick(10).observedCycle(300).observationSequence(20L)
            .defenderRef("player").amount(12).hitsplatType(16).hitsplatTypeKnown(true).impactSceneKey("room")
            .impactWorldViewId(0).impactPlane(0).attributionCandidates(witnesses).build();
        List<ObservedEvent> events = new ArrayList<>(observed);
        events.add(ObservedEvent.builder().type("HITSPLAT").actorRef("player").sceneKey("room").tick(10).gameCycle(300).sequence(20)
            .data(map("{amount:12,hitsplatType:16,receiverKind:'PLAYER',actorState:{actorKind:'LOCAL_PLAYER',worldViewId:0,plane:0,planeKnown:true}}")).build());
        ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(Collections.emptyList(),Collections.emptyList(),
            events,Collections.singletonList(hit),Collections.emptyList(),10,10,20,1);
        return new RawEvidenceWindow(window,gson).hit(hit);
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> map(String value)
    {
        return gson.fromJson(value,Map.class);
    }
}
