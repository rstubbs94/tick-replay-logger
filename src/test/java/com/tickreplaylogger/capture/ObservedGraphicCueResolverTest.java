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
import com.tickreplaylogger.model.world.SceneData;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Arbitrary held-out effects verify correlation without inventing timers or server causes. */
public class ObservedGraphicCueResolverTest
{
	private static final Gson GSON = new Gson();
	private static final int ID = 999987;
	private final CombatCatalog catalog = CombatCatalog.load(GSON);

	private JsonObject declarations()
	{
		return GSON.fromJson("{\"version\":1,\"rules\":[{\"key\":\"held-out-effect\",\"name\":\"Held-out blast\",\"graphicIds\":[999987],"
			+ "\"referenceKeys\":[],\"observationKind\":\"GRAPHICS_OBJECT_CREATED\",\"temporalRelation\":\"SAME_RECORDED_TICK_CO_OCCURRENCE\","
			+ "\"qualification\":\"POSSIBLE_SOURCE_HINT\",\"unidentifiedPossible\":true,\"recipients\":\"OBSERVED_LOCAL_PLAYER\",\"observedOutcomeDomain\":\"ORDINARY_HP_OR_BLOCK_CALLBACK_ONLY\",\"observedHitsplatTypes\":[12,13,16,17,43],"
			+ "\"geometry\":{\"operator\":\"CHEBYSHEV_RADIUS\",\"radius\":1,\"scope\":\"POSSIBLE_ENVELOPE\"},\"provenance\":{}}]}", JsonObject.class);
	}

	private SceneData scene()
	{
		return SceneData.builder().sceneKey("native").worldViewId(7).plane(1).baseX(3100).baseY(3200).width(104).height(104).build();
	}

	private Map<String, Object> fields()
	{
		Map<String, Object> data = new HashMap<>();
		data.put("id", ID); data.put("graphicObservationId", 42L); data.put("worldViewId", 7); data.put("localWorldViewId", 7);
		data.put("plane", 1); data.put("localX", 2 * 128 + 64); data.put("localY", 3 * 128 + 64);
		data.put("scopeKnown", true); data.put("positionScopeKnown", true); data.put("nativeSceneKey", "native");
		return data;
	}

	private ObservedEvent event(Map<String, Object> data)
	{ return ObservedEvent.builder().type("GRAPHICS_OBJECT_CREATED").tick(10).sequence(7).gameCycle(300).sceneKey("recipient").data(data).build(); }

	private DamageRecord hit()
	{
		return DamageRecord.builder().direction("RECEIVED").defenderRef("player").impactTick(10).impactX(3103).impactY(3204)
			.impactPositionKnown(true).impactSceneKey("native").impactWorldViewId(7).impactPlane(1).hitsplatType(16)
			.hitsplatTypeKnown(true).amount(9).observationSequence(3L).observedCycle(299).build();
	}

	private ObservedGraphicCueResolver resolver(JsonObject declarations, ObservedEvent... events)
	{ return new ObservedGraphicCueResolver(declarations, Arrays.asList(events), Collections.singletonMap("native", scene())); }

	@Test public void recordedCoOccurrenceUsesNativeTileAndNeverClaimsCauseOrCallbackOrder()
	{
		ObservedMechanismResolver.Result result = resolver(declarations(), event(fields())).resolve(hit(), catalog);
		assertEquals(1, result.getContributions().size()); assertTrue(result.isUnidentifiedPossible());
		com.tickreplaylogger.model.world.SourceResolution.Candidate candidate = result.getContributions().get(0).getHypothesis().getCandidate();
		assertEquals("Held-out blast", candidate.getName()); assertEquals("POSSIBLE", candidate.getStrength());
		assertEquals("native", candidate.getSceneKey()); assertEquals(Integer.valueOf(7), candidate.getWorldViewId());
		assertNull(candidate.getActorRef()); assertNull(candidate.getNpcId());
		assertTrue(candidate.getEvidence().toString().contains("graphicObservationId=42"));
		// Effect callback happens after the hitsplat: same-tick correlation must not pretend ordered activation.
		assertTrue(candidate.getEvidence().toString().contains("ordering, owner and server cause are not established"));
	}

	@Test public void otherTickScopePlaneAndOutsideEnvelopeCannotBecomeCandidates()
	{
		ObservedGraphicCueResolver resolver = resolver(declarations(), event(fields()));
		for (DamageRecord changed : Arrays.asList(hit().toBuilder().impactTick(11).build(), hit().toBuilder().impactTick(9).build(),
			hit().toBuilder().impactSceneKey("other").build(), hit().toBuilder().impactWorldViewId(8).build(),
			hit().toBuilder().impactPlane(0).build(), hit().toBuilder().impactX(3104).build(),
			hit().toBuilder().impactPositionKnown(false).build()))
			assertTrue(resolver.resolve(changed, catalog).getContributions().isEmpty());
	}

	@Test public void resourceOnlyHealingAndNpcRecipientsAreNotHpSourceHints()
	{
		ObservedGraphicCueResolver resolver = resolver(declarations(), event(fields()));
		for (DamageRecord changed : Arrays.asList(hit().toBuilder().direction("NPC_RECEIVED").defenderRef("npc:7").build(),
			hit().toBuilder().hitsplatType(6).build(), hit().toBuilder().hitsplatType(5).build(),
			hit().toBuilder().hitsplatType(3).build(), hit().toBuilder().hitsplatType(999998).build(), hit().toBuilder().hitsplatTypeKnown(false).build()))
			assertTrue(resolver.resolve(changed, catalog).getContributions().isEmpty());
		assertFalse(catalog.possibleOutcomeSurfaces(6).contains("HP"));
		assertTrue(resolver.resolve(hit().toBuilder().direction("RESOURCE_IMPACT").hitsplatType(3).build(), catalog).getContributions().isEmpty());
		assertEquals(1, resolver.resolve(hit().toBuilder().hitsplatType(12).amount(0).build(), catalog).getContributions().size());
	}

	@Test public void unavailableOrConflictingNativePositionDoesNotBorrowPrimaryCoordinates()
	{
		for (String field : Arrays.asList("scopeKnown", "positionScopeKnown", "worldViewId", "localWorldViewId", "plane", "localX", "localY", "graphicObservationId"))
		{
			Map<String, Object> data = fields(); data.remove(field);
			ObservedMechanismResolver.Result result = resolver(declarations(), event(data)).resolve(hit(), catalog);
			assertTrue(field, result.getContributions().isEmpty()); assertTrue(field, result.isUnidentifiedPossible());
		}
		Map<String, Object> data = fields(); data.put("localWorldViewId", 8);
		assertTrue(resolver(declarations(), event(data)).resolve(hit(), catalog).getContributions().isEmpty());
		data = fields(); data.put("localX", 104 * 128);
		assertTrue(resolver(declarations(), event(data)).resolve(hit(), catalog).getContributions().isEmpty());
		data = fields(); data.put("observationIssues", Collections.singletonMap("id", "FAILED"));
		assertTrue(resolver(declarations(), event(data)).resolve(hit(), catalog).getContributions().isEmpty());
	}

	@Test public void missingNativeSceneAndUnknownGraphicCannotInventAName()
	{
		ObservedGraphicCueResolver missing = new ObservedGraphicCueResolver(declarations(), Collections.singletonList(event(fields())), Collections.emptyMap());
		assertTrue(missing.resolve(hit(), catalog).getContributions().isEmpty());
		Map<String, Object> data = fields(); data.put("id", ID + 1);
		assertTrue(resolver(declarations(), event(data)).resolve(hit(), catalog).getContributions().isEmpty());
		data = fields(); data.put("graphicObservationId", 1.5);
		assertTrue(resolver(declarations(), event(data)).resolve(hit(), catalog).getContributions().isEmpty());
	}

	@Test public void duplicatesDoNotAddHitsAndLaterCallerMutationCannotMoveTheCue()
	{
		Map<String, Object> data = fields(); ObservedEvent event = event(data);
		ObservedGraphicCueResolver resolver = resolver(declarations(), event, event);
		data.put("localX", 9000); data.put("id", ID + 1);
		assertEquals(1, resolver.resolve(hit(), catalog).getContributions().size());
		assertEquals(9, hit().getAmount());
	}

	@Test public void declarationsCannotPromoteCoOccurrenceToConfirmedImpactOrInfiniteArea()
	{
		for (String field : Arrays.asList("qualification", "temporalRelation", "unidentifiedPossible", "recipients"))
		{
			JsonObject document = declarations(); document.getAsJsonArray("rules").get(0).getAsJsonObject().remove(field);
			ObservedMechanismResolver.Result result = resolver(document, event(fields())).resolve(hit(), catalog);
			assertTrue(result.getContributions().isEmpty()); assertTrue(result.isUnidentifiedPossible());
		}
		JsonObject document = declarations(); document.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("geometry").addProperty("radius", 105);
		assertTrue(resolver(document, event(fields())).resolve(hit(), catalog).getContributions().isEmpty());
	}

	@Test public void fullRecordedResolutionPreservesSourceHintAlongsideUnknown() throws Exception
	{
		DamageMap map = DamageMap.load(GSON);
		Field field = CombatCatalog.class.getDeclaredField("root"); field.setAccessible(true);
		((JsonObject) field.get(map.catalog())).add("observedGraphicCues", declarations());
		WorldSnapshot frame = WorldSnapshot.builder().tick(10).sceneKey("native").worldViewId(7).plane(1)
			.nativeContextKnown(true).npcs(Collections.emptyList()).projectiles(Collections.emptyList()).hazards(Collections.emptyList()).build();
		SourceResolution result = new RecordedSourceResolver(Collections.singletonList(frame), Collections.singletonList(scene()), map,
			Collections.singletonList(event(fields())), Collections.emptyList()).resolve(hit());
		assertTrue(result.isUnidentifiedPossible());
		assertTrue(result.getCandidates().stream().anyMatch(candidate -> "Held-out blast".equals(candidate.getName())));
		assertNotEquals("IDENTIFIED", result.getStatus());
	}

	@Test public void failedCallbackOnlyRetainsUncertaintyOnItsTickAndNoncontradictedScope()
	{
		Map<String, Object> data = fields(); data.remove("localX");
		ObservedGraphicCueResolver resolver = resolver(declarations(), event(data));
		assertTrue(resolver.resolve(hit(), catalog).isUnidentifiedPossible());
		assertFalse(resolver.resolve(hit().toBuilder().impactTick(90).build(), catalog).isUnidentifiedPossible());
		assertFalse(resolver.resolve(hit().toBuilder().impactWorldViewId(99).build(), catalog).isUnidentifiedPossible());
		assertFalse(resolver.resolve(hit().toBuilder().impactPlane(0).build(), catalog).isUnidentifiedPossible());
		assertFalse(resolver.resolve(hit().toBuilder().impactSceneKey("other").build(), catalog).isUnidentifiedPossible());
		data = fields(); data.remove("id");
		assertTrue(resolver(declarations(), event(data)).resolve(hit(), catalog).isUnidentifiedPossible());
	}
}
