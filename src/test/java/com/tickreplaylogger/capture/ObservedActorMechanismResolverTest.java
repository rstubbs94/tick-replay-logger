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
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Arbitrary definitions exercise actual past cues, independent of ordinary actor attacks. */
public class ObservedActorMechanismResolverTest
{
	private static final Gson GSON = new Gson();
	private static final int ID = 999954;

	private static JsonObject declaration(String cue, String delivery, String footprint)
	{
		JsonObject rule = GSON.fromJson("{\"key\":\"held-out-area\",\"label\":\"Held-out source\",\"component\":\"independent-area-hp\","
			+ "\"selector\":{\"kind\":\"NPC\",\"ids\":[" + ID + "],\"names\":[\"Held-out source\"],\"combatLevels\":[10]},"
			+ "\"cue\":{\"type\":\"" + cue + "\"},\"delivery\":\"" + delivery + "\",\"origin\":\"SOURCE_AT_CUE\","
			+ "\"footprint\":{\"operator\":\"" + footprint + "\",\"radius\":2},"
			+ "\"interval\":{\"operator\":\"OBSERVED_CUE_WINDOW\",\"minDelayTicks\":2,\"maxDelayTicks\":2},"
			+ "\"recipients\":\"OBSERVED_RECIPIENT_IN_FOOTPRINT\",\"lifetime\":\"RETAIN_OBSERVED_CUE\","
			+ "\"requiredObservations\":[\"SOURCE_ACTOR_IDENTITY_KNOWN\",\"SOURCE_DEFINITION_BOUND\",\"SOURCE_POSITION_KNOWN\","
			+ "\"IMPACT_POSITION_KNOWN\",\"SAME_NATIVE_SCOPE\",\"CUE_BEFORE_HIT\",\"NORMAL_WORLD_CONTEXT\","
			+ "\"NO_MODEL_OVERRIDES_OBSERVED\",\"QUALIFIED_NPC_VARIANT\"],\"referenceKeys\":[],\"sourceRefs\":[\"Synthetic primary facts\"]}", JsonObject.class);
		if ("ANIMATION_CHANGED".equals(cue))
		{
			JsonArray ids = new JsonArray(); ids.add(999953); rule.getAsJsonObject("cue").add("animationIds", ids);
		}
		return rule;
	}

	private static JsonObject namespace(JsonObject rule)
	{
		JsonObject root = new JsonObject(); root.addProperty("version", 1); root.add("rules", new JsonArray());
		JsonArray actorRules = new JsonArray(); actorRules.add(rule); root.add("actorRules", actorRules); return root;
	}

	private static DamageRecord hit()
	{
		return DamageRecord.builder().impactTick(5).impactSceneKey("room").impactWorldViewId(0).impactPlane(0)
			.impactPositionKnown(true).impactX(102).impactY(202).observedCycle(55).observationSequence(50L)
			.amount(80).hitsplatTypeKnown(true).hitsplatType(16).attackerName("Unknown").build();
	}

	private static Map<String, Object> metadata()
	{
		return row("actorKind", "NPC", "npcId", ID, "name", "Held-out source", "combatLevel", 10,
			"definitionEvidence", "NPC_API_ID", "normalWorldContextObserved", true, "modelOverridesPresent", false,
			"worldPositionKnown", true, "planeKnown", true, "worldX", 100, "worldY", 200, "worldViewId", 0, "plane", 0,
			"animationId", 999953, "interaction", row("actorRef", "another-player"));
	}

	private static ObservedEvent event(String type, Map<String, Object> metadata)
	{
		return ObservedEvent.builder().tick(3).gameCycle(30).sequence(20L).sceneKey("room").actorRef("npc:lifetime-one")
			.type(type).data(metadata).build();
	}

	private static WorldSnapshot frame(int tick)
	{
		return WorldSnapshot.builder().tick(tick).sceneKey("room").worldViewId(0).plane(0).nativeContextKnown(true)
			.npcs(Collections.emptyList()).captureCoverage(Collections.singletonMap("npcs", "available")).build();
	}

	private static ObservedMechanismResolver.Result resolve(JsonObject rule, DamageRecord hit, List<ObservedEvent> events,
		List<WorldSnapshot> history, List<DeathRecordingV2.Events.CaptureGap> gaps)
	{
		return new ObservedActorMechanismResolver(MechanismRules.load(namespace(rule)), history, events, gaps).resolve(hit);
	}

	@Test
	public void actualCueCanAffectBystandersAndSurvivesSourceRemoval()
	{
		for (String type : Arrays.asList("ANIMATION_CHANGED", "ACTOR_DEATH"))
		{
			String delivery = "ACTOR_DEATH".equals(type) ? "TERMINAL_AREA" : "AREA_IMPACT";
			ObservedMechanismResolver.Result result = resolve(declaration(type, delivery, "CHEBYSHEV_RADIUS"), hit(),
				Collections.singletonList(event(type, metadata())), Collections.singletonList(frame(3)), Collections.emptyList());
			assertEquals(1, result.getContributions().size()); assertFalse(result.isUnidentifiedPossible());
			LegacyConstraintOracle.Hypothesis hypothesis = result.getContributions().get(0).getHypothesis();
			assertEquals(delivery, hypothesis.getMechanism()); assertEquals("independent-area-hp", hypothesis.getDamageComponent());
			assertNull(hypothesis.getConstraints()); assertEquals("npc:lifetime-one", hypothesis.getCandidate().getActorRef());
			assertTrue(hypothesis.isCausalAssociationOnly());
			com.tickreplaylogger.model.world.SourceResolution resolution = LegacyConstraintOracle.resolve(
				Collections.singletonList(hypothesis), hit().getAmount(), result.isUnidentifiedPossible(), "fixture");
			assertEquals("ALTERNATIVES", resolution.getStatus()); assertTrue(resolution.isUnidentifiedPossible());
			assertEquals("npc:lifetime-one", resolution.getCandidates().get(0).getActorRef());
			assertEquals(80, hit().getAmount());
		}
	}

	@Test
	public void sourceBoundsRequireDirectAreaInsteadOfSizeOrPointAnchor()
	{
		JsonObject rule = declaration("ANIMATION_CHANGED", "AREA_IMPACT", "SOURCE_BOUNDS_EXPANDED");
		Map<String, Object> data = metadata(); data.put("size", 20); data.put("worldX", 102); data.put("worldY", 202);
		data.putAll(row("worldAreaKnown", true, "worldAreaWorldViewKnown", true, "worldAreaWorldViewId", 0,
			"worldArea", row("minX", 100, "minY", 200, "maxX", 104, "maxY", 204, "plane", 0)));
		DamageRecord edge = hit().toBuilder().impactX(106).impactY(206).build();
		ObservedMechanismResolver.Result actual = resolve(rule, edge, Collections.singletonList(event("ANIMATION_CHANGED", data)),
			Collections.singletonList(frame(3)), Collections.emptyList());
		assertEquals(1, actual.getContributions().size()); assertFalse(actual.isUnidentifiedPossible());
		data.remove("worldArea");
		ObservedMechanismResolver.Result missing = resolve(rule, edge, Collections.singletonList(event("ANIMATION_CHANGED", data)),
			Collections.singletonList(frame(3)), Collections.emptyList());
		assertEquals(1, missing.getContributions().size()); assertTrue(missing.isUnidentifiedPossible());
	}

	@Test
	public void independentDirectAreaAndDefinitionSurviveAnUnavailableWorldPointRead()
	{
		Map<String, Object> data = metadata();
		data.putAll(row("worldPositionKnown", false, "planeKnown", false, "worldX", null, "worldY", null, "plane", null,
			"observationIssues", row("worldPosition", "RuntimeException"), "worldAreaKnown", true,
			"worldAreaWorldViewKnown", true, "worldAreaWorldViewId", 0,
			"worldArea", row("minX", 100, "minY", 200, "maxX", 102, "maxY", 202, "plane", 0)));
		ObservedMechanismResolver.Result result = resolve(declaration("ANIMATION_CHANGED", "AREA_IMPACT", "SOURCE_BOUNDS_EXPANDED"), hit(),
			Collections.singletonList(event("ANIMATION_CHANGED", data)), Collections.singletonList(frame(3)), Collections.emptyList());
		assertEquals(1, result.getContributions().size()); assertFalse(result.isUnidentifiedPossible());
		assertTrue(result.getContributions().get(0).isDefinitionBound());
	}

	@Test
	public void laterActorPositionCannotReplaceOriginalCueGeometry()
	{
		WorldSnapshot later = frame(5).toBuilder().npcs(Collections.singletonList(NpcState.builder().npcId(ID)
			.actorRef("npc:lifetime-one").worldPositionKnown(true).worldX(900).worldY(900).worldViewId(0).plane(0).build())).build();
		ObservedMechanismResolver.Result result = resolve(declaration("ANIMATION_CHANGED", "AREA_IMPACT", "CHEBYSHEV_RADIUS"), hit(),
			Collections.singletonList(event("ANIMATION_CHANGED", metadata())), Arrays.asList(frame(3), later), Collections.emptyList());
		assertEquals(1, result.getContributions().size()); assertFalse(result.isUnidentifiedPossible());
	}

	@Test
	public void wrongScopeWrongDefinitionAndOutsideGeometryCannotBecomeCandidates()
	{
		for (Map<String, Object> replacement : Arrays.asList(row("worldViewId", 9), row("plane", 1),
			row("name", "Another definition"), row("combatLevel", 99), row("worldX", 500),
			row("normalWorldContextObserved", false), row("modelOverridesPresent", true)))
		{
			Map<String, Object> data = metadata(); data.putAll(replacement);
			ObservedMechanismResolver.Result result = resolve(declaration("ANIMATION_CHANGED", "AREA_IMPACT", "CHEBYSHEV_RADIUS"), hit(),
				Collections.singletonList(event("ANIMATION_CHANGED", data)), Collections.singletonList(frame(3)), Collections.emptyList());
			assertTrue(result.getContributions().isEmpty());
		}
	}

	@Test
	public void incompleteReadsKeepPossibilityAndCannotCreateCompleteAttribution()
	{
		for (String unavailable : Arrays.asList("name", "combatLevel", "definitionEvidence", "normalWorldContextObserved",
			"modelOverridesPresent", "worldPositionKnown", "worldViewId", "animationId"))
		{
			Map<String, Object> data = metadata(); data.remove(unavailable);
			ObservedMechanismResolver.Result result = resolve(declaration("ANIMATION_CHANGED", "AREA_IMPACT", "CHEBYSHEV_RADIUS"), hit(),
				Collections.singletonList(event("ANIMATION_CHANGED", data)), Collections.singletonList(frame(3)), Collections.emptyList());
			assertEquals(unavailable, 1, result.getContributions().size()); assertTrue(unavailable, result.isUnidentifiedPossible());
		}
	}

	@Test
	public void unknownNativeScopeCannotUseCoordinateDistanceToExcludeACandidate()
	{
		Map<String, Object> data = metadata(); data.remove("worldViewId"); data.put("worldX", 900);
		ObservedMechanismResolver.Result result = resolve(declaration("ANIMATION_CHANGED", "AREA_IMPACT", "CHEBYSHEV_RADIUS"), hit(),
			Collections.singletonList(event("ANIMATION_CHANGED", data)), Collections.singletonList(frame(3)), Collections.emptyList());
		assertEquals(1, result.getContributions().size()); assertTrue(result.isUnidentifiedPossible());
		Map<String, Object> unavailable = metadata(); unavailable.put("worldViewId", 9);
		unavailable.put("plane", 1); unavailable.put("planeKnown", false);
		unavailable.put("observationIssues", row("worldViewId", "RuntimeException"));
		ObservedMechanismResolver.Result placeholders = resolve(declaration("ANIMATION_CHANGED", "AREA_IMPACT", "CHEBYSHEV_RADIUS"), hit(),
			Collections.singletonList(event("ANIMATION_CHANGED", unavailable)), Collections.singletonList(frame(3)), Collections.emptyList());
		assertEquals(1, placeholders.getContributions().size()); assertTrue(placeholders.isUnidentifiedPossible());
	}

	@Test
	public void sameTickUsesActualEventOrderAndFutureEventsCannotCauseEarlierHits()
	{
		JsonObject rule = declaration("ANIMATION_CHANGED", "AREA_IMPACT", "CHEBYSHEV_RADIUS");
		rule.getAsJsonObject("interval").addProperty("minDelayTicks", 0); rule.getAsJsonObject("interval").addProperty("maxDelayTicks", 0);
		for (long sequence : Arrays.asList(49L, 50L, 51L))
		{
			ObservedEvent cue = ObservedEvent.builder().tick(5).gameCycle(55).sequence(sequence).sceneKey("room")
				.actorRef("npc:lifetime-one").type("ANIMATION_CHANGED").data(metadata()).build();
			ObservedMechanismResolver.Result result = resolve(rule, hit(), Collections.singletonList(cue),
				Collections.singletonList(frame(5)), Collections.emptyList());
			assertEquals(sequence == 49 ? 1 : 0, result.getContributions().size());
		}
	}

	@Test
	public void disappearingNpcIsNotAnAttackOrTerminalCue()
	{
		ObservedMechanismResolver.Result result = resolve(declaration("ACTOR_DEATH", "TERMINAL_AREA", "CHEBYSHEV_RADIUS"), hit(),
			Collections.singletonList(event("NPC_DESPAWNED", metadata())), Collections.singletonList(frame(3)), Collections.emptyList());
		assertTrue(result.getContributions().isEmpty()); assertFalse(result.isUnidentifiedPossible());
	}

	@Test
	public void observedSourceWithMissingOrTruncatedEventWindowRetainsUnknown()
	{
		WorldSnapshot relevant = frame(3).toBuilder().npcs(Collections.singletonList(NpcState.builder().npcId(ID).build())).build();
		JsonObject rule = declaration("ANIMATION_CHANGED", "AREA_IMPACT", "CHEBYSHEV_RADIUS");
		assertTrue(resolve(rule, hit(), null, Collections.singletonList(relevant), null).isUnidentifiedPossible());
		List<DeathRecordingV2.Events.CaptureGap> gaps = Collections.singletonList(DeathRecordingV2.Events.CaptureGap.builder()
			.tick(3).channel("rawCallbacks").omittedObservations(1).reason("OBSERVATION_BUFFER_LIMIT").build());
		ObservedMechanismResolver.Result result = resolve(rule, hit(), Collections.singletonList(event("ANIMATION_CHANGED", metadata())),
			Collections.singletonList(relevant), gaps);
		assertEquals(1, result.getContributions().size()); assertTrue(result.isUnidentifiedPossible());
		assertFalse(resolve(rule, hit(), Collections.emptyList(), Collections.singletonList(relevant), Collections.emptyList()).isUnidentifiedPossible());
	}

	@Test
	public void failedCallbackDefinitionReadIsNotAnObservedAbsenceOfRelevantCues()
	{
		WorldSnapshot relevant = frame(3).toBuilder().npcs(Collections.singletonList(NpcState.builder().npcId(ID).build())).build();
		Map<String, Object> failed = metadata(); failed.remove("npcId");
		ObservedMechanismResolver.Result result = resolve(declaration("ANIMATION_CHANGED", "AREA_IMPACT", "CHEBYSHEV_RADIUS"), hit(),
			Collections.singletonList(event("ANIMATION_CHANGED", failed)), Collections.singletonList(relevant), Collections.emptyList());
		assertTrue(result.isUnidentifiedPossible()); assertTrue(result.getContributions().isEmpty());
	}

	@Test
	public void newCueIdentityAndSignatureCannotBorrowOrdinaryOutcomeRules() throws Exception
	{
		DamageMap map = LegacyCombatCatalog.isolate(DamageMap.load(GSON));
		Field rootField = CombatCatalog.class.getDeclaredField("root"); rootField.setAccessible(true);
		rootField.set(map.catalog(), ((JsonObject) rootField.get(map.catalog())).deepCopy());
		JsonObject root = (JsonObject) rootField.get(map.catalog());
		root.add("mechanismRules", namespace(declaration("ANIMATION_CHANGED", "AREA_IMPACT", "CHEBYSHEV_RADIUS")));
		JsonObject ordinary = GSON.fromJson("{\"key\":\"only-ordinary-venom\",\"selector\":{\"kind\":\"NPC_ORDINARY_ATTACK\",\"ids\":[" + ID
			+ "]},\"mechanism\":\"ORDINARY_ATTACK\",\"positiveHitsplatTypes\":[5],\"zeroHitsplatTypes\":[5],\"complete\":true,"
			+ "\"requiredObservations\":[\"QUALIFIED_ORDINARY_COMPONENT\"],\"sourceRefs\":[\"Synthetic ordinary-only rule\"]}", JsonObject.class);
		root.getAsJsonObject("damageCompatibility").getAsJsonArray("rules").add(ordinary);
		for (String index : Arrays.asList("mechanismRuleIndex", "damageCompatibilityIndex"))
		{
			Field field = LegacyCombatCatalog.class.getDeclaredField(index); field.setAccessible(true); field.set(LegacyCombatCatalog.from(map.catalog()), null);
		}
		SourceResolution result = new RecordedSourceResolver(Arrays.asList(frame(3), frame(5)), Collections.emptyList(), map,
			Collections.singletonList(event("ANIMATION_CHANGED", metadata())), Collections.emptyList()).resolve(hit());
		assertTrue(result.getCandidates().stream().anyMatch(c -> "Held-out source".equals(c.getName())));
		assertTrue(result.getExcludedCandidates() == null
			|| result.getExcludedCandidates().stream().noneMatch(c -> "Held-out source".equals(c.getName())));
	}

	@Test
	public void unknownSemanticFieldsCannotBecomeUnconditionalRules()
	{
		for (String section : Arrays.asList("selector", "cue", "footprint", "interval"))
		{
			JsonObject invalid = declaration("ANIMATION_CHANGED", "AREA_IMPACT", "CHEBYSHEV_RADIUS");
			invalid.getAsJsonObject(section).addProperty("unobservedCondition", 1);
			assertTrue(MechanismRules.load(namespace(invalid)).actorMatching(ID).isEmpty());
			assertTrue(MechanismRules.load(namespace(invalid)).actorIncomplete(ID));
		}
		JsonObject invalid = declaration("ANIMATION_CHANGED", "AREA_IMPACT", "CHEBYSHEV_RADIUS");
		invalid.getAsJsonArray("requiredObservations").add("UNKNOWN_MODIFIER");
		assertTrue(MechanismRules.load(namespace(invalid)).actorMatching(ID).isEmpty());
	}

	@Test
	public void eventWorkLimitsCannotTurnTruncatedInputIntoNoSource()
	{
		List<ObservedEvent> events = new ArrayList<>();
		for (int i = 0; i < 4097; i++) events.add(event("ANIMATION_CHANGED", metadata()));
		ObservedMechanismResolver.Result result = resolve(declaration("ANIMATION_CHANGED", "AREA_IMPACT", "CHEBYSHEV_RADIUS"), hit(),
			events, Collections.singletonList(frame(3)), Collections.emptyList());
		assertTrue(result.isUnidentifiedPossible()); assertTrue(result.getContributions().size() <= SourceEvidenceResolver.MAX_HYPOTHESES);
	}

	private static Map<String, Object> row(Object... fields)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
		return result;
	}
}
