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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.DeathRecordingV2;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.SourceResolution;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Held-out definitions test the explicit weak policy, never a content-specific death inference. */
public class ObservedLifecycleCooccurrenceTest
{
	private static final Gson GSON = new Gson();
	private static final int ID = 999941;

	public static JsonObject rule(String type)
	{
		return GSON.fromJson("{\"key\":\"heldout:" + type + "\",\"label\":\"Heldout source\",\"component\":\"heldout:terminal\","
			+ "\"selector\":{\"kind\":\"NPC\",\"ids\":[" + ID + "],\"names\":[\"Heldout source\"],\"combatLevels\":[10]},"
			+ "\"cue\":{\"type\":\"" + type + "\"},\"delivery\":\"TERMINAL_AREA\",\"origin\":\"SOURCE_AT_CUE\","
			+ "\"footprint\":{\"operator\":\"CHEBYSHEV_RADIUS\",\"radius\":1},"
			+ "\"interval\":{\"operator\":\"SAME_RECORDED_TICK_LIFECYCLE_COOCCURRENCE\"},"
			+ "\"observedOutcomeDomain\":\"ORDINARY_HP_OR_BLOCK_CALLBACK_ONLY\",\"observedHitsplatTypes\":[12,13,16,17,43],"
			+ "\"recipients\":\"OBSERVED_RECIPIENT_IN_FOOTPRINT\",\"lifetime\":\"RETAIN_OBSERVED_CUE\","
			+ "\"requiredObservations\":[\"SOURCE_ACTOR_IDENTITY_KNOWN\",\"SOURCE_DEFINITION_BOUND\",\"SOURCE_POSITION_KNOWN\","
			+ "\"IMPACT_POSITION_KNOWN\",\"SAME_NATIVE_SCOPE\",\"SAME_RECORDED_TICK\",\"KNOWN_LOCAL_HP_CALLBACK\","
			+ "\"NORMAL_WORLD_CONTEXT\",\"NO_MODEL_OVERRIDES_OBSERVED\",\"QUALIFIED_NPC_VARIANT\"],"
			+ "\"referenceKeys\":[],\"sourceRefs\":[\"Synthetic independently reviewed component\"]}", JsonObject.class);
	}

	public static JsonObject namespace(JsonObject... rules)
	{
		JsonObject root = new JsonObject(); root.addProperty("version", 1); root.add("rules", new JsonArray());
		JsonArray rows = new JsonArray(); for (JsonObject rule : rules) rows.add(rule); root.add("actorRules", rows); return root;
	}

	private static DamageRecord hit()
	{
		return DamageRecord.builder().direction("RECEIVED").defenderRef("player").impactTick(5).observedCycle(100)
			.observationSequence(50L).hitsplatTypeKnown(true).hitsplatType(16).amount(8)
			.impactSceneKey("room").impactWorldViewId(0).impactPlane(0).impactPositionKnown(true).impactX(101).impactY(201).build();
	}

	private static Map<String, Object> source()
	{
		return row("actorKind", "NPC", "npcId", ID, "name", "Heldout source", "combatLevel", 10,
			"definitionEvidence", "NPC_API_ID", "normalWorldContextObserved", true, "modelOverridesPresent", false,
			"worldPositionKnown", true, "planeKnown", true, "worldX", 100, "worldY", 200, "worldViewId", 0, "plane", 0);
	}

	private static ObservedEvent lifecycle(String type, int tick, int cycle, long sequence, Map<String, Object> source)
	{
		return ObservedEvent.builder().type(type).tick(tick).gameCycle(cycle).sequence(sequence).sceneKey("room")
			.actorRef("npc:original-lifetime").data(source).build();
	}

	private static ObservedEvent hp(DamageRecord hit)
	{
		return ObservedEvent.builder().type("HITSPLAT").tick(hit.getImpactTick()).gameCycle(hit.getObservedCycle())
			.sequence(hit.getObservationSequence()).sceneKey(hit.getImpactSceneKey()).actorRef("player")
			.data(row("receiverKind", "PLAYER", "amount", hit.getAmount(), "hitsplatType", hit.getHitsplatType(),
				"actorState", row("actorKind", "LOCAL_PLAYER", "worldPositionKnown", true, "planeKnown", true,
					"worldX", hit.getImpactX(), "worldY", hit.getImpactY(), "worldViewId", hit.getImpactWorldViewId(), "plane", hit.getImpactPlane()),
				"numericContext", row("observationKind", "STATIC_WORLD_AT_HP_CALLBACK", "localRecipientKnown", true,
					"observedTick", hit.getImpactTick(), "observedCycle", hit.getObservedCycle(), "worldTypes", Collections.emptyList(),
					"leagueType", 0, "evidence", row("worldTypes", "OBSERVED_CLIENT_WORLD_TYPES", "LEAGUE_TYPE", "OBSERVED_CLIENT_VARBIT")))).build();
	}

	private static ObservedMechanismResolver.Result resolve(JsonObject rules, DamageRecord hit, List<ObservedEvent> events)
	{
		return new ObservedActorMechanismResolver(MechanismRules.load(rules), Collections.emptyList(), events, Collections.emptyList()).resolve(hit);
	}

	@Test
	public void eitherRecordedCallbackOrderPreservesClocksAndNeverClaimsCause()
	{
		for (long sequence : Arrays.asList(49L, 51L)) for (String type : Arrays.asList("ACTOR_DEATH", "NPC_DESPAWNED"))
		{
			ObservedMechanismResolver.Result result = resolve(namespace(rule(type)), hit(), Arrays.asList(hp(hit()), lifecycle(type, 5, sequence == 49 ? 99 : 101, sequence, source())));
			assertEquals(1, result.getContributions().size());
			LegacyConstraintOracle.Hypothesis hypothesis = result.getContributions().get(0).getHypothesis();
			assertTrue(hypothesis.isCausalAssociationOnly()); assertNull(hypothesis.getConstraints());
			assertEquals("TERMINAL_AREA", hypothesis.getMechanism()); assertEquals("heldout:terminal", hypothesis.getDamageComponent());
			assertTrue(hypothesis.getCandidate().getEvidence().toString().contains("sequence=" + sequence));
			assertTrue(hypothesis.getCandidate().getEvidence().toString().contains("not necessarily at HP callback"));
			SourceResolution resolution = LegacyConstraintOracle.resolve(Collections.singletonList(hypothesis), 8, false, "fixture");
			assertEquals("ALTERNATIVES", resolution.getStatus()); assertTrue(resolution.isUnidentifiedPossible());
		}
	}

	@Test
	public void originalHpBindingRejectsMissingDuplicateAlteredAndLaterContext()
	{
		ObservedEvent cue = lifecycle("ACTOR_DEATH", 5, 101, 51, source());
		assertTrue(resolve(namespace(rule("ACTOR_DEATH")), hit(), Collections.singletonList(cue)).getContributions().isEmpty());
		assertTrue(resolve(namespace(rule("ACTOR_DEATH")), hit(), Arrays.asList(cue, hp(hit()), hp(hit()))).getContributions().isEmpty());
		for (DamageRecord mismatch : Arrays.asList(hit().toBuilder().amount(9).build(), hit().toBuilder().observedCycle(101).build(),
			hit().toBuilder().impactX(100).build(), hit().toBuilder().observationSequence(99L).build()))
			assertTrue(resolve(namespace(rule("ACTOR_DEATH")), mismatch, Arrays.asList(cue, hp(hit()))).getContributions().isEmpty());
		ObservedEvent exceptional = hp(hit());
		@SuppressWarnings("unchecked") Map<String, Object> context = (Map<String, Object>) exceptional.getData().get("numericContext");
		context.put("leagueType", 1);
		assertTrue(resolve(namespace(rule("ACTOR_DEATH")), hit(), Arrays.asList(cue, exceptional)).getContributions().isEmpty());
	}

	@Test
	public void missingSourceFactsNeverBorrowSettledModelGeometryOrContext()
	{
		for (String missing : Arrays.asList("name", "combatLevel", "definitionEvidence", "normalWorldContextObserved", "modelOverridesPresent",
			"worldPositionKnown", "planeKnown", "worldX", "worldY", "worldViewId", "plane"))
		{
			Map<String, Object> data = source(); data.remove(missing);
			assertTrue(missing, resolve(namespace(rule("ACTOR_DEATH")), hit(), Arrays.asList(hp(hit()), lifecycle("ACTOR_DEATH", 5, 101, 51, data))).getContributions().isEmpty());
		}
		for (Map<String, Object> changed : Arrays.asList(row("worldViewId", 7), row("worldViewId", -1), row("plane", -1),
			row("plane", 4), row("worldX", 110), row("name", "Other"), row("combatLevel", 11),
			row("normalWorldContextObserved", false), row("modelOverridesPresent", true), row("observationIssues", row("worldPosition", "failed"))))
		{
			Map<String, Object> data = source(); data.putAll(changed);
			assertTrue(resolve(namespace(rule("ACTOR_DEATH")), hit(), Arrays.asList(hp(hit()), lifecycle("ACTOR_DEATH", 5, 101, 51, data))).getContributions().isEmpty());
		}
	}

	@Test
	public void localPlainOutcomeAndExactTickAreMandatoryNoTerminalPrediction()
	{
		for (int type : Arrays.asList(3, 5, 6, 18, 67, 74, 999999))
		{
			DamageRecord hit = hit().toBuilder().hitsplatType(type).build();
			assertTrue(resolve(namespace(rule("ACTOR_DEATH")), hit, Arrays.asList(hp(hit), lifecycle("ACTOR_DEATH", 5, 101, 51, source()))).getContributions().isEmpty());
		}
		for (int tick : Arrays.asList(4, 6))
			assertTrue(resolve(namespace(rule("ACTOR_DEATH")), hit(), Arrays.asList(hp(hit()), lifecycle("ACTOR_DEATH", tick, 101, 51, source()))).getContributions().isEmpty());
		for (DamageRecord invalid : Arrays.asList(hit().toBuilder().direction("DEALT").build(), hit().toBuilder().defenderRef("other-player").build(),
			hit().toBuilder().hitsplatTypeKnown(false).build(), hit().toBuilder().amount(-8).build()))
			assertTrue(resolve(namespace(rule("ACTOR_DEATH")), invalid, Arrays.asList(hp(invalid), lifecycle("ACTOR_DEATH", 5, 101, 51, source()))).getContributions().isEmpty());
		assertTrue(resolve(namespace(rule("ACTOR_DEATH")), hit(), Arrays.asList(hp(hit()), lifecycle("NPC_DESPAWNED", 5, 101, 51, source()))).getContributions().isEmpty());
	}

	@Test
	public void directAreaCanBeIndependentButUnboundedOrForeignAreaCannot()
	{
		JsonObject rule = rule("ACTOR_DEATH"); rule.getAsJsonObject("footprint").addProperty("operator", "SOURCE_BOUNDS_EXPANDED");
		Map<String, Object> data = source(); data.put("worldPositionKnown", false); data.put("planeKnown", false); data.remove("plane");
		data.putAll(row("worldAreaKnown", true, "worldAreaWorldViewKnown", true, "worldAreaWorldViewId", 0,
			"worldArea", row("minX", 100, "minY", 200, "maxX", 100, "maxY", 200, "plane", 0), "observationIssues", row("worldPosition", "failed")));
		assertEquals(1, resolve(namespace(rule), hit(), Arrays.asList(hp(hit()), lifecycle("ACTOR_DEATH", 5, 101, 51, data))).getContributions().size());
		data.put("worldAreaWorldViewId", 7);
		assertTrue(resolve(namespace(rule), hit(), Arrays.asList(hp(hit()), lifecycle("ACTOR_DEATH", 5, 101, 51, data))).getContributions().isEmpty());
		data.put("worldAreaWorldViewId", 0); data.put("worldArea", row("minX", Integer.MIN_VALUE, "minY", 200, "maxX", Integer.MAX_VALUE, "maxY", 200, "plane", 0));
		assertTrue(resolve(namespace(rule), hit(), Arrays.asList(hp(hit()), lifecycle("ACTOR_DEATH", 5, 101, 51, data))).getContributions().isEmpty());
	}

	@Test
	public void deathAndDespawnReceiptsMergeOnlySameLifetimeComponent()
	{
		List<ObservedEvent> events = Arrays.asList(hp(hit()), lifecycle("ACTOR_DEATH", 5, 99, 49, source()), lifecycle("NPC_DESPAWNED", 5, 101, 51, source()));
		ObservedMechanismResolver.Result result = resolve(namespace(rule("ACTOR_DEATH"), rule("NPC_DESPAWNED")), hit(), events);
		assertEquals(1, result.getContributions().size());
		assertTrue(result.getContributions().get(0).getHypothesis().getCandidate().getEvidence().toString().contains("ACTOR_DEATH"));
		assertTrue(result.getContributions().get(0).getHypothesis().getCandidate().getEvidence().toString().contains("NPC_DESPAWNED"));
	}

	@Test
	public void malformedWeakGrammarCannotSilentlyBecomeOrderedOrBroadOutcome()
	{
		assertFalse(MechanismRules.load(namespace(rule("ACTOR_DEATH"))).hasOrderedActorRules());
		for (String mutation : Arrays.asList("delay", "status", "missing_local", "animation"))
		{
			JsonObject rule = rule("ACTOR_DEATH");
			if ("delay".equals(mutation)) rule.getAsJsonObject("interval").addProperty("minDelayTicks", 0);
			if ("status".equals(mutation)) rule.add("observedHitsplatTypes", GSON.fromJson("[5]", JsonArray.class));
			if ("missing_local".equals(mutation)) rule.add("requiredObservations", new JsonArray());
			if ("animation".equals(mutation)) rule.getAsJsonObject("cue").addProperty("type", "ANIMATION_CHANGED");
			MechanismRules rules = MechanismRules.load(namespace(rule)); assertTrue(rules.actorMatching(ID).isEmpty()); assertTrue(rules.actorIncomplete(ID));
		}
	}

	@Test
	public void independentTerminalComponentSurvivesOrdinaryExclusionAndPreservesRival()
	{
		LegacyConstraintOracle.Hypothesis terminal = resolve(namespace(rule("ACTOR_DEATH")), hit(),
			Arrays.asList(hp(hit()), lifecycle("ACTOR_DEATH", 5, 101, 51, source()))).getContributions().get(0).getHypothesis();
		AttributionConstraint bound = AttributionConstraint.builder().key("ordinary-only-five").maximum(5).qualification("HARD_BOUND")
			.mechanism("ORDINARY_ATTACK").damageComponent("SINGLE_HIT").worldMode("NORMAL")
			.requiredObservations(Collections.singletonList("ORDINARY_ATTACK_COMPONENT")).build();
		LegacyConstraintOracle.Hypothesis ordinary = LegacyConstraintOracle.Hypothesis.builder()
			.candidate(terminal.getCandidate().toBuilder().key("ordinary").build()).mechanism("ORDINARY_ATTACK")
			.damageComponent("SINGLE_HIT").worldMode("NORMAL").constraints(Collections.singletonList(bound))
			.observations(Collections.singletonMap("ORDINARY_ATTACK_COMPONENT", true)).build();
		LegacyConstraintOracle.Hypothesis rival = LegacyConstraintOracle.Hypothesis.builder().causalAssociationOnly(true)
			.candidate(terminal.getCandidate().toBuilder().key("rival").actorRef("npc:another-lifetime").name("Rival").build()).build();
		SourceResolution result = LegacyConstraintOracle.resolve(Arrays.asList(ordinary, terminal, rival), 8, false, "fixture");
		assertTrue(result.isUnidentifiedPossible()); assertEquals("ALTERNATIVES", result.getStatus());
		assertTrue(result.getCandidates().stream().anyMatch(c -> "npc:original-lifetime".equals(c.getActorRef())));
		assertTrue(result.getCandidates().stream().anyMatch(c -> "npc:another-lifetime".equals(c.getActorRef())));
		assertTrue(result.getExcludedCandidates().stream().anyMatch(c -> c.getReasons().toString().contains("MAXIMUM_EXCEEDED:ordinary-only-five")));
	}

	@Test
	public void sentinelClocksAndDifferentLifetimesNeverMergeByName()
	{
		for (ObservedEvent cue : Arrays.asList(lifecycle("ACTOR_DEATH", 5, -1, 51, source()), lifecycle("ACTOR_DEATH", 5, 101, 0, source())))
			assertTrue(resolve(namespace(rule("ACTOR_DEATH")), hit(), Arrays.asList(hp(hit()), cue)).getContributions().isEmpty());
		Map<String, Object> tagged = source(); tagged.put("name", "<col=00ffff>Heldout source</col>");
		ObservedEvent a = lifecycle("ACTOR_DEATH", 5, 101, 51, tagged);
		ObservedEvent b = ObservedEvent.builder().type("ACTOR_DEATH").tick(5).gameCycle(102).sequence(52).sceneKey("room")
			.actorRef("npc:reused-slot-new-lifetime").data(source()).build();
		assertEquals(2, resolve(namespace(rule("ACTOR_DEATH")), hit(), Arrays.asList(hp(hit()), a, b)).getContributions().size());
	}

	@Test
	public void completedTickTailDoesNotNeedTheLargeRetainedJournal()
	{
		LegacyWorldSnapshotRecorder recorder = new LegacyWorldSnapshotRecorder(100, GSON);
		for (int i = 0; i < 20000; i++) recorder.addObservation(ObservedEvent.builder().tick(4).sequence(i + 1L).type("UNRELATED").build());
		DamageRecord hit = hit().toBuilder().observationSequence(20050L).build();
		recorder.addObservation(hp(hit)); recorder.addObservation(lifecycle("ACTOR_DEATH", 5, 101, 20051, source()));
		List<ObservedEvent> tail = recorder.observationsAtTickForSourceResolution(5);
		assertEquals(2, tail.size());
		assertFalse(MechanismRules.load(namespace(rule("ACTOR_DEATH"))).hasOrderedActorRules());
		assertEquals(1, resolve(namespace(rule("ACTOR_DEATH")), hit, tail).getContributions().size());
	}

	@Test
	public void workLimitsPreserveUnknownInsteadOfClaimingMissingTerminalSources()
	{
		List<ObservedEvent> events = new ArrayList<>(); events.add(hp(hit()));
		for (int i = 0; i < 4097; i++) events.add(lifecycle("ACTOR_DEATH", 5, 101, 100 + i, source()));
		ObservedMechanismResolver.Result result = resolve(namespace(rule("ACTOR_DEATH")), hit(), events);
		assertTrue(result.isUnidentifiedPossible());
		assertTrue(result.getContributions().size() <= LegacyConstraintOracle.MAX_HYPOTHESES);
	}

	@Test
	public void originalFailureFlagsAreFrozenAndCannotBeClearedByLaterNestedMutation()
	{
		for (String key : Arrays.asList("worldPosition", "definitionContext", "normalWorldContextObserved", "worldTypes", "LEAGUE_TYPE"))
		{
			Map<String, Object> issues = row(key, "failed"), data = source(); data.put("observationIssues", issues);
			ObservedActorMechanismResolver resolver = new ObservedActorMechanismResolver(MechanismRules.load(namespace(rule("ACTOR_DEATH"))),
				Collections.emptyList(), Arrays.asList(hp(hit()), lifecycle("ACTOR_DEATH", 5, 101, 51, data)), Collections.emptyList());
			issues.clear();
			assertTrue(key, resolver.resolve(hit()).getContributions().isEmpty());
		}
		Map<String, Object> issues = row(), data = source(); data.put("observationIssues", issues);
		ObservedActorMechanismResolver resolver = new ObservedActorMechanismResolver(MechanismRules.load(namespace(rule("ACTOR_DEATH"))),
			Collections.emptyList(), Arrays.asList(hp(hit()), lifecycle("ACTOR_DEATH", 5, 101, 51, data)), Collections.emptyList());
		issues.put("worldPosition", "later failure");
		assertEquals(1, resolver.resolve(hit()).getContributions().size());
	}

	@Test
	public void omittedSameTickLifecycleCannotCloseASeparateRivalAsCertainCause()
	{
		List<DeathRecordingV2.Events.CaptureGap> gaps = Collections.singletonList(DeathRecordingV2.Events.CaptureGap.builder()
			.tick(5).channel("rawCallbacks").omittedObservations(1).reason("OBSERVATION_BUFFER_LIMIT").build());
		ObservedMechanismResolver.Result result = new ObservedActorMechanismResolver(MechanismRules.load(namespace(rule("ACTOR_DEATH"))),
			Collections.emptyList(), Collections.singletonList(hp(hit())), gaps).resolve(hit());
		assertTrue(result.getContributions().isEmpty()); assertTrue(result.isUnidentifiedPossible());
		LegacyConstraintOracle.Hypothesis rival = LegacyConstraintOracle.Hypothesis.builder().candidate(SourceResolution.Candidate.builder()
			.key("rival").actorRef("npc:rival").name("Rival").sceneKey("room").worldViewId(0).plane(0).strength("STRONG").build()).build();
		SourceResolution resolution = LegacyConstraintOracle.resolve(Collections.singletonList(rival), 8, result.isUnidentifiedPossible(), "fixture");
		assertTrue(resolution.isUnidentifiedPossible()); assertEquals("ALTERNATIVES", resolution.getStatus());
		assertEquals("Rival", resolution.getCandidates().get(0).getName());
	}

	@Test
	public void missingRequiredSourceReadKeepsAnonymousUnknownButNoNamedClue()
	{
		for (String missing : Arrays.asList("name", "combatLevel", "worldPositionKnown", "normalWorldContextObserved"))
		{
			Map<String, Object> data = source(); data.remove(missing);
			ObservedMechanismResolver.Result result = resolve(namespace(rule("ACTOR_DEATH")), hit(),
				Arrays.asList(hp(hit()), lifecycle("ACTOR_DEATH", 5, 101, 51, data)));
			assertTrue(missing, result.getContributions().isEmpty()); assertTrue(missing, result.isUnidentifiedPossible());
		}
		Map<String, Object> foreign = source(); foreign.remove("name"); foreign.put("worldViewId", 7);
		ObservedMechanismResolver.Result result = resolve(namespace(rule("ACTOR_DEATH")), hit(),
			Arrays.asList(hp(hit()), lifecycle("ACTOR_DEATH", 5, 101, 51, foreign)));
		assertTrue(result.getContributions().isEmpty()); assertFalse(result.isUnidentifiedPossible());
	}

	@Test
	public void optionalReviewedTerminalMaximumUsesExistingEngineAndDoesNotTaintExcludedCases()
	{
		for (int amount : Arrays.asList(3, 4))
		{
			DamageRecord hit = hit().toBuilder().amount(amount).build(); JsonObject rule = rule("ACTOR_DEATH"); rule.addProperty("perHitMaximum", 3);
			ObservedMechanismResolver.Result actual = resolve(namespace(rule), hit, Arrays.asList(hp(hit), lifecycle("ACTOR_DEATH", 5, 101, 51, source())));
			assertEquals(1, actual.getContributions().size());
			LegacyConstraintOracle.Hypothesis terminal = actual.getContributions().get(0).getHypothesis();
			assertEquals(Integer.valueOf(3), terminal.getConstraints().get(0).getMaximum());
			SourceResolution result = LegacyConstraintOracle.resolve(Collections.singletonList(terminal), amount, actual.isUnidentifiedPossible(), "fixture");
			if (amount == 3) { assertEquals(1, result.getCandidates().size()); assertTrue(result.isUnidentifiedPossible()); }
			else
			{
				assertTrue(result.getCandidates().isEmpty());
				assertTrue(result.getExcludedCandidates().get(0).getReasons().toString().contains("MAXIMUM_EXCEEDED:lifecycle-per-callback:"));
				LegacyConstraintOracle.Hypothesis rival = LegacyConstraintOracle.Hypothesis.builder().candidate(terminal.getCandidate().toBuilder()
					.key("rival").name("Rival").actorRef("npc:rival").build()).build();
				SourceResolution withRival = LegacyConstraintOracle.resolve(Arrays.asList(terminal, rival), amount, actual.isUnidentifiedPossible(), "fixture");
				assertEquals("IDENTIFIED", withRival.getStatus()); assertFalse(withRival.isUnidentifiedPossible());
				assertEquals("Rival", withRival.getCandidates().get(0).getName());
			}
		}
		JsonObject death = rule("ACTOR_DEATH"), removal = rule("NPC_DESPAWNED"); death.addProperty("perHitMaximum", 3);
		ObservedMechanismResolver.Result union = resolve(namespace(death, removal), hit(), Arrays.asList(hp(hit()),
			lifecycle("ACTOR_DEATH", 5, 99, 49, source()), lifecycle("NPC_DESPAWNED", 5, 101, 51, source())));
		assertNull("An unbounded alternative must not borrow a smaller death ceiling", union.getContributions().get(0).getHypothesis().getConstraints());
	}

	@Test
	public void malformedTerminalMaximumIsNotIgnoredByRuntimeLoader()
	{
		for (String maximum : Arrays.asList("-1", "1000001", "true", "null", "3.5"))
		{
			JsonObject row = rule("ACTOR_DEATH"); row.add("perHitMaximum", GSON.fromJson(maximum, com.google.gson.JsonElement.class));
			MechanismRules rules = MechanismRules.load(namespace(row));
			assertTrue(maximum, rules.actorMatching(ID).isEmpty()); assertTrue(maximum, rules.actorIncomplete(ID));
		}
	}

	private static Map<String, Object> row(Object... fields)
	{
		Map<String, Object> out = new LinkedHashMap<>();
		for (int i = 0; i < fields.length; i += 2) out.put((String) fields[i], fields[i + 1]);
		return out;
	}
}
