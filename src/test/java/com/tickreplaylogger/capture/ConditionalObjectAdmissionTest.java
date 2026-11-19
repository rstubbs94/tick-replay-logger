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
import com.tickreplaylogger.model.world.ContextBlock;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.HazardState;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.SceneData;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Held-out source identity, original HP clock and alternate entry-route regressions. */
public class ConditionalObjectAdmissionTest
{
	private static final int ID = 987601;
	private static final String NAME = "Observed conditional contact";
	private JsonObject admission()
	{
		return new Gson().fromJson("{outcomeDomain:'ORDINARY_HP_OR_BLOCK_CALLBACK_ONLY',observedHitsplatTypes:[12,13,16,17,43],"
			+ "perHitMaximum:8,maximumWitness:{page:'Held-out surface',pageHash:'" + String.join("", Collections.nCopies(64, "a"))
			+ "',start:2,end:3,text:'8',quantity:'OBJECT_CONTACT_HP_PER_CALLBACK_MAXIMUM'}}", JsonObject.class);
	}
	private DamageMap map(JsonObject admission) throws Exception
	{
		JsonObject root = new Gson().fromJson("{catalogId:'anonymous',hitsplatRules:{rules:{"
			+ "'12':{surface:'BLOCK'},'13':{surface:'BLOCK'},'16':{surface:'HP'},'17':{surface:'HP'},'43':{surface:'HP'},"
			+ "'65':{surface:'HP',damageType:'poison'},'5':{surface:'HP',damageType:'venom'},"
			+ "'3':{surface:'SKILL_OR_HP'},'6':{surface:'HEAL'}}}}", JsonObject.class);
		CombatCatalog catalog = new CombatCatalog(); set(catalog, "root", root);
		JsonObject rule = new Gson().fromJson("{key:'held-out-contact',name:'" + NAME
			+ "',state:'conditional',surfaceOperator:'CONDITIONAL_HP_TILE',contactMode:'same_server_tile'}", JsonObject.class);
		if (admission != null) rule.add("contactAdmission", admission);
		DamageMap map = new DamageMap(); set(map, "catalog", catalog);
		set(map, "objectHazardRules", Collections.singletonMap(String.valueOf(ID), new Gson().fromJson(rule, DamageMap.ObjectHazardRule.class)));
		return map;
	}
	private static void set(Object target, String name, Object value) throws Exception
	{ Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
	private DamageRecord hit(int amount)
	{
		return DamageRecord.builder().direction("RECEIVED").defenderRef("player").observationSequence(7L).observedCycle(120)
			.impactTick(4).impactSceneKey("native").impactWorldViewId(0).impactPlane(0).impactPositionKnown(true)
			.impactX(101).impactY(201).amount(amount).hitsplatTypeKnown(true).hitsplatType(16).build();
	}
	private Map<String, Object> callback()
	{
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("kind", "OBJECT_SURFACE"); c.put("objectId", ID); c.put("name", NAME); c.put("objectKind", "groundObject");
		c.put("sceneKey", "native"); c.put("worldViewId", 0); c.put("plane", 0); c.put("objectNativeScopeKnown", true);
		c.put("objectWorldViewId", 0); c.put("objectPlane", 0); c.put("sourceEligibility", "CONDITION_NOT_OBSERVED");
		c.put("scopeEvidence", "NATIVE_SCENE_TILE_MEMBERSHIP"); c.put("defenderPositionEvidence", "SERVER_REPORTED_TILE");
		c.put("contactWorldX", 101); c.put("contactWorldY", 201); return c;
	}
	private HazardState.HazardStateBuilder hazard()
	{
		return HazardState.builder().id(ID).baseId(ID).kind("groundObject").worldViewId(0).plane(0)
			.objectNativeScopeKnown(true).objectWorldViewId(0).objectPlane(0).mappedName(NAME).state("conditional")
			.sourceEligibility("CONDITION_NOT_OBSERVED").worldPositionKnown(true).worldX(101).worldY(201);
	}
	private ObservedEvent hp(DamageRecord hit, String worldType)
	{
		ContextBlock context = ObservedHpCallbackContextTest.normal().toBuilder()
			.worldTypes(worldType == null ? Collections.emptyList() : Collections.singletonList(worldType)).build();
		ObservedEvent event = ObservedHpCallbackContextTest.observation(hit, context);
		@SuppressWarnings("unchecked") Map<String, Object> actor = (Map<String, Object>) event.getData().get("actorState");
		actor.put("worldX", hit.getImpactX()); actor.put("worldY", hit.getImpactY()); return event;
	}
	private SourceResolution resolve(DamageMap map, DamageRecord hit, HazardState settled, ObservedEvent original)
	{
		WorldSnapshot frame = WorldSnapshot.builder().tick(4).sceneKey("native").worldViewId(0).plane(0).nativeContextKnown(true)
			.player(PlayerState.builder().worldViewId(0).plane(0).planeKnown(true).worldPositionKnown(true).worldX(101).worldY(201).build())
			.npcs(Collections.emptyList()).projectiles(Collections.emptyList())
			.hazards(settled == null ? Collections.emptyList() : Collections.singletonList(settled)).build();
		SceneData scene = SceneData.builder().sceneKey("native").worldViewId(0).plane(0).baseX(100).baseY(200).width(104).height(104).build();
		return new RecordedSourceResolver(Collections.singletonList(frame), Collections.singletonList(scene), map,
			original == null ? Collections.emptyList() : Collections.singletonList(original), Collections.emptyList()).resolve(hit);
	}
	private boolean named(SourceResolution result)
	{ return result.getCandidates().stream().anyMatch(c -> NAME.equals(c.getName())); }
	private DamageRecord callbackHit(int amount)
	{ return hit(amount).toBuilder().observedHazardContacts(Collections.singletonList(callback())).build(); }

	@Test public void optInHintAdmitsOrdinaryAndBlockButNotPoisonVenomMixedResourceHealingOrUnknown() throws Exception
	{
		DamageMap map = map(admission());
		for (int type : Arrays.asList(12, 13, 16, 17, 43, 5, 65, 3, 6, 999999))
		{
			DamageRecord hit = callbackHit(0).toBuilder().hitsplatType(type).build();
			boolean expected = Arrays.asList(12, 13, 16, 17, 43).contains(type);
			for (boolean callback : Arrays.asList(true, false))
			{
				DamageRecord observed = callback ? hit : hit.toBuilder().observedHazardContacts(Collections.emptyList()).build();
				SourceResolution result = resolve(map, observed, callback ? null : hazard().build(), hp(observed, null));
				assertEquals("type " + type + ", callback " + callback, expected, named(result));
				assertTrue(result.isUnidentifiedPossible());
			}
		}
	}

	@Test public void qualifiedMaximumExcludesBothEntryRoutesWithoutChangingRawHpOrAnIndependentRival() throws Exception
	{
		DamageMap map = map(admission());
		for (boolean callback : Arrays.asList(true, false)) for (int amount : Arrays.asList(8, 9, 50))
		{
			DamageRecord hit = callback ? callbackHit(amount) : hit(amount);
			hit = hit.toBuilder().attributionCandidates(Collections.singletonList(Collections.<String, Object>singletonMap("kind", "unused"))).build();
			SourceResolution result = resolve(map, hit, callback ? null : hazard().build(), hp(hit, null));
			assertEquals(amount <= 8, named(result)); assertEquals(amount, hit.getAmount()); assertTrue(result.isUnidentifiedPossible());
			assertEquals(amount > 8, result.getExcludedCandidates() != null && result.getExcludedCandidates().stream().anyMatch(c -> NAME.equals(c.getName())
				&& c.getReasons().toString().contains("MAXIMUM_EXCEEDED:held-out-contact:contact-hp-maximum")));
		}
		Map<String, Object> rival = new LinkedHashMap<>(); rival.put("kind", "ITEM_CONSUMPTION_CANDIDATE");
		rival.put("name", "Independent observed cause"); rival.put("sourceRef", "fixture:other");
		DamageRecord hit = callbackHit(50).toBuilder().attributionCandidates(Collections.singletonList(rival)).build();
		SourceResolution result = resolve(map, hit, null, hp(hit, null));
		assertFalse(named(result)); assertTrue(result.getCandidates().stream().anyMatch(c -> "Independent observed cause".equals(c.getName())));
	}

	@Test public void missingOrExceptionalOriginalModeKeepsAnUncappedHintAndLaterContextCannotSupplyIt() throws Exception
	{
		DamageMap map = map(admission()); DamageRecord hit = callbackHit(50);
		for (String world : Arrays.asList("SEASONAL", "DEADMAN", "TOURNAMENT", "BETA"))
			assertTrue(world, named(resolve(map, hit, null, hp(hit, world))));
		ObservedEvent event = hp(hit, null);
		@SuppressWarnings("unchecked") Map<String, Object> numeric = (Map<String, Object>) event.getData().get("numericContext");
		numeric.remove("worldTypes"); assertTrue(named(resolve(map, hit, null, event)));
		event.getData().remove("numericContext"); assertTrue(named(resolve(map, hit, null, event)));
		assertFalse(named(resolve(map, hit, null, null))); // Original callback/position unavailable: optional admission is unestablished.
		assertFalse(named(resolve(map, hit.toBuilder().observedCycle(121).build(), null, event)));
		DamageRecord negative = callbackHit(-1); assertFalse(named(resolve(map, negative, null, hp(negative, null))));
	}

	@Test public void nativeMissingForeignOrRenderedOnlyContactCannotReturnThroughTheLegacyName() throws Exception
	{
		DamageMap map = map(admission());
		for (String field : Arrays.asList("objectNativeScopeKnown", "objectWorldViewId", "objectPlane", "sceneKey", "scopeEvidence", "defenderPositionEvidence", "contactWorldX", "contactWorldY"))
		{
			Map<String, Object> c = callback(); c.remove(field);
			assertRejectedWithLegacy(map, c, null);
		}
		for (String field : Arrays.asList("objectWorldViewId", "objectPlane", "worldViewId", "plane", "contactWorldX", "contactWorldY"))
		{ Map<String, Object> c = callback(); c.put(field, 7); assertRejectedWithLegacy(map, c, null); }
		for (HazardState bad : Arrays.asList(hazard().objectNativeScopeKnown(false).build(), hazard().objectWorldViewId(7).build(),
			hazard().objectPlane(1).build(), hazard().localPositionKnown(true).minSceneX(1).minSceneY(1).maxSceneX(2).maxSceneY(1).build())) assertRejectedWithLegacy(map, null, bad);
	}
	private void assertRejectedWithLegacy(DamageMap map, Map<String, Object> c, HazardState hazard) throws Exception
	{
		Map<String, Object> legacy = new LinkedHashMap<>(); legacy.put("kind", "HAZARD_ON_IMPACT_TILE"); legacy.put("name", NAME);
		DamageRecord hit = hit(8).toBuilder().attackerName(NAME).attributionCandidates(Collections.singletonList(legacy))
			.observedHazardContacts(c == null ? Collections.emptyList() : Collections.singletonList(c)).build();
		SourceResolution result = resolve(map, hit, hazard, hp(hit, null)); assertFalse(named(result)); assertTrue(result.isUnidentifiedPossible());
	}

	@Test public void anExistingConditionalTypedSurfaceWithoutAnOptInDomainKeepsItsPoisonOrVenomPossibility() throws Exception
	{
		DamageMap map = map(null);
		for (int type : Arrays.asList(5, 65))
		{
			DamageRecord hit = callbackHit(5).toBuilder().hitsplatType(type).build();
			assertTrue(named(resolve(map, hit, null, hp(hit, null))));
			assertTrue(named(resolve(map, hit.toBuilder().observedHazardContacts(Collections.emptyList()).build(), hazard().build(), hp(hit, null))));
		}
	}

	@Test public void malformedQualifierOrUnqualifiedDefinitionCannotInventAnUncappedOrdinaryHint() throws Exception
	{
		for (int change = 0; change < 4; change++)
		{
			JsonObject data = admission();
			if (change == 0) data.addProperty("inventedMode", "NORMAL");
			if (change == 1) data.addProperty("perHitMaximum", true);
			if (change == 2) data.getAsJsonArray("observedHitsplatTypes").add(65);
			if (change == 3) data.getAsJsonObject("maximumWitness").addProperty("text", "9");
			DamageRecord hit = callbackHit(8); assertFalse(named(resolve(map(data), hit, null, hp(hit, null))));
		}
		DamageMap map = map(admission());
		for (String state : Arrays.asList("STATE_UNAVAILABLE", "DEFINITION_NOT_QUALIFIED"))
		{ Map<String, Object> c = callback(); c.put("sourceEligibility", state);
		  assertRejectedWithLegacy(map, c, null); }
	}
}
