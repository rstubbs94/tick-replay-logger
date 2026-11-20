/*
 * Copyright (c) 2026, rstubbs94
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecordedProjectileSelectionTest
{
	private static final Gson GSON = new Gson();
	private static final DamageRecord HIT = DamageRecord.builder().direction("RECEIVED").defenderRef("player")
		.impactTick(10).observedCycle(301).observationSequence(20L).amount(12).hitsplatType(16).hitsplatTypeKnown(true)
		.impactSceneKey("room").impactWorldViewId(0).impactPlane(0).build();

	@Test
	public void anObservationAfterTheHitCannotReplaceItsOriginalOwner()
	{
		List<ResolverEvidence.Source> cues = normalize(Arrays.asList(event(projectile(99L, 320, 9, 280, "Before"), 10, 280),
			event(projectile(99L, 320, 10, 302, "After"), 21, 302)), Collections.emptyList());
		assertTrue(hasOwner(cues, "Before"));
		assertFalse(hasOwner(cues, "After"));
	}

	@Test
	public void aNewerPreHitFrameKeepsItsRecordedOwnerConflictInsteadOfAStaleCallback()
	{
		ProjectileState original = projectile(99L, 320, 9, 280, "Original");
		ProjectileState changed = changedOwner(original, 290);
		List<ResolverEvidence.Source> cues = normalize(Collections.singletonList(event(original, 10, 280)), Collections.singletonList(frame(changed, 290)));
		assertTrue(hasHistoryConflict(cues));
		assertEquals(1, apiCount(cues));
	}

	@Test
	public void anOwnerConflictObservedAfterTheHitCannotChangeItsEarlierSource()
	{
		ProjectileState original = projectile(99L, 320, 9, 280, "Original");
		ProjectileState after = changedOwner(original, 302);
		List<ResolverEvidence.Source> cues = normalize(Collections.singletonList(event(original, 10, 280)), Collections.singletonList(frame(after, 302)));
		assertFalse(hasHistoryConflict(cues));
		assertTrue(hasOwner(cues, "Original"));
		assertEquals(1, apiCount(cues));
	}

	@Test
	public void anOriginalCallbackWithoutLifetimeStillRetainsItsObservedOwner()
	{
		for (Long instance : Arrays.asList(99L, null))
		{
			ProjectileState projectile = projectile(instance, null, 9, 280, "Untimed");
			List<ResolverEvidence.Source> cues = normalize(Collections.singletonList(event(projectile, 10, 280)), Collections.emptyList());
			assertTrue("Original owner with instance " + instance, hasOwner(cues, "Untimed"));
			assertNull(projectile.getEndCycle());
			assertEquals(instance, projectile.getObservationId());
		}
	}

	@Test
	public void anIndependentEarlierNativeFrameKeepsItsOwnKnownClock()
	{
		ProjectileState callback = projectile(99L, null, 9, 280, "Owner");
		ProjectileState sampled = projectile(99L, 320, 9, 290, "Owner");
		WorldSnapshot frame = WorldSnapshot.builder().tick(9).gameCycle(290).gameCycleKnown(true)
			.nativeContextKnown(true).sceneKey("room").worldViewId(0).plane(0)
			.projectiles(Collections.singletonList(sampled)).build();
		List<ResolverEvidence.Source> cues = normalize(Collections.singletonList(event(callback, 10, 280)), Collections.singletonList(frame));
		boolean found = false;
		for (ResolverEvidence.Source cue : cues)
		{
			JsonObject witness = cue.raw();
			found |= witness.get("observedCycle").getAsInt() == 290 && witness.get("endCycle").getAsInt() == 320;
		}
		assertTrue("Use the independently recorded native frame, without filling the callback's missing clock", found);
		assertNull(callback.getEndCycle());
		assertEquals(Integer.valueOf(280), callback.getObservedCycle());
	}

	private static ProjectileState projectile(Long instance, Integer end, int tick, int cycle, String name)
	{
		ProjectileSourceHistory.SourceObservation owner = ProjectileSourceHistory.SourceObservation.builder()
			.actorKind("NPC").sourceRef("npc:owner").sourceNpcId(990001).sourceName(name).sourceObservedName(name)
			.sourceNameKnown(true).sourceActorKnown(true).sourceEvidence("ACTOR").sourceCombatLevel(10)
			.sourceSceneKey("room").sourceWorldViewId(0).sourcePlane(0).observedTick(tick).observedCycle(cycle)
			.sourceNormalWorldContextObserved(true).sourceModelOverridesPresent(false).build();
		return ProjectileState.builder().observationId(instance).projectileId(999001).worldViewId(0).plane(0)
			.samplePlaneKnown(true).observedTick(tick).observedCycle(cycle).spawnTick(tick).endCycle(end).targetRef("player")
			.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(owner).build()).build();
	}

	private static ProjectileState changedOwner(ProjectileState original, int cycle)
	{
		return ProjectileState.builder().observationId(original.getObservationId()).projectileId(999001).worldViewId(0).plane(0)
			.samplePlaneKnown(true).observedTick(10).observedCycle(cycle).spawnTick(9).endCycle(320).targetRef("player")
			.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(original.getSourceHistory().getFirstObservedSource())
				.actorChanged(true).referenceChanged(true).build()).build();
	}

	private static WorldSnapshot frame(ProjectileState projectile, int cycle)
	{
		return WorldSnapshot.builder().tick(10).gameCycle(cycle).gameCycleKnown(true).nativeContextKnown(true)
			.sceneKey("room").worldViewId(0).plane(0).projectiles(Collections.singletonList(projectile)).build();
	}

	private static int apiCount(List<ResolverEvidence.Source> cues)
	{
		return cues.size();
	}

	private static boolean hasHistoryConflict(List<ResolverEvidence.Source> cues)
	{
		return cues.stream().anyMatch(cue -> cue.raw().getAsJsonObject("sourceHistory").get("actorChanged").getAsBoolean());
	}

	private static ObservedEvent event(ProjectileState projectile, long sequence, int cycle)
	{
		return ObservedEvent.builder().tick(projectile.getObservedTick()).gameCycle(cycle).sequence(sequence).type("PROJECTILE_MOVED")
			.sceneKey("room").data(Collections.singletonMap("projectile", projectile)).build();
	}

	private static List<ResolverEvidence.Source> normalize(List<ObservedEvent> projectiles, List<WorldSnapshot> frames)
	{
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("amount", 12); data.put("hitsplatType", 16); data.put("receiverKind", "PLAYER");
		ObservedEvent hp = ObservedEvent.builder().tick(10).gameCycle(301).sequence(20).type("HITSPLAT")
			.actorRef("player").sceneKey("room").data(data).build();
		List<ObservedEvent> events = new ArrayList<>(projectiles); events.add(hp);
		DeathRecordingV2 recording = DeathRecordingV2.builder().ticks(frames).events(DeathRecordingV2.Events.builder()
			.damageEvents(Collections.singletonList(HIT)).observations(events).build()).build();
		String original = GSON.toJson(recording);
		ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(frames, null, events,
			Collections.singletonList(HIT), null, Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, 0);
		List<ResolverEvidence.Source> cues = new ArrayList<>();
		for (ResolverEvidence.Source source : new RawEvidenceWindow(window, GSON).hit(HIT).sources())
		{
			if (source.channel() == ResolverEvidence.Channel.PROJECTILE) cues.add(source);
		}
		assertEquals(original, GSON.toJson(recording));
		return cues;
	}

	private static boolean hasOwner(List<ResolverEvidence.Source> cues, String name)
	{
		return cues.stream().anyMatch(cue -> name.equals(cue.identity().name));
	}
}
