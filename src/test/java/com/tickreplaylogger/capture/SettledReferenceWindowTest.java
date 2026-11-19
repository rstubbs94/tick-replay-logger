/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.Gson;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettledReferenceWindowTest
{
	@Test
	public void aLaterRecordedSurfaceIsOnlyADeclaredWeakAlternativeAndCannotBecomeContactProof()
	{
		for (String scenario : Arrays.asList("active", "conditional", "later_start", "later_birth", "foreign", "finished", "warning", "non_damage", "undeclared"))
		{
			Gson gson = new Gson();
			Map<String, Object> npc = fields("kind", "npc", "ids", Collections.singletonList(990001));
			Map<String, Object> graphic = fields("kind", "graphic", "ids", Collections.singletonList(999002));
			ResolverV1 engine = new ResolverV1(ResolverTestCatalog.lower(gson.toJsonTree(fields("schemaVersion", 1,
				"purpose", "retrospective_damage_resolution", "specialRules", Arrays.asList(
					fields("id", "synthetic:actor", "pattern", "direct_attack", "source", npc,
						"signals", fields("animationIds", Collections.singletonList(999003))),
					fields("id", "synthetic:surface", "label", "A mechanic label must not become proof", "pattern", "contact_hazard", "source", graphic)))).getAsJsonObject()));
			Map<String, Object> actor = fields("actorKind", "NPC", "npcId", 990001, "name", "Actor source", "combatLevel", 100,
				"worldViewId", 0, "plane", 0, "planeKnown", true, "animationId", 999003, "normalWorldContextObserved", true,
				"modelOverridesPresent", false, "interaction", fields("reportedInteracting", true, "actorRef", "player"));
			ObservedEvent attack = ObservedEvent.builder().type("ANIMATION_CHANGED").tick(9).gameCycle(280).sequence(10)
				.actorRef("npc:source").sceneKey("room").data(actor).build();
			ObservedEvent hp = ObservedEvent.builder().type("HITSPLAT").tick(10).gameCycle(300).sequence(20).actorRef("player")
				.sceneKey("room").data(fields("amount", 7, "hitsplatType", 16, "receiverKind", "PLAYER",
					"actorState", fields("actorKind", "LOCAL_PLAYER", "worldViewId", 0, "plane", 0, "planeKnown", true,
						"worldPositionKnown", true, "worldX", 3200, "worldY", 3200))).build();
			DamageRecord hit = DamageRecord.builder().direction("RECEIVED").defenderRef("player").impactTick(10).observedCycle(300)
				.observationSequence(20L).impactSceneKey("room").impactWorldViewId(0).impactPlane(0).impactPositionKnown(true)
				.impactX(3200).impactY(3200).hitsplatType(16).hitsplatTypeKnown(true).amount(7).build();
			HazardState surface = HazardState.builder().kind("graphicsObject").id("undeclared".equals(scenario) ? 999004 : 999002)
				.graphicObservationId(77L).worldViewId("foreign".equals(scenario) ? 1 : 0).plane(0).worldX(3200).worldY(3200)
				.worldPositionKnown(true).mappedName("Observed surface").sourceEligibility("REFERENCE_CANDIDATE")
				.state("warning".equals(scenario) ? "warning" : "conditional".equals(scenario) ? "unknown" : "active")
				.damaging("conditional".equals(scenario) ? null : !"non_damage".equals(scenario)).finished("finished".equals(scenario))
				.startCycle("later_start".equals(scenario) ? 310 : 270).build();
			WorldSnapshot frame = WorldSnapshot.builder().tick(10).gameCycle(302).gameCycleKnown(true).nativeContextKnown(true)
				.sceneKey("room").worldViewId(0).plane(0).hazards(Collections.singletonList(surface)).build();
			List<ObservedEvent> observations = new ArrayList<>(Arrays.asList(attack, hp));
			if ("later_birth".equals(scenario)) observations.add(ObservedEvent.builder().type("GRAPHICS_OBJECT_CREATED")
				.tick(10).gameCycle(301).sequence(21).sceneKey("room").data(fields("id", 999002, "graphicObservationId", 77L,
					"worldViewId", 0, "plane", 0, "nativeSceneKey", "room")).build());
			DeathRecordingV2 recording = DeathRecordingV2.builder().ticks(Collections.singletonList(frame)).events(DeathRecordingV2.Events.builder()
				.damageEvents(Collections.singletonList(hit)).observations(observations).build()).build();
			String original = gson.toJson(recording);
			DamageRecord result = new ResolverV1Recording(engine, gson, recording).resolveHit(hit);
			boolean expected = Arrays.asList("active", "conditional").contains(scenario);
			SourceResolution.Candidate candidate = result.getSourceResolution().getCandidates().stream()
				.filter(value -> "Observed surface".equals(value.getName())).findFirst().orElse(null);
			assertEquals(scenario, expected, candidate != null);
			assertFalse(scenario, result.getSourceResolution().getCandidates().stream()
				.anyMatch(value -> "A mechanic label must not become proof".equals(value.getName())));
			if (candidate != null)
			{
				assertEquals(scenario, "Unknown", result.getAttackerName());
				assertTrue(result.getSourceResolution().isUnidentifiedPossible());
				assertFalse(candidate.getEvidence().stream().anyMatch(value -> value.contains("ORIGINAL_CONTACT_AGREEMENT")));
				assertTrue(candidate.getEvidence().stream().anyMatch(value -> value.contains("SETTLED")));
			}
			assertEquals(scenario, original, gson.toJson(recording));
		}
	}

	private static Map<String, Object> fields(Object... values)
	{
		Map<String, Object> fields = new LinkedHashMap<>();
		for (int index = 0; index < values.length; index += 2) fields.put((String) values[index], values[index + 1]);
		return fields;
	}
}
