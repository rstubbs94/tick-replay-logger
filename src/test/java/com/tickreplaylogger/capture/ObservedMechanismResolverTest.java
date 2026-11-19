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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.HazardState;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Arbitrary definitions exercise shared primitive rules rather than encounter predicates. */
public class ObservedMechanismResolverTest
{
	private static final Gson GSON = new Gson();
	private static final int ID = 999961;

	private static JsonObject rule(String kind, String footprint, Integer radius)
	{
		JsonObject rule = GSON.fromJson("{\"key\":\"held-out-component\",\"label\":\"Declared patch\","
			+ "\"selector\":{\"kind\":\"" + kind + "\",\"ids\":[" + ID + "]},\"delivery\":\"" + ("TILE".equals(footprint) ? "CONTACT" : "AREA_IMPACT") + "\","
			+ "\"footprint\":{\"operator\":\"" + footprint + "\"},\"interval\":{\"operator\":\"OBSERVED_ACTIVE\"},"
			+ "\"requiredObservations\":[\"IMPACT_POSITION_KNOWN\",\"SOURCE_POSITION_KNOWN\",\"SAME_NATIVE_SCOPE\",\"ACTIVE_INTERVAL_OBSERVED\"],"
			+ "\"referenceKeys\":[\"fixture:source\"],\"sourceRefs\":[\"Fixture evidence\"]}", JsonObject.class);
		if (radius != null) rule.getAsJsonObject("footprint").addProperty("radius", radius);
		return rule;
	}

	private static JsonObject root(DamageMap map) throws Exception
	{
		Field field = CombatCatalog.class.getDeclaredField("root"); field.setAccessible(true);
		return (JsonObject) field.get(map.catalog());
	}

	private static void reset(DamageMap map, String name) throws Exception
	{
		Field field = CombatCatalog.class.getDeclaredField(name); field.setAccessible(true); field.set(map.catalog(), null);
	}

	private static DamageMap map(JsonObject rule) throws Exception
	{
		DamageMap map = DamageMap.load(GSON);
		JsonObject namespace = new JsonObject(); namespace.addProperty("version", 1);
		JsonArray rules = new JsonArray(); rules.add(rule); namespace.add("rules", rules);
		root(map).add("mechanismRules", namespace); reset(map, "mechanismRuleIndex");
		return map;
	}

	private static HazardState graphic(int x, int y, Integer start, Boolean finished, Integer view, int plane)
	{
		return HazardState.builder().kind("graphicsObject").id(ID).mappedName("RAW_UNCLASSIFIED_SYMBOL")
			.worldPositionKnown(true).worldX(x).worldY(y).plane(plane).worldViewId(view).startCycle(start).finished(finished).build();
	}

	private static WorldSnapshot frame(List<HazardState> hazards)
	{
		return WorldSnapshot.builder().tick(2).sceneKey("scope").worldViewId(0).plane(0).nativeContextKnown(true)
			.player(PlayerState.builder().worldX(102).worldY(202).build()).hazards(hazards)
			.npcs(Collections.singletonList(NpcState.builder().npcId(999962).actorRef("actor:rival").name("Rival").build())).build();
	}

	private static DamageRecord hit(boolean rival)
	{
		return DamageRecord.builder().impactTick(2).impactSceneKey("scope").impactWorldViewId(0).impactPlane(0)
			.impactPositionKnown(true).impactX(102).impactY(202).observedCycle(100).amount(7)
			.hitsplatType(16).hitsplatTypeKnown(true).observationSequence(2L).attackerName("Unknown")
			.attributionCandidates(rival ? Collections.singletonList(row("kind", "NPC_ANIMATION_CORRELATION",
				"name", "Rival", "actorRef", "actor:rival", "npcId", 999962)) : Collections.emptyList()).build();
	}

	private static SourceResolution resolve(DamageMap map, WorldSnapshot frame, DamageRecord hit)
	{
		return new RecordedSourceResolver(Collections.singletonList(frame), Collections.emptyList(), map).resolve(hit);
	}

	@Test
	public void explicitRadiusIncludesDiagonalBoundaryWithoutInventingAnOwner() throws Exception
	{
		DamageMap map = map(rule("GRAPHIC", "CHEBYSHEV_RADIUS", 2));
		DamageRecord hit = hit(false);
		SourceResolution result = resolve(map, frame(Collections.singletonList(graphic(100, 200, 90, false, 0, 0))), hit);
		assertEquals("ALTERNATIVES", result.getStatus());
		assertEquals("FAMILY", result.getIdentityLevel());
		assertEquals("Declared patch", result.getCandidates().get(0).getName());
		assertEquals("graphic-definition:" + ID, result.getCandidates().get(0).getFamilyKey());
		assertNull(result.getCandidates().get(0).getActorRef());
		assertTrue("Known geometry identifies the possible surface, not the HP callback's cause", result.isUnidentifiedPossible());
		assertTrue(result.getCandidates().get(0).getEvidence().stream()
			.anyMatch(e -> e.startsWith("HIT_CAUSALITY_UNESTABLISHED:")));
		assertEquals(7, hit.getAmount());
	}

	@Test
	public void foreignFutureFinishedAndOutsideFootprintCannotDisplaceTheOtherActor() throws Exception
	{
		DamageMap map = map(rule("GRAPHIC", "CHEBYSHEV_RADIUS", 2));
		for (HazardState hazard : Arrays.asList(graphic(99, 202, 90, false, 0, 0), graphic(102, 202, 101, false, 0, 0),
			graphic(102, 202, 90, true, 0, 0), graphic(102, 202, 90, false, 1, 0), graphic(102, 202, 90, false, 0, 1)))
		{
			SourceResolution result = resolve(map, frame(Collections.singletonList(hazard)), hit(true));
			assertEquals("ALTERNATIVES", result.getStatus());
			assertTrue("Rejecting an impossible graphic does not prove the rival animation caused this hit", result.isUnidentifiedPossible());
			assertEquals("Rival", result.getCandidates().get(0).getName());
			assertEquals(1, result.getCandidates().size());
			assertTrue(result.getCandidates().get(0).getEvidence().stream()
				.anyMatch(e -> e.startsWith("HIT_CAUSALITY_UNESTABLISHED:")));
		}
	}

	@Test
	public void missingLifetimeOrSourcePositionRetainsUnknownInsteadOfDefaultingToZero() throws Exception
	{
		DamageMap map = map(rule("GRAPHIC", "CHEBYSHEV_RADIUS", 1));
		for (HazardState hazard : Arrays.asList(graphic(102, 202, null, false, 0, 0), graphic(102, 202, 90, null, 0, 0),
			HazardState.builder().kind("graphicsObject").id(ID).worldPositionKnown(false).worldViewId(0).plane(0).startCycle(90).finished(false).build()))
		{
			SourceResolution result = resolve(map, frame(Collections.singletonList(hazard)), hit(false));
			assertEquals("ALTERNATIVES", result.getStatus());
			assertTrue(result.isUnidentifiedPossible());
			assertEquals("Declared patch", result.getCandidates().get(0).getName());
		}
	}

	@Test
	public void callbackOnlyGraphicSurvivesItsRemovalBeforeTheSettledSnapshot() throws Exception
	{
		DamageMap map = map(rule("GRAPHIC", "TILE", null));
		Map<String, Object> contact = row("kind", "GRAPHIC_SURFACE", "graphicId", ID, "graphicObservationId", 12L,
			"sourceEligibility", "UNCLASSIFIED_GRAPHIC_SURFACE", "worldViewId", 0, "plane", 0,
			"sceneKey", "scope", "worldX", 102, "worldY", 202, "startCycle", 90, "finished", false, "observedCycle", 100);
		SourceResolution result = resolve(map, frame(Collections.emptyList()), hit(false).toBuilder()
			.observedHazardContacts(Collections.singletonList(contact)).build());
		assertEquals("ALTERNATIVES", result.getStatus());
		assertEquals("Declared patch", result.getCandidates().get(0).getName());
		assertTrue("Callback-time presence survives removal but does not prove the hit's cause", result.isUnidentifiedPossible());
		assertTrue(result.getCandidates().get(0).getEvidence().stream()
			.anyMatch(e -> e.startsWith("HIT_CAUSALITY_UNESTABLISHED:")));
		contact.remove("sceneKey");
		SourceResolution missingScope = resolve(map, frame(Collections.emptyList()), hit(false).toBuilder()
			.observedHazardContacts(Collections.singletonList(contact)).build());
		assertTrue(missingScope.isUnidentifiedPossible());
		assertNull(missingScope.getCandidates().get(0).getSceneKey());
	}

	@Test
	public void primaryEvidenceDoesNotRequireAnUnrelatedStructuredReferenceKey() throws Exception
	{
		JsonObject rule = rule("GRAPHIC", "TILE", null);
		rule.add("referenceKeys", new JsonArray());
		DamageMap map = map(rule);
		SourceResolution result = resolve(map, frame(Collections.singletonList(graphic(102, 202, 90, false, 0, 0))), hit(false));
		assertEquals("ALTERNATIVES", result.getStatus());
		assertEquals("Declared patch", result.getCandidates().get(0).getName());
		assertTrue("A valid primary reference establishes the possible component, not causality", result.isUnidentifiedPossible());
		rule.add("sourceRefs", new JsonArray());
		assertFalse(map(rule).catalog().mechanismRules().sourceClassified("GRAPHIC", ID));
	}

	@Test
	public void duplicateExistingFamilyDoesNotDuplicateCreditedDamage() throws Exception
	{
		DamageMap map = map(rule("GRAPHIC", "TILE", null));
		HazardState hazard = HazardState.builder().kind("graphicsObject").id(ID).mappedName("Declared patch")
			.worldPositionKnown(true).worldX(102).worldY(202).worldViewId(0).plane(0).startCycle(90).finished(false)
			.damaging(true).sourceEligibility("ELIGIBLE_REFERENCE_SURFACE").build();
		SourceResolution result = resolve(map, frame(Collections.singletonList(hazard)), hit(false));
		assertEquals(1, result.getCandidates().size());
		assertEquals("graphic-definition:" + ID, result.getCandidates().get(0).getFamilyKey());
		assertTrue(result.getCandidates().get(0).getEvidence().stream().anyMatch(e -> e.startsWith("MECHANISM_RULE:")));
	}

	@Test
	public void objectActivityRequiresAnOriginalTimedContactAndDoesNotInventGraphicCycles() throws Exception
	{
		DamageMap map = map(rule("OBJECT", "TILE", null));
		for (Boolean damaging : Arrays.asList(true, null, false))
		{
			HazardState hazard = HazardState.builder().kind("groundObject").id(ID).worldPositionKnown(true)
				.worldX(102).worldY(202).worldViewId(0).plane(0).state(damaging == null ? null : damaging ? "active" : "warning")
				.damaging(damaging).sourceEligibility("ELIGIBLE_REFERENCE_SURFACE").build();
			SourceResolution result = resolve(map, frame(Collections.singletonList(hazard)), hit(false));
			if (Boolean.FALSE.equals(damaging)) assertEquals("UNIDENTIFIED", result.getStatus());
			else assertTrue("Settled activation alone cannot establish the earlier impact interval", result.isUnidentifiedPossible());
		}
		HazardState active = HazardState.builder().kind("groundObject").id(ID).worldPositionKnown(true)
			.worldX(102).worldY(202).worldViewId(0).plane(0).state("active").damaging(true)
			.sourceEligibility("ELIGIBLE_REFERENCE_SURFACE").build();
		Map<String, Object> contact = row("kind", "OBJECT_SURFACE", "objectId", ID, "objectObservationId", 6L,
			"sceneKey", "scope", "worldViewId", 0, "plane", 0, "contactWorldX", 102, "contactWorldY", 202,
			"state", "active", "damaging", true, "sourceEligibility", "ELIGIBLE_REFERENCE_SURFACE",
			"observationStage", "HITSPLAT_CALLBACK", "observedCycle", 100);
		SourceResolution callback = resolve(map, frame(Collections.singletonList(active)), hit(false).toBuilder()
			.observedHazardContacts(Collections.singletonList(contact)).build());
		assertEquals("ALTERNATIVES", callback.getStatus());
		assertTrue("A correctly timed object contact is still an association with this HP callback", callback.isUnidentifiedPossible());
		assertTrue(callback.getCandidates().get(0).getEvidence().stream()
			.anyMatch(e -> e.startsWith("HIT_CAUSALITY_UNESTABLISHED:")));
		contact.put("observationStage", "DEFERRED_RESOLUTION");
		SourceResolution deferred = resolve(map, frame(Collections.singletonList(active)), hit(false).toBuilder()
			.observedHazardContacts(Collections.singletonList(contact)).build());
		assertTrue(deferred.isUnidentifiedPossible());
		contact.put("observationStage", "HITSPLAT_CALLBACK");
		contact.remove("observedCycle");
		assertTrue(resolve(map, frame(Collections.emptyList()), hit(false).toBuilder()
			.observedHazardContacts(Collections.singletonList(contact)).build()).isUnidentifiedPossible());
	}

	@Test
	public void absentRadiusUnknownOperatorAndWrongVersionCannotSupplyDefaults() throws Exception
	{
		for (JsonObject invalid : Arrays.asList(rule("GRAPHIC", "CHEBYSHEV_RADIUS", null), rule("GRAPHIC", "UNKNOWN", 1)))
		{
			DamageMap map = map(invalid);
			SourceResolution result = resolve(map, frame(Collections.singletonList(graphic(102, 202, 90, false, 0, 0))), hit(true));
			assertTrue(result.isUnidentifiedPossible());
			assertFalse(result.getCandidates().stream().anyMatch(c -> "Declared patch".equals(c.getName())));
		}
		DamageMap map = map(rule("GRAPHIC", "TILE", null));
		root(map).getAsJsonObject("mechanismRules").addProperty("version", 2); reset(map, "mechanismRuleIndex");
		assertTrue(resolve(map, frame(Collections.emptyList()), hit(true)).isUnidentifiedPossible());
	}

	@Test
	public void extraSemanticConstraintsCannotBeSilentlyIgnored() throws Exception
	{
		for (String section : Arrays.asList("selector", "footprint", "interval"))
		{
			JsonObject declared = rule("GRAPHIC", "TILE", null);
			declared.getAsJsonObject(section).addProperty("unsupportedCondition", 3);
			assertFalse(map(declared).catalog().mechanismRules().sourceClassified("GRAPHIC", ID));
		}
	}

	@Test
	public void anUnknownImpactScopeCannotBorrowTheOnlySettledFrame() throws Exception
	{
		DamageMap map = map(rule("GRAPHIC", "TILE", null));
		HazardState graphic = graphic(102, 202, 90, false, 0, 0);
		for (DamageRecord hit : Arrays.asList(hit(false).toBuilder().impactSceneKey(null).build(),
			hit(false).toBuilder().impactWorldViewId(null).build(), hit(false).toBuilder().impactPlane(null).build()))
			assertTrue(resolve(map, frame(Collections.singletonList(graphic)), hit).isUnidentifiedPossible());
	}

	@Test
	public void unknownSourceScopeCannotUseCoordinateDistanceAsAnExclusion() throws Exception
	{
		DamageMap map = map(rule("GRAPHIC", "TILE", null));
		SourceResolution result = resolve(map, frame(Collections.singletonList(graphic(900, 900, 90, false, null, 0))), hit(true));
		assertTrue(result.isUnidentifiedPossible());
		assertTrue(result.getCandidates().stream().anyMatch(c -> "Declared patch".equals(c.getName())));
	}

	@Test
	public void mechanismContributionCannotReviveAKnownHitsplatContradiction() throws Exception
	{
		DamageMap map = map(rule("GRAPHIC", "TILE", null));
		JsonObject signature = GSON.fromJson("{\"key\":\"held-out-outcome\",\"selector\":{\"kind\":\"GRAPHIC\",\"ids\":[" + ID
			+ "]},\"mechanism\":\"ENVIRONMENT_CONTACT\",\"positiveHitsplatTypes\":[5],\"zeroHitsplatTypes\":[5],"
			+ "\"complete\":true,\"requiredObservations\":[\"SOURCE_DEFINITION_BOUND\"],\"sourceRefs\":[\"Fixture evidence\"]}", JsonObject.class);
		root(map).getAsJsonObject("damageCompatibility").getAsJsonArray("rules").add(signature);
		reset(map, "damageCompatibilityIndex");
		SourceResolution result = resolve(map, frame(Collections.singletonList(graphic(102, 202, 90, false, 0, 0))), hit(true));
		assertEquals("Rival", result.getCandidates().get(0).getName());
		assertEquals(1, result.getCandidates().size());
		assertTrue(result.getExcludedCandidates().stream().anyMatch(c -> "Declared patch".equals(c.getName())));
		JsonObject area = rule("GRAPHIC", "CHEBYSHEV_RADIUS", 1);
		root(map).getAsJsonObject("mechanismRules").getAsJsonArray("rules").set(0, area);
		reset(map, "mechanismRuleIndex");
		SourceResolution separateArea = resolve(map, frame(Collections.singletonList(graphic(102, 202, 90, false, 0, 0))), hit(true));
		assertEquals(2, separateArea.getCandidates().size());
		assertTrue(separateArea.getCandidates().stream().anyMatch(c -> "Declared patch".equals(c.getName())));
	}

	@Test
	public void failedDefinitionOrResourceOnlySurfaceCannotGainAnHpMechanism() throws Exception
	{
		DamageMap map = map(rule("OBJECT", "TILE", null));
		for (String eligibility : Arrays.asList("DEFINITION_NOT_QUALIFIED", "RESOURCE_REFERENCE_ONLY"))
		{
			HazardState surface = HazardState.builder().kind("groundObject").id(ID).worldPositionKnown(true)
				.worldX(102).worldY(202).worldViewId(0).plane(0).state("active").damaging(null)
				.sourceEligibility(eligibility).build();
			SourceResolution result = resolve(map, frame(Collections.singletonList(surface)), hit(true));
			assertFalse(result.getCandidates().stream().anyMatch(c -> "Declared patch".equals(c.getName())));
		}
	}

	@Test
	public void unqualifiedLegacyContactsCannotBecomeCompleteAttribution() throws Exception
	{
		DamageMap map = map(rule("OBJECT", "TILE", null));
		for (String eligibility : Arrays.asList("DEFINITION_NOT_QUALIFIED", "STATE_UNAVAILABLE", "CONDITION_NOT_OBSERVED"))
		{
			Map<String, Object> contact = row("kind", "OBJECT_SURFACE", "name", "Unqualified patch", "objectId", ID,
				"sourceEligibility", eligibility, "sceneKey", "scope", "worldViewId", 0, "plane", 0,
				"state", "unknown", "contactWorldX", 102, "contactWorldY", 202,
				"observationStage", "HITSPLAT_CALLBACK", "observedCycle", 100);
			SourceResolution result = resolve(map, frame(Collections.emptyList()), hit(true).toBuilder()
				.observedHazardContacts(Collections.singletonList(contact)).build());
			assertTrue(result.isUnidentifiedPossible());
			assertEquals("ALTERNATIVES", result.getStatus());
		}
	}

	@Test
	public void everyCompiledPrimitiveDeclarationHasAConsumerRoute() throws Exception
	{
		DamageMap map = DamageMap.load(GSON);
		JsonArray declarations = root(map).getAsJsonObject("mechanismRules").getAsJsonArray("rules");
		assertFalse("Current compiler must emit its reviewed primitive declarations", declarations.size() == 0);
		for (JsonElement value : declarations)
		{
			JsonObject declared = value.getAsJsonObject(), selector = declared.getAsJsonObject("selector");
			for (JsonElement valueId : selector.getAsJsonArray("ids"))
			{
				String kind = selector.get("kind").getAsString(), key = declared.get("key").getAsString();
				int id = valueId.getAsInt();
				assertTrue(key, map.catalog().mechanismRules().sourceClassified(kind, id));
				assertTrue(key, map.catalog().mechanismRules().matching(kind, id).stream().anyMatch(rule -> key.equals(rule.getKey())));
			}
		}
	}

	private static Map<String, Object> row(Object... values)
	{
		Map<String, Object> row = new LinkedHashMap<>();
		for (int i = 0; i < values.length; i += 2) row.put((String) values[i], values[i + 1]);
		return row;
	}
}
