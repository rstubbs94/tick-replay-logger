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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Expected numbers come from the independent primary-source review, not the shipped rule output. */
public class ReviewedWholeActorSourceTest
{
	private static DamageMap map;
	private static JsonObject expectations;
	private static final String REF = "npc:reviewed-source:v0:lifetime1";

	@BeforeClass public static void load() throws Exception
	{
		Gson gson = new Gson(); map = DamageMap.load(gson);
		try (InputStream input = ReviewedWholeActorSourceTest.class.getResourceAsStream("/com/tickreplaylogger/whole-actor-source-expectations.json"))
		{
			assertNotNull(input);
			expectations = gson.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class);
		}
		assertEquals(58, expectations.get("declaredGuards").getAsInt());
		assertEquals(79, expectations.getAsJsonArray("cases").size());
	}

	@AfterClass public static void release()
	{
		map = null;
		expectations = null;
	}

	private static SourceResolution resolve(JsonObject sample, int amount, int control)
	{
		NpcState actor = NpcState.builder().actorRef(REF).npcId(sample.get("npcId").getAsInt())
			.name(sample.get("name").getAsString()).combatLevel(control == 1 ? null : sample.get("level").getAsInt())
			.plane(0).worldViewId(0).planeKnown(true).normalWorldContextObserved(control == 2 ? null : true)
			.modelOverridesPresent(control == 3).build();
		NpcState other = NpcState.builder().actorRef("npc:unreviewed-alternative").npcId(999911).name("Other observed actor")
			.combatLevel(99).worldViewId(0).plane(0).planeKnown(true).build();
		WorldSnapshot frame = WorldSnapshot.builder().tick(4).sceneKey("room").worldViewId(0).plane(0).nativeContextKnown(true)
			.player(PlayerState.builder().worldViewId(0).plane(0).planeKnown(true).worldPositionKnown(true).worldX(100).worldY(200).build())
			.npcs(Arrays.asList(actor, other)).projectiles(Collections.emptyList()).hazards(Collections.emptyList()).build();
		DamageRecord hit = DamageRecord.builder().impactTick(4).direction("RECEIVED").defenderRef(control == 5 ? null : "player")
			.impactSceneKey("room").impactWorldViewId(0).impactPlane(0).impactPositionKnown(true).impactX(100).impactY(200)
			.hitsplatTypeKnown(true).hitsplatType(control == 4 ? 5 : 16).observationSequence(7L).amount(amount)
			.attributionCandidates(Arrays.asList(witness(actor), witness(other))).attackerName("Unknown").build();
		return new RecordedSourceResolver(Collections.singletonList(frame), Collections.emptyList(), map,
			Collections.emptyList(), Collections.emptyList()).resolve(hit);
	}

	private static Map<String, Object> witness(NpcState actor)
	{
		Map<String, Object> out = new HashMap<>(); out.put("kind", "NPC_ANIMATION_CORRELATION");
		out.put("actorRef", actor.getActorRef()); out.put("name", actor.getName()); out.put("npcId", actor.getNpcId());
		out.put("animationId", 999912); return out;
	}

	private static SourceResolution.Candidate actor(SourceResolution result, boolean excluded)
	{
		List<SourceResolution.Candidate> candidates = excluded ? result.getExcludedCandidates() : result.getCandidates();
		if (candidates == null) return null;
		return candidates.stream().filter(c -> REF.equals(c.getActorRef())).findFirst().orElse(null);
	}

	@Test public void everyReviewedExactVariantUsesItsCeilingAndRetainsBoundaryDamage()
	{
		for (JsonElement value : expectations.getAsJsonArray("cases"))
		{
			JsonObject sample = value.getAsJsonObject(); String key = sample.get("rule").getAsString();
			int maximum = sample.get("maximum").getAsInt();
			SourceResolution.Candidate within = actor(resolve(sample, maximum, 0), false);
			assertNotNull(sample.toString(), within);
			assertTrue(sample.toString(), within.getEvidence().stream().anyMatch(e -> e.startsWith("QUALIFIED_MAXIMUM:" + key + ":")));
			SourceResolution exceeded = resolve(sample, maximum + 1, 0);
			assertNull(sample.toString(), actor(exceeded, false));
			assertNotNull(sample.toString(), actor(exceeded, true));
			assertTrue(sample.toString(), actor(exceeded, true).getReasons().stream().anyMatch(e -> e.startsWith("MAXIMUM_EXCEEDED:" + key + ":")));
			assertTrue(exceeded.getCandidates().stream().anyMatch(c -> "Other observed actor".equals(c.getName())));
		}
	}

	@Test public void missingFactsOverridesStatusAndMissingRecipientCannotActivateAnyNewGuard()
	{
		for (JsonElement value : expectations.getAsJsonArray("cases"))
		{
			JsonObject sample = value.getAsJsonObject(); String key = sample.get("rule").getAsString();
			for (int control = 1; control <= 5; control++)
			{
				SourceResolution result = resolve(sample, sample.get("maximum").getAsInt() + 1, control);
				SourceResolution.Candidate kept = actor(result, false);
				assertNotNull(sample + " control=" + control, kept);
				assertFalse(sample + " control=" + control, kept.getEvidence().stream().anyMatch(e -> e.startsWith("QUALIFIED_MAXIMUM:" + key + ":")));
			}
		}
	}
}
