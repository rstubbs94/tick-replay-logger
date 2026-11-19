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
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.HazardState;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.SceneData;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObservedObjectFootprintTest
{
	private static DamageMap map;
	@BeforeClass public static void load() { map = DamageMap.load(new Gson()); }
	@AfterClass public static void release() { map = null; }
	private HazardState hazard() { return hazardBuilder().build(); }
	private HazardState.HazardStateBuilder hazardBuilder()
	{
		return HazardState.builder().id(36199).baseId(36199).kind("gameObject").worldViewId(0).plane(0)
			.objectNativeScopeKnown(true).objectWorldViewId(0).objectPlane(0)
			.mappedName("Orange demonic symbol").sourceEligibility("CONDITION_NOT_OBSERVED").state("conditional")
			.minSceneX(0).minSceneY(0).maxSceneX(2).maxSceneY(2).worldX(101).worldY(201)
			.worldPositionKnown(true).localPositionKnown(true);
	}
	private DamageRecord hit()
	{
		return DamageRecord.builder().impactTick(4).observationSequence(7L).observedCycle(120).direction("RECEIVED")
			.defenderRef("player").impactSceneKey("room").impactWorldViewId(0).impactPlane(0).impactPositionKnown(true)
			.impactX(101).impactY(201).amount(8).hitsplatTypeKnown(true).hitsplatType(16).build();
	}
	private SourceResolution resolve(HazardState hazard, DamageRecord hit, boolean includeScene)
	{
		return resolve(hazard, hit, includeScene ? SceneData.builder().sceneKey("room").worldViewId(0)
			.plane(0).baseX(100).baseY(200).width(104).height(104).build() : null);
	}
	private SourceResolution resolve(HazardState hazard, DamageRecord hit, SceneData scene)
	{
		WorldSnapshot frame = WorldSnapshot.builder().tick(4).sceneKey("room").worldViewId(0).plane(0).nativeContextKnown(true)
			.player(PlayerState.builder().worldViewId(0).plane(0).planeKnown(true).worldPositionKnown(true).worldX(101).worldY(201).build())
			.npcs(Collections.emptyList()).projectiles(Collections.emptyList())
			.hazards(hazard == null ? Collections.emptyList() : Collections.singletonList(hazard)).build();
		List<SceneData> scenes = scene == null ? Collections.emptyList() : Collections.singletonList(scene);
		return new RecordedSourceResolver(Collections.singletonList(frame), scenes, map, Collections.emptyList(), Collections.emptyList()).resolve(hit);
	}
	private boolean named(SourceResolution result)
	{ return result.getCandidates().stream().anyMatch(c -> "Orange demonic symbol".equals(c.getName())); }

	@Test public void actualBoundsWorkWithoutARenderedCentreAndKeepActivationUncertain()
	{
		SourceResolution result = resolve(hazardBuilder().localPositionKnown(false).worldPositionKnown(false).build(), hit(), true);
		assertTrue(named(result)); assertTrue(result.isUnidentifiedPossible());
		assertFalse(named(resolve(hazard(), hit().toBuilder().impactX(103).build(), true)));
	}

	@Test public void absentInvalidOrForeignBoundsNeverFallBackToTheWorldPoint()
	{
		for (HazardState invalid : Arrays.asList(hazardBuilder().minSceneX(null).build(),
			hazardBuilder().maxSceneY(null).build(), hazardBuilder().minSceneX(3).build(),
			hazardBuilder().minSceneY(-1).build(), hazardBuilder().maxSceneY(104).build(),
			hazardBuilder().objectNativeScopeKnown(false).build(), hazardBuilder().objectWorldViewId(7).build(),
			hazardBuilder().objectPlane(1).build(),
			hazardBuilder().kind("groundObject").build()))
		{
			SourceResolution result = resolve(invalid, hit(), true);
			assertFalse(named(result)); assertTrue(result.isUnidentifiedPossible());
		}
		assertFalse(named(resolve(hazard(), hit(), false)));
		assertFalse(named(resolve(hazardBuilder().worldViewId(7).build(), hit(), true)));
		for (SceneData foreign : Arrays.asList(SceneData.builder().sceneKey("room").worldViewId(7).plane(0)
			.baseX(100).baseY(200).width(104).height(104).build(), SceneData.builder().sceneKey("room").worldViewId(0).plane(1)
			.baseX(100).baseY(200).width(104).height(104).build()))
			assertFalse(named(resolve(hazard(), hit(), foreign)));
	}

	@Test public void callbackContactRequiresActualGameObjectMembershipAtTheReportedServerTile()
	{
		Map<String, Object> contact = new HashMap<>();
		contact.put("kind", "OBJECT_SURFACE"); contact.put("objectId", 36199); contact.put("name", "Orange demonic symbol");
		contact.put("objectKind", "gameObject"); contact.put("scopeEvidence", "NATIVE_SCENE_TILE_MEMBERSHIP");
		contact.put("defenderPositionEvidence", "SERVER_REPORTED_TILE"); contact.put("sourceEligibility", "CONDITION_NOT_OBSERVED");
		contact.put("sceneKey", "room"); contact.put("worldViewId", 0); contact.put("plane", 0);
		contact.put("objectNativeScopeKnown", true); contact.put("objectWorldViewId", 0); contact.put("objectPlane", 0);
		assertTrue(named(resolve(null, hit().toBuilder().observedHazardContacts(Collections.singletonList(contact)).build(), true)));
		for (String field : Arrays.asList("objectKind", "scopeEvidence", "defenderPositionEvidence", "objectNativeScopeKnown", "objectWorldViewId", "objectPlane"))
		{
			Map<String, Object> changed = new HashMap<>(contact); changed.remove(field);
			assertFalse(field, named(resolve(null, hit().toBuilder().observedHazardContacts(Collections.singletonList(changed)).build(), true)));
		}
		Map<String, Object> foreign = new HashMap<>(contact); foreign.put("objectWorldViewId", 7);
		assertFalse(named(resolve(null, hit().toBuilder().observedHazardContacts(Collections.singletonList(foreign)).build(), true)));
	}

	@Test public void aRejectedDefinitionCannotReappearThroughItsRetainedName()
	{
		Map<String, Object> contact = new HashMap<>();
		contact.put("kind", "OBJECT_SURFACE"); contact.put("objectId", 36199); contact.put("name", "Orange demonic symbol");
		contact.put("sourceEligibility", "DEFINITION_NOT_QUALIFIED"); contact.put("sceneKey", "room");
		contact.put("worldViewId", 0); contact.put("plane", 0);
		Map<String, Object> retained = new HashMap<>();
		retained.put("kind", "HAZARD_ON_IMPACT_TILE"); retained.put("name", "Orange demonic symbol");
		SourceResolution result = resolve(null, hit().toBuilder().attributionCandidates(Collections.singletonList(retained))
			.observedHazardContacts(Collections.singletonList(contact)).build(), true);
		assertFalse(named(result)); assertTrue(result.isUnidentifiedPossible());
	}

	@Test public void typedStatusMixedUnknownAndHealingDoNotEnterTheOptionalFootprintHint()
	{
		for (int type : Arrays.asList(5, 65, 3, 6, 999999))
			assertFalse("type " + type, named(resolve(hazard(), hit().toBuilder().hitsplatType(type).build(), true)));
	}

	@Test public void anIndependentGraphicNamespaceCannotInheritAnObjectsFootprintRequirements()
	{
		HazardState graphic = hazardBuilder().kind("graphicsObject").mappedName("Recorded graphic source")
			.sourceEligibility("ELIGIBLE_REFERENCE_SURFACE").damaging(true).state("active")
			.minSceneX(null).minSceneY(null).maxSceneX(null).maxSceneY(null).build();
		SourceResolution result = resolve(graphic, hit(), true);
		assertTrue(result.getCandidates().stream().anyMatch(candidate -> "Recorded graphic source".equals(candidate.getName())
			&& "graphic-definition:36199".equals(candidate.getFamilyKey())));
	}
}
