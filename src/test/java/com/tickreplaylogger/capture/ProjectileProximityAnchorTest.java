/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.tickreplaylogger.capture;

import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.ProjectileSourceCandidate;
import com.tickreplaylogger.model.world.ProjectileState;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Projectile;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProjectileProximityAnchorTest
{
	private static ProjectileSourceCandidate candidate(int id, String ref, String name)
	{
		return ProjectileSourceCandidate.builder().npcId(id).actorRef(ref).name(name)
			.combatLevel(id == 900001 ? 80 : 200).animationId(777777).modelOverridesPresent(false)
			.normalWorldContextObserved(true).familyKey("family:" + id).sceneKey("scene-a").worldViewId(0).plane(0)
			.build();
	}

	private static Projectile projectile()
	{
		return (Projectile) Proxy.newProxyInstance(Projectile.class.getClassLoader(), new Class<?>[]
		{ Projectile.class }, (object, method, args) ->
		{
			throw new AssertionError("Tracker must use supplied primitive sample, not native getters");
		});
	}

	private static ProjectileState sample(ProjectileSourceCandidate candidate, int tick, boolean failed)
	{
		return ProjectileState.builder().projectileId(700001).observationId(17L).observedTick(tick).spawnTick(1)
			.sourceName(candidate == null ? "Unknown" : candidate.getName())
			.sourceEvidence(candidate == null ? "UNKNOWN" : "PROXIMITY").sourceActorKnown(true).sourceRef(null)
			.sourcePlane(0).targetsPlayer(true).proximitySourceCandidate(candidate).observationIssues(
				failed ? Collections.singletonMap("source.proximityName", "IllegalStateException") : null)
			.build();
	}

	private static SourceResolution resolve(ProjectileState current)
	{
		Map<String, Object> raw = new LinkedHashMap<>();
		raw.put("kind", "PROJECTILE_CORRELATION");
		raw.put("name", current.getSourceName());
		raw.put("projectileObservationId", 17L);
		raw.put("arrivalGapCycles", 0);
		raw.put("sourceHistory", current.getSourceHistory());
		DamageRecord hit = DamageRecord.builder().impactTick(2).observationSequence(9L).observedCycle(100).amount(8)
			.impactSceneKey("scene-a").impactWorldViewId(0).impactPlane(0).attackerName("Unknown")
			.attributionCandidates(Collections.singletonList(raw)).build();
		WorldSnapshot laterOnly = WorldSnapshot.builder().tick(2).sceneKey("scene-a").worldViewId(0).plane(0)
			.nativeContextKnown(true).player(PlayerState.builder().build())
			.projectiles(Collections.singletonList(current)).build();
		return new RecordedSourceResolver(Collections.singletonList(laterOnly), Collections.emptyList(), null)
			.resolve(hit);
	}

	private static void originalIsRetained(String laterRef)
	{
		Projectile projectile = projectile();
		ProjectileSourceTracker tracker = new ProjectileSourceTracker();
		ProjectileSourceCandidate original = candidate(900001, "npc:original:v0:g1", "Original entity");
		ProjectileSourceCandidate later = candidate(900002, laterRef, "Later entity");
		ProjectileState first = tracker.observe(projectile, sample(original, 1, false), null, null, 7, true);
		ProjectileState second = tracker.observe(projectile, sample(later, 2, false), null, null, 7, true);
		SourceResolution resolution = resolve(second);
		assertEquals(1, resolution.getCandidates().size());
		assertEquals(Integer.valueOf(900001), resolution.getCandidates().get(0).getNpcId());
		assertEquals("npc:original:v0:g1", resolution.getCandidates().get(0).getActorRef());
		assertEquals("Original entity", resolution.getCandidates().get(0).getName());
		assertEquals(Integer.valueOf(900002), second.getProximitySourceCandidate().getNpcId());
		assertEquals(laterRef, second.getProximitySourceCandidate().getActorRef());
		assertEquals("Original entity", first.getSourceHistory().getFirstObservedSource().getSourceName());
		assertNull(first.getSourceRef());
		assertNull(second.getSourceRef());
		assertTrue(second.getSourceActorKnown());
	}

	@Test
	public void aFormChangeCannotReplaceOriginalProximityWhenWindowStartsMidFlight()
	{
		originalIsRetained("npc:original:v0:g1");
	}

	@Test
	public void aDifferentLaterNearbyActorCannotReplaceOriginalProximityWhenWindowStartsMidFlight()
	{
		originalIsRetained("npc:later:v0:g2");
	}

	private static void unavailableFirstDoesNotRecover(boolean failed)
	{
		Projectile projectile = projectile();
		ProjectileSourceTracker tracker = new ProjectileSourceTracker();
		tracker.observe(projectile, sample(null, 1, failed), null, null, 7, true);
		ProjectileState later = tracker.observe(projectile,
			sample(candidate(900002, "npc:later:v0:g2", "Later entity"), 2, false), null, null, 7, true);
		SourceResolution resolution = resolve(later);
		assertEquals("UNIDENTIFIED", resolution.getStatus());
		assertTrue(resolution.getCandidates().isEmpty());
		assertTrue(resolution.isUnidentifiedPossible());
		assertNotNull(later.getProximitySourceCandidate());
		assertNull(later.getSourceRef());
	}

	@Test
	public void firstKnownNullProximityCannotBorrowLaterRecovery()
	{
		unavailableFirstDoesNotRecover(false);
	}

	@Test
	public void firstFailedProximityCannotBorrowLaterRecovery()
	{
		unavailableFirstDoesNotRecover(true);
	}

	@Test public void originalApiMetadataIsImmutableAndNeverBackfilledFromLaterSamples()
	{
		net.runelite.api.NPC actor=(net.runelite.api.NPC)Proxy.newProxyInstance(net.runelite.api.NPC.class.getClassLoader(),
			new Class<?>[] {net.runelite.api.NPC.class},(object,method,args) -> { throw new AssertionError("No native reread"); });
		for (Boolean originalOverride : new Boolean[] {null,false,true})
		{
			Projectile projectile=projectile(); ProjectileSourceTracker tracker=new ProjectileSourceTracker();
			ProjectileState first=sample(null,1,false).toBuilder().sourceEvidence("ACTOR").sourceRef("first-owner")
				.sourceSceneKey("original-native").sourceNpcId(900001).sourceCombatLevel(80).sourceModelOverridesPresent(originalOverride)
				.sourceNormalWorldContextObserved(true).build();
			assertTrue(tracker.needsOriginalActorMetadata(projectile,first.getObservationId(),actor));
			tracker.observe(projectile,first,actor,null,7,true);
			assertFalse(tracker.needsOriginalActorMetadata(projectile,first.getObservationId(),actor));
			ProjectileState later=tracker.observe(projectile,first.toBuilder().observedTick(2).sourceSceneKey("later-native")
				.sourceCombatLevel(200).sourceModelOverridesPresent(false).sourceNormalWorldContextObserved(false).build(),actor,null,7,true);
			assertEquals(originalOverride,later.getSourceHistory().firstActor().getSourceModelOverridesPresent());
			assertEquals(Integer.valueOf(80),later.getSourceHistory().firstActor().getSourceCombatLevel());
			assertEquals("original-native",later.getSourceHistory().firstActor().getSourceSceneKey());
			assertTrue(later.getSourceHistory().firstActor().getSourceNormalWorldContextObserved());
		}
	}
}
