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
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.ActorInteractionState;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObservedActorActivityResolverTest
{
	private static final String REF = "npc:unseen:v0:g1";
	private static CombatCatalog catalog()
	{
		try
		{
			CombatCatalog catalog = new CombatCatalog();
			java.lang.reflect.Field root = CombatCatalog.class.getDeclaredField("root"); root.setAccessible(true);
			root.set(catalog, new Gson().fromJson("{\"hitsplatRules\":{\"rules\":{\"16\":{\"surface\":\"HP\"},\"12\":{\"surface\":\"BLOCK\"},\"5\":{\"surface\":\"HP\",\"damageType\":\"venom\"},\"3\":{\"surface\":\"SKILL_OR_HP\"}}}}", JsonObject.class));
			return catalog;
		}
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static DamageMap map()
	{
		try
		{
			DamageMap map = new DamageMap(); java.lang.reflect.Field field = DamageMap.class.getDeclaredField("catalog");
			field.setAccessible(true); field.set(map, catalog()); return map;
		}
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static DamageRecord hit()
	{
		return DamageRecord.builder().impactTick(5).observationSequence(20L).observedCycle(101)
			.direction("RECEIVED").defenderRef("player").impactSceneKey("native").impactWorldViewId(0).impactPlane(0)
			.amount(7).hitsplatType(16).hitsplatTypeKnown(true).attackerName("Unknown").build();
	}
	private static Map<String, Object> data()
	{
		Map<String, Object> data = new LinkedHashMap<>(); data.put("actorKind", "NPC"); data.put("name", "Unseen creature");
		data.put("npcId", 990001); data.put("animationId", 990002); data.put("worldViewId", 0); data.put("plane", 0);
		data.put("planeKnown", true); data.put("worldPositionKnown", true); data.put("callbackMetadataEvidence", "LOGGED_IN_ACTOR_FIELDS");
		data.put("interaction", ActorInteractionState.builder().reportedInteracting(true).actorRef("player")
			.actorKind("LOCAL_PLAYER").worldViewId(0).evidence("ACTOR_REFERENCE_OBSERVED").build()); return data;
	}
	private static ObservedEvent event(long sequence, Map<String, Object> data)
	{
		return ObservedEvent.builder().tick(5).gameCycle(100).sequence(sequence).type("ANIMATION_CHANGED")
			.actorRef(REF).actorName("Unseen creature").sceneKey("native").data(data).build();
	}

	@Test
	public void originalPreHitActivitySurvivesASettledIdleActorWithoutACombatReference()
	{
		Map<String, Object> idle = data(); idle.put("animationId", -1);
		WorldSnapshot frame = WorldSnapshot.builder().tick(5).sceneKey("native").worldViewId(0).plane(0).nativeContextKnown(true)
			.npcs(Collections.emptyList()).hazards(Collections.emptyList()).build();
		SourceResolution resolution = new RecordedSourceResolver(Collections.singletonList(frame), Collections.emptyList(), map(),
			Arrays.asList(event(10, data()), event(30, idle)), Collections.emptyList()).resolve(hit());
		assertEquals(1, resolution.getCandidates().size()); SourceResolution.Candidate source = resolution.getCandidates().get(0);
		assertEquals(REF, source.getActorRef()); assertEquals("native", source.getSceneKey()); assertEquals(Integer.valueOf(0), source.getWorldViewId());
		assertEquals("POSSIBLE", source.getStrength()); assertNull(source.getFamilyKey());
		assertEquals("ALTERNATIVES", resolution.getStatus()); assertTrue(resolution.isUnidentifiedPossible());
		assertTrue(resolution.getExcludedCandidates() == null || resolution.getExcludedCandidates().isEmpty());
		assertEquals(7, hit().getAmount()); assertEquals("Unknown", hit().getAttackerName());
	}

	@Test
	public void aPostHitCallbackDifferentRecipientForeignScopeAndInactivePoseCannotSupplyTheHint()
	{
		for (int variant = 0; variant < 5; variant++)
		{
			Map<String, Object> fields = data(); ObservedEvent event;
			if (variant == 1) fields.put("interaction", ActorInteractionState.builder().reportedInteracting(true).actorRef("otherplayer:x")
				.actorKind("OTHER_PLAYER").worldViewId(0).build());
			if (variant == 2) fields.put("worldViewId", 8);
			if (variant == 3) fields.put("animationId", -1);
			if (variant == 4) fields.put("actorKind", "OTHER_PLAYER");
			event = event(variant == 0 ? 21 : 10, fields);
			assertTrue("variant " + variant, new ObservedActorActivityResolver(Collections.singletonList(event), catalog()).resolve(hit()).getCandidates().isEmpty());
		}
	}

	@Test
	public void distinctOriginalModelCohortsRemainIndependentAndAreFrozenBeforeLaterReads()
	{
		Map<String, Object> earlier = data(), later = data();
		earlier.put("combatLevel", 999); later.put("combatLevel", 230);
		for (Map<String, Object> fields : Arrays.asList(earlier, later))
		{
			fields.put("definitionEvidence", "NPC_API_ID"); fields.put("normalWorldContextObserved", true); fields.put("modelOverridesPresent", false);
		}
		ObservedActorActivityResolver resolver = new ObservedActorActivityResolver(Arrays.asList(event(10, earlier), event(11, later)), catalog());
		earlier.put("combatLevel", 230); later.put("combatLevel", 999);
		ObservedActorActivityResolver.Result result = resolver.resolve(hit());
		assertEquals(2, result.getCandidates().size()); assertEquals(2, result.getSourceSamples().size());
		assertTrue(result.getSourceSamples().values().stream().anyMatch(source -> Integer.valueOf(999).equals(source.getCombatLevel())));
		assertTrue(result.getSourceSamples().values().stream().anyMatch(source -> Integer.valueOf(230).equals(source.getCombatLevel())));
	}

	@Test
	public void missingAndKnownOriginalModelFactsDoNotCollapseIntoTheKnownCohort()
	{
		Map<String, Object> missing = data(), known = data();
		known.put("combatLevel", 230); known.put("definitionEvidence", "NPC_API_ID");
		known.put("normalWorldContextObserved", true); known.put("modelOverridesPresent", false);
		ObservedActorActivityResolver.Result result = new ObservedActorActivityResolver(Arrays.asList(event(10, missing), event(11, known)), catalog()).resolve(hit());
		assertEquals(2, result.getCandidates().size());
		assertTrue(result.getSourceSamples().values().stream().anyMatch(source -> source.getCombatLevel() == null));
	}

	@Test
	public void missingRequiredReadsOrScopeRetainUncertaintyWithoutFabricatingACandidate()
	{
		for (int variant = 0; variant < 6; variant++)
		{
			Map<String, Object> fields = data();
			if (variant == 0) { fields.remove("animationId"); fields.put("observationIssues", Collections.singletonMap("animationId", "UNAVAILABLE")); }
			if (variant == 1) fields.put("interaction", ActorInteractionState.builder().reportedInteracting(null).actorRef("player").actorKind("LOCAL_PLAYER").worldViewId(0).build());
			if (variant == 2) fields.remove("worldViewId");
			if (variant == 3) fields.put("planeKnown", false);
			if (variant == 4) fields.put("scopeKnown", false);
			ObservedEvent callback = variant == 5 ? ObservedEvent.builder().tick(5).gameCycle(100).sequence(10).type("ANIMATION_CHANGED")
				.actorRef(REF).data(fields).build() : event(10, fields);
			ObservedActorActivityResolver.Result result = new ObservedActorActivityResolver(Collections.singletonList(callback), catalog()).resolve(hit());
			assertTrue("variant " + variant, result.getCandidates().isEmpty()); assertTrue("variant " + variant, result.isIncomplete());
		}
	}

	@Test
	public void parsedInteractionMapsUseTheSamePositiveRequirementsAndNoModelMetadata()
	{
		Map<String, Object> fields = data(); Map<String, Object> target = new LinkedHashMap<>();
		target.put("reportedInteracting", true); target.put("actorRef", "player"); target.put("actorKind", "LOCAL_PLAYER"); target.put("worldViewId", 0.0);
		fields.put("interaction", target);
		ObservedActorActivityResolver resolver = new ObservedActorActivityResolver(Collections.singletonList(event(10, fields)), catalog());
		target.put("actorRef", "otherplayer:mutated"); fields.put("animationId", -1);
		assertEquals(1, resolver.resolve(hit()).getCandidates().size());
		assertNull(resolver.resolve(hit()).getCandidates().get(0).getFamilyKey());
	}

	@Test
	public void genericActivityHintsCannotReviveTypedStatusMixedResourceOrUnclassifiedImpacts()
	{
		ObservedActorActivityResolver resolver = new ObservedActorActivityResolver(Collections.singletonList(event(10, data())), catalog());
		for (int type : Arrays.asList(5, 3, 999999))
			assertTrue(resolver.resolve(hit().toBuilder().hitsplatType(type).build()).getCandidates().isEmpty());
		assertTrue(resolver.resolve(hit().toBuilder().hitsplatTypeKnown(false).build()).getCandidates().isEmpty());
		assertEquals(1, resolver.resolve(hit().toBuilder().hitsplatType(12).amount(0).build()).getCandidates().size());
		assertEquals(1, resolver.resolve(hit()).getCandidates().size());
	}
}
