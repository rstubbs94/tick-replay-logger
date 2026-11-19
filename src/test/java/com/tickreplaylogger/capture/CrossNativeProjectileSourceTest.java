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
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.tickreplaylogger.capture;

import com.google.gson.Gson;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.OtherPlayerState;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.ProjectileSourceCandidate;
import com.tickreplaylogger.model.world.ProjectileSourceHistory;
import com.tickreplaylogger.model.world.ProjectileState;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class CrossNativeProjectileSourceTest
{
	private static final int RECIPIENT_VIEW = 17, ID = 987654;
	private static final String SOURCE_SCENE = "source-native", IMPACT_SCENE = "recipient-native";

	private static WorldSnapshot frame(int tick, int view, String scene)
	{
		return WorldSnapshot.builder().tick(tick).apiTickCount(1000 + tick).gameCycle(100 + tick)
			.gameCycleKnown(true).nativeContextKnown(true).sceneKey(scene).worldViewId(view).plane(0)
			.player(view == RECIPIENT_VIEW ? PlayerState.builder().worldViewId(view).plane(0).planeKnown(true)
				.worldPositionKnown(true).worldX(100).worldY(200).build() : null)
			.npcs(Collections.emptyList()).players(Collections.emptyList()).projectiles(Collections.emptyList())
			.hazards(Collections.emptyList()).build();
	}

	private static NpcState npc(String ref, int id, int view, String name, int level)
	{
		return NpcState.builder().actorRef(ref).npcId(id).name(name).combatLevel(level).worldViewId(view)
			.plane(0).planeKnown(true).worldPositionKnown(true).worldX(5000).worldY(6000)
			.normalWorldContextObserved(true).modelOverridesPresent(false).baseNpcId(id)
			.definitionEvidence("NPC_API_ID").definitionHasTransforms(false).build();
	}

	private static final class Fixture
	{
		final String ref;
		final ProjectileState projectile;
		final WorldSnapshot source, launch, impact;

		Fixture(boolean npc, int view)
		{
			ref = (npc ? "npc:" : "otherplayer:") + "7:v" + view + ":g1";
			String name = npc ? "Unseen actor" : "Recorded other player";
			ProjectileSourceHistory.SourceObservation first = ProjectileSourceHistory.SourceObservation.builder()
				.observationId(99L).projectileId(876543).observedTick(1).observedCycle(101)
				.actorKind(npc ? "NPC" : "OTHER_PLAYER").sourceActorKnown(true).sourceEvidence("ACTOR")
				.sourceRef(ref).sourceName(name).sourceWorldViewId(view).sourcePlane(0).sourceNpcId(npc ? ID : null)
				.sourceNameKnown(true).sourceObservedName(name).sourceCombatLevel(npc ? 50 : null)
				.sourceNormalWorldContextObserved(npc ? true : null).sourceBaseNpcId(npc ? ID : null)
				.sourceDefinitionEvidence(npc ? "NPC_API_ID" : null).sourceDefinitionHasTransforms(npc ? false : null).build();
			projectile = ProjectileState.builder().observationId(99L).projectileId(876543).worldViewId(view)
				.spawnTick(1).observedTick(1).sourceActorKnown(true).sourceEvidence("ACTOR").sourceRef(ref)
				.sourceName(name).sourceNpcId(npc ? ID : null).sourceWorldViewId(view).sourcePlane(0)
				.sourceNameKnown(true).sourceObservedName(name).sourceCombatLevel(npc ? 50 : null)
				.sourceNormalWorldContextObserved(npc ? true : null).sourceBaseNpcId(npc ? ID : null)
				.sourceDefinitionEvidence(npc ? "NPC_API_ID" : null).sourceDefinitionHasTransforms(npc ? false : null)
				.targetRef("player").targetsPlayer(true).targetWorldViewId(RECIPIENT_VIEW).targetPlane(0)
				.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(first).build()).build();
			source = frame(1, view, view == RECIPIENT_VIEW ? IMPACT_SCENE : SOURCE_SCENE).toBuilder()
				.npcs(npc ? Collections.singletonList(npc(ref, ID, view, name, 50)) : Collections.emptyList())
				.players(npc ? Collections.emptyList() : Collections.singletonList(OtherPlayerState.builder()
					.actorRef(ref).name(name).worldViewId(view).plane(0).planeKnown(true).worldPositionKnown(true)
					.worldX(5000).worldY(6000).build())).projectiles(Collections.singletonList(projectile)).build();
			launch = view == RECIPIENT_VIEW ? source
				: frame(1, RECIPIENT_VIEW, IMPACT_SCENE).toBuilder().projectiles(Collections.singletonList(projectile))
					.additionalViews(Collections.singletonList(source)).build();
			impact = frame(2, RECIPIENT_VIEW, IMPACT_SCENE);
		}
	}

	private static Map<String, Object> witness(ProjectileState projectile)
	{
		Map<String, Object> raw = new LinkedHashMap<>(); raw.put("kind", "PROJECTILE_CORRELATION");
		raw.put("name", projectile.getSourceName()); raw.put("projectileObservationId", 99L);
		raw.put("arrivalGapCycles", 0); raw.put("matchBasis", "OBSERVED_CLIENT_CYCLE_WINDOW");
		raw.put("sourceHistory", projectile.getSourceHistory()); return raw;
	}

	private static DamageRecord hit(ProjectileState projectile)
	{
		return DamageRecord.builder().impactTick(2).observationSequence(8L).observedCycle(102).direction("RECEIVED")
			.defenderRef("player").amount(15).hitsplatType(16).hitsplatTypeKnown(true).attackerName("Unknown")
			.impactSceneKey(IMPACT_SCENE).impactWorldViewId(RECIPIENT_VIEW).impactPlane(0)
			.impactPositionKnown(true).impactX(100).impactY(200).attributionCandidates(Collections.singletonList(witness(projectile))).build();
	}

	private static SourceResolution resolve(WorldSnapshot launch, WorldSnapshot impact, ProjectileState projectile)
	{
		return new RecordedSourceResolver(Arrays.asList(launch, impact), Collections.emptyList(), null).resolve(hit(projectile));
	}

	private static SourceResolution.Candidate only(SourceResolution resolution)
	{
		assertEquals(1, resolution.getCandidates().size()); return resolution.getCandidates().get(0);
	}

	private static void unqualified(SourceResolution resolution)
	{
		SourceResolution.Candidate source = only(resolution); assertNull(source.getActorRef());
		assertNull(source.getSceneKey()); assertEquals("POSSIBLE", source.getStrength());
		assertTrue(resolution.isUnidentifiedPossible());
		assertTrue(resolution.getExcludedCandidates() == null || resolution.getExcludedCandidates().isEmpty());
	}

	@Test
	public void actualNpcAndPlayerSourcesSurviveInAnotherRecordedNativeViewWithoutProjection()
	{
		for (boolean npc : Arrays.asList(true, false))
		{
			Fixture f = new Fixture(npc, 0); SourceResolution.Candidate source = only(resolve(f.launch, f.impact, f.projectile));
			assertEquals(f.ref, source.getActorRef()); assertEquals(Integer.valueOf(0), source.getWorldViewId());
			assertEquals(SOURCE_SCENE, source.getSceneKey()); assertEquals(Integer.valueOf(0), source.getPlane());
			assertEquals("STRONG", source.getStrength());
			assertEquals(IMPACT_SCENE, hit(f.projectile).getImpactSceneKey());
			assertEquals(Integer.valueOf(RECIPIENT_VIEW), hit(f.projectile).getImpactWorldViewId());
			assertEquals(f.ref, f.projectile.getSourceHistory().getFirstObservedSource().getSourceRef());
		}
	}

	@Test
	public void sameNativeControlsStillKeepTheExactActor()
	{
		for (boolean npc : Arrays.asList(true, false))
		{
			Fixture f = new Fixture(npc, RECIPIENT_VIEW); SourceResolution.Candidate source = only(resolve(f.launch, f.impact, f.projectile));
			assertEquals(f.ref, source.getActorRef()); assertEquals(IMPACT_SCENE, source.getSceneKey());
		}
	}

	@Test
	public void missingOriginalViewOrPlaneCannotBorrowTheLatestOrRecipientScope()
	{
		Fixture f = new Fixture(true, 0);
		for (boolean missingView : Arrays.asList(true, false))
		{
			ProjectileSourceHistory.SourceObservation first = f.projectile.getSourceHistory().getFirstObservedSource().toBuilder()
				.sourceWorldViewId(missingView ? null : 0).sourcePlane(missingView ? 0 : null).build();
			ProjectileState p = f.projectile.toBuilder().sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(first).build()).build();
			SourceResolution resolution = resolve(f.launch.toBuilder().projectiles(Collections.singletonList(p)).build(), f.impact, p);
			unqualified(resolution); assertNotEquals(Integer.valueOf(RECIPIENT_VIEW), only(resolution).getWorldViewId());
		}
	}

	@Test
	public void sourceBeforeTheRetainedWindowAndLaterActorSnapshotsStayUnqualified()
	{
		Fixture f = new Fixture(true, 0);
		ProjectileSourceHistory.SourceObservation first = f.projectile.getSourceHistory().getFirstObservedSource().toBuilder().observedTick(0).build();
		ProjectileState before = f.projectile.toBuilder().sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(first).build()).build();
		unqualified(resolve(f.launch.toBuilder().projectiles(Collections.singletonList(before)).build(), f.impact, before));
		WorldSnapshot withoutSource = f.launch.toBuilder().additionalViews(Collections.emptyList()).build();
		WorldSnapshot later = f.impact.toBuilder().additionalViews(Collections.singletonList(f.source.toBuilder().tick(2).build())).build();
		unqualified(resolve(withoutSource, later, f.projectile));
	}

	@Test
	public void sameNameSlotReuseAndContradictoryDuplicateSamplesCannotRestoreAnIdentity()
	{
		Fixture f = new Fixture(true, 0);
		WorldSnapshot reused = f.source.toBuilder().npcs(Collections.singletonList(npc("npc:7:v0:g2", ID, 0, "Unseen actor", 50))).build();
		unqualified(resolve(f.launch.toBuilder().additionalViews(Collections.singletonList(reused)).build(), f.impact, f.projectile));
		WorldSnapshot contradiction = f.source.toBuilder().npcs(Collections.singletonList(npc(f.ref, ID + 1, 0, "Different source", 100))).build();
		unqualified(resolve(f.launch.toBuilder().additionalViews(Arrays.asList(f.source, contradiction)).build(), f.impact, f.projectile));
	}

	@Test
	public void foreignActorScopeAndUnknownNativeFrameCannotQualify()
	{
		Fixture f = new Fixture(true, 0);
		for (WorldSnapshot source : Arrays.asList(f.source.toBuilder().npcs(Collections.singletonList(npc(f.ref, ID, 8, "Unseen actor", 50))).build(),
			f.source.toBuilder().nativeContextKnown(false).build(), f.source.toBuilder().sceneKey(null).build(), f.source.toBuilder().plane(1).build()))
			unqualified(resolve(f.launch.toBuilder().additionalViews(Collections.singletonList(source)).build(), f.impact, f.projectile));
	}

	@Test
	public void originalHistoryActorRemainsAuthoritativeWhenLaterActorOrScopeChanged()
	{
		Fixture f = new Fixture(true, 0); ProjectileState p = f.projectile.toBuilder().sourceRef("npc:later:v8:g2")
			.sourceWorldViewId(8).sourcePlane(1).sourceName("Later actor")
			.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(f.projectile.getSourceHistory().getFirstObservedSource())
				.referenceChanged(true).scopeChanged(true).build()).build();
		SourceResolution resolution = resolve(f.launch.toBuilder().projectiles(Collections.singletonList(p)).build(), f.impact, p);
		SourceResolution.Candidate original = only(resolution); assertEquals(f.ref, original.getActorRef());
		assertEquals(Integer.valueOf(0), original.getWorldViewId()); assertEquals(SOURCE_SCENE, original.getSceneKey());
		assertEquals("POSSIBLE", original.getStrength()); assertTrue(resolution.isUnidentifiedPossible());
	}

	@Test
	public void aSuccessfulNullSourceReadIsNotAPositiveActorWitness()
	{
		Fixture f = new Fixture(true, 0); ProjectileSourceHistory.SourceObservation empty = f.projectile.getSourceHistory()
			.getFirstObservedSource().toBuilder().sourceRef(null).sourceActorKnown(true).sourceEvidence("UNKNOWN")
			.actorKind("NONE").sourceName("Unknown").sourceNpcId(null).build();
		ProjectileState p = f.projectile.toBuilder().sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(empty).build()).build();
		SourceResolution resolution = resolve(f.launch.toBuilder().projectiles(Collections.singletonList(p)).build(), f.impact, p);
		assertTrue(resolution.getCandidates().stream().noneMatch(c -> c.getActorRef() != null)); assertTrue(resolution.isUnidentifiedPossible());
	}

	@Test
	public void aRecordedProximityCandidateNeverGainsApiActorPrivilegesFromAdditionalViews()
	{
		Fixture f = new Fixture(true, 0); ProjectileSourceCandidate proximity = ProjectileSourceCandidate.builder()
			.actorRef(f.ref).npcId(ID).name("Unseen actor").worldViewId(0).plane(0).sceneKey(SOURCE_SCENE).build();
		ProjectileSourceHistory.SourceObservation first = f.projectile.getSourceHistory().getFirstObservedSource().toBuilder()
			.sourceRef(null).sourceEvidence("PROXIMITY").sourceWorldViewId(null).actorKind("NONE")
			.proximitySampleRetained(true).proximityCandidate(proximity).build();
		ProjectileState p = f.projectile.toBuilder().sourceRef(null).sourceEvidence("PROXIMITY").sourceWorldViewId(null)
			.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(first).build()).build();
		SourceResolution.Candidate source = only(resolve(f.launch.toBuilder().projectiles(Collections.singletonList(p)).build(), f.impact, p));
		assertEquals("POSSIBLE", source.getStrength());
		assertTrue(source.getEvidence().contains("RECORDED_PROXIMITY_CANDIDATE; not API source actor"));
		assertFalse(source.getEvidence().stream().anyMatch(e -> e.contains("original source-native sample")));
	}

	@Test
	public void aForgedCrossNativeSourceScopeCannotActivateTheWholeActorHpBound()
	{
		DamageMap map = DamageMap.load(new Gson()); Fixture f = new Fixture(true, 0);
		ProjectileSourceHistory.SourceObservation first = f.projectile.getSourceHistory().getFirstObservedSource().toBuilder()
			.sourceNpcId(5779).sourceName("Giant Mole").sourceObservedName("Giant Mole")
			.sourceCombatLevel(230).sourceBaseNpcId(5779).build();
		ProjectileState p = f.projectile.toBuilder().sourceNpcId(5779).sourceName("Giant Mole")
			.sourceObservedName("Giant Mole").sourceCombatLevel(230).sourceBaseNpcId(5779)
			.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(first).build()).build();
		Map<String, Object> rival = new LinkedHashMap<>(); rival.put("kind", "ITEM_CONSUMPTION_CANDIDATE"); rival.put("name", "Other captured cause");
		DamageRecord hit = hit(p).toBuilder().amount(22).attributionCandidates(Arrays.asList(witness(p), rival)).build();
		for (boolean foreign : Arrays.asList(false, true))
		{
			WorldSnapshot source = f.source.toBuilder().npcs(Collections.singletonList(npc(f.ref, 5779, foreign ? 8 : 0, "Giant Mole", 230)))
				.projectiles(Collections.singletonList(p)).build();
			WorldSnapshot launch = f.launch.toBuilder().projectiles(Collections.singletonList(p)).additionalViews(Collections.singletonList(source)).build();
			SourceResolution resolution = new RecordedSourceResolver(Arrays.asList(launch, f.impact), Collections.emptyList(), map).resolve(hit);
			if (foreign)
			{
				assertTrue(resolution.getCandidates().stream().anyMatch(c -> Integer.valueOf(5779).equals(c.getNpcId())));
				assertTrue(resolution.getExcludedCandidates() == null || resolution.getExcludedCandidates().isEmpty());
			}
			else assertTrue(resolution.getExcludedCandidates() != null && resolution.getExcludedCandidates().stream()
				.anyMatch(c -> Integer.valueOf(5779).equals(c.getNpcId())));
		}
	}

	@Test
	public void missingOrContradictoryOriginalModelFactsNeverBorrowTheSettledVariantForBounds()
	{
		DamageMap map = DamageMap.load(new Gson()); Fixture f = new Fixture(true, 0);
		ProjectileSourceHistory.SourceObservation original = f.projectile.getSourceHistory().getFirstObservedSource().toBuilder()
			.sourceNpcId(5779).sourceBaseNpcId(5779).sourceName("Giant Mole").sourceObservedName("Giant Mole")
			.sourceCombatLevel(230).build();
		ProjectileState baseline = f.projectile.toBuilder().sourceNpcId(5779).sourceBaseNpcId(5779)
			.sourceName("Giant Mole").sourceObservedName("Giant Mole").sourceCombatLevel(230).build();
		for (ProjectileSourceHistory.SourceObservation first : Arrays.asList(
			original.toBuilder().sourceCombatLevel(999).build(), original.toBuilder().sourceCombatLevel(null).build(),
			original.toBuilder().sourceNameKnown(false).build(), original.toBuilder().sourceObservedName("Another original name").build(),
			original.toBuilder().sourceNpcId(5778).build(), original.toBuilder().sourceNpcId(null).build(),
			original.toBuilder().sourceNormalWorldContextObserved(false).build(), original.toBuilder().sourceNormalWorldContextObserved(null).build(),
			original.toBuilder().sourceDefinitionEvidence(null).build(), original.toBuilder().sourceDefinitionEvidence("DEFINITION_CONTEXT_UNAVAILABLE").build(),
			original.toBuilder().sourceBaseNpcId(888888).build(), original.toBuilder().sourceDefinitionHasTransforms(true).build()))
		{
			ProjectileState p = baseline.toBuilder().sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(first).build()).build();
			WorldSnapshot source = f.source.toBuilder().projectiles(Collections.singletonList(p))
				.npcs(Collections.singletonList(npc(f.ref, 5779, 0, "Giant Mole", 230))).build();
			WorldSnapshot launch = f.launch.toBuilder().projectiles(Collections.singletonList(p)).additionalViews(Collections.singletonList(source)).build();
			SourceResolution resolution = new RecordedSourceResolver(Arrays.asList(launch, f.impact), Collections.emptyList(), map)
				.resolve(hit(p).toBuilder().amount(22).build());
			SourceResolution.Candidate retained = only(resolution); assertEquals(f.ref, retained.getActorRef());
			assertEquals(SOURCE_SCENE, retained.getSceneKey()); assertNull(retained.getFamilyKey());
			assertTrue(retained.getEvidence().stream().anyMatch(e -> e.startsWith("ORIGINAL_SOURCE_MODEL_UNQUALIFIED")));
			assertTrue(resolution.getExcludedCandidates() == null || resolution.getExcludedCandidates().isEmpty());
		}
		for (Integer originalLevel : Arrays.asList(null, 999))
		{
			ProjectileState p = baseline.toBuilder().sourceHistory(null).sourceCombatLevel(originalLevel).build();
			WorldSnapshot source = f.source.toBuilder().projectiles(Collections.singletonList(p))
				.npcs(Collections.singletonList(npc(f.ref, 5779, 0, "Giant Mole", 230))).build();
			WorldSnapshot launch = f.launch.toBuilder().projectiles(Collections.singletonList(p)).additionalViews(Collections.singletonList(source)).build();
			SourceResolution resolution = new RecordedSourceResolver(Arrays.asList(launch, f.impact), Collections.emptyList(), map)
				.resolve(hit(p).toBuilder().amount(22).build());
			assertEquals(f.ref, only(resolution).getActorRef()); assertNull(only(resolution).getFamilyKey());
			assertTrue(resolution.getExcludedCandidates() == null || resolution.getExcludedCandidates().isEmpty());
		}
	}

	@Test
	public void sharedOrReusedProjectileMarkersPreserveIdentityWithoutClaimingAUniqueAssignment()
	{
		Fixture f = new Fixture(true, 0);
		for (String kind : Arrays.asList("OBSERVATION_BATCH_WITHOUT_UNIQUE_ATTACK_LINK",
			"PROJECTILE_MAY_HAVE_ALREADY_IMPACTED", "OUTCOME_SURFACE_UNRESOLVED"))
		{
			Map<String, Object> marker = new LinkedHashMap<>(); marker.put("kind", kind); marker.put("observationCount", 2);
			for (int amount : Arrays.asList(5, 12))
			{
				DamageRecord hit = hit(f.projectile).toBuilder().amount(amount)
					.attributionCandidates(Arrays.asList(witness(f.projectile), marker)).build();
				SourceResolution resolution = new RecordedSourceResolver(Arrays.asList(f.launch, f.impact), Collections.emptyList(), null).resolve(hit);
				assertEquals(f.ref, only(resolution).getActorRef()); assertEquals(SOURCE_SCENE, only(resolution).getSceneKey());
				assertTrue(kind, resolution.isUnidentifiedPossible()); assertEquals(kind, "ALTERNATIVES", resolution.getStatus());
				assertEquals(amount, hit.getAmount());
			}
		}
	}

	@Test
	public void anUnknownProjectileCannotBorrowALaterReviewedOrdinaryAnimation() throws Exception
	{
		DamageMap map = DamageMap.load(new Gson()); Fixture f = new Fixture(true, 0);
		java.lang.reflect.Field field = CombatCatalog.class.getDeclaredField("root"); field.setAccessible(true);
		com.google.gson.JsonObject root = (com.google.gson.JsonObject) field.get(map.catalog());
		root.getAsJsonObject("reviewedBindings").getAsJsonObject("npcOverrides").add("12077", new Gson().fromJson(
			"{\"animations\":{\"999963\":{\"kind\":\"attack\",\"outcomeMechanism\":\"ORDINARY_ATTACK\"}}}", com.google.gson.JsonObject.class));
		ProjectileSourceHistory.SourceObservation first = f.projectile.getSourceHistory().getFirstObservedSource().toBuilder()
			.sourceNpcId(12077).sourceBaseNpcId(12077).sourceName("Phantom Muspah").sourceObservedName("Phantom Muspah")
			.sourceCombatLevel(741).build();
		ProjectileState p = f.projectile.toBuilder().sourceNpcId(12077).sourceBaseNpcId(12077)
			.sourceName("Phantom Muspah").sourceObservedName("Phantom Muspah").sourceCombatLevel(741)
			.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(first).build()).build();
		NpcState later = NpcState.builder().actorRef(f.ref).npcId(12077).baseNpcId(12077).name("Phantom Muspah")
			.combatLevel(741).worldViewId(0).plane(0).planeKnown(true).normalWorldContextObserved(true)
			.modelOverridesPresent(false).definitionEvidence("NPC_API_ID").definitionHasTransforms(false)
			.animationId(999963).build();
		WorldSnapshot source = f.source.toBuilder().npcs(Collections.singletonList(later)).projectiles(Collections.singletonList(p)).build();
		WorldSnapshot launch = f.launch.toBuilder().additionalViews(Collections.singletonList(source)).projectiles(Collections.singletonList(p)).build();
		SourceResolution resolution = new RecordedSourceResolver(Arrays.asList(launch, f.impact), Collections.emptyList(), map)
			.resolve(hit(p).toBuilder().amount(80).build());
		assertEquals(f.ref, only(resolution).getActorRef());
		assertTrue(resolution.getExcludedCandidates() == null || resolution.getExcludedCandidates().isEmpty());
		assertFalse(only(resolution).getEvidence().stream().anyMatch(e -> e.startsWith("QUALIFIED_MAXIMUM:component_bound:")));
	}
}
