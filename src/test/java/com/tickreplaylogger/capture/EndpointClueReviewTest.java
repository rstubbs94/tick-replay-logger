/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
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
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.ProjectileSourceHistory;
import com.tickreplaylogger.model.world.ProjectileState;
import com.tickreplaylogger.model.world.SourceResolution;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.junit.Test;
import static org.junit.Assert.*;

/** Independent adversarial cases for recorded endpoints, not server impact assertions. */
public class EndpointClueReviewTest
{
	private static final int NPC = 990124, PROJECTILE = 990125, X = 3100, Y = 3200;
	private static final Gson GSON = new Gson();
	private static final String SCENE = "observed-native", REF = "npc:held-out:7";

	private CombatCatalog catalog() throws Exception
	{
		CombatCatalog catalog = new CombatCatalog();
		JsonObject raw = GSON.fromJson("{\"hitsplatRules\":{\"rules\":{\"16\":{\"surface\":\"HP\"},\"12\":{\"surface\":\"BLOCK\"}}}}", JsonObject.class);
		Field field = CombatCatalog.class.getDeclaredField("root");
		field.setAccessible(true); field.set(catalog, raw);
		return catalog;
	}

	private ObservedProjectileEndpointResolver.Rules rules()
	{
		return new ObservedProjectileEndpointResolver.Rules(GSON.fromJson("{\"version\":1,\"rules\":[{"
			+ "\"key\":\"held-out-endpoint\",\"name\":\"Held-out endpoint\",\"referenceKeys\":[],"
			+ "\"sourceNpcIds\":[990124],\"sourceNames\":[\"Held-out creature\"],\"projectileIds\":[990125],"
			+ "\"observationKind\":\"API_PROJECTILE_CALLBACK\",\"temporalRelation\":\"CLIENT_END_CYCLE_COOCCURRENCE\","
			+ "\"analysisWindow\":{\"minOffsetCycles\":-30,\"maxOffsetCycles\":30,\"qualification\":\"ANALYSIS_POLICY_NOT_SERVER_TIMING\"},"
			+ "\"geometry\":{\"operator\":\"EXACT_RECORDED_DESTINATION_TILE\",\"scope\":\"POSSIBLE_ENDPOINT_ASSOCIATION\"},"
			+ "\"qualification\":\"POSSIBLE_SOURCE_HINT\",\"unidentifiedPossible\":true,\"recipients\":\"OBSERVED_LOCAL_PLAYER\","
			+ "\"observedOutcomeDomain\":\"ORDINARY_HP_OR_BLOCK_CALLBACK_ONLY\",\"observedHitsplatTypes\":[12,13,16,17,43],"
			+ "\"provenance\":{\"reviewedFixture\":true}}]}", JsonObject.class));
	}

	private ProjectileSourceHistory.SourceObservation source(long id, String evidence)
	{
		return ProjectileSourceHistory.SourceObservation.builder().observationId(id).projectileId(PROJECTILE)
			.observedTick(9).observedCycle(270).contextEpoch(1L).sampleEvidence(evidence).actorKind("NPC")
			.sourceEvidence("ACTOR").sourceActorKnown(true).sourceNameKnown(true)
			.sourceNormalWorldContextObserved(true).sourceRef(REF).sourceNpcId(NPC)
			.sourceName("Held-out creature").sourceObservedName("Held-out creature")
			.sourceWorldViewId(7).sourcePlane(1).sourceDefinitionEvidence("NPC_API_ID").build();
	}

	private ProjectileState state(long id, int tick, int cycle, int x, String evidence)
	{
		return ProjectileState.builder().observationId(id).projectileId(PROJECTILE).observedTick(tick).observedCycle(cycle)
			.endCycle(300).destinationKnown(true).destinationEvidence("PROJECTILE_API_TARGET_POINT")
			.endWorldX(x).endWorldY(Y).destinationPlane(1).targetPlane(1)
			.targetWorldViewId(7).targetLocalWorldViewId(7)
			.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(source(id, evidence)).build()).build();
	}

	private ObservedEvent callback(long sequence, int tick, int cycle, ProjectileState state)
	{
		return ObservedEvent.builder().sequence(sequence).tick(tick).gameCycle(cycle).sceneKey(SCENE)
			.type(sequence == 1 ? "PROJECTILE_FIRST_SEEN" : "PROJECTILE_TARGET_CHANGED")
			.data(Collections.singletonMap("projectile", state)).build();
	}

	private DamageRecord hit()
	{
		return DamageRecord.builder().direction("RECEIVED").defenderRef("player").impactTick(10)
			.impactSceneKey(SCENE).impactWorldViewId(7).impactPlane(1).impactPositionKnown(true).impactX(X).impactY(Y)
			.observationSequence(10L).observedCycle(300).hitsplatTypeKnown(true).hitsplatType(16).amount(40).build();
	}

	private ObservedMechanismResolver.Result resolve(List<ObservedEvent> observations, DamageRecord hit,
		Function<Long, SourceResolution.Candidate> lookup) throws Exception
	{
		return new ObservedProjectileEndpointResolver(rules(), observations, catalog(), true, lookup).resolve(hit);
	}

	private List<ObservedEvent> callbackOrigin(long id)
	{
		return new ArrayList<>(Arrays.asList(callback(1, 9, 270, state(id, 9, 270, X, "MOVED_CALLBACK")),
			callback(2, 10, 295, state(id, 10, 295, X, "MOVED_CALLBACK"))));
	}

	@Test public void latestPreHitRetargetInvalidatesOldEndpointAndPostHitMatchingCannotReviveIt() throws Exception
	{
		List<ObservedEvent> observations = callbackOrigin(42);
		assertEquals(1, resolve(observations, hit(), null).getContributions().size());
		observations.add(callback(3, 10, 296, state(42, 10, 296, X + 1, "MOVED_CALLBACK")));
		observations.add(callback(11, 10, 301, state(42, 10, 301, X, "MOVED_CALLBACK")));
		assertTrue(resolve(observations, hit(), null).getContributions().isEmpty());
	}

	@Test public void newestPreHitMissingOrForeignDestinationCannotBorrowAnOldMatchingOwner() throws Exception
	{
		for (ProjectileState changed : Arrays.asList(state(42, 10, 296, X, "MOVED_CALLBACK").toBuilder().destinationKnown(false).build(),
			state(42, 10, 296, X, "MOVED_CALLBACK").toBuilder().targetWorldViewId(null).build(),
			state(42, 10, 296, X, "MOVED_CALLBACK").toBuilder().targetLocalWorldViewId(8).build()))
		{
			List<ObservedEvent> observations = callbackOrigin(42);
			observations.add(callback(3, 10, 296, changed));
			assertTrue(resolve(observations, hit(), null).getContributions().isEmpty());
		}
	}

	@Test public void identicalEndpointsFromSeparateObservedInstancesStaySeparateWitnesses() throws Exception
	{
		List<ObservedEvent> observations = callbackOrigin(42);
		observations.add(callback(3, 9, 270, state(43, 9, 270, X, "MOVED_CALLBACK")));
		observations.add(callback(4, 10, 295, state(43, 10, 295, X, "MOVED_CALLBACK")));
		ObservedMechanismResolver.Result result = resolve(observations, hit(), null);
		assertEquals(2, result.getContributions().size());
		assertNotEquals(result.getContributions().get(0).getHypothesis().getCandidate().getKey(),
			result.getContributions().get(1).getHypothesis().getCandidate().getKey());
		assertTrue(result.isUnidentifiedPossible());
	}

	@Test public void originalTickSampleRequiresIndependentMatchingNativeSourceScene() throws Exception
	{
		List<ObservedEvent> observations = Collections.singletonList(callback(2, 10, 295, state(42, 10, 295, X, "TICK_SAMPLE")));
		SourceResolution.Candidate actualSource = SourceResolution.Candidate.builder().key("original-native-source")
			.name("Held-out creature").actorRef(REF).npcId(NPC).worldViewId(7).plane(1).sceneKey(SCENE).strength("POSSIBLE").build();
		assertTrue(resolve(observations, hit(), null).getContributions().isEmpty());
		assertEquals(1, resolve(observations, hit(), id -> actualSource).getContributions().size());
		assertTrue(resolve(observations, hit(), id -> actualSource.toBuilder().sceneKey("foreign").build()).getContributions().isEmpty());
		assertTrue(resolve(observations, hit(), id -> actualSource.toBuilder().actorRef("npc:replacement").build()).getContributions().isEmpty());
	}

	@Test public void firstAvailablePreHitApiActorCanFollowAnUnavailableInitialSourceButCannotFollowTheHit() throws Exception
	{
		ProjectileSourceHistory.SourceObservation unknown = ProjectileSourceHistory.SourceObservation.builder()
			.observationId(42L).projectileId(PROJECTILE).observedTick(9).observedCycle(260).contextEpoch(1L)
			.sampleEvidence("TICK_SAMPLE").actorKind("NONE").sourceEvidence("UNKNOWN").sourceActorKnown(true).build();
		ProjectileSourceHistory history = ProjectileSourceHistory.builder().version(1).firstObservedSource(unknown)
			.firstActorSource(source(42, "MOVED_CALLBACK")).build();
		List<ObservedEvent> observations = new ArrayList<>(Arrays.asList(
			callback(1, 9, 270, state(42, 9, 270, X, "MOVED_CALLBACK").toBuilder().sourceHistory(history).build()),
			callback(2, 10, 295, state(42, 10, 295, X, "MOVED_CALLBACK").toBuilder().sourceHistory(history).build())));
		assertEquals(1, resolve(observations, hit(), null).getContributions().size());
		ProjectileSourceHistory changed = ProjectileSourceHistory.builder().version(1).firstObservedSource(unknown)
			.firstActorSource(source(42, "MOVED_CALLBACK")).actorChanged(true).build();
		observations.set(1, callback(2, 10, 295, state(42, 10, 295, X, "MOVED_CALLBACK").toBuilder().sourceHistory(changed).build()));
		assertTrue(resolve(observations, hit(), null).getContributions().isEmpty());
		ProjectileSourceHistory postHit = ProjectileSourceHistory.builder().version(1).firstObservedSource(unknown)
			.firstActorSource(source(42, "MOVED_CALLBACK").toBuilder().observedTick(10).observedCycle(301).build()).build();
		observations.set(1, callback(2, 10, 295, state(42, 10, 295, X, "MOVED_CALLBACK").toBuilder().sourceHistory(postHit).build()));
		assertTrue(resolve(observations, hit(), null).getContributions().isEmpty());
	}

	@Test public void knownPreHitSourceNameConflictCannotHideBehindUnchangedImmutableHistoryFlags() throws Exception
	{
		List<ObservedEvent> observations = callbackOrigin(42);
		ProjectileState changed = state(42, 10, 296, X, "MOVED_CALLBACK").toBuilder()
			.sourceActorKnown(true).sourceRef(REF).sourceNpcId(NPC).sourceWorldViewId(7).sourcePlane(1)
			.sourceNameKnown(true).sourceObservedName("Different observed creature").build();
		observations.add(callback(3, 10, 296, changed));
		assertTrue(resolve(observations, hit(), null).getContributions().isEmpty());
	}

	@Test public void authoredCycleWindowHasExactBoundariesWithoutClaimingAnArrival() throws Exception
	{
		for (int offset : new int[] { -30, 30 })
		{
			List<ObservedEvent> observations = callbackOrigin(42);
			ProjectileState changed = state(42, 10, 295, X, "MOVED_CALLBACK").toBuilder().endCycle(300 - offset).build();
			observations.set(1, callback(2, 10, 295, changed));
			ObservedMechanismResolver.Result result = resolve(observations, hit(), null);
			assertEquals(1, result.getContributions().size());
			assertTrue(result.getContributions().get(0).getHypothesis().getCandidate().getEvidence().toString().contains("arrival, cause"));
		}
		for (int offset : new int[] { -31, 31 })
		{
			List<ObservedEvent> observations = callbackOrigin(42);
			observations.set(1, callback(2, 10, 295, state(42, 10, 295, X, "MOVED_CALLBACK").toBuilder().endCycle(300 - offset).build()));
			assertTrue(resolve(observations, hit(), null).getContributions().isEmpty());
		}
	}

	@Test public void excludedOrdinaryComponentDoesNotEraseIndependentPossibleEndpointOrLendItsBound() throws Exception
	{
		ObservedMechanismResolver.Result result = resolve(callbackOrigin(42), hit(), null);
		assertEquals(1, result.getContributions().size());
		SourceEvidenceResolver.Hypothesis endpoint = result.getContributions().get(0).getHypothesis();
		assertNull(endpoint.getConstraints());
		SourceEvidenceResolver.Hypothesis excludedOrdinary = SourceEvidenceResolver.Hypothesis.builder()
			.candidate(endpoint.getCandidate().toBuilder().key("ordinary-component").strength("STRONG").build())
			.eligibilityExclusions(Collections.singletonList("MAXIMUM_EXCEEDED:ordinary-component:40 > 32")).build();
		SourceResolution resolution = SourceEvidenceResolver.resolve(Arrays.asList(excludedOrdinary, endpoint), 40,
			result.isUnidentifiedPossible(), "synthetic-review");
		assertEquals(1, resolution.getCandidates().size());
		assertEquals(REF, resolution.getCandidates().get(0).getActorRef());
		assertEquals("POSSIBLE", resolution.getCandidates().get(0).getStrength());
		assertEquals(1, resolution.getExcludedCandidates().size());
		assertTrue(resolution.isUnidentifiedPossible());
	}
}
