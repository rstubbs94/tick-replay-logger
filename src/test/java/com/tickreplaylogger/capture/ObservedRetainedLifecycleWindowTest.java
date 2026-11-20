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
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Anonymous components exercise a recorded analysis window, never a predicted detonation. */
public class ObservedRetainedLifecycleWindowTest
{
	private static final Gson GSON = new Gson();
	private static Map<String, Object> row(Object... pairs)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
		return result;
	}
	private static JsonObject rule()
	{
		JsonObject rule = ObservedLifecycleCooccurrenceTest.rule("ACTOR_DEATH");
		rule.add("interval", GSON.fromJson("{operator:'STRICT_RETAINED_LIFECYCLE_CUE_WINDOW',minDelayTicks:0,maxDelayTicks:6,clock:'RECORDED_TICKS_ANALYSIS_WINDOW'}", JsonObject.class));
		rule.getAsJsonObject("footprint").addProperty("semantics", "POSSIBLE_SOURCE_VICINITY");
		JsonArray required = new JsonArray();
		for (com.google.gson.JsonElement item : rule.getAsJsonArray("requiredObservations"))
			if (!"SAME_RECORDED_TICK".equals(item.getAsString())) required.add(item);
		required.add("CUE_BEFORE_HIT"); required.add("RETAINED_CUE_WINDOW_COMPLETE");
		rule.add("requiredObservations", required); return rule;
	}
	private static DamageRecord hit()
	{
		return DamageRecord.builder().direction("RECEIVED").defenderRef("player").impactTick(10).observedCycle(300)
			.observationSequence(100L).hitsplatTypeKnown(true).hitsplatType(16).amount(50)
			.impactSceneKey("room").impactWorldViewId(0).impactPlane(0).impactPositionKnown(true).impactX(101).impactY(201).build();
	}
	private static Map<String, Object> source()
	{
		return row("actorKind", "NPC", "npcId", 999941, "name", "Heldout source", "combatLevel", 10,
			"definitionEvidence", "NPC_API_ID", "normalWorldContextObserved", true, "modelOverridesPresent", false,
			"worldPositionKnown", true, "planeKnown", true, "worldX", 100, "worldY", 200, "worldViewId", 0, "plane", 0);
	}
	private static ObservedEvent cue(int tick, int cycle, long sequence)
	{
		return ObservedEvent.builder().type("ACTOR_DEATH").tick(tick).gameCycle(cycle).sequence(sequence).sceneKey("room")
			.actorRef("npc:original").data(source()).build();
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
	private static ObservedMechanismResolver.Result resolve(JsonObject rule, DamageRecord hit, List<ObservedEvent> events,
		List<DeathRecordingV2.Events.CaptureGap> gaps, boolean complete)
	{
		return new ObservedActorMechanismResolver(MechanismRules.load(ObservedLifecycleCooccurrenceTest.namespace(rule)),
			Collections.emptyList(), events, gaps, new LegacyObservedHpCallbackContext(events), complete).resolve(hit);
	}
	private static ObservedMechanismResolver.Result resolve(JsonObject rule, DamageRecord hit, ObservedEvent cue)
	{ return resolve(rule, hit, Arrays.asList(cue, hp(hit)), Collections.emptyList(), true); }

	@Test public void declaredRecordedWindowRetainsOriginalCueWithoutServerTimingOrCertainty()
	{
		for (int tick : Arrays.asList(4, 9, 10))
		{
			ObservedMechanismResolver.Result result = resolve(rule(), hit(), cue(tick, 100, 50));
			assertEquals(1, result.getContributions().size());
			LegacyConstraintOracle.Hypothesis hypothesis = result.getContributions().get(0).getHypothesis();
			assertTrue(hypothesis.isCausalAssociationOnly()); assertNull(hypothesis.getConstraints());
			assertEquals("npc:original", hypothesis.getCandidate().getActorRef());
			assertTrue(hypothesis.getCandidate().getEvidence().toString().contains("Recorded-tick analysis window 0..6"));
			SourceResolution finalResult = LegacyConstraintOracle.resolve(Collections.singletonList(hypothesis), 50, false, "fixture");
			assertEquals("ALTERNATIVES", finalResult.getStatus()); assertTrue(finalResult.isUnidentifiedPossible());
		}
		for (ObservedEvent invalid : Arrays.asList(cue(3, 100, 50), cue(11, 100, 50), cue(-1, 100, 50),
			cue(9, -1, 50), cue(9, 301, 50), cue(9, 100, 100), cue(10, 301, 101)))
			assertTrue(resolve(rule(), hit(), invalid).getContributions().isEmpty());
	}

	@Test public void missingOriginalHpOrSourceFactsNeverBorrowLaterFacts()
	{
		assertTrue(resolve(rule(), hit(), Collections.singletonList(cue(9, 100, 50)), Collections.emptyList(), true).getContributions().isEmpty());
		for (String field : Arrays.asList("definitionEvidence", "normalWorldContextObserved", "modelOverridesPresent", "name", "combatLevel", "worldViewId"))
		{
			ObservedEvent cue = cue(9, 100, 50); cue.getData().remove(field);
			assertTrue(field, resolve(rule(), hit(), cue).getContributions().isEmpty());
		}
		for (Map<String, Object> changed : Arrays.asList(row("worldViewId", 7), row("worldViewId", -1), row("plane", 4),
			row("modelOverridesPresent", true), row("worldX", 120)))
		{
			ObservedEvent cue = cue(9, 100, 50); cue.getData().putAll(changed);
			assertTrue(resolve(rule(), hit(), cue).getContributions().isEmpty());
		}
		ObservedEvent originalHp = hp(hit()); originalHp.getData().remove("numericContext");
		assertTrue(resolve(rule(), hit(), Arrays.asList(cue(9, 100, 50), originalHp), Collections.emptyList(), true).getContributions().isEmpty());
	}

	@Test public void onlyOriginalOrdinaryLocalHpAndBlockAreAdmitted()
	{
		for (int type : Arrays.asList(3, 5, 6, 18, 67, 74, 999999))
			assertTrue(resolve(rule(), hit().toBuilder().hitsplatType(type).build(), cue(9, 100, 50)).getContributions().isEmpty());
		DamageRecord zero = hit().toBuilder().amount(0).hitsplatType(12).build();
		assertEquals(1, resolve(rule(), zero, cue(9, 100, 50)).getContributions().size());
		ObservedEvent originalHp = hp(hit());
		@SuppressWarnings("unchecked") Map<String, Object> context = (Map<String, Object>) originalHp.getData().get("numericContext");
		context.put("leagueType", 1);
		assertTrue(resolve(rule(), hit(), Arrays.asList(cue(9, 100, 50), originalHp), Collections.emptyList(), true).getContributions().isEmpty());
	}

	@Test public void optionalMaximumExcludesOnlyThisComponentAndPreservesRawAmountAndRival()
	{
		JsonObject rule = rule(); rule.addProperty("perHitMaximum", 50);
		LegacyConstraintOracle.Hypothesis allowed = resolve(rule, hit(), cue(9, 100, 50)).getContributions().get(0).getHypothesis();
		assertEquals(1, LegacyConstraintOracle.resolve(Collections.singletonList(allowed), 50, true, "fixture").getCandidates().size());
		DamageRecord hit = hit().toBuilder().amount(51).build();
		LegacyConstraintOracle.Hypothesis terminal = resolve(rule, hit, cue(9, 100, 50)).getContributions().get(0).getHypothesis();
		LegacyConstraintOracle.Hypothesis rival = LegacyConstraintOracle.Hypothesis.builder().candidate(SourceResolution.Candidate.builder()
			.key("other").name("Other recorded cause").actorRef("npc:original").npcId(999941)
			.sceneKey("room").worldViewId(0).plane(0).strength("POSSIBLE").build())
			.mechanism("ORDINARY_ATTACK").damageComponent("heldout:independent-attack").causalAssociationOnly(true).build();
		SourceResolution result = LegacyConstraintOracle.resolve(Arrays.asList(terminal, rival), 51, true, "fixture");
		assertEquals(51, hit.getAmount()); assertEquals(1, result.getCandidates().size());
		assertEquals("Other recorded cause", result.getCandidates().get(0).getName()); assertEquals(1, result.getExcludedCandidates().size());
	}

	@Test public void partialDuplicateAndGapWindowsCannotEnableTheRetainedHint()
	{
		List<ObservedEvent> events = Arrays.asList(cue(9, 100, 50), hp(hit()));
		ObservedMechanismResolver.Result partial = resolve(rule(), hit(), events, Collections.emptyList(), false);
		assertTrue(partial.getContributions().isEmpty()); assertTrue(partial.isUnidentifiedPossible());
		List<DeathRecordingV2.Events.CaptureGap> gaps = Collections.singletonList(DeathRecordingV2.Events.CaptureGap.builder()
			.tick(8).channel("rawCallbacks").omittedObservations(1).build());
		assertTrue(resolve(rule(), hit(), events, gaps, true).getContributions().isEmpty());
		assertTrue(resolve(rule(), hit(), Arrays.asList(cue(9, 100, 50), cue(9, 101, 50), hp(hit())), Collections.emptyList(), true).getContributions().isEmpty());
	}

	@Test public void strictWindowGrammarNeverFallsThroughToPermissiveOrderedRules()
	{
		MechanismRules valid = MechanismRules.load(ObservedLifecycleCooccurrenceTest.namespace(rule()));
		assertFalse(valid.hasOrderedActorRules()); assertEquals(6, valid.maximumRetainedLifecycleDelay());
		for (String mutation : Arrays.asList("clock", "semantics", "limit", "negative", "despawn", "missing_before", "status"))
		{
			JsonObject rule = rule();
			if ("clock".equals(mutation)) rule.getAsJsonObject("interval").remove("clock");
			if ("semantics".equals(mutation)) rule.getAsJsonObject("footprint").remove("semantics");
			if ("limit".equals(mutation)) rule.getAsJsonObject("interval").addProperty("maxDelayTicks", 33);
			if ("negative".equals(mutation)) rule.getAsJsonObject("interval").addProperty("minDelayTicks", -1);
			if ("despawn".equals(mutation)) rule.getAsJsonObject("cue").addProperty("type", "NPC_DESPAWNED");
			if ("missing_before".equals(mutation)) rule.add("requiredObservations", new JsonArray());
			if ("status".equals(mutation)) rule.add("observedHitsplatTypes", GSON.fromJson("[5]", JsonArray.class));
			assertTrue(mutation, MechanismRules.load(ObservedLifecycleCooccurrenceTest.namespace(rule)).actorMatching(999941).isEmpty());
		}
		ObservedEvent after = cue(10, 301, 101);
		assertEquals(1, resolve(ObservedLifecycleCooccurrenceTest.rule("ACTOR_DEATH"), hit(), after).getContributions().size());
		assertTrue(resolve(rule(), hit(), after).getContributions().isEmpty());
	}

	@Test public void retrievalStopsAtWindowBoundaryWithoutCopyingOldUnrelatedCallbacks() throws Exception
	{
		LegacyWorldSnapshotRecorder recorder = new LegacyWorldSnapshotRecorder(100, GSON);
		for (int i = 0; i < 20000; i++) recorder.addObservation(ObservedEvent.builder().tick(1).sequence(i + 1L).type("UNRELATED").build());
		ObservedEvent death = cue(9, 100, 20050), hp = hp(hit().toBuilder().observationSequence(20100L).build());
		recorder.addObservation(death); recorder.addObservation(hp);
		Field raw = LegacyWorldSnapshotRecorder.class.getDeclaredField("observations"); raw.setAccessible(true);
		@SuppressWarnings("unchecked") java.util.Deque<ObservedEvent> stored = (java.util.Deque<ObservedEvent>) raw.get(recorder);
		ArrayDeque<ObservedEvent> bounded = new ArrayDeque<ObservedEvent>(stored)
		{
			@Override public Iterator<ObservedEvent> iterator() { throw new AssertionError("Forward/full raw scan"); }
			@Override public Iterator<ObservedEvent> descendingIterator()
			{
				Iterator<ObservedEvent> iterator = super.descendingIterator();
				return new Iterator<ObservedEvent>()
				{
					int count;
					public boolean hasNext() { return iterator.hasNext(); }
					public ObservedEvent next() { if (++count > 3) throw new AssertionError("Past window boundary"); return iterator.next(); }
				};
			}
		};
		raw.set(recorder, bounded);
		LegacyWorldSnapshotRecorder.LifecycleObservationBatch batch = recorder.retainedLifecycleObservationsForSourceResolution(10, 6, Collections.singletonList(hp));
		assertTrue(batch.isComplete()); assertEquals(Arrays.asList(death, hp), batch.getEvents());
	}

	@Test public void denseAndUnorderedRetainedWindowsFailClosedWithoutDroppingCurrentTail()
	{
		LegacyWorldSnapshotRecorder recorder = new LegacyWorldSnapshotRecorder(100, GSON);
		for (int i = 0; i < 9000; i++) recorder.addObservation(ObservedEvent.builder().tick(9).sequence(i + 1L).type("UNRELATED").build());
		ObservedEvent hp = hp(hit()); recorder.addObservation(hp);
		LegacyWorldSnapshotRecorder.LifecycleObservationBatch batch = recorder.retainedLifecycleObservationsForSourceResolution(10, 6, Collections.singletonList(hp));
		assertFalse(batch.isComplete()); assertEquals(Collections.singletonList(hp), batch.getEvents());
		recorder.addObservation(cue(8, 100, 20000));
		assertFalse(recorder.retainedLifecycleObservationsForSourceResolution(10, 6, Collections.singletonList(hp)).isComplete());
	}
}
