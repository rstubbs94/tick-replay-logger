/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.Gson;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.tickreplaylogger.capture.ResolverEvidence.*;

public class RawEvidenceWindowTest
{
    private final Gson gson = new Gson();
    private final DamageRecord hp = DamageRecord.builder().impactTick(10).observedCycle(301).observationSequence(20L)
        .direction("RECEIVED").defenderRef("player").impactSceneKey("room").impactWorldViewId(0).impactPlane(0)
        .impactContextEpoch(9L).finalizationContextEpoch(9L).amount(12).hitsplatType(16).hitsplatTypeKnown(true).build();

    @Test
    public void aContactKeepsItsOwnUnknownScopeAndOriginalPacketInsteadOfBorrowingTheReceiverScope()
    {
        Map<String, Object> contact = map("kind", "GRAPHIC_SURFACE", "graphicId", 123,
            "graphicObservationId", 99L, "observationStage", "HITSPLAT_CALLBACK", "observedCycle", 301,
            "contactWorldX", 3201, "contactWorldY", 3202, "damaging", true);
        DamageRecord hit = hp.toBuilder().observedHazardContacts(Collections.singletonList(contact)).build();
        Source source = sources(hit, Collections.singletonList(callback()), Collections.emptyList()).stream()
            .filter(value -> value.channel() == Channel.HP_CONTACT).findFirst().get();
        assertNull(source.identity().scope.sceneKey);
        assertNull(source.identity().scope.worldViewId);
        assertNull(source.identity().scope.plane);
        assertEquals(gson.toJsonTree(contact), source.raw());
        assertFalse(source.raw().has("worldX"));
        assertEquals(Integer.valueOf(10), source.receipt().clock.tick);
        assertEquals(Long.valueOf(20), source.related().clock.sequence);
    }

    @Test
    public void anExplicitPlayerKindCannotBecomeAnNpcAndAnEmptyPoseRetainsItsKnownReference()
    {
        Map<String, Object> actor = map("actorKind", "OTHER_PLAYER", "npcId", 100,
            "name", "Observed player", "worldViewId", 0, "plane", 0);
        ObservedEvent event = ObservedEvent.builder().tick(10).gameCycle(300).sequence(19).actorRef("player:known")
            .type("ANIMATION_CHANGED").sceneKey("room").data(actor).build();
        Map<String, Object> pose = map("kind", "NPC_ACTIVITY_OBSERVATION", "actorRef", "npc:known", "name", "Observed NPC",
            "actorState", Collections.emptyMap(), "observedTick", 10, "observedCycle", 301, "impactSequence", 20L);
        List<Source> sources = sources(hp.toBuilder().attributionCandidates(Collections.singletonList(pose)).build(),
            Arrays.asList(event, callback()), Collections.emptyList());
        Source player = sources.stream().filter(value -> value.channel() == Channel.ACTOR_CALLBACK).findFirst().get();
        assertEquals("player", player.identity().kind);
        assertNull(player.identity().id);
        Source npc = sources.stream().filter(value -> value.channel() == Channel.HP_ACTOR_POSE).findFirst().get();
        assertEquals("npc:known", npc.identity().actorRef);
        assertEquals("Observed NPC", npc.identity().name);
        assertNull(npc.identity().id);
        assertFalse(npc.actor().has("modelOverridesPresent"));
    }

    @Test
    public void flatPoseDefinitionMetadataSurvivesWithoutBecomingModelProof()
    {
        Map<String, Object> pose = map("kind", "NPC_ACTIVITY_OBSERVATION", "actorRef", "npc:known", "name", "Observed NPC",
            "npcId", 123, "combatLevel", 456, "actorState", Collections.emptyMap(),
            "observedTick", 10, "observedCycle", 301, "impactSequence", 20L);
        Source source = sources(hp.toBuilder().attributionCandidates(Collections.singletonList(pose)).build(),
            Collections.singletonList(callback()), Collections.emptyList()).stream()
            .filter(value -> value.channel() == Channel.HP_ACTOR_POSE).findFirst().get();
        assertEquals(Integer.valueOf(123), source.identity().id);
        assertEquals(Integer.valueOf(456), source.identity().combatLevel);
        assertEquals(0, source.actor().size());
        assertEquals(gson.toJsonTree(pose), source.raw());
    }

    @Test
    public void theSameOriginalPoseInTwoStorageListsIsOnePoseAndNeverAContactSurface()
    {
        Map<String, Object> pose = map("kind", "NPC_ACTIVITY_OBSERVATION", "actorRef", "npc:known", "name", "Observed NPC",
            "npcId", 123, "actorState", Collections.emptyMap(), "observedTick", 10, "observedCycle", 301, "impactSequence", 20L);
        DamageRecord hit = hp.toBuilder().attributionCandidates(Collections.singletonList(pose))
            .observedHazardContacts(Collections.singletonList(new LinkedHashMap<>(pose))).build();
        List<Source> sources = sources(hit, Collections.singletonList(callback()), Collections.emptyList());
        assertEquals(1, sources.stream().filter(value -> value.channel() == Channel.HP_ACTOR_POSE).count());
        assertEquals(0, sources.stream().filter(value -> value.channel() == Channel.HP_CONTACT).count());
        assertEquals(gson.toJsonTree(pose), sources.stream().filter(value -> value.channel() == Channel.HP_ACTOR_POSE)
            .findFirst().get().raw());
        assertEquals(1, hit.getObservedHazardContacts().size());
        assertEquals(1, hit.getAttributionCandidates().size());
    }

    @Test
    public void aProximityOnlyProjectileDoesNotInventAnApiPlayerOwner()
    {
        ProjectileSourceCandidate proxy = ProjectileSourceCandidate.builder().actorRef("npc:proxy").name("Observed proxy").build();
        ProjectileSourceHistory.SourceObservation first = ProjectileSourceHistory.SourceObservation.builder()
            .contextEpoch(9L).actorKind("NONE").sourceEvidence("PROXIMITY").sourceActorKnown(true).proximityCandidate(proxy)
            .observedTick(9).observedCycle(280).build();
        ProjectileState projectile = ProjectileState.builder().projectileId(999001).observationId(99L).observedTick(9).observedCycle(280)
            .sourceHistory(ProjectileSourceHistory.builder().version(1).contextEpoch(9L).firstObservedSource(first).build()).build();
        ObservedEvent event = ObservedEvent.builder().type("PROJECTILE_FIRST_SEEN").tick(9).gameCycle(280).sequence(10)
            .data(map("projectile", projectile)).build();
        List<Source> sources = sources(hp, Arrays.asList(event, callback()), Collections.emptyList());
        Source asset = sources.stream().filter(source -> source.channel() == Channel.PROJECTILE).findFirst().get();
        assertEquals("projectile", asset.identity().kind);
        assertEquals(Integer.valueOf(999001), asset.identity().id);
        assertNull(asset.identity().actorRef);
        assertEquals(0, asset.first().size());
        assertFalse(sources.stream().anyMatch(source -> "player".equals(source.identity().kind)));
        assertTrue(sources.stream().anyMatch(source -> source.channel() == Channel.PROJECTILE_ORIGIN
            && "npc:proxy".equals(source.identity().actorRef)));
        assertEquals(gson.toJsonTree(projectile), asset.raw());
    }

    @Test
    public void duplicateNativeSamplesCannotSelectAnArbitraryProjectileOwner()
    {
        ProjectileSourceHistory.SourceObservation owner = ProjectileSourceHistory.SourceObservation.builder()
            .contextEpoch(9L).actorKind("NPC").sourceRef("npc:owner").sourceNpcId(123).sourceObservedName("Owner").sourceNameKnown(true)
            .sourceEvidence("ACTOR").sourceActorKnown(true).observedTick(9).observedCycle(280).build();
        ProjectileState projectile = ProjectileState.builder().observationId(99L).observedTick(10).observedCycle(290)
            .sourceHistory(ProjectileSourceHistory.builder().version(1).contextEpoch(9L).firstObservedSource(owner).build()).build();
        WorldSnapshot frame = WorldSnapshot.builder().tick(10).gameCycle(290).gameCycleKnown(true).nativeContextKnown(true)
            .sceneKey("room").worldViewId(0).plane(0).projectiles(Collections.singletonList(projectile)).build();
        assertTrue(sources(hp, Collections.singletonList(callback()), Collections.singletonList(frame)).stream()
            .anyMatch(source -> source.channel() == Channel.PROJECTILE));
        assertFalse(sources(hp, Collections.singletonList(callback()), Arrays.asList(frame, frame)).stream()
            .anyMatch(source -> source.channel() == Channel.PROJECTILE));
        assertEquals(Long.valueOf(99), projectile.getObservationId());
    }

    @Test
    public void aChangedFinalizationEpochCannotBorrowAnOriginalLookingFrame()
    {
        WorldSnapshot frame = WorldSnapshot.builder().tick(10).gameCycle(300).gameCycleKnown(true)
            .nativeContextKnown(true).sceneKey("room").worldViewId(0).plane(0).build();
        ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(Collections.singletonList(frame),
            null, Collections.singletonList(callback()), Collections.singletonList(hp), null, 9, 10, 20, 1);
        RawEvidenceWindow raw = new RawEvidenceWindow(window, gson);
        Clock clock = new Clock(10, 300, null);
        Scope scope = new Scope("room", 0, 0, 9L);
        assertSame(frame, raw.hit(hp).exactFrame(clock, scope));
        DamageRecord finalizedLater = hp.toBuilder().finalizationContextEpoch(10L).build();
        Hit changed = raw.hit(finalizedLater);
        assertNull(changed.exactFrame(clock, scope));
        assertEquals(Long.valueOf(9), changed.callback().scope.epoch);
        assertEquals(gson.toJsonTree(callback().getData()), changed.callback().raw);
    }

    @Test
    public void actorlessMessagesUseOnlyTheirOwnUniqueRegisteredScene()
    {
        ObservedEvent message = ObservedEvent.builder().tick(10).gameCycle(301).sequence(21).type("SYSTEM_MESSAGE")
            .sceneKey("message-room").data(map("message", "Recorded response", "channel", "GAMEMESSAGE")).build();
        SceneData own = SceneData.builder().sceneKey("message-room").worldViewId(7).plane(2).width(104).height(104).build();
        SceneData conflict = SceneData.builder().sceneKey("message-room").worldViewId(8).plane(2).width(104).height(104).build();
        for (List<SceneData> registered : Arrays.asList(Collections.singletonList(own), Collections.<SceneData>emptyList(),
            Arrays.asList(own, conflict)))
        {
            ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(null, registered,
                Arrays.asList(callback(), message), Collections.singletonList(hp), null, 9, 10, 21, 1);
            Source source = new RawEvidenceWindow(window, gson).hit(hp).sources().stream()
                .filter(value -> value.channel() == Channel.MESSAGE).findFirst().get();
            assertEquals("message-room", source.receipt().scope.sceneKey);
            assertEquals(registered.size() == 1 ? Integer.valueOf(7) : null, source.receipt().scope.worldViewId);
            assertEquals(registered.size() == 1 ? Integer.valueOf(2) : null, source.receipt().scope.plane);
            assertEquals(gson.toJsonTree(message.getData()), source.raw());
            assertFalse(source.raw().has("worldViewId"));
        }
    }

    @Test
    public void sameTickLifecycleReceiptsRetainTheirActualAfterHpOrderWithoutTurningIntoAttackCallbacks()
    {
        ObservedEvent despawn = ObservedEvent.builder().tick(10).gameCycle(302).sequence(21).type("NPC_DESPAWNED")
            .actorRef("npc:removed").sceneKey("room").data(map("actorKind", "NPC", "npcId", 123, "name", "Observed source")).build();
        ObservedEvent animation = ObservedEvent.builder().tick(10).gameCycle(302).sequence(22).type("ANIMATION_CHANGED")
            .actorRef("npc:removed").sceneKey("room").data(map("actorKind", "NPC", "npcId", 123, "animationId", 456)).build();
        ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(null, null,
            Arrays.asList(callback(), despawn, animation), Collections.singletonList(hp), null, 9, 10, 22, 1);
        List<Source> sources = new RawEvidenceWindow(window, gson).hit(hp).sources();
        Source lifecycle = sources.stream().filter(source -> source.channel() == Channel.ACTOR_LIFECYCLE).findFirst().get();
        assertSame(despawn, lifecycle.receipt().event);
        assertEquals(Integer.valueOf(302), lifecycle.receipt().clock.cycle);
        assertEquals(Long.valueOf(21), lifecycle.receipt().clock.sequence);
        assertEquals(Long.valueOf(20), lifecycle.related().clock.sequence);
        assertFalse(sources.stream().anyMatch(source -> source.channel() == Channel.ACTOR_CALLBACK));
        assertEquals(gson.toJsonTree(despawn.getData()), lifecycle.raw());
    }

    @Test
    public void outgoingReceiptsKeepTheirOwnNativeFactsWhenTheRichRecordOmitsThem()
    {
        DamageRecord outgoing = DamageRecord.builder().impactTick(10).observedCycle(300).observationSequence(19L)
            .direction("NPC_RECEIVED").ownership("LOCAL_PLAYER").defenderRef("npc:victim")
            .amount(5).hitsplatType(16).hitsplatTypeKnown(true).build();
        ObservedEvent event = ObservedEvent.builder().type("HITSPLAT").tick(10).gameCycle(300).sequence(19)
            .actorRef("npc:victim").sceneKey("outgoing-room").data(map("amount", 5, "hitsplatType", 16,
                "causalSceneEpoch", 9L, "actorState", map("actorKind", "NPC", "npcId", 123,
                    "worldViewId", 2, "plane", 1, "planeKnown", true))).build();
        List<DamageRecord> variants = Arrays.asList(outgoing, outgoing.toBuilder().direction("DEALT").build(),
            outgoing.toBuilder().impactSceneKey("contradiction").build(), outgoing.toBuilder().impactWorldViewId(3).build(),
            outgoing.toBuilder().impactPlane(0).build(), outgoing.toBuilder().impactContextEpoch(10L).build(),
            outgoing.toBuilder().amount(6).build(), outgoing.toBuilder().defenderRef("npc:other").build());
        for (int i = 0; i < variants.size(); i++)
        {
            ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(null, null,
                Arrays.asList(event, callback()), Arrays.asList(hp, variants.get(i)), null, 9, 10, 20, 1);
            List<Source> triggers = new ArrayList<>();
            for (Source source : new RawEvidenceWindow(window, gson).hit(hp).sources())
            {
                if (source.channel() == Channel.OUTGOING_TRIGGER) triggers.add(source);
            }
            assertEquals("Original outgoing receipt variant " + i, i < 2 ? 1 : 0, triggers.size());
            if (i < 2)
            {
                Source trigger = triggers.get(0);
                assertSame(event, trigger.receipt().event);
                assertEquals("outgoing-room", trigger.receipt().scope.sceneKey);
                assertEquals(Integer.valueOf(2), trigger.receipt().scope.worldViewId);
                assertEquals(Integer.valueOf(1), trigger.receipt().scope.plane);
                assertEquals("room", trigger.related().scope.sceneKey);
                assertNull(variants.get(i).getImpactSceneKey());
                assertNull(variants.get(i).getImpactWorldViewId());
            }
        }
    }

    @Test
    public void incomingReceiptsUseTheirOwnOriginalScopeWhenRichNativeMetadataIsMissing()
    {
        DamageRecord missing = hp.toBuilder().impactSceneKey(null).impactWorldViewId(null).impactPlane(null)
            .impactContextEpoch(null).hitsplatTypeKnown(null).build();
        List<DamageRecord> variants = Arrays.asList(missing, hp,
            missing.toBuilder().impactSceneKey("contradiction").build(),
            missing.toBuilder().impactWorldViewId(3).build(), missing.toBuilder().impactPlane(1).build(),
            missing.toBuilder().impactContextEpoch(10L).build(), missing.toBuilder().amount(13).build(),
            missing.toBuilder().defenderRef("other-player").build(), missing.toBuilder().impactTick(9).build(),
            missing.toBuilder().observedCycle(300).build(), missing.toBuilder().observationSequence(19L).build(),
            missing.toBuilder().hitsplatType(5).build());
        ObservedEvent original = callback();
        String originalJson = gson.toJson(original);
        for (int i = 0; i < variants.size(); i++)
        {
            DamageRecord hit = variants.get(i);
            String before = gson.toJson(hit);
            ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(null, null,
                Collections.singletonList(original), Collections.singletonList(hit), null, 9, 10, 20, 1);
            Receipt receipt = new RawEvidenceWindow(window, gson).hit(hit).callback();
            if (i < 2)
            {
                assertNotNull("Matching original incoming receipt " + i, receipt);
                assertSame(original, receipt.event);
                assertEquals("room", receipt.scope.sceneKey);
                assertEquals(Integer.valueOf(0), receipt.scope.worldViewId);
                assertEquals(Integer.valueOf(0), receipt.scope.plane);
                assertEquals(Long.valueOf(9), receipt.scope.epoch);
            }
            else assertNull("Original incoming contradiction " + i, receipt);
            assertEquals(before, gson.toJson(hit));
            assertEquals(originalJson, gson.toJson(original));
        }
        assertNull(missing.getImpactSceneKey());
        assertNull(missing.getImpactWorldViewId());
        assertNull(missing.getImpactPlane());
        assertNull(missing.getImpactContextEpoch());
    }

    @Test
    public void anOriginalTypedReceiptSurvivesMissingRichScopeButNeverOverridesAnExplicitUnknownType()
    {
        DamageRecord missing = hp.toBuilder().impactSceneKey(null).impactWorldViewId(null).impactPlane(null)
            .impactContextEpoch(null).hitsplatType(5).hitsplatTypeKnown(null).build();
        Map<String, Object> fields = new LinkedHashMap<>(callback().getData());
        fields.put("hitsplatType", 5);
        ObservedEvent original = ObservedEvent.builder().tick(10).gameCycle(301).sequence(20).type("HITSPLAT")
            .actorRef("player").sceneKey("room").data(fields).build();
        ResolverV1 engine = ResolverV1.load(gson);
        for (boolean explicitlyUnknown : new boolean[] { false, true })
        {
            DamageRecord hit = missing.toBuilder().hitsplatTypeKnown(explicitlyUnknown ? false : null).build();
            ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(null, null,
                Collections.singletonList(original), Collections.singletonList(hit), null, 9, 10, 20, 1);
            Hit input = new RawEvidenceWindow(window, gson).hit(hit);
            assertSame(original, input.callback().event);
            ResolutionResult result = engine.evaluate(input);
            if (explicitlyUnknown) assertFalse(known(result.hitAnnotations.get("damageType")));
            else assertEquals("venom", text(result.hitAnnotations, "damageType"));
            assertNull(hit.getImpactSceneKey());
            assertEquals(Integer.valueOf(5), Integer.valueOf(hit.getHitsplatType()));
        }
    }

    @Test
    public void anOriginalLocalPlayerReceiptCanSupplyAMissingHeaderReferenceWithoutASettledVerdict()
    {
        for (int variant = 0; variant < 9; variant++)
        {
            Map<String, Object> fields = new LinkedHashMap<>(callback().getData());
            Map<String, Object> actor = map("actorKind", variant == 3 ? "OTHER_PLAYER" : variant == 4 ? "PLAYER" : "LOCAL_PLAYER",
                "worldViewId", 0, "plane", 0);
            if (variant == 5) actor.remove("actorKind");
            fields.put("actorState", actor);
            fields.put("receiverKind", variant == 6 ? "NPC" : "PLAYER");
            ObservedEvent event = ObservedEvent.builder().tick(10).gameCycle(301).sequence(20).type("HITSPLAT")
                .actorRef(variant == 2 ? "other-player" : null).sceneKey("room").data(fields).build();
            DamageRecord hit = hp.toBuilder().defenderIdentityEvidence(variant == 1 ? "SETTLED_LOCAL_REFERENCE_MATCH" : null).build();
            List<ObservedEventLink> links = variant >= 7 ? Collections.singletonList(ObservedEventLink.builder()
                .observationSequence(20).observationType("HITSPLAT").tick(10).resolvedTick(10)
                .actorRef(variant == 7 ? "other-player" : "player").build()) : Collections.emptyList();
            ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(null, null,
                Collections.singletonList(event), Collections.singletonList(hit), null, links, 9, 10, 20, 1);
            String before = gson.toJson(event);
            Receipt original = new RawEvidenceWindow(window, gson).hit(hit).callback();
            if (variant < 2 || variant == 8)
            {
                assertNotNull("Original local recipient variant " + variant, original);
                assertSame(event, original.event);
                assertEquals(gson.toJsonTree(fields), original.raw);
            }
            else assertNull("No positive original local recipient variant " + variant, original);
            assertEquals(variant == 2 ? "other-player" : null, event.getActorRef());
            assertEquals(before, gson.toJson(event));
        }
    }

    private List<Source> sources(DamageRecord hit, List<ObservedEvent> events, List<WorldSnapshot> frames)
    {
        ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(frames, null, events,
            Collections.singletonList(hit), null, 9, 10, 20, 1);
        return new RawEvidenceWindow(window, gson).hit(hit).sources();
    }

    private ObservedEvent callback()
    {
        return ObservedEvent.builder().tick(10).gameCycle(301).sequence(20).type("HITSPLAT").actorRef("player")
            .sceneKey("room").data(map("amount", 12, "hitsplatType", 16, "causalSceneEpoch", 9L,
                "actorState", map("actorKind", "LOCAL_PLAYER", "worldViewId", 0, "plane", 0))).build();
    }

    private static Map<String, Object> map(Object... fields)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
}
