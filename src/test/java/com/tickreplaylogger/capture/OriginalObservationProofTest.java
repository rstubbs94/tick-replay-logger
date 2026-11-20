/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.ProjectileState;
import com.tickreplaylogger.model.world.ProjectileSourceHistory;
import com.tickreplaylogger.model.world.WorldSnapshot;
import com.tickreplaylogger.model.world.SceneData;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.tickreplaylogger.capture.ResolverEvidence.*;

/** Integrity boundaries exercised independently of mechanic/reference declarations. */
public class OriginalObservationProofTest
{
    private final Scope nativeScope = new Scope("scene", 0, 0, 7L);

    @Test
    public void missingNativeContextIsDifferentFromAnObservedForeignContext()
    {
        assertEquals(Truth.UNKNOWN, OriginalSourceProof.scope(new Scope(null, 0, 0, 7L), nativeScope));
        assertEquals(Truth.FALSE, OriginalSourceProof.scope(new Scope("scene", 2, 0, 7L), nativeScope));
        assertEquals(Truth.FALSE, OriginalSourceProof.scope(new Scope("scene", 0, 0, 8L), nativeScope));
        assertEquals(Truth.TRUE, OriginalSourceProof.scope(new Scope("scene", 0, 0, null), nativeScope));
    }

    @Test
    public void aSettledSampleAtTheSameCycleCannotInventCallbackOrder()
    {
        Clock impact = new Clock(10, 100, 9L);
        assertFalse(OriginalSourceProof.before(new Clock(10, 100, null), impact, false));
        assertFalse(OriginalSourceProof.before(new Clock(10, 100, 10L), impact, false));
        assertTrue(OriginalSourceProof.before(new Clock(10, 100, 8L), impact, true));
        assertFalse(OriginalSourceProof.before(new Clock(10, 100, 9L), impact, true));
    }

    @Test
    public void matchingDefinitionsAndTilesDoNotJoinIndependentObjectLifetimes()
    {
        TestSource first = source("object", "{objectObservationId:3}", "{}");
        TestSource replacement = source("object", "{objectObservationId:4}", "{}");
        assertEquals(Truth.FALSE, OriginalSourceProof.samePhysical(first, replacement));
        assertEquals(Truth.UNKNOWN, OriginalSourceProof.samePhysical(first, source("object", "{objectKey:3}", "{}")));
        assertEquals(Truth.TRUE, OriginalSourceProof.samePhysical(first, source("object", "{objectObservationId:3}", "{}")));
        assertNotEquals(Truth.TRUE, OriginalSourceProof.samePhysical(first, source("graphic", "{graphicObservationId:3}", "{}")));
    }

    @Test
    public void theRecipientQueryTileIsNeverAnObjectAnchor()
    {
        TestSource object = source("object", "{contactWorldX:100,contactWorldY:100}", "{}");
        assertEquals(Truth.UNKNOWN, OriginalSourceProof.geometry(object, hit(), json("{shape:'point'}")));
        TestSource graphic = source("graphic", "{worldX:99,worldY:100,contactWorldX:100,contactWorldY:100}", "{}");
        assertEquals(Truth.FALSE, OriginalSourceProof.geometry(graphic, hit(), json("{shape:'point'}")));
    }

    @Test
    public void laterRecordedGeometryDoesNotEstablishAnEarlierNumericOrAttackProof()
    {
        TestSource source = source("npc", "{}", "{worldX:100,worldY:100,worldPositionKnown:true}");
        source.receipt = new Receipt(null, new Clock(10, 101, null), nativeScope, json("{}"));
        assertEquals(Truth.TRUE, OriginalSourceProof.geometry(source, hit(), json("{shape:'point'}")));
        assertFalse(OriginalSourceProof.numericReference(source, hit()));
    }

    @Test
    public void aFailedOriginalHitPositionCannotBeRepairedByItsVisibleCoordinates()
    {
        TestHit hit = hit();
        hit.callback().raw.add("captureIssues", json("{observationActorScope:'FAILED'}"));
        assertFalse(OriginalSourceProof.originalPosition(hit));
        assertEquals(Truth.UNKNOWN, OriginalSourceProof.geometry(source("graphic", "{worldX:100,worldY:100}", "{}"), hit,
            json("{shape:'point'}")));
    }

    @Test
    public void laterFinalizationEpochDoesNotRewriteTheOriginalHitScope()
    {
        TestHit hit = hit();
        DamageRecord original = hit.record();
        hit.record = original.toBuilder().finalizationContextEpoch(8L).build();
        assertTrue(OriginalSourceProof.originalPosition(hit));
        assertEquals(Truth.TRUE, OriginalSourceProof.scope(nativeScope, hit.callback().scope));
    }

    @Test
    public void statusNeedsAnEarlierSampleRatherThanJustAnEarlierSameCycleSequence()
    {
        TestSource source = source("status", "{active:true,valueObserved:true}", "{}");
        source.receipt = new Receipt(null, new Clock(10, 100, 8L), nativeScope, json("{}"));
        assertFalse(OriginalSourceProof.statusEnvelope(source, hit()));
        source.receipt = new Receipt(null, new Clock(10, 99, 8L), nativeScope, json("{}"));
        assertTrue(OriginalSourceProof.statusEnvelope(source, hit()));
        source.raw().addProperty("active",false);
        assertFalse(OriginalSourceProof.statusEnvelope(source,hit()));
        source.raw().addProperty("active",true);
        source.raw().remove("valueObserved");
        assertFalse(OriginalSourceProof.statusEnvelope(source,hit()));
    }

    @Test
    public void aSameTickDespawnOrAnotherActorCannotProveThisSourceInactive()
    {
        TestSource source = source("npc", "{}", "{}");
        source.receipt = new Receipt(null, new Clock(8, 80, 3L), nativeScope, json("{}"));
        TestHit hit = hit();
        Receipt sameTick = lifecycle("NPC_DESPAWNED", "actor", 10, 99, 8L);
        Receipt another = lifecycle("NPC_DESPAWNED", "other-actor", 9, 90, 4L);
        hit.lifecycles = Arrays.asList(sameTick, another);
        assertEquals(Truth.UNKNOWN, OriginalSourceProof.sourceActive(source, hit));
        Receipt prior = lifecycle("NPC_DESPAWNED", "actor", 9, 90, 4L);
        hit.lifecycles = Collections.singletonList(prior);
        assertEquals(Truth.FALSE, OriginalSourceProof.sourceActive(source, hit));
        hit.lifecycles = Arrays.asList(prior, lifecycle("NPC_SPAWNED", "actor", 9, 91, 5L));
        assertEquals(Truth.UNKNOWN, OriginalSourceProof.sourceActive(source, hit));
    }

    @Test
    public void aLifetimeReferenceAndSceneCanDisprovePresenceWithoutProvingNumericScope()
    {
        TestSource source=source("npc","{}","{}");
        source.receipt=new Receipt(null,new Clock(8,80,3L),nativeScope,json("{}"));
        TestHit hit=hit();
        Receipt prior=lifecycle("NPC_DESPAWNED","actor",9,90,4L);
        Scope partial=new Scope("scene",null,null,7L);
        hit.lifecycles=Collections.singletonList(new Receipt(prior.event,prior.clock,partial,json("{}")));
        assertEquals(Truth.UNKNOWN,OriginalSourceProof.scope(nativeScope,partial));
        assertEquals(Truth.FALSE,OriginalSourceProof.sourceActive(source,hit));
        Receipt born=lifecycle("NPC_SPAWNED","actor",11,110,10L);
        hit.lifecycles=Collections.singletonList(new Receipt(born.event,born.clock,partial,json("{}")));
        assertTrue(OriginalSourceProof.observedBirthAfterImpact(source,hit));
        hit.lifecycles=Collections.singletonList(new Receipt(born.event,born.clock,new Scope("scene",1,0,7L),json("{}")));
        assertFalse(OriginalSourceProof.observedBirthAfterImpact(source,hit));
        hit.lifecycles=Collections.singletonList(new Receipt(born.event,born.clock,partial,json("{actorState:{npcId:100}}")));
        assertFalse(OriginalSourceProof.observedBirthAfterImpact(source,hit));
    }

    @Test
    public void originalContactDistinguishesMissingEvidenceFromAnExplicitRenderFallback()
    {
        TestSource source = source("graphic", "{kind:'GRAPHIC_SURFACE',observationStage:'HITSPLAT_CALLBACK',observedCycle:100,"
            + "contactWorldX:100,contactWorldY:100,damaging:true}", "{}");
        source.receipt = new Receipt(null, new Clock(10, 100, 9L), nativeScope, source.raw());
        assertEquals(Truth.UNKNOWN, OriginalSourceProof.contact(source, hit()));
        source.raw().addProperty("defenderPositionEvidence", "CLIENT_RENDER_FALLBACK");
        assertEquals(Truth.FALSE, OriginalSourceProof.contact(source, hit()));
        source.raw().addProperty("defenderPositionEvidence", "SERVER_REPORTED_TILE");
        assertEquals(Truth.TRUE, OriginalSourceProof.contact(source, hit()));
        source.raw().addProperty("contactWorldX", 101);
        assertEquals(Truth.FALSE, OriginalSourceProof.contact(source, hit()));
        source.raw().addProperty("contactWorldX",100);
        source.identity=new Identity("graphic",99,null,null,"Observed",null,new Scope(null,0,0,7L));
        ResolutionCandidate row=ResolutionCandidate.hint("contact",source);row.sourceOnly=true;
        OriginalSourceProof.scalar(source,hit(),row);
        assertEquals("ENVIRONMENTAL",row.confidence);
        assertEquals(Truth.UNKNOWN,OriginalSourceProof.contact(source,hit()));
        assertFalse(row.componentQualified);
        assertTrue(row.sourceOnly);
        assertNull(source.identity.scope.sceneKey);
        row.status="EXCLUDED";OriginalSourceProof.scalar(source,hit(),row);
        assertEquals("UNKNOWN",row.confidence);
        assertEquals("EXCLUDED",row.status);
    }

    @Test
    public void onlyAnAdmittedInteractionResponseCanSupplyItsCorrelationConfidence()
    {
        TestSource source = source("message", "{}", "{}");
        ResolutionCandidate row = ResolutionCandidate.hint("response",source);
        row.sourceAssociation = "PICKPOCKET_FAILURE_CORRELATION";
        row.rule = json("{evidenceMode:'INTERACTION_RESPONSE'}");
        OriginalSourceProof.scalar(source,hit(),row);
        assertFalse(row.scalarEligible);
        row.componentQualified = true;
        OriginalSourceProof.scalar(source,hit(),row);
        assertTrue(row.scalarEligible);
        assertEquals("TIMED",row.confidence);
        row.sourceOnly = true;
        OriginalSourceProof.scalar(source,hit(),row);
        assertFalse(row.scalarEligible);
        assertEquals("UNKNOWN",row.confidence);
    }

    @Test
    public void anObservedCarrierKeepsItsOriginalOwnerAfterThatActorDespawns()
    {
        ProjectileSourceHistory.SourceObservation first = ProjectileSourceHistory.SourceObservation.builder()
            .observationId(42L).projectileId(700123).observedTick(8).observedCycle(80).contextEpoch(7)
            .actorKind("NPC").sourceRef("npc:original").sourceEvidence("ACTOR").sourceActorKnown(true)
            .sourceNameKnown(true).sourceObservedName("Recorded caster").sourceNpcId(900111).sourceCombatLevel(77)
            .sourceSceneKey("scene").sourceWorldViewId(0).sourcePlane(0).sourceDefinitionEvidence("NPC_API_ID")
            .sourceNormalWorldContextObserved(true).sourceModelOverridesPresent(false).build();
        ProjectileState projectile = ProjectileState.builder().observationId(42L).projectileId(700123)
            .observedTick(8).observedCycle(80).worldViewId(0).plane(0).samplePlaneKnown(true)
            .sourceHistory(ProjectileSourceHistory.builder().version(1).observedTick(8).contextEpoch(7).firstObservedSource(first).build())
            .sourceEvidence("ACTOR").sourceActorKnown(true).sourceRef("npc:original").sourceNpcId(900111)
            .sourceNameKnown(true).sourceObservedName("Recorded caster").sourceCombatLevel(77).sourceDefinitionEvidence("NPC_API_ID")
            .sourceSceneKey("scene").sourceWorldViewId(0).sourcePlane(0).sourceNormalWorldContextObserved(true).sourceModelOverridesPresent(false)
            .targetRef("player").targetActorKnown(true).targetWorldViewId(0).targetActorWorldViewId(0).targetPlane(0)
            .startCycle(80).endCycle(100).remainingCycles(20).spawnTick(8).spawnTickEvidence("FIRST_OBSERVED").build();
        Map<String,Object> projectileData = new LinkedHashMap<>();
        projectileData.put("projectile",projectile);projectileData.put("causalSceneEpoch",7L);
        ObservedEvent flight = ObservedEvent.builder().type("PROJECTILE_SPAWNED").tick(8).gameCycle(80).sequence(3)
            .sceneKey("scene").data(projectileData).build();
        Map<String,Object> despawnData = new Gson().fromJson("{causalSceneEpoch:7,actorState:{actorKind:'NPC',npcId:900111,worldViewId:0,plane:0,planeKnown:true}}",Map.class);
        ObservedEvent despawn = ObservedEvent.builder().type("NPC_DESPAWNED").actorRef("npc:original")
            .tick(9).gameCycle(90).sequence(4).sceneKey("scene").data(despawnData).build();
        Hit original = hit();
        DamageRecord damage = original.record();
        Map<String,Object> hpData = new Gson().fromJson(original.callback().raw,Map.class);hpData.put("causalSceneEpoch",7L);
        ObservedEvent hp = ObservedEvent.builder().type("HITSPLAT").actorRef("player").tick(10).gameCycle(100)
            .sequence(9).sceneKey("scene").data(hpData).build();
        ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(Collections.emptyList(),Collections.emptyList(),
            Arrays.asList(flight,despawn,hp),Collections.singletonList(damage),Collections.emptyList(),8,10,9,1);
        Hit recorded = new RawEvidenceWindow(window,new Gson()).hit(damage);
        Source source = recorded.sources().stream().filter(value -> value.channel() == Channel.PROJECTILE).findFirst().get();
        assertEquals(Truth.FALSE,OriginalSourceProof.sourceActive(source,recorded));
        assertTrue(OriginalSourceProof.api(source));
        ResolverV1 engine = ResolverTestCatalog.engine(json("{schemaVersion:1,purpose:'retrospective_damage_resolution',resolutionRules:[]}"));
        ResolutionResult result = engine.evaluate(recorded);
        assertTrue(result.candidates.stream().anyMatch(row -> "POSSIBLE".equals(row.status)
            && "npc:original".equals(row.source.identity().actorRef)));
        DamageRecord projected = SourceEvidenceResolver.project(recorded,result,engine.id());
        assertEquals("Recorded caster",projected.getAttackerName());
        assertEquals("TIMED",projected.getAttribution());
        assertEquals(12,projected.getAmount());
        assertEquals(16,projected.getHitsplatType());
    }

    @Test
    public void aliasRetirementKeepsApiTimingWithoutInheritingModelOrNativeQualification()
    {
        TestSource source=source("npc","{targetRef:'player',endCycle:100,sourceHistory:{version:1,actorChanged:false,referenceChanged:true,"
            + "definitionChanged:false,scopeChanged:false,observationGap:false,projectileDefinitionChanged:false}}","{}");
        source.channel=Channel.PROJECTILE;
        source.first=json("{sourceEvidence:'ACTOR',sourceActorKnown:true,actorKind:'NPC',sourceNameKnown:true,sourceObservedName:'Observed',"
            + "sourceNpcId:99,sourceCombatLevel:20,sourceRef:'actor',sourceNormalWorldContextObserved:true,sourceModelOverridesPresent:false,"
            + "sourceSceneKey:'scene',sourceWorldViewId:0,sourcePlane:0,observedTick:10,observedCycle:99}");
        source.sample=new Receipt(null,source.receipt.clock,nativeScope,source.raw);
        ResolutionCandidate row=ResolutionCandidate.hint("recorded",source);
        OriginalSourceProof.scalar(source,hit(),row);
        assertEquals("TIMED",row.confidence);
        assertTrue(OriginalSourceProof.staticReference(source,hit()));
        assertFalse(OriginalSourceProof.numericReference(source,hit()));
        source.raw.getAsJsonObject("sourceHistory").addProperty("definitionChanged",true);
        assertFalse(OriginalSourceProof.staticReference(source,hit()));
        source.raw.getAsJsonObject("sourceHistory").addProperty("definitionChanged",false);
        source.raw.getAsJsonObject("sourceHistory").addProperty("actorChanged",true);
        row=ResolutionCandidate.hint("recorded",source);OriginalSourceProof.scalar(source,hit(),row);
        assertEquals("UNKNOWN",row.confidence);
        source.raw.getAsJsonObject("sourceHistory").addProperty("actorChanged",false);
        source.raw.getAsJsonObject("sourceHistory").addProperty("referenceChanged",false);
        source.first.remove("sourceSceneKey");
        source.identity=new Identity("npc",99,"actor",null,"Observed",20,new Scope(null,0,0,7L));
        row=ResolutionCandidate.hint("recorded",source);OriginalSourceProof.scalar(source,hit(),row);
        assertEquals("TIMED",row.confidence);
        assertFalse(OriginalSourceProof.numericReference(source,hit()));
        assertNull(source.identity.scope.sceneKey);
    }

    @Test
    public void originalPoseAndRetainedOriginRemainWeakCorrelationsWithoutNativeQualification()
    {
        TestSource source=source("npc","{}","{animationId:123,interaction:{reportedInteracting:true,actorRef:'player'}}");
        source.channel=Channel.HP_ACTOR_POSE;
        source.identity=new Identity("npc",99,"actor",null,"Observed",20,new Scope(null,null,0,7L));
        source.receipt=new Receipt(null,new Clock(10,100,9L),source.identity.scope,source.raw);
        ResolutionCandidate row=ResolutionCandidate.hint("pose",source);
        OriginalSourceProof.scalar(source,hit(),row);
        assertEquals("PROXIMITY",row.confidence);
        assertFalse(row.componentQualified);assertNull(row.maximum);
        source.actor.getAsJsonObject("interaction").addProperty("reportedInteracting",false);
        row=ResolutionCandidate.hint("pose",source);OriginalSourceProof.scalar(source,hit(),row);
        assertEquals("UNKNOWN",row.confidence);
        source.channel=Channel.PROJECTILE_ORIGIN;
        source.raw=json("{targetRef:'player',endCycle:123}");
        source.first=json("{proximitySampleRetained:true,proximityCandidate:{actorRef:'actor'}}");
        source.receipt=new Receipt(null,new Clock(10,99,8L),source.identity.scope,source.first);
        source.sample=new Receipt(null,new Clock(10,99,8L),source.identity.scope,source.raw);
        row=ResolutionCandidate.hint("origin",source);row.sourceOnly=true;
        OriginalSourceProof.scalar(source,hit(),row);
        assertEquals("PROXIMITY",row.confidence);
        assertFalse(OriginalSourceProof.api(source));assertFalse(OriginalSourceProof.numericReference(source,hit()));
        assertFalse(row.componentQualified);assertNull(row.maximum);
        source.raw.addProperty("endCycle",141);
        row=ResolutionCandidate.hint("origin",source);row.sourceOnly=true;OriginalSourceProof.scalar(source,hit(),row);
        assertEquals("UNKNOWN",row.confidence);
        assertFalse(OriginalSourceProof.expiredCarrier(source,hit()));
        source.raw.addProperty("endCycle",69);
        assertTrue(OriginalSourceProof.expiredCarrier(source,hit()));
        source.raw.addProperty("endCycle",70);
        assertFalse(OriginalSourceProof.expiredCarrier(source,hit()));
        source.raw.addProperty("endCycle",123);
        source.identity=new Identity("npc",99,"actor",null,"Observed",20,new Scope("scene",1,0,7L));
        row=ResolutionCandidate.hint("origin",source);OriginalSourceProof.scalar(source,hit(),row);
        assertEquals("UNKNOWN",row.confidence);
    }

    @Test
    public void onlyTheSameRecordedGraphicLifetimeCanProveALaterBirth()
    {
        TestSource source=source("graphic","{graphicObservationId:77}","{}");
        source.channel=Channel.FRAME_SURFACE;
        TestHit hit=hit();
        ObservedEvent event=ObservedEvent.builder().type("GRAPHICS_OBJECT_CREATED").tick(10).gameCycle(101).sequence(10).build();
        Receipt birth=new Receipt(event,new Clock(10,101,10L),nativeScope,json("{id:99,graphicObservationId:77}"));
        hit.lifecycles=Collections.singletonList(birth);
        assertTrue(OriginalSourceProof.observedBirthAfterImpact(source,hit));
        source.raw.addProperty("graphicObservationId",78);
        assertFalse(OriginalSourceProof.observedBirthAfterImpact(source,hit));
        source.raw.remove("graphicObservationId");source.raw.addProperty("objectKey",77);
        assertFalse(OriginalSourceProof.observedBirthAfterImpact(source,hit));
        source.raw.addProperty("graphicObservationId",77);
        hit.lifecycles=Collections.singletonList(new Receipt(event,new Clock(10,99,8L),nativeScope,birth.raw));
        assertFalse(OriginalSourceProof.observedBirthAfterImpact(source,hit));
    }

    @Test
    public void aGraphicBirthUsesOnlyItsOwnObservedLocalCoordinatesAndRegisteredNativeBase()
    {
        TestHit hit=hit();
        hit.scene=SceneData.builder().sceneKey("scene").worldViewId(0).plane(0).baseX(100).baseY(99).width(104).height(104).build();
        TestSource source=source("graphic","{localX:64,localY:192,localWorldViewId:0,nativeSceneKey:'scene',"
            + "scopeKnown:true,positionScopeKnown:true}","{}");source.channel=Channel.GRAPHIC_BIRTH;
        assertEquals(Truth.TRUE,OriginalSourceProof.geometry(source,hit,json("{shape:'point'}")));
        source.raw.addProperty("localWorldViewId",1);
        assertEquals(Truth.UNKNOWN,OriginalSourceProof.geometry(source,hit,json("{shape:'point'}")));
        source.raw.addProperty("localWorldViewId",0);source.raw.addProperty("positionScopeKnown",false);
        assertEquals(Truth.UNKNOWN,OriginalSourceProof.geometry(source,hit,json("{shape:'point'}")));
    }

    private Receipt lifecycle(String type, String actorRef, int tick, int cycle, long sequence)
    {
        ObservedEvent event = ObservedEvent.builder().type(type).actorRef(actorRef).tick(tick).gameCycle(cycle).sequence(sequence).build();
        return new Receipt(event, new Clock(tick, cycle, sequence), nativeScope, json("{actorState:{npcId:99}}"));
    }

    private TestHit hit()
    {
        TestHit hit = new TestHit();
        DamageRecord record = DamageRecord.builder().impactTick(10).observedCycle(100).observationSequence(9L)
            .impactContextEpoch(7L).finalizationContextEpoch(7L).amount(12).hitsplatType(16).hitsplatTypeKnown(true)
            .impactX(100).impactY(100).impactSceneKey("scene").impactWorldViewId(0).impactPlane(0).defenderRef("player").build();
        JsonObject raw = json("{receiverKind:'PLAYER',amount:12,hitsplatType:16,actorState:{actorKind:'LOCAL_PLAYER',"
            + "worldPositionKnown:true,planeKnown:true,worldViewId:0,plane:0,worldX:100,worldY:100}}");
        hit.record = record;
        hit.callback = new Receipt(null, new Clock(10, 100, 9L), nativeScope, raw);
        hit.lifecycles = Collections.emptyList();
        return hit;
    }

    private TestSource source(String kind, String raw, String actor)
    {
        TestSource source = new TestSource();
        source.identity = new Identity(kind, 99, "npc".equals(kind) ? "actor" : null, null, "Observed", 20, nativeScope);
        source.raw = json(raw);
        source.actor = json(actor);
        source.first = json("{}");
        source.receipt = new Receipt(null, new Clock(10, 99, 8L), nativeScope, json(raw));
        source.channel = Channel.HP_CONTACT;
        return source;
    }

    private static final class TestHit implements Hit
    {
        DamageRecord record;
        Receipt callback;
        SceneData scene;
        List<Receipt> lifecycles = Collections.emptyList();
        public DamageRecord record() { return record; }
        public Receipt callback() { return callback; }
        public WorldSnapshot impact() { return null; }
        public SceneData scene() { return scene; }
        public List<Source> sources() { return Collections.emptyList(); }
        public List<Receipt> lifecycles() { return lifecycles; }
        public List<DamageRecord> peers() { return Collections.singletonList(record); }
        public boolean complete() { return true; }
        public WorldSnapshot exactFrame(Clock clock,Scope scope) { return null; }
    }

    private static final class TestSource implements Source
    {
        Identity identity;
        Channel channel;
        Receipt receipt,sample;
        JsonObject raw,actor,first;
        public Channel channel() { return channel; }
        public String key() { return "original-test-source"; }
        public Identity identity() { return identity; }
        public Receipt receipt() { return receipt; }
        public Receipt sample() { return sample; }
        public Receipt related() { return null; }
        public Receipt launch() { return null; }
        public JsonObject raw() { return raw; }
        public JsonObject actor() { return actor; }
        public JsonObject first() { return first; }
        public WorldSnapshot frame() { return null; }
    }

    private static JsonObject json(String value)
    {
        return new JsonParser().parse(value).getAsJsonObject();
    }
}
