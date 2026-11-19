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

import com.google.gson.Gson;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.DeathRecordingV2;
import com.tickreplaylogger.model.world.SceneData;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class CaptureTest
{
	@Test
	public void commentedAndUnusedNpcIdsCannotBorrowActiveWikiReferences()
	{
		CombatCatalog catalog = DamageMap.load(new Gson()).catalog();
		for (int id : new int[]
		{ 16765, 16766, 16767, 16768, 16769, 16770 })
			for (com.google.gson.JsonElement candidate : catalog.npcCandidates(id))
				assertFalse("Commented Orion form leaked into active identity",
					"Orion".equals(candidate.getAsJsonObject().get("page").getAsString()));
		boolean currentWolf = false;
		for (com.google.gson.JsonElement candidate : catalog.npcCandidates(110))
			currentWolf |= "Wolf".equals(candidate.getAsJsonObject().get("page").getAsString());
		assertTrue("Current Wolf ID must survive its adjacent annotation", currentWolf);
		for (com.google.gson.JsonElement candidate : catalog.npcCandidates(117))
			assertFalse("Unused Wolf ID leaked out of its comment",
				"Wolf".equals(candidate.getAsJsonObject().get("page").getAsString()));
	}

	@Test
	public void callbackOverflowIsExplicitWindowedAndCannotCertifyAFatalSource()
	{
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(2, new com.google.gson.Gson());
		for (int i = 0; i < WorldSnapshotRecorder.MAX_OBSERVATIONS + 2; i++)
			recorder.addObservation(com.tickreplaylogger.model.world.ObservedEvent.builder().tick(1).sequence(i)
				.type("SYSTEM_MESSAGE").build());
		recorder.addSnapshot(com.tickreplaylogger.model.world.WorldSnapshot.builder().tick(1).sceneKey("s").build());
		DeathRecordingV2 full = recorder.buildExport(null, null);
		assertEquals(WorldSnapshotRecorder.MAX_OBSERVATIONS, full.getEvents().getObservations().size());
		assertEquals(2L, full.getEvents().getCaptureGaps().get(0).getOmittedObservations());
		assertTrue(recorder.hasUnresolvedPlayerImpact(1, null));
		recorder.addSnapshot(com.tickreplaylogger.model.world.WorldSnapshot.builder().tick(2).sceneKey("s").build());
		assertTrue(recorder.buildExport(null, null, 2, 2).getEvents().getCaptureGaps().isEmpty());
		recorder.clear();
		assertFalse(recorder.hasUnresolvedPlayerImpact(1, null));
	}
	// --- bundled damage map ---

	@Test
	public void bundledDamageMapLoads()
	{
		DamageMap map = DamageMap.load(new Gson());
		// Resource is on the classpath and parsed — version proves it loaded.
		assertEquals("bundled map did not load from resources", "2026-11-04", map.getVersion());
	}

	@Test
	public void unknownNpcHasNoStyles()
	{
		DamageMap map = DamageMap.load(new Gson());
		assertTrue(map.stylesForNpc(-12345).isEmpty());
		assertEquals(null, map.hazardName("graphicsObject", -12345));
		assertEquals(null, map.effectForSpotanim(-12345));
	}

	@Test
	public void determinationReflectsReactability()
	{
		DamageMap map = DamageMap.load(new Gson());
		// A boss name alone cannot distinguish its ordinary attacks from specials.
		assertEquals("UNKNOWN", map.determinationFor("TzTok-Jad"));
		// No substring/default timing claims without an individual reviewed attack binding.
		assertEquals("UNKNOWN", map.determinationFor("Zebak"));
		assertEquals("UNKNOWN", map.determinationFor("The Leviathan"));
		assertEquals("UNKNOWN", map.determinationFor("the whisperer"));
		assertEquals("UNKNOWN", map.determinationForAttack(9036, 1712));
		assertEquals("LAUNCH", map.determinationForAttack(9036, 1712, 894, true));
		assertEquals("UNKNOWN", map.determinationForAttack(9036, 2444));
		assertEquals("UNKNOWN", map.determinationForAttack(12204, 2444));
		assertEquals("IMPACT", map.determinationForAttack(12204, 2444, 720, true));
		assertEquals("UNKNOWN", map.determinationForAttack(12204, -1));
		assertEquals(null, map.soleStyleForNpc(499));
		assertEquals(null, map.soleStyleForNpc(499, 301));
		assertEquals("typeless", map.soleStyleForNpc(499, 301, true));
		assertEquals(null, map.soleStyleForNpc(499, 602));
		assertTrue(!map.catalog().prayerIneffective(499, 602));
		assertFalse(map.catalog().prayerIneffective(13011));
		assertTrue(map.catalog().prayerIneffective(13011, 503, true));
		assertNull(map.catalog().entityDamageType(1834, 149, false));
		assertEquals("typeless", map.catalog().entityDamageType(1834, 149, true));
		// No attacker name -> we do not guess.
		assertEquals("UNKNOWN", map.determinationFor(null));
		assertEquals("UNKNOWN", map.determinationFor(""));
	}

	@Test
	public void reviewedProjectileStyleNeedsTheExactActorFamilyAndNormalModeWitness()
	{
		CombatCatalog catalog = CombatCatalog.load(new Gson());
		assertEquals("ranged", catalog.projectileDamageType(9022, 1711, 674, true));
		assertEquals("magic", catalog.projectileDamageType(9023, 1713, 674, true));
		assertEquals(null, catalog.projectileDamageType(9022, 1711, 674, false));
		assertEquals(null, catalog.projectileDamageType(499, 1712, 301, true));
		assertEquals(null, catalog.projectileDamageType(9036, 999999, 894, true));
		assertEquals("LAUNCH", catalog.prayerTiming(9022, 1711, 674, true));
		assertEquals("hunllef_ordinary_projectiles", catalog.prayerTimingRuleKey(9022, 1711, 674, true));
		assertNull(catalog.prayerTimingRuleKey(9022, 1711, 674, false));
		assertNull(catalog.prayerTimingRuleKey(9023, 1713, 674, true));
		assertEquals("UNKNOWN", catalog.prayerTiming(9023, 1713, 674, true));
		assertEquals("UNKNOWN", catalog.prayerTiming(9037, 1714, 894, true));
	}

	@Test
	public void vorkathTravelStylesExcludeSleepingActorsOtherFamiliesImpactsAndSpecials()
	{
		CombatCatalog catalog = CombatCatalog.load(new Gson());
		assertEquals("magic", catalog.projectileDamageType(8060, 1479, 392, true));
		assertEquals("ranged", catalog.projectileDamageType(8061, 1477, 732, true));
		assertNull(catalog.projectileDamageType(8059, 1479, 732, true));
		assertNull(catalog.projectileDamageType(9035, 1479, 894, true));
		assertNull(catalog.projectileDamageType(8061, 1479, 732, false));
		assertNull(catalog.projectileDamageType(8061, 1480, 732, true));
		assertNull(catalog.projectileDamageType(8061, 1481, 732, true));
		assertEquals("UNKNOWN", catalog.prayerTiming(8061, 1479, 732, true));
	}

	@Test
	public void overlappingTimingPredicatesDoNotChooseTheFirstRule() throws Exception
	{
		CombatCatalog catalog = CombatCatalog.load(new Gson());
		java.lang.reflect.Field field = CombatCatalog.class.getDeclaredField("root");
		field.setAccessible(true);
		com.google.gson.JsonObject root = (com.google.gson.JsonObject) field.get(catalog);
		com.google.gson.JsonArray rules = root.getAsJsonObject("reviewedBindings").getAsJsonArray("prayerTimingRules");
		rules.add(rules.get(0).deepCopy());
		assertEquals("UNKNOWN", catalog.prayerTiming(9022, 1711, 674, true));
		assertNull(catalog.prayerTimingRuleKey(9022, 1711, 674, true));
	}

	@Test
	public void additionalProjectileStylesRequireTheObservedActorAndWorldScope()
	{
		CombatCatalog catalog = CombatCatalog.load(new Gson());
		for (int npc : new int[]
		{ 8615, 8619, 8620, 8621 })
			for (int projectile : new int[]
			{ 1662, 1663 })
			{
				assertEquals(projectile == 1662 ? "magic" : "ranged",
					catalog.projectileDamageType(npc, projectile, 426, true));
				assertEquals("hydra_ordinary_projectile_styles",
					catalog.projectileDamageTypeRuleKey(npc, projectile, 426, true));
				assertNull(catalog.projectileDamageType(npc, projectile, 426, false));
			}
		for (int npc : new int[]
		{ 12077, 12078, 12079, 12080 })
			for (int projectile : new int[]
			{ 2327, 2329 })
			{
				assertEquals(projectile == 2327 ? "magic" : "ranged",
					catalog.projectileDamageType(npc, projectile, 741, true));
				assertEquals("UNKNOWN", catalog.prayerTiming(npc, projectile, 741, true));
			}
		for (int npc : new int[]
		{ 11278, 11280, 11281 })
			for (int projectile : new int[]
			{ 1997, 1999, 2000, 2004, 2007 })
			{
				assertEquals(projectile == 1999 ? "ranged" : "magic",
					catalog.projectileDamageType(npc, projectile, 1001, true));
				assertEquals("UNKNOWN", catalog.prayerTiming(npc, projectile, 1001, true));
			}
		for (int npc : new int[]
		{ 12073, 12074, 12075, 12076, 12081, 12082, 15549 })
			assertNull(catalog.projectileDamageType(npc, 2327, 741, true));
		for (int projectile : new int[]
		{ 1644, 1665, 1667 })
			assertNull(catalog.projectileDamageType(8615, projectile, 426, true));
		for (int projectile : new int[]
		{ 2002, 2006, 2009, 3036 })
			assertNull(catalog.projectileDamageType(11278, projectile, 1001, true));
		assertNull(catalog.projectileDamageType(11283, 1997, 285, true));
		assertNull(catalog.projectileDamageType(12077, 4029, 741, true));
	}

	@Test
	public void correctedSnakelingReferencesReachBothDefinitionsWithoutCreatingAnAttackRule()
	{
		CombatCatalog catalog = CombatCatalog.load(new Gson());
		String key = "attack_reference:7a56549ae90c3a9326b6:1";
		assertTrue(catalog.referenceKeys(2045).contains(key));
		assertTrue(catalog.referenceKeys(2046).contains(key));
		assertFalse(catalog.referenceKeys(2127).contains(key));
		assertFalse(catalog.referenceKeys(2047).contains(key));
		assertNull(catalog.entityDamageType(2045, 90, true));
		assertNull(catalog.entityDamageType(2046, 90, true));
		assertEquals("UNKNOWN", catalog.prayerTiming(2046, 1045, 90, true));
	}

	@Test
	public void conflictingProjectileStyleRulesDoNotDependOnTheirOrder() throws Exception
	{
		CombatCatalog catalog = CombatCatalog.load(new Gson());
		java.lang.reflect.Field field = CombatCatalog.class.getDeclaredField("root");
		field.setAccessible(true);
		com.google.gson.JsonObject root = (com.google.gson.JsonObject) field.get(catalog);
		com.google.gson.JsonArray rules = root.getAsJsonObject("reviewedBindings")
			.getAsJsonArray("projectileStyleRules");
		com.google.gson.JsonObject duplicate = new com.google.gson.JsonObject();
		com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
		ids.add(8061);
		duplicate.add("npcIds", ids);
		com.google.gson.JsonObject styles = new com.google.gson.JsonObject();
		styles.addProperty("1479", "magic");
		duplicate.add("styles", styles);
		rules.add(duplicate);
		assertEquals("magic", catalog.projectileDamageType(8061, 1479, 732, true));
		styles.addProperty("1479", "ranged");
		assertNull(catalog.projectileDamageType(8061, 1479, 732, true));
		com.google.gson.JsonArray reversed = new com.google.gson.JsonArray();
		for (int i = rules.size() - 1; i >= 0; i--)
			reversed.add(rules.get(i));
		root.getAsJsonObject("reviewedBindings").add("projectileStyleRules", reversed);
		assertNull(catalog.projectileDamageType(8061, 1479, 732, true));
	}

	@Test
	public void environmentalPresenceDoesNotSupplyABlanketDamageType()
	{
		CombatCatalog catalog = CombatCatalog.load(new Gson());
		assertEquals("typeless", catalog.hazardDamageType("Corrupted Hunllef floor"));
		assertEquals("typeless", catalog.hazardDamageType("Hunllef floor"));
		assertNull(catalog.hazardDamageType("Poison cloud"));
		assertNull(catalog.hazardDamageType("Maiden blood pool"));
		assertNull(catalog.hazardDamageType("Unmapped environment"));
	}

	@Test
	public void contactHazardDefinitionsAreScopedAndDoNotClaimAnUnprovenSubtype()
	{
		CombatCatalog catalog = CombatCatalog.load(new Gson());
		assertEquals("Gauntlet tornado", catalog.npcContactHazardName(9025, 0, true));
		assertEquals("Corrupted tornado", catalog.npcContactHazardName(9039, 0, true));
		assertEquals("Blue Moon tornado", catalog.npcContactHazardName(13027, 0, true));
		assertNull(catalog.npcContactHazardName(9039, 0, false));
		assertNull(catalog.npcContactHazardName(9035, 894, true));
		assertNull(catalog.hazardDamageType("Corrupted tornado"));
	}

	@Test
	public void contactOrbsAndReferenceOnlyWavesHaveDifferentAdmissionRules()
	{
		CombatCatalog catalog = CombatCatalog.load(new Gson());
		for (int id : new int[]
		{ 11708, 11800, 11801, 11802, 11803, 11804 })
		{
			assertNotNull(catalog.npcContactHazardName(id, 0, true));
			assertNull(catalog.npcContactHazardName(id, 0, false));
		}
		for (int id : new int[]
		{ 11738, 11739, 14152, 14153 })
			assertNull(catalog.npcContactHazardName(id, 0, true));
		assertNull(catalog.hazardDamageType("Unstable orb"));
	}

	// --- rolling buffer / export assembly ---
	@Test
	public void cacheStyleBindingsExcludeBlockAnimationsAndUnknownProjectiles()
	{
		DamageMap map = DamageMap.load(new Gson());
		assertEquals("ranged", map.styleForProjectile(1712));
		assertEquals("magic", map.styleForProjectile(1708));
		for (int projectile : new int[] { 1713, 1714 })
		{
			assertNull(map.styleForProjectile(projectile));
			assertNull(map.catalog().projectileDamageType(-1, projectile, null, true));
			assertEquals("magic", map.catalog().projectileDamageType(9036, projectile, 894, true));
		}
		assertEquals("melee", map.styleForPlayerAnimation(401));
		assertEquals(null, map.styleForPlayerAnimation(403));
		assertEquals(null, map.styleForProjectile(-1));
	}

	@Test
	public void environmentalAndNpcAttackRulesAreLoadedFromTheResearchBundle()
	{
		DamageMap map = DamageMap.load(new Gson());
		assertEquals("unknown", map.environmentalRule().getDamageType());
		assertEquals(5, map.environmentalRule().getSourceKeys().size());
		assertTrue(!map.environmentalRule().getSourceKeys().contains("wintertodt"));
		assertTrue(!map.environmentalRule().getSourceKeys().contains("tempoross"));
		assertEquals("unknown", map.environmentalRule().getPrayable());
		assertEquals(null, map.attackRuleForNpc(9036, 8420)); // Shared mode ID without observed context.
		assertEquals("typeless", map.attackRuleForNpc(9036, 8420, 894, true).getDamageType());
		assertTrue(map.attackRuleForNpc(9036, 8419, 894, true).isRequiresProjectile());
		assertEquals(null, map.attackRuleForNpc(9036, 8753));
	}

	@Test
	public void expiredScenesAndEventsArePrunedAndSelectedExportsExcludeLaterObservations()
	{
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(2, new com.google.gson.Gson());
		for (int tick = 1; tick <= 5; tick++)
		{
			recorder.putScene(SceneData.builder().sceneKey("s" + tick).build());
			recorder.addObservation(com.tickreplaylogger.model.world.ObservedEvent.builder().tick(tick).sequence(tick)
				.type("HITSPLAT").build());
			recorder.addPlayerAction(tick, "ATTACK", "Attack input");
			recorder.addDeath(DeathRecordingV2.Events.DeathMark.builder().tick(tick).actorRef("npc:" + tick).build());
			recorder.addDamage(DamageRecord.builder().direction("RECEIVED").impactTick(tick).amount(tick).build());
			recorder.addSnapshot(snap(tick, "s" + tick));
		}
		assertTrue(!recorder.hasScene("s1"));
		DeathRecordingV2 all = recorder.buildExport(DeathRecordingV2.Meta.builder().build(),
			DeathRecordingV2.Summary.builder().build());
		assertEquals(2, all.getScenes().size());
		assertEquals(2, all.getEvents().getObservations().size());
		assertEquals(2, all.getEvents().getDamageEvents().size());
		assertEquals(2, all.getEvents().getPlayerActions().size());
		DeathRecordingV2 selected = recorder.buildExport(DeathRecordingV2.Meta.builder().build(),
			DeathRecordingV2.Summary.builder().build(), 4, 4);
		assertEquals(1, selected.getEvents().getObservations().size());
		assertEquals(4, selected.getEvents().getObservations().get(0).getTick());
		assertEquals(1, selected.getEvents().getDeaths().size());
		assertEquals(4, selected.getEvents().getDeaths().get(0).getTick());
	}

	@Test
	public void onlyReceivedPlayerDamageCanBeSelectedAsTheKillingBlow()
	{
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(10, new com.google.gson.Gson());
		DamageRecord received = DamageRecord.builder().direction("RECEIVED").amount(5).impactTick(1).build();
		recorder.addDamage(received);
		recorder.addDamage(DamageRecord.builder().direction("DEALT").amount(50).impactTick(1).build());
		recorder.addDamage(DamageRecord.builder().direction("NPC_RECEIVED").amount(99).impactTick(2).build());
		recorder.addDamage(DamageRecord.builder().direction("RECEIVED").amount(0).impactTick(2).build());
		assertEquals(received, recorder.lastDamagingHit());
	}

	private WorldSnapshot snap(int tick, String sceneKey)
	{
		return WorldSnapshot.builder().tick(tick).wallClockMs(0).sceneKey(sceneKey).plane(0).npcs(new ArrayList<>())
			.players(new ArrayList<>()).projectiles(new ArrayList<>()).hazards(new ArrayList<>()).build();
	}

	@Test
	public void bufferEvictsToCapacity()
	{
		WorldSnapshotRecorder r = new WorldSnapshotRecorder(3, new com.google.gson.Gson());
		for (int i = 1; i <= 5; i++)
		{
			r.addSnapshot(snap(i, "s1"));
		}
		assertEquals(3, r.size());
	}

	@Test
	public void exportKeepsOnlyReferencedScenesAndWindowsEvents()
	{
		WorldSnapshotRecorder r = new WorldSnapshotRecorder(10, new com.google.gson.Gson());
		r.putScene(SceneData.builder().sceneKey("s1").build());
		r.putScene(SceneData.builder().sceneKey("s2-unused").build());
		r.addSnapshot(snap(10, "s1"));
		r.addSnapshot(snap(11, "s1"));
		r.addSnapshot(snap(12, "s1"));

		// one event inside the buffer window, one before it
		r.addDamage(DamageRecord.builder().impactTick(11).amount(5).build());
		r.addDamage(DamageRecord.builder().impactTick(2).amount(9).build());

		DeathRecordingV2 export = r.buildExport(DeathRecordingV2.Meta.builder().build(),
			DeathRecordingV2.Summary.builder().build());

		assertNotNull(export);
		assertEquals("1.3", export.getSchemaVersion());
		assertEquals(3, export.getTicks().size());
		assertEquals("only the referenced scene is exported", 1, export.getScenes().size());
		assertEquals("s1", export.getScenes().get(0).getSceneKey());
		assertEquals("only in-window damage events are exported", 1, export.getEvents().getDamageEvents().size());
		assertEquals(5, export.getEvents().getDamageEvents().get(0).getAmount());
		DeathRecordingV2 selected = r.buildExport(DeathRecordingV2.Meta.builder().build(),
			DeathRecordingV2.Summary.builder().ticksCaptured(100).build(), 11, 11);
		assertEquals(1, selected.getTicks().size());
		assertEquals(1, selected.getSummary().getTicksCaptured());
		assertEquals(1, selected.getEvents().getDamageEvents().size());
	}
}
