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
import com.tickreplaylogger.model.world.HazardState;
import com.tickreplaylogger.model.world.PlayerState;
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

/** Held-out IDs test recorded contact history independently from named encounters. */
public class ObservedContactHistoryTest
{
	private static final Gson GSON = new Gson();
	private static final int ID = 999955;

	private static JsonObject declaration(String kind, String anchor, int min, int max)
	{
		return GSON.fromJson("{\"key\":\"delayed-fixture\",\"label\":\"Earlier patch\","
			+ "\"selector\":{\"kind\":\"" + kind + "\",\"ids\":[" + ID + "]},\"delivery\":\"CONTACT\","
			+ "\"footprint\":{\"operator\":\"TILE\"},\"interval\":{\"operator\":\"OBSERVED_CONTACT_HISTORY\","
			+ "\"anchor\":\"" + anchor + "\",\"minDelayTicks\":" + min + ",\"maxDelayTicks\":" + max + "},"
			+ "\"requiredObservations\":[\"IMPACT_POSITION_KNOWN\",\"SOURCE_POSITION_KNOWN\",\"SAME_NATIVE_SCOPE\",\"ACTIVE_INTERVAL_OBSERVED\"],"
			+ "\"referenceKeys\":[],\"sourceRefs\":[\"Synthetic explicit timing evidence\"]}", JsonObject.class);
	}

	private static MechanismRules rules(JsonObject declaration)
	{
		JsonObject root = new JsonObject(); root.addProperty("version", 1);
		JsonArray list = new JsonArray(); list.add(declaration); root.add("rules", list);
		return MechanismRules.load(root);
	}

	private static HazardState surface(String kind)
	{
		return HazardState.builder().kind("GRAPHIC".equals(kind) ? "graphicsObject" : "groundObject").id(ID)
			.worldPositionKnown(true).worldX(100).worldY(200).worldViewId(0).plane(0)
			.state("active").damaging(true).sourceEligibility("ELIGIBLE_REFERENCE_SURFACE")
			.objectKey(50L).startCycle(20).finished(false).build();
	}

	private static WorldSnapshot frame(int tick, int playerX, List<HazardState> surfaces)
	{
		return WorldSnapshot.builder().tick(tick).sceneKey("native-room").worldViewId(0).plane(0)
			.gameCycle(tick * 10).gameCycleKnown(true).nativeContextKnown(true)
			.player(PlayerState.builder().worldPositionKnown(true).planeKnown(true).worldViewId(0)
				.worldX(playerX).worldY(200).plane(0).build()).hazards(surfaces)
			.captureCoverage(Collections.singletonMap("graphicsAndMappedHazards", "available"))
			.hazardCoverage(row("graphicsStatus", "available", "graphicsReadFailures", 0, "unpositionedGraphics", 0, "unavailableColumns", 0,
				"graphicsScope", "EXPOSED_NATIVE_PLANE_GRAPHICS", "mappedObjectsStatus", "available",
				"objectScanCompleted", true, "mappedObjectReadFailures", 0, "mappedObjectsScope", "EXPOSED_NATIVE_PLANE")).build();
	}

	private static DamageRecord hit()
	{
		return DamageRecord.builder().impactTick(5).impactSceneKey("native-room").impactWorldViewId(0).impactPlane(0)
			.impactPositionKnown(true).impactX(101).impactY(200).observedCycle(55).hitsplatTypeKnown(true).hitsplatType(16)
			.amount(7).attackerName("Unknown").build();
	}

	private static ObservedMechanismResolver.Result resolve(MechanismRules rules, DamageRecord hit, List<WorldSnapshot> history)
	{
		return ObservedMechanismResolver.resolve(rules, hit, frame(5, 101, Collections.emptyList()), null,
			history, Collections.emptyMap());
	}

	@Test
	public void earlierRecordedContactSurvivesSourceRemovalAndCurrentOffTilePosition()
	{
		for (String kind : Arrays.asList("OBJECT", "GRAPHIC"))
		{
			ObservedMechanismResolver.Result result = resolve(rules(declaration(kind, "CONTACT_SAMPLE", 2, 2)), hit(),
				Arrays.asList(frame(2, 101, Collections.emptyList()), frame(3, 100, Collections.singletonList(surface(kind))),
					frame(4, 101, Collections.emptyList()), frame(5, 101, Collections.emptyList())));
			assertEquals(1, result.getContributions().size());
			assertFalse(result.isUnidentifiedPossible());
			assertEquals("CONTACT", result.getContributions().get(0).getHypothesis().getMechanism());
			assertTrue(result.getContributions().get(0).getHypothesis().getCandidate().getEvidence().get(0).contains("contactTick=3"));
			assertNull(result.getContributions().get(0).getHypothesis().getCandidate().getActorRef());
			LegacyConstraintOracle.Hypothesis hypothesis = result.getContributions().get(0).getHypothesis();
			assertTrue("Complete history remains recorded co-location, not a cause token", hypothesis.isCausalAssociationOnly());
			com.tickreplaylogger.model.world.SourceResolution resolution = LegacyConstraintOracle.resolve(
				Collections.singletonList(hypothesis), hit().getAmount(), result.isUnidentifiedPossible(), "fixture");
			assertEquals("ALTERNATIVES", resolution.getStatus());
			assertTrue(resolution.isUnidentifiedPossible());
			assertEquals("Earlier patch", resolution.getCandidates().get(0).getName());
		}
	}

	@Test
	public void delayRangeUsesExplicitInclusiveBounds()
	{
		List<WorldSnapshot> history = Arrays.asList(frame(1, 101, Collections.emptyList()),
			frame(2, 100, Collections.singletonList(surface("OBJECT"))), frame(3, 100, Collections.singletonList(surface("OBJECT"))),
			frame(4, 100, Collections.singletonList(surface("OBJECT"))), frame(5, 101, Collections.emptyList()));
		assertEquals(2, resolve(rules(declaration("OBJECT", "CONTACT_SAMPLE", 2, 3)), hit(), history).getContributions().size());
		assertEquals(1, resolve(rules(declaration("OBJECT", "CONTACT_SAMPLE", 3, 3)), hit(), history).getContributions().size());
		assertTrue(resolve(rules(declaration("OBJECT", "CONTACT_SAMPLE", 4, 4)), hit(), history).getContributions().isEmpty());
	}

	@Test
	public void onsetIsNotRestartedByEveryContactSample()
	{
		List<WorldSnapshot> history = Arrays.asList(frame(1, 101, Collections.emptyList()),
			frame(2, 100, Collections.singletonList(surface("OBJECT"))), frame(3, 100, Collections.singletonList(surface("OBJECT"))),
			frame(4, 100, Collections.singletonList(surface("OBJECT"))), frame(5, 101, Collections.emptyList()));
		ObservedMechanismResolver.Result onset = resolve(rules(declaration("OBJECT", "CONTACT_ONSET", 1, 3)), hit(), history);
		assertEquals(1, onset.getContributions().size());
		assertTrue(onset.getContributions().get(0).getHypothesis().getCandidate().getEvidence().get(0).contains("contactTick=2"));
		assertFalse(onset.isUnidentifiedPossible());
		ObservedMechanismResolver.Result tooLate = resolve(rules(declaration("OBJECT", "CONTACT_ONSET", 1, 2)), hit(), history);
		assertTrue(tooLate.getContributions().isEmpty());
		assertFalse(tooLate.isUnidentifiedPossible());
	}

	@Test
	public void overlappingGraphicsCannotRestartAnUnprovenInstanceTimer()
	{
		HazardState original = surface("GRAPHIC");
		HazardState overlap = HazardState.builder().kind("graphicsObject").id(ID).worldPositionKnown(true)
			.worldX(100).worldY(200).worldViewId(0).plane(0).state("active").damaging(true)
			.sourceEligibility("ELIGIBLE_REFERENCE_SURFACE").startCycle(21).finished(false).build();
		ObservedMechanismResolver.Result result = resolve(rules(declaration("GRAPHIC", "CONTACT_ONSET", 1, 3)), hit(),
			Arrays.asList(frame(1, 101, Collections.emptyList()), frame(2, 100, Collections.singletonList(original)),
				frame(3, 100, Arrays.asList(original, overlap)), frame(4, 100, Arrays.asList(original, overlap))));
		assertEquals(1, result.getContributions().size());
		assertTrue(result.isUnidentifiedPossible());
	}

	@Test
	public void separatelyRecordedGraphicIdentitiesHaveIndependentContactOnsets()
	{
		HazardState original = HazardState.builder().kind("graphicsObject").id(ID).graphicObservationId(71L)
			.worldPositionKnown(true).worldX(100).worldY(200).worldViewId(0).plane(0).state("active").damaging(true)
			.sourceEligibility("ELIGIBLE_REFERENCE_SURFACE").startCycle(20).finished(false).build();
		HazardState overlap = HazardState.builder().kind("graphicsObject").id(ID).graphicObservationId(72L)
			.worldPositionKnown(true).worldX(100).worldY(200).worldViewId(0).plane(0).state("active").damaging(true)
			.sourceEligibility("ELIGIBLE_REFERENCE_SURFACE").startCycle(21).finished(false).build();
		ObservedMechanismResolver.Result result = resolve(rules(declaration("GRAPHIC", "CONTACT_ONSET", 1, 3)), hit(),
			Arrays.asList(frame(1, 101, Collections.emptyList()), frame(2, 100, Collections.singletonList(original)),
				frame(3, 100, Arrays.asList(original, overlap)), frame(4, 100, Arrays.asList(original, overlap))));
		assertEquals(2, result.getContributions().size());
		assertFalse(result.isUnidentifiedPossible());
	}

	@Test
	public void firstRetainedContactAndGapsCannotInventAnOnset()
	{
		for (List<WorldSnapshot> history : Arrays.asList(
			Collections.singletonList(frame(3, 100, Collections.singletonList(surface("OBJECT")))),
			Arrays.asList(frame(1, 101, Collections.emptyList()), frame(3, 100, Collections.singletonList(surface("OBJECT"))))))
		{
			ObservedMechanismResolver.Result result = resolve(rules(declaration("OBJECT", "CONTACT_ONSET", 2, 2)), hit(), history);
			assertTrue(result.getContributions().isEmpty()); assertTrue(result.isUnidentifiedPossible());
		}
	}

	@Test
	public void relevantCurrentSurfaceOrCallbackWithNoHistoryCannotClaimAnEmptyWindow()
	{
		MechanismRules rules = rules(declaration("OBJECT", "CONTACT_SAMPLE", 2, 4));
		WorldSnapshot impact = frame(5, 100, Collections.singletonList(surface("OBJECT")));
		ObservedMechanismResolver.Result fromCurrent = ObservedMechanismResolver.resolve(rules, hit(), impact, null,
			Collections.emptyList(), Collections.emptyMap());
		assertTrue(fromCurrent.isUnidentifiedPossible()); assertTrue(fromCurrent.getContributions().isEmpty());
		DamageRecord callback = hit().toBuilder().observedHazardContacts(Collections.singletonList(row("kind", "OBJECT_SURFACE",
			"objectId", ID, "sceneKey", "native-room", "worldViewId", 0, "plane", 0))).build();
		ObservedMechanismResolver.Result fromCallback = resolve(rules, callback, Collections.emptyList());
		assertTrue(fromCallback.isUnidentifiedPossible()); assertTrue(fromCallback.getContributions().isEmpty());
	}

	@Test
	public void failedChannelIsNotAnEmptyScanAndUnknownPlayerPositionIsNotZero()
	{
		WorldSnapshot known = frame(3, 100, Collections.singletonList(surface("OBJECT")));
		for (WorldSnapshot bad : Arrays.asList(known.toBuilder().hazards(null).build(),
			known.toBuilder().hazardCoverage(row("mappedObjectsStatus", "unavailable")).build(),
			known.toBuilder().player(PlayerState.builder().worldX(100).worldY(200).build()).build()))
		{
			ObservedMechanismResolver.Result result = resolve(rules(declaration("OBJECT", "CONTACT_SAMPLE", 2, 3)), hit(),
				Arrays.asList(frame(2, 101, Collections.singletonList(surface("OBJECT"))), bad));
			assertTrue(result.isUnidentifiedPossible());
		}
		assertFalse(resolve(rules(declaration("OBJECT", "CONTACT_SAMPLE", 2, 3)), hit(),
			Arrays.asList(frame(2, 101, Collections.singletonList(surface("OBJECT"))),
				frame(3, 101, Collections.emptyList()))).isUnidentifiedPossible());
	}

	@Test
	public void foreignAndUnseenOtherRoomRulesDoNotBecomeCandidatesOrGlobalUnknown()
	{
		WorldSnapshot foreign = frame(3, 100, Collections.singletonList(surface("OBJECT"))).toBuilder().worldViewId(9).build();
		ObservedMechanismResolver.Result result = resolve(rules(declaration("OBJECT", "CONTACT_SAMPLE", 2, 5000)), hit(),
			Collections.singletonList(foreign));
		assertTrue(result.getContributions().isEmpty()); assertFalse(result.isUnidentifiedPossible());
		ObservedMechanismResolver.Result missingHitScope = resolve(rules(declaration("OBJECT", "CONTACT_SAMPLE", 2, 2)),
			hit().toBuilder().impactWorldViewId(null).build(), Collections.singletonList(frame(3, 100, Collections.singletonList(surface("OBJECT")))));
		assertTrue(missingHitScope.getContributions().isEmpty()); assertTrue(missingHitScope.isUnidentifiedPossible());
	}

	@Test
	public void historicalUnknownViewCannotUseUnrelatedCoordinatesToProveNoContact()
	{
		HazardState unknownView = HazardState.builder().kind("groundObject").id(ID).worldPositionKnown(true)
			.worldX(900).worldY(900).plane(0).state("active").damaging(true)
			.sourceEligibility("ELIGIBLE_REFERENCE_SURFACE").build();
		ObservedMechanismResolver.Result result = resolve(rules(declaration("OBJECT", "CONTACT_SAMPLE", 2, 2)), hit(),
			Collections.singletonList(frame(3, 100, Collections.singletonList(unknownView))));
		assertTrue(result.getContributions().isEmpty()); assertTrue(result.isUnidentifiedPossible());
	}

	@Test
	public void sameTickRequiresActualEarlierSampleCycle()
	{
		for (int sampleCycle : Arrays.asList(54, 55, 56))
		{
			WorldSnapshot sample = frame(5, 100, Collections.singletonList(surface("OBJECT"))).toBuilder().gameCycle(sampleCycle).build();
			ObservedMechanismResolver.Result result = resolve(rules(declaration("OBJECT", "CONTACT_SAMPLE", 0, 0)), hit(), Collections.singletonList(sample));
			assertEquals(sampleCycle == 54 ? 1 : 0, result.getContributions().size());
			assertEquals(sampleCycle != 54, result.isUnidentifiedPossible());
		}
	}

	@Test
	public void runningDoesNotInterpolateAnUnrecordedCrossing()
	{
		ObservedMechanismResolver.Result result = resolve(rules(declaration("OBJECT", "CONTACT_SAMPLE", 2, 3)), hit(),
			Arrays.asList(frame(2, 99, Collections.singletonList(surface("OBJECT"))),
				frame(3, 101, Collections.singletonList(surface("OBJECT")))));
		assertTrue(result.getContributions().isEmpty()); assertTrue(result.isUnidentifiedPossible());
	}

	@Test
	public void timingParametersAreClosedAndCannotDefault()
	{
		for (JsonObject invalid : Arrays.asList(declaration("OBJECT", "CONTACT_SAMPLE", -1, 2),
			declaration("OBJECT", "CONTACT_SAMPLE", 3, 2), declaration("OBJECT", "CONTACT_SAMPLE", 0, 5001),
			declaration("OBJECT", "UNKNOWN", 1, 2))) assertTrue(rules(invalid).historyRules().isEmpty());
		JsonObject missing = declaration("OBJECT", "CONTACT_SAMPLE", 1, 2);
		missing.getAsJsonObject("interval").remove("anchor"); assertTrue(rules(missing).historyRules().isEmpty());
		JsonObject extra = declaration("OBJECT", "CONTACT_SAMPLE", 1, 2);
		extra.getAsJsonObject("interval").addProperty("repeatPeriod", 1); assertTrue(rules(extra).historyRules().isEmpty());
		JsonObject area = declaration("GRAPHIC", "CONTACT_SAMPLE", 1, 2);
		area.addProperty("delivery", "AREA_IMPACT"); assertTrue(rules(area).historyRules().isEmpty());
	}

	@Test
	public void boundedHistoryNeverBecomesACompleteEmptyWindow()
	{
		List<WorldSnapshot> history = new ArrayList<>();
		history.add(frame(3, 100, Collections.singletonList(surface("OBJECT"))));
		for (int i = 0; i < 20001; i++) history.add(frame(-10000, 101, Collections.emptyList()));
		ObservedMechanismResolver.Result result = resolve(rules(declaration("OBJECT", "CONTACT_SAMPLE", 2, 2)), hit(), history);
		assertEquals(1, result.getContributions().size()); assertTrue(result.isUnidentifiedPossible());
	}

	@Test
	public void historyCannotReviveARejectedContactOutcome() throws Exception
	{
		DamageMap map = LegacyCombatCatalog.isolate(DamageMap.load(GSON));
		Field rootField = CombatCatalog.class.getDeclaredField("root"); rootField.setAccessible(true);
		rootField.set(map.catalog(), ((JsonObject) rootField.get(map.catalog())).deepCopy());
		JsonObject root = (JsonObject) rootField.get(map.catalog());
		JsonObject namespace = new JsonObject(); namespace.addProperty("version", 1);
		JsonArray declarations = new JsonArray(); declarations.add(declaration("OBJECT", "CONTACT_SAMPLE", 2, 2));
		namespace.add("rules", declarations); root.add("mechanismRules", namespace);
		JsonObject signature = GSON.fromJson("{\"key\":\"held-out-contact\",\"selector\":{\"kind\":\"OBJECT\",\"ids\":[" + ID
			+ "]},\"mechanism\":\"ENVIRONMENT_CONTACT\",\"positiveHitsplatTypes\":[5],\"zeroHitsplatTypes\":[5],"
			+ "\"complete\":true,\"requiredObservations\":[\"SOURCE_DEFINITION_BOUND\"],\"sourceRefs\":[\"Synthetic signature\"]}", JsonObject.class);
		root.getAsJsonObject("damageCompatibility").getAsJsonArray("rules").add(signature);
		for (String name : Arrays.asList("mechanismRuleIndex", "damageCompatibilityIndex"))
		{
			Field field = LegacyCombatCatalog.class.getDeclaredField(name); field.setAccessible(true); field.set(LegacyCombatCatalog.from(map.catalog()), null);
		}
		SourceResolution result = new RecordedSourceResolver(Arrays.asList(frame(3, 100, Collections.singletonList(surface("OBJECT"))),
			frame(5, 101, Collections.emptyList())), Collections.emptyList(), map).resolve(hit());
		assertFalse(result.getCandidates().stream().anyMatch(c -> "Earlier patch".equals(c.getName())));
		assertTrue(result.getExcludedCandidates().stream().anyMatch(c -> "Earlier patch".equals(c.getName())));
	}

	private static Map<String, Object> row(Object... fields)
	{
		Map<String, Object> row = new LinkedHashMap<>();
		for (int i = 0; i < fields.length; i += 2) row.put((String) fields[i], fields[i + 1]);
		return row;
	}
}
