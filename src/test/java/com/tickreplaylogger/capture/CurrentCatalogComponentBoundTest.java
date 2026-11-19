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

import com.google.gson.Gson;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.ProjectileState;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Fixed source expectations exercised through packaged reference data and original observed DTOs. */
public class CurrentCatalogComponentBoundTest
{
	private static DamageMap map;
	private static final String ACTOR = "npc:catalog-review:v0:lifetime1";
	private static final String SCENE = "catalog-review";

	@BeforeClass
	public static void loadCatalog()
	{
		map = DamageMap.load(new Gson());
	}

	@AfterClass
	public static void releaseCatalog()
	{
		map = null;
	}

	private static final class Case
	{
		final String name, rule;
		final int npc, level, projectile, maximum;

		Case(String name, int npc, int level, int projectile, int maximum, String rule)
		{
			this.name = name; this.npc = npc; this.level = level; this.projectile = projectile;
			this.maximum = maximum; this.rule = "component_bound:" + rule;
		}
	}

	private static final Case[] CASES = {
		new Case("Vorkath", 8061, 732, 1477, 32, "vorkath_postquest_ordinary_projectile_envelope"),
		new Case("Vorkath", 8060, 392, 1479, 30, "vorkath_quest_ordinary_projectile_envelope"),
		new Case("Phantom Muspah", 12077, 741, 2327, 79, "muspah_ranged_ordinary_projectile_envelope"),
		new Case("Phantom Muspah", 12078, 741, 2329, 79, "muspah_melee_ordinary_projectile_envelope"),
		new Case("Phantom Muspah", 12079, 741, 2327, 79, "muspah_shielded_ordinary_projectile_envelope"),
		new Case("Phantom Muspah", 12080, 741, 2329, 79, "muspah_postshield_ordinary_projectile_envelope"),
		new Case("Nex", 11278, 1001, 1997, 60, "nex_ordinary_projectile_envelope"),
		new Case("Nex", 11280, 1001, 2007, 60, "nex_ordinary_projectile_envelope"),
		new Case("Nex", 11281, 1001, 2007, 60, "nex_ordinary_projectile_envelope")
	};

	private static SourceResolution resolve(Case sample, int damage, Integer level, Boolean apiSourceKnown,
		Boolean modelOverrides, int projectileId)
	{
		NpcState npc = NpcState.builder().actorRef(ACTOR).npcId(sample.npc).name(sample.name).combatLevel(level)
			.worldViewId(0).plane(0).worldX(102).worldY(203).worldPositionKnown(true).planeKnown(true)
			.normalWorldContextObserved(true).modelOverridesPresent(modelOverrides).animationId(-1)
			.baseNpcId(sample.npc).definitionEvidence("TRANSFORMED_COMPOSITION").definitionHasTransforms(false).build();
		ProjectileState projectile = ProjectileState.builder().observationId(99L).projectileId(projectileId)
			.spawnTick(1).observedTick(1).sourceActorKnown(apiSourceKnown).sourceRef(ACTOR).sourceName(sample.name)
			.sourceNameKnown(true).sourceObservedName(sample.name).sourceCombatLevel(level)
			.sourceNormalWorldContextObserved(modelOverrides == null ? null : !modelOverrides)
			.sourceBaseNpcId(sample.npc).sourceDefinitionEvidence("TRANSFORMED_COMPOSITION").sourceDefinitionHasTransforms(false)
			.sourceNpcId(sample.npc).sourceWorldViewId(0).sourcePlane(0).sourceEvidence("ACTOR").targetsPlayer(true).targetRef("player").build();
		WorldSnapshot launch = frame(1).toBuilder().npcs(Collections.singletonList(npc))
			.projectiles(Collections.singletonList(projectile)).build();
		Map<String, Object> witness = new LinkedHashMap<>();
		witness.put("kind", "PROJECTILE_CORRELATION"); witness.put("name", sample.name);
		witness.put("projectileObservationId", 99L); witness.put("arrivalGapCycles", 0);
		witness.put("matchBasis", "OBSERVED_CLIENT_CYCLE_WINDOW");
		Map<String, Object> other = new LinkedHashMap<>();
		other.put("kind", "ITEM_CONSUMPTION_CANDIDATE"); other.put("name", "Other recorded cause");
		other.put("sourceRef", "fixture:other");
		DamageRecord hit = DamageRecord.builder().impactTick(2).observedCycle(100).observationSequence(7L)
			.direction("RECEIVED").hitsplatType(16).hitsplatTypeKnown(true).amount(damage).attackerName("Unknown")
			.defenderRef("player").impactSceneKey(SCENE).impactWorldViewId(0).impactPlane(0)
			.impactX(102).impactY(203).impactPositionKnown(true).attributionCandidates(Arrays.asList(witness, other)).build();
		return new RecordedSourceResolver(Arrays.asList(launch, frame(2)), Collections.emptyList(), map).resolve(hit);
	}

	private static WorldSnapshot frame(int tick)
	{
		return WorldSnapshot.builder().tick(tick).sceneKey(SCENE).worldViewId(0).plane(0).nativeContextKnown(true)
			.player(PlayerState.builder().worldViewId(0).plane(0).worldX(102).worldY(203)
				.worldPositionKnown(true).planeKnown(true).build())
			.npcs(Collections.emptyList()).projectiles(Collections.emptyList()).hazards(Collections.emptyList()).build();
	}

	private static SourceResolution.Candidate npc(SourceResolution resolution, Case sample, boolean excluded)
	{
		return (excluded ? resolution.getExcludedCandidates() == null ? Collections.<SourceResolution.Candidate>emptyList()
			: resolution.getExcludedCandidates() : resolution.getCandidates()).stream()
			.filter(value -> Integer.valueOf(sample.npc).equals(value.getNpcId())).findFirst().orElse(null);
	}

	@Test
	public void exactCapsRetainEachObservedActorAndShowItsCurrentQualifiedRule()
	{
		for (Case sample : CASES)
		{
			SourceResolution result = resolve(sample, sample.maximum, sample.level, true, false, sample.projectile);
			SourceResolution.Candidate actor = npc(result, sample, false);
			assertNotNull(sample.rule + " NPC " + sample.npc, actor);
			assertTrue(sample.rule + " must be active rather than an old unqualified profile", actor.getEvidence().stream()
				.anyMatch(value -> value.contains("QUALIFIED_MAXIMUM:" + sample.rule + ":" + sample.maximum + " <= " + sample.maximum)));
			assertTrue(result.getCandidates().stream().anyMatch(value -> "Other recorded cause".equals(value.getName())));
		}
	}

	@Test
	public void exceedingTheCapExcludesOnlyTheQualifiedComponentAndDoesNotTriggerLastApiFallback()
	{
		for (Case sample : CASES)
		{
			SourceResolution result = resolve(sample, sample.maximum + 1, sample.level, true, false, sample.projectile);
			assertNull(sample.rule, npc(result, sample, false));
			SourceResolution.Candidate excluded = npc(result, sample, true);
			assertNotNull(sample.rule, excluded);
			assertTrue(sample.rule, excluded.getReasons().stream().anyMatch(value -> value.contains(
				"MAXIMUM_EXCEEDED:" + sample.rule + ":" + (sample.maximum + 1) + " > " + sample.maximum)));
			assertEquals(1, result.getCandidates().size());
			assertEquals("Other recorded cause", result.getCandidates().get(0).getName());
		}
	}

	@Test
	public void missingSampleLevelApiSourceOrOverrideAvailabilityCannotActivateTheGuard()
	{
		for (Case sample : CASES)
		{
			for (int missing = 0; missing < 4; missing++)
			{
				SourceResolution result = resolve(sample, sample.maximum + 1, missing == 0 ? null : sample.level,
					missing == 1 ? null : true, missing == 2 ? null : missing == 3, sample.projectile);
				SourceResolution.Candidate actor = npc(result, sample, false);
				assertNotNull(sample.rule + " missing predicate " + missing, actor);
				assertFalse(actor.getEvidence().stream().anyMatch(value -> value.startsWith("QUALIFIED_MAXIMUM:" + sample.rule + ":")));
			}
		}
	}

	@Test
	public void anUnknownProjectileCannotBorrowAnOrdinaryUnionCap()
	{
		for (Case sample : CASES)
		{
			SourceResolution result = resolve(sample, sample.maximum + 1, sample.level, true, false, 999965);
			SourceResolution.Candidate actor = npc(result, sample, false);
			assertNotNull(sample.rule, actor);
			assertFalse(actor.getEvidence().stream().anyMatch(value -> value.startsWith("QUALIFIED_MAXIMUM:" + sample.rule + ":")));
		}
	}
}
