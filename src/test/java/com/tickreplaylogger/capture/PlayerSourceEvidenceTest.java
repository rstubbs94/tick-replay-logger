/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
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
import com.tickreplaylogger.model.world.OtherPlayerState;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.ProjectileState;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlayerSourceEvidenceTest
{
	private static final String FIRST = "player:42:v0:lifetime1";
	private static final String SECOND = "player:42:v0:lifetime2";

	private static OtherPlayerState player(String ref, String name, Integer view, int plane, boolean planeKnown)
	{
		return OtherPlayerState.builder().actorRef(ref).playerId(42).name(name).worldViewId(view).plane(plane)
			.planeKnown(planeKnown).worldPositionKnown(true).worldX(102).worldY(203).build();
	}

	private static WorldSnapshot frame(int tick, List<OtherPlayerState> players)
	{
		return WorldSnapshot.builder().tick(tick).sceneKey("scene").worldViewId(0).plane(0).nativeContextKnown(true)
			.player(PlayerState.builder().worldViewId(0).plane(0).worldX(103).worldY(203).build())
			.players(players).npcs(Collections.emptyList()).projectiles(Collections.emptyList()).hazards(Collections.emptyList()).build();
	}

	private static ProjectileState projectile(long id, String actor)
	{
		return ProjectileState.builder().observationId(id).projectileId(1044).spawnTick(1).observedTick(1)
			.sourceRef(actor).sourceName("Recorded projectile player").sourceActorKnown(true).sourceWorldViewId(0)
			.sourcePlane(0).sourceEvidence("ACTOR").targetsPlayer(true).targetRef("player").build();
	}

	private static Map<String, Object> witness(long id)
	{
		Map<String, Object> raw = new LinkedHashMap<>();
		raw.put("kind", "PROJECTILE_CORRELATION"); raw.put("name", "Recorded projectile player");
		raw.put("projectileObservationId", id); raw.put("arrivalGapCycles", 0);
		raw.put("matchBasis", "OBSERVED_CLIENT_CYCLE_WINDOW"); return raw;
	}

	private static DamageRecord hit(List<Map<String, Object>> witnesses)
	{
		return DamageRecord.builder().impactTick(2).observedCycle(100).observationSequence(8L).defenderRef("player")
			.direction("RECEIVED").pvp(true)
			.impactSceneKey("scene").impactWorldViewId(0).impactPlane(0).amount(15).hitsplatType(16).hitsplatTypeKnown(true)
			.attributionCandidates(witnesses).attackerName("Unknown").build();
	}

	private static SourceResolution resolve(WorldSnapshot launch, WorldSnapshot impact, List<Map<String, Object>> witnesses)
	{
		return new RecordedSourceResolver(Arrays.asList(launch, impact), Collections.emptyList(), null).resolve(hit(witnesses));
	}

	@Test
	public void projectileKeepsTheActualOriginalPlayerAfterDespawnAndSlotReuse()
	{
		WorldSnapshot launch = frame(1, Collections.singletonList(player(FIRST, "Original player", 0, 0, true))).toBuilder()
			.projectiles(Collections.singletonList(projectile(99, FIRST))).build();
		WorldSnapshot impact = frame(2, Collections.singletonList(player(SECOND, "New occupant", 0, 0, true)));
		SourceResolution result = resolve(launch, impact, Collections.singletonList(witness(99)));
		assertEquals(1, result.getCandidates().size());
		SourceResolution.Candidate source = result.getCandidates().get(0);
		assertEquals(FIRST, source.getActorRef());
		assertEquals("Original player", source.getName());
		assertNull(source.getNpcId()); assertNull(source.getFamilyKey());
		assertFalse(source.getEvidence().stream().anyMatch(value -> value.startsWith("QUALIFIED_MAXIMUM:")
			|| value.startsWith("HITSPLAT_SIGNATURE:")));
	}

	@Test
	public void sameNamePlayersRemainSeparateActualActorAlternatives()
	{
		WorldSnapshot launch = frame(1, Arrays.asList(player(FIRST, "Same name", 0, 0, true),
			player(SECOND, "Same name", 0, 0, true))).toBuilder()
			.projectiles(Arrays.asList(projectile(99, FIRST), projectile(100, SECOND))).build();
		SourceResolution result = resolve(launch, frame(2, Collections.emptyList()), Arrays.asList(witness(99), witness(100)));
		assertEquals(2, result.getCandidates().size());
		assertTrue(result.getCandidates().stream().anyMatch(value -> FIRST.equals(value.getActorRef())));
		assertTrue(result.getCandidates().stream().anyMatch(value -> SECOND.equals(value.getActorRef())));
		assertTrue(result.getCandidates().stream().allMatch(value -> "Same name".equals(value.getName())));
	}

	@Test
	public void missingPrivacySamplesNeverRestoreAProjectileRawRefOrJoinByName()
	{
		for (List<OtherPlayerState> players : Arrays.<List<OtherPlayerState>>asList(null, Collections.emptyList(),
			Collections.singletonList(player(SECOND, "Recorded projectile player", 0, 0, true))))
		{
			WorldSnapshot launch = frame(1, players).toBuilder().projectiles(Collections.singletonList(projectile(99, FIRST))).build();
			SourceResolution result = resolve(launch, frame(2, Collections.emptyList()), Collections.singletonList(witness(99)));
			assertEquals(1, result.getCandidates().size());
			assertNull(result.getCandidates().get(0).getActorRef());
			assertNull(result.getCandidates().get(0).getNpcId());
			assertEquals("POSSIBLE", result.getCandidates().get(0).getStrength());
		}
	}

	@Test
	public void foreignUnknownOrUnobservedPlayerScopeCannotQualifyAnActorIdentity()
	{
		for (OtherPlayerState player : Arrays.asList(player(FIRST, "Foreign view", 7, 0, true),
			player(FIRST, "Missing view", null, 0, true), player(FIRST, "Foreign plane", 0, 1, true),
			player(FIRST, "Missing plane", 0, 0, false)))
		{
			WorldSnapshot launch = frame(1, Collections.singletonList(player)).toBuilder()
				.projectiles(Collections.singletonList(projectile(99, FIRST))).build();
			SourceResolution result = resolve(launch, frame(2, Collections.emptyList()), Collections.singletonList(witness(99)));
			assertNull(result.getCandidates().get(0).getActorRef());
			assertEquals("POSSIBLE", result.getCandidates().get(0).getStrength());
		}
	}

	@Test
	public void animationUsesTheActualImpactPlayerWithoutBorrowingAnotherNameMatch()
	{
		Map<String, Object> animation = new LinkedHashMap<>(); animation.put("kind", "ANIMATION_CORRELATION");
		animation.put("actorRef", FIRST); animation.put("name", "Unverified label"); animation.put("animationId", 12345);
		WorldSnapshot impact = frame(2, Collections.singletonList(player(FIRST, "Observed player", 0, 0, true)));
		SourceResolution actual = resolve(frame(1, Collections.emptyList()), impact, Collections.singletonList(animation));
		assertEquals(FIRST, actual.getCandidates().get(0).getActorRef());
		assertEquals("Observed player", actual.getCandidates().get(0).getName());
		SourceResolution absent = resolve(frame(1, Collections.emptyList()), frame(2,
			Collections.singletonList(player(SECOND, "Unverified label", 0, 0, true))), Collections.singletonList(animation));
		assertNull(absent.getCandidates().get(0).getActorRef());
	}

	@Test
	public void contradictoryRepeatedActorSamplesDoNotArbitrarilyChooseOnePlayer()
	{
		WorldSnapshot launch = frame(1, Arrays.asList(player(FIRST, "One", 0, 0, true),
			player(FIRST, "Two", 0, 0, true))).toBuilder().projectiles(Collections.singletonList(projectile(99, FIRST))).build();
		SourceResolution result = resolve(launch, frame(2, Collections.emptyList()), Collections.singletonList(witness(99)));
		assertNull(result.getCandidates().get(0).getActorRef());
	}
}
