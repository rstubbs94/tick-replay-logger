/* Copyright (c) 2026, rstubbs94. All rights reserved.
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * under the BSD 2-Clause license distributed with this project. THIS SOFTWARE IS PROVIDED "AS IS".
 */
package com.tickreplaylogger.capture;

import com.google.gson.Gson;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.DeathRecordingV2;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import com.tickreplaylogger.model.world.ProjectileState;
import com.tickreplaylogger.model.world.ProjectileSourceHistory;
import com.tickreplaylogger.model.DamageEvent;
import com.tickreplaylogger.model.TickData;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResolverEvidenceWindowTest
{
	@Test
	public void anItemWitnessUsesItsOriginalInputEpochAndNeverTheReceiverReceiptEpoch()
	{
		Gson gson = new Gson(); DeathRecordingV2 base = minimal(false, true);
		ObservedEvent rawHp = base.getEvents().getObservations().get(1);
		Map<String, Object> hpFields = new LinkedHashMap<>(rawHp.getData()); hpFields.put("causalSceneEpoch", 9L);
		ObservedEvent hp = ObservedEvent.builder().tick(10).gameCycle(301).sequence(20).type("HITSPLAT").actorRef("player")
			.sceneKey("room").data(hpFields).build();
		ObservedEvent input = ObservedEvent.builder().tick(9).gameCycle(280).sequence(5).type("MENU_OPTION").actorRef("player")
			.sceneKey("room").data(map("causalSceneEpoch", 7L, "actorState", map("worldViewId", 0, "plane", 0))).build();
		DamageRecord hit = base.getEvents().getDamageEvents().get(0).toBuilder().impactContextEpoch(9L).finalizationContextEpoch(9L)
			.attributionCandidates(Collections.singletonList(map("kind", "ITEM_CONSUMPTION_CANDIDATE", "name", "Observed item",
				"itemId", 999001, "inputSequence", 5L, "observedTick", 10, "observedCycle", 301, "observationSequence", 20L))).build();
		for (boolean originalAvailable : new boolean[] { true, false })
		{
			DeathRecordingV2 recording = base.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(hit))
				.observations(originalAvailable ? Arrays.asList(input, hp) : Collections.singletonList(hp)).build()).build();
			com.google.gson.JsonObject normalized = new ResolverV1Recording(ResolverV1.load(gson), gson, recording).normalize(hit);
			com.google.gson.JsonObject source = normalized.getAsJsonArray("cues").get(0).getAsJsonObject().getAsJsonObject("source");
			if (originalAvailable) assertEquals(7L, source.getAsJsonObject("scope").get("contextEpoch").getAsLong());
			else assertFalse(source.getAsJsonObject("scope").has("contextEpoch"));
			assertEquals(Long.valueOf(9), hit.getImpactContextEpoch());
		}
	}

	@Test
	public void oneOriginalActorObservationRetainsItsDirectAndAreaRuleAlternatives()
	{
		Gson gson = new Gson();
		Map<String, Object> selector = map("kind", "npc", "ids", Collections.singletonList(990001));
		Map<String, Object> signals = map("animationIds", Collections.singletonList(990777));
		ResolverV1 engine = new ResolverV1(ResolverTestCatalog.lower(gson.toJsonTree(map("schemaVersion", 1, "purpose", "retrospective_damage_resolution",
			"specialRules", Arrays.asList(map("id", "synthetic:direct", "pattern", "direct_attack", "source", selector, "signals", signals),
				map("id", "synthetic:area", "pattern", "area_impact", "source", selector, "signals", signals)))).getAsJsonObject()));
		DeathRecordingV2 recording = minimal(false, true);
		ResolverV1Recording adapter = new ResolverV1Recording(engine, gson, recording);
		com.google.gson.JsonObject normalized = adapter.normalize(recording.getEvents().getDamageEvents().get(0));
		assertEquals(1, normalized.getAsJsonArray("cues").size());
		assertEquals(gson.toJsonTree(recording.getEvents().getObservations().get(0).getData()),
			normalized.getAsJsonArray("cues").get(0).getAsJsonObject().get("originalActorState"));
		SourceResolution result = adapter.resolveHit(recording.getEvents().getDamageEvents().get(0)).getSourceResolution();
		assertEquals(1, result.getCandidates().size());
		assertTrue(result.getCandidates().get(0).getEvidence().contains("RESOLVER_V1:synthetic:direct"));
		assertTrue(result.getCandidates().get(0).getEvidence().contains("RESOLVER_V1:synthetic:area"));
		assertTrue(result.isUnidentifiedPossible());
	}

	@Test
	public void missingProjectileLifetimeRetainsItsObservedOwnerWithoutInventingArrival()
	{
		Gson gson = new Gson(); DeathRecordingV2 base = minimal(false, true);
		ProjectileSourceHistory.SourceObservation owner = ProjectileSourceHistory.SourceObservation.builder().actorKind("NPC").sourceRef("npc:original")
			.sourceNpcId(990001).sourceObservedName("Observed owner").sourceNameKnown(true).sourceCombatLevel(10)
			.sourceActorKnown(true).sourceEvidence("ACTOR").sourceSceneKey("room").sourceWorldViewId(0).sourcePlane(0)
			.observedTick(9).observedCycle(280).sourceNormalWorldContextObserved(true).sourceModelOverridesPresent(false).build();
		ProjectileState projectile = ProjectileState.builder().observationId(99L).projectileId(999001).worldViewId(0).plane(0).samplePlaneKnown(true)
			.observedTick(9).observedCycle(280).spawnTick(9).targetRef("player")
			.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(owner).build()).build();
		DamageRecord raw = base.getEvents().getDamageEvents().get(0).toBuilder().attributionCandidates(Collections.singletonList(
			map("kind", "PROJECTILE_OBSERVATION", "projectile", projectile, "sceneKey", "room"))).build();
		DeathRecordingV2 input = base.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(raw))
			.observations(Collections.singletonList(base.getEvents().getObservations().get(1))).build()).build();
		DamageRecord result = ResolverV1Recording.resolve(input, gson).getEvents().getDamageEvents().get(0);
		assertEquals("Observed owner", com.tickreplaylogger.util.SourceResolutionLabels.label(result.getSourceResolution()));
		assertEquals("UNKNOWN", result.getAttribution());
		assertEquals(raw.getImpactTick(), result.getLaunchTick());
		assertTrue(result.getSourceResolution().isUnidentifiedPossible());
		assertNull(projectile.getEndCycle());
		assertEquals(gson.toJson(raw.getAttributionCandidates()), gson.toJson(result.getAttributionCandidates()));
	}

	@Test
	public void fatalSummaryReusesCanonicalAnnotationsOnlyForTheSameOriginalCallback()
	{
		Gson gson = new Gson(); DeathRecordingV2 base = minimal(false, true);
		DamageRecord raw = base.getEvents().getDamageEvents().get(0);
		DeathRecordingV2 input = base.toBuilder().summary(DeathRecordingV2.Summary.builder().fatalHit(raw)
			.fatalHitCandidates(Collections.singletonList(raw)).killerName("Original death selection").killerBasis("ORIGINAL_BASIS").build()).build();
		String frozen = gson.toJson(input);
		DeathRecordingV2 resolved = ResolverV1Recording.resolve(input, gson);
		assertEquals(gson.toJson(resolved.getEvents().getDamageEvents().get(0)), gson.toJson(resolved.getSummary().getFatalHit()));
		assertEquals(gson.toJson(resolved.getSummary().getFatalHit()), gson.toJson(resolved.getSummary().getFatalHitCandidates().get(0)));
		assertEquals("Original death selection", resolved.getSummary().getKillerName());
		assertEquals("ORIGINAL_BASIS", resolved.getSummary().getKillerBasis());
		assertEquals(frozen, gson.toJson(input));
		for (DamageRecord different : Arrays.asList(raw.toBuilder().amount(raw.getAmount() + 1).build(),
			raw.toBuilder().impactContextEpoch(1L).build(), raw.toBuilder().finalizationContextEpoch(1L).build()))
		{
			DeathRecordingV2 mismatched = input.toBuilder().summary(input.getSummary().toBuilder().fatalHit(different).build()).build();
			assertEquals(gson.toJson(different), gson.toJson(ResolverV1Recording.resolve(mismatched, gson).getSummary().getFatalHit()));
		}
	}

	@Test
	public void anUnidentifiedOtherDeathTickCallbackCannotTurnTheSelectedRecordIntoAKnownKiller()
	{
		Gson gson = new Gson(); DeathRecordingV2 base = minimal(false, true);
		DamageRecord last = base.getEvents().getDamageEvents().get(0);
		DamageRecord unknown = last.toBuilder().observationSequence(18L).observedCycle(299).amount(4).build();
		DeathRecordingV2 input = base.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(Arrays.asList(unknown, last))
			.observations(base.getEvents().getObservations()).build()).summary(DeathRecordingV2.Summary.builder().fatalHit(last)
			.fatalHitCandidates(Arrays.asList(unknown, last)).killerName("Unknown").killerConfidence("UNKNOWN")
			.killerBasis("MULTIPLE_DISTINCT_OR_UNLINKED_IMPACTS").fatalHitBasis("LAST_POSITIVE_RECEIVED_CALLBACK_IN_DEATH_TICK").build()).build();
		DeathRecordingV2 resolved = ResolverV1Recording.resolve(input, gson);
		assertEquals("Uncatalogued NPC", com.tickreplaylogger.util.SourceResolutionLabels.label(resolved.getEvents().getDamageEvents().get(1).getSourceResolution()));
		assertEquals("Unknown", resolved.getSummary().getFatalHit().getAttackerName());
		assertEquals("Unknown", resolved.getSummary().getKillerName());
		assertEquals(2, resolved.getSummary().getFatalHitCandidates().size());
		assertTrue(resolved.getSummary().getFatalHitCandidates().get(0).getSourceResolution().getCandidates().isEmpty());
		assertTrue(resolved.getSummary().getSourceResolution().isUnidentifiedPossible());
		assertEquals(4, resolved.getSummary().getFatalHitCandidates().get(0).getAmount());
	}

	@Test
	public void unavailableObservationJournalDoesNotBecomeAKnownEmptyJournal()
	{
		ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(null, null, null, null, null, 1, 2, 10, 1);
		assertNull(window.getObservations());
	}

	@Test
	public void originalNpcActivityWithoutAnAvailableDefinitionKeepsItsObservedIdentity()
	{
		Gson gson = new Gson(); DeathRecordingV2 base = minimal(false, true);
		ObservedEvent before = base.getEvents().getObservations().get(0);
		Map<String, Object> fields = new LinkedHashMap<>(before.getData()); fields.remove("npcId");
		ObservedEvent original = ObservedEvent.builder().tick(before.getTick()).gameCycle(before.getGameCycle()).sequence(before.getSequence())
			.type(before.getType()).actorRef(before.getActorRef()).sceneKey(before.getSceneKey()).data(fields).build();
		DeathRecordingV2 input = base.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(base.getEvents().getDamageEvents())
			.observations(Arrays.asList(original, base.getEvents().getObservations().get(1))).build()).build();
		SourceResolution result = ResolverV1Recording.resolve(input, gson).getEvents().getDamageEvents().get(0).getSourceResolution();
		assertEquals("Uncatalogued NPC", com.tickreplaylogger.util.SourceResolutionLabels.label(result));
		assertTrue(result.isUnidentifiedPossible());
		assertNull(result.getCandidates().get(0).getNpcId());
	}

	@Test
	public void outgoingDamageWithUnavailableNpcDefinitionCannotAbortIncomingResolution()
	{
		Gson gson = new Gson(); DeathRecordingV2 base = minimal(false, true);
		DamageRecord outgoing = DamageRecord.builder().direction("NPC_RECEIVED").ownership("LOCAL_PLAYER").defenderRef("npc:no-definition")
			.impactTick(10).observedCycle(299).observationSequence(18L).amount(2).hitsplatType(16).hitsplatTypeKnown(true)
			.impactSceneKey("room").impactWorldViewId(0).impactPlane(0).build();
		ObservedEvent observed = ObservedEvent.builder().tick(10).gameCycle(299).sequence(18).type("HITSPLAT").actorRef("npc:no-definition")
			.sceneKey("room").data(map("amount", 2, "hitsplatType", 16, "isMine", true,
				"actorState", map("actorKind", "NPC", "name", "Uncatalogued target", "worldViewId", 0, "plane", 0, "planeKnown", true))).build();
		DeathRecordingV2 input = base.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(Arrays.asList(base.getEvents().getDamageEvents().get(0), outgoing))
			.observations(Arrays.asList(observed, base.getEvents().getObservations().get(0), base.getEvents().getObservations().get(1))).build()).build();
		DeathRecordingV2 result = ResolverV1Recording.resolve(input, gson);
		assertEquals(2, result.getEvents().getDamageEvents().size());
		assertEquals(gson.toJson(outgoing), gson.toJson(result.getEvents().getDamageEvents().get(1)));
		assertEquals(12, result.getEvents().getDamageEvents().get(0).getAmount());
	}

	@Test
	public void basicNpcAndPlayerObservationsRemainUsefulWithoutWorldSnapshotsOrSceneMaps()
	{
		Gson gson = new Gson();
		for (boolean player : new boolean[]{false, true})
		{
			DeathRecordingV2 original = minimal(player, false);
			String before = gson.toJson(original);
			DamageRecord projected = ResolverV1Recording.resolve(original, gson).getEvents().getDamageEvents().get(0);
			assertEquals(player ? "Uncatalogued player" : "Uncatalogued NPC", com.tickreplaylogger.util.SourceResolutionLabels.label(projected.getSourceResolution()));
			assertEquals(12, projected.getAmount()); assertEquals(Long.valueOf(20), projected.getObservationSequence());
			assertTrue(projected.getSourceResolution().isUnidentifiedPossible());
			assertEquals(before, gson.toJson(original));
		}
	}

	@Test
	public void anEarlierCanonicalResultCannotSeedOrAccumulateEvidenceOnRepeatedWindows()
	{
		Gson gson = new Gson();
		DeathRecordingV2 once = ResolverV1Recording.resolve(minimal(false, true), gson);
		DeathRecordingV2 twice = ResolverV1Recording.resolve(once, gson);
		assertEquals(gson.toJson(once.getEvents().getDamageEvents()), gson.toJson(twice.getEvents().getDamageEvents()));
	}

	@Test
	public void unstableGlobalAssetClassificationCannotEraseTheImmutableOriginalApiOwner()
	{
		Gson gson = new Gson(); DeathRecordingV2 base = minimal(false, true);
		ProjectileSourceHistory.SourceObservation owner = ProjectileSourceHistory.SourceObservation.builder().actorKind("NPC").sourceRef("npc:original")
			.sourceNpcId(990001).sourceObservedName("Original owner").sourceName("Original owner").sourceNameKnown(true).sourceCombatLevel(10)
			.sourceActorKnown(true).sourceEvidence("ACTOR").sourceSceneKey("room").sourceWorldViewId(0).sourcePlane(0)
			.observedTick(9).observedCycle(280).sourceNormalWorldContextObserved(true).sourceModelOverridesPresent(false).build();
		ProjectileState projectile = ProjectileState.builder().observationId(99L).projectileId(1687).worldViewId(0).plane(0).samplePlaneKnown(true)
			.observedTick(9).observedCycle(280).spawnTick(9).endCycle(301).targetRef("player")
			.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(owner).definitionChanged(true).build()).build();
		ObservedEvent first = ObservedEvent.builder().tick(9).gameCycle(280).sequence(10).type("PROJECTILE_FIRST_SEEN").sceneKey("room")
			.data(map("projectile", projectile)).build();
		ObservedEvent hp = base.getEvents().getObservations().get(1);
		DeathRecordingV2 input = base.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(base.getEvents().getDamageEvents())
			.observations(Arrays.asList(first, hp)).build()).build();
		SourceResolution resolution = ResolverV1Recording.resolve(input, gson).getEvents().getDamageEvents().get(0).getSourceResolution();
		assertEquals("Original owner", com.tickreplaylogger.util.SourceResolutionLabels.label(resolution));
		assertTrue(resolution.isUnidentifiedPossible());
		assertTrue(resolution.getCandidates().get(0).getEvidence().stream().anyMatch(value -> value.contains("UNSTABLE")));
	}

	@Test
	public void unavailableTrackedReferenceKeepsOriginalApiDefinitionAndStaticStyleWithoutNativeProof()
	{
		Gson gson = new Gson(); DeathRecordingV2 base = minimal(false, true);
		ProjectileSourceHistory.SourceObservation owner = ProjectileSourceHistory.SourceObservation.builder().actorKind("NPC")
			.sourceNpcId(8615).sourceObservedName("Alchemical Hydra").sourceName("Alchemical Hydra").sourceNameKnown(true).sourceCombatLevel(426)
			.sourceActorKnown(true).sourceEvidence("ACTOR").observedTick(9).observedCycle(280)
			.sourceNormalWorldContextObserved(true).sourceModelOverridesPresent(false).build();
		ProjectileState projectile = ProjectileState.builder().observationId(98L).projectileId(1662).worldViewId(0).plane(0).samplePlaneKnown(true)
			.observedTick(9).observedCycle(280).spawnTick(9).endCycle(301).targetRef("player")
			.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(owner).build()).build();
		ObservedEvent first = ObservedEvent.builder().tick(9).gameCycle(280).sequence(10).type("PROJECTILE_FIRST_SEEN").sceneKey("room")
			.data(map("projectile", projectile)).build();
		DeathRecordingV2 input = base.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(base.getEvents().getDamageEvents())
			.observations(Arrays.asList(first, base.getEvents().getObservations().get(1))).build()).build();
		DamageRecord result = ResolverV1Recording.resolve(input, gson).getEvents().getDamageEvents().get(0);
		assertEquals("magic", result.getDamageType());
		assertEquals(Integer.valueOf(8615), result.getSourceResolution().getCandidates().get(0).getNpcId());
		assertNull(result.getSourceResolution().getCandidates().get(0).getActorRef());
		assertNull(result.getSourceResolution().getCandidates().get(0).getSceneKey());
		assertTrue(result.getSourceResolution().isUnidentifiedPossible());
		assertEquals(12, result.getAmount());

		// A later observation changes eligibility, while the immutable first owner and HP callback stay intact.
		ProjectileState conflicted = ProjectileState.builder().observationId(98L).projectileId(1662).worldViewId(0).plane(0).samplePlaneKnown(true)
			.observedTick(10).observedCycle(300).spawnTick(9).endCycle(301).targetRef("player")
			.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(owner).definitionChanged(true).build()).build();
		ObservedEvent move = ObservedEvent.builder().tick(10).gameCycle(300).sequence(19).type("PROJECTILE_MOVED").sceneKey("room")
			.data(map("projectile", conflicted)).build();
		DeathRecordingV2 later = input.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(Collections.singletonList(result))
			.observations(Arrays.asList(first, move, base.getEvents().getObservations().get(1))).build()).build();
		DamageRecord downgraded = ResolverV1Recording.resolve(later, gson).getEvents().getDamageEvents().get(0);
		assertEquals("unknown", downgraded.getDamageType());
		assertNull(downgraded.getDamageTypeRuleKey());
		assertEquals("Alchemical Hydra", com.tickreplaylogger.util.SourceResolutionLabels.label(downgraded.getSourceResolution()));
		assertEquals(result.getAmount(), downgraded.getAmount());
		assertEquals(result.getImpactSceneKey(), downgraded.getImpactSceneKey());
		assertEquals(result.getObservedCycle(), downgraded.getObservedCycle());
	}

	@Test
	public void resourceOnlyExportsResolveOriginalContextWithoutCreatingAnHpEvent()
	{
		Gson gson = new Gson(); DeathRecordingV2 base = minimal(false, true);
		DamageRecord context = base.getEvents().getDamageEvents().get(0).toBuilder().direction("RESOURCE_IMPACT").build();
		ObservedEvent before = base.getEvents().getObservations().get(1);
		Map<String, Object> fields = new LinkedHashMap<>(before.getData()); fields.put("attributionContext", context);
		fields.put("possibleOutcomeSurfaces", Arrays.asList("HP", "PRAYER")); fields.put("resourceOutcomeStatus", "UNRESOLVED");
		ObservedEvent hp = ObservedEvent.builder().tick(before.getTick()).gameCycle(before.getGameCycle()).sequence(before.getSequence())
			.type(before.getType()).actorRef(before.getActorRef()).sceneKey(before.getSceneKey()).data(fields).build();
		TickData journal = TickData.builder().tickNumber(10).timestamp(1).effectNotes(Collections.singletonList("Impact 12 (HP / PRAYER) from Unknown"))
			.effectNoteSourceSequences(Collections.singletonMap(0, 20L)).build();
		DeathRecordingV2 input = base.toBuilder().journalTicks(Collections.singletonList(journal)).events(DeathRecordingV2.Events.builder()
			.damageEvents(Collections.emptyList()).observations(Arrays.asList(base.getEvents().getObservations().get(0), hp)).build()).build();
		String frozen = gson.toJson(input); DeathRecordingV2 resolved = ResolverV1Recording.resolve(input, gson);
		assertTrue(resolved.getEvents().getDamageEvents().isEmpty());
		ObservedEvent derived = resolved.getEvents().getObservations().get(1);
		assertEquals("Uncatalogued NPC", com.tickreplaylogger.util.SourceResolutionLabels.label((SourceResolution) derived.getData().get("sourceResolution")));
		assertEquals(Arrays.asList("HP", "PRAYER"), derived.getData().get("possibleOutcomeSurfaces"));
		assertEquals("UNRESOLVED", derived.getData().get("resourceOutcomeStatus"));
		assertEquals("Impact 12 (HP / PRAYER) from Uncatalogued NPC", resolved.getJournalTicks().get(0).getEffectNotes().get(0));
		assertEquals(frozen, gson.toJson(input));
		Map<String, Object> linkedFields = new LinkedHashMap<>(fields);
		linkedFields.put("attributionContext", context.toBuilder().defenderIdentityEvidence("SETTLED_LOCAL_REFERENCE_MATCH").build());
		ObservedEvent linked = ObservedEvent.builder().tick(hp.getTick()).gameCycle(hp.getGameCycle()).sequence(hp.getSequence())
			.type(hp.getType()).sceneKey(hp.getSceneKey()).data(linkedFields).build();
		DeathRecordingV2 linkedInput = input.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(Collections.emptyList())
			.observations(Arrays.asList(base.getEvents().getObservations().get(0), linked)).build()).build();
		assertEquals("Impact 12 (HP / PRAYER) from Uncatalogued NPC", ResolverV1Recording.resolve(linkedInput, gson).getJournalTicks().get(0).getEffectNotes().get(0));
	}

	private static DeathRecordingV2 minimal(boolean player, boolean nativeKnown)
	{
		String scene = nativeKnown ? "room" : null, ref = player ? "player:7:v0:g1" : "npc:original";
		Map<String, Object> actor = map("actorKind", player ? "OTHER_PLAYER" : "NPC", "name", player ? "Uncatalogued player" : "Uncatalogued NPC",
			"worldViewId", 0, "plane", 0, "planeKnown", true, "worldPositionKnown", true, "worldX", 3200, "worldY", 3201,
			"combatLevel", 10, "animationId", 990777, "normalWorldContextObserved", true, "modelOverridesPresent", false,
			"interaction", map("reportedInteracting", true, "actorRef", "player", "actorKind", "LOCAL_PLAYER", "worldViewId", 0),
			"callbackMetadataEvidence", "LOGGED_IN_ACTOR_FIELDS");
		if (!player) actor.put("npcId", 990001);
		ObservedEvent attack = ObservedEvent.builder().tick(10).gameCycle(300).sequence(19).type("ANIMATION_CHANGED").actorRef(ref).sceneKey(scene).data(actor).build();
		ObservedEvent hp = ObservedEvent.builder().tick(10).gameCycle(301).sequence(20).type("HITSPLAT").actorRef("player").sceneKey(scene)
			.data(map("amount", 12, "hitsplatType", 16, "receiverKind", "PLAYER", "actorState", map("actorKind", "LOCAL_PLAYER", "worldViewId", 0,
				"plane", 0, "planeKnown", true, "worldPositionKnown", true, "worldX", 3200, "worldY", 3200))).build();
		DamageRecord hit = DamageRecord.builder().direction("RECEIVED").defenderRef("player").impactTick(10).observedCycle(301).observationSequence(20L)
			.impactSceneKey(scene).impactWorldViewId(0).impactPlane(0).impactPositionKnown(true).impactX(3200).impactY(3200)
			.hitsplatType(16).hitsplatTypeKnown(true).amount(12).attackerName("Unknown").build();
		return DeathRecordingV2.builder().ticks(Collections.emptyList()).scenes(Collections.emptyList()).events(DeathRecordingV2.Events.builder()
			.damageEvents(Collections.singletonList(hit)).observations(Arrays.asList(attack, hp)).build()).build();
	}
	private static Map<String, Object> map(Object... values)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
		return result;
	}

	@Test
	public void membershipCutoffRetainsOutgoingZeroesAndGapsWithoutCopyingDtoContents()
	{
		DamageRecord zero = DamageRecord.builder().impactTick(10).observationSequence(5L).amount(0).direction("RECEIVED").build();
		DamageRecord outgoing = zero.toBuilder().direction("NPC_RECEIVED").ownership("LOCAL_PLAYER").amount(12).observationSequence(4L).build();
		DamageRecord future = zero.toBuilder().observationSequence(6L).build();
		WorldSnapshot tick = WorldSnapshot.builder().tick(10).build();
		List<WorldSnapshot> frames = new ArrayList<>(Collections.singletonList(tick));
		List<DamageRecord> hits = new ArrayList<>(Arrays.asList(zero, outgoing, future));
		List<ObservedEvent> events = new ArrayList<>(Arrays.asList(
			ObservedEvent.builder().tick(9).sequence(1).type("NPC_SPAWNED").build(),
			ObservedEvent.builder().tick(10).sequence(5).type("HITSPLAT").build(),
			ObservedEvent.builder().tick(10).sequence(6).type("ANIMATION_CHANGED").build()));
		DeathRecordingV2.Events.CaptureGap gap = DeathRecordingV2.Events.CaptureGap.builder().tick(10).channel("observations").omittedObservations(2).build();
		ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(frames, null, events, hits,
			Collections.singletonList(gap), 10, 10, 5, 42);
		frames.clear(); hits.clear(); events.clear();
		assertSame(tick, window.getTicks().get(0));
		assertEquals(Arrays.asList(zero, outgoing), window.getHits());
		assertSame(gap, window.getCaptureGaps().get(0));
		assertEquals(1, window.getObservations().size());
		assertEquals(5, window.getObservations().get(0).getSequence());
		assertEquals(42, window.getEvidenceRevision());
		assertFalse(window.contains(future));
		try { window.getHits().clear(); fail("window membership must be immutable"); }
		catch (UnsupportedOperationException expected) { }
	}

	@Test
	public void individualCompletionAndExportUseTheSameOriginalWindowAndProjection() throws Exception
	{
		Gson gson = new Gson();
		DeathRecordingV2 original;
		try (InputStreamReader reader = new InputStreamReader(getClass().getResourceAsStream(
			"/com/tickreplaylogger/zulrah-export-20261101.json"), StandardCharsets.UTF_8))
		{
			com.google.gson.JsonObject wrapper = gson.fromJson(reader, com.google.gson.JsonObject.class);
			original = gson.fromJson(wrapper.get("richExportJson").getAsString(), DeathRecordingV2.class);
		}
		String frozen = gson.toJson(original);
		DeathRecordingV2.Events events = original.getEvents();
		ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(original.getTicks(), original.getScenes(),
			events.getObservations(), events.getDamageEvents(), events.getCaptureGaps(), Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, 7);
		ResolverV1Recording live = new ResolverV1Recording(window, gson);
		DeathRecordingV2 exported = ResolverV1Recording.resolve(original, gson);
		assertEquals(events.getDamageEvents().size(), exported.getEvents().getDamageEvents().size());
		for (int i = 0; i < events.getDamageEvents().size(); i++)
		{
			DamageRecord hit = events.getDamageEvents().get(i);
			assertEquals(gson.toJson(exported.getEvents().getDamageEvents().get(i)), gson.toJson(live.resolveHit(hit)));
		}
		assertEquals(frozen, gson.toJson(original));
	}

	@Test
	public void presentationProjectionDoesNotReevaluateOrRemoveAlreadyEvaluatedAnnotations()
	{
		SourceResolution.Candidate first = SourceResolution.Candidate.builder().key("first").name("Recorded source").actorRef("npc:a")
			.npcId(900001).sceneKey("scene").worldViewId(0).plane(0).strength("STRONG")
			.evidence(Collections.singletonList("ACTUAL_API_RELATIONSHIP")).build();
		SourceResolution.Candidate second = first.toBuilder().key("second").strength("POSSIBLE")
			.evidence(Collections.singletonList("INDEPENDENT_ACTIVITY")).build();
		SourceResolution.Candidate excluded = first.toBuilder().key("excluded").name("Excluded source").actorRef("npc:b")
			.exclusionScope("ALL_DIRECT_HP_COMPONENTS").reasons(Collections.singletonList("MAXIMUM_EXCEEDED:test")).build();
		SourceResolution projected = SourceEvidenceResolver.project(Arrays.asList(first, second), Collections.singletonList(excluded),
			true, "catalog", "CANONICAL_PROJECTOR");
		assertEquals(1, projected.getCandidates().size());
		assertEquals("STRONG", projected.getCandidates().get(0).getStrength());
		assertTrue(projected.getCandidates().get(0).getEvidence().containsAll(Arrays.asList("ACTUAL_API_RELATIONSHIP", "INDEPENDENT_ACTIVITY")));
		assertEquals("ALL_DIRECT_HP_COMPONENTS", projected.getExcludedCandidates().get(0).getExclusionScope());
		assertTrue(projected.isUnidentifiedPossible());
		assertEquals("CANONICAL_PROJECTOR", projected.getMethod());
	}

	@Test
	public void unscopedComponentAliasesGroupOnlyWithinTheSameOriginalObservation()
	{
		Gson gson = new Gson();
		Map<String, Object> selector = map("kind", "npc", "ids", Collections.singletonList(990001));
		Map<String, Object> signals = map("projectileIds", Collections.singletonList(999001));
		ResolverV1 engine = new ResolverV1(ResolverTestCatalog.lower(gson.toJsonTree(map("schemaVersion", 1, "purpose", "retrospective_damage_resolution",
			"specialRules", Arrays.asList(map("id", "synthetic:component-a", "pattern", "projectile_impact", "source", selector, "signals", signals),
				map("id", "synthetic:component-b", "pattern", "projectile_impact", "source", selector, "signals", signals)))).getAsJsonObject()));
		DeathRecordingV2 base = minimal(false, true);
		List<ObservedEvent> observations = new ArrayList<>();
		for (int count = 1; count <= 2; count++)
		{
			ProjectileSourceHistory.SourceObservation owner = ProjectileSourceHistory.SourceObservation.builder().actorKind("NPC")
				.sourceRef("npc:unscoped").sourceNpcId(990001).sourceName("Shared name").sourceObservedName("Shared name")
				.sourceNameKnown(true).sourceActorKnown(true).sourceEvidence("ACTOR").observedTick(9).observedCycle(280 + count).build();
			ProjectileState projectile = ProjectileState.builder().observationId((long) count).projectileId(999001).observedTick(9)
				.observedCycle(280 + count).spawnTick(9).endCycle(301).targetRef("player")
				.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(owner).build()).build();
			observations.add(ObservedEvent.builder().tick(9).gameCycle(280 + count).sequence(10 + count).type("PROJECTILE_FIRST_SEEN")
				.sceneKey("room").data(map("projectile", projectile)).build());
			List<ObservedEvent> current = new ArrayList<>(observations); current.add(base.getEvents().getObservations().get(1));
			DeathRecordingV2 recording = base.toBuilder().events(DeathRecordingV2.Events.builder().damageEvents(base.getEvents().getDamageEvents())
				.observations(current).build()).build();
			SourceResolution result = new ResolverV1Recording(engine, gson, recording).enrich(recording).getEvents().getDamageEvents().get(0).getSourceResolution();
			assertEquals(count, result.getCandidates().size());
			assertNotEquals("ACTOR", result.getIdentityLevel());
			for (SourceResolution.Candidate candidate : result.getCandidates())
			{
				assertNull(candidate.getSceneKey());
				assertTrue(candidate.getEvidence().contains("RESOLVER_V1:synthetic:component-a"));
				assertTrue(candidate.getEvidence().contains("RESOLVER_V1:synthetic:component-b"));
			}
		}
	}
}
