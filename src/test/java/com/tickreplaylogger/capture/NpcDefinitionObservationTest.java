/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
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

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.runelite.api.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class NpcDefinitionObservationTest
{
	@Test
	public void everyArchivedTransformKeepsBaseIdentitySeparateFromUnresolvedAndObservedTargetDefinitions()
		throws Exception
	{
		int unresolved = 0, resolved = 0;
		for (JsonObject row : NpcDefinitionTestData.transforms())
		{
			int baseId = row.get("npcId").getAsInt();
			int[] configs = NpcDefinitionTestData.configs(baseId);
			NPCComposition base = definition(baseId, configs);
			NPC missing = npc(baseId, base, null, false);
			NpcDefinitionObservation observation = NpcDefinitionObservation.capture(missing, new LinkedHashMap<>(), "");
			assertEquals(Integer.valueOf(baseId), observation.getBaseId());
			assertNull(observation.getId());
			assertEquals(Boolean.TRUE, observation.getHasTransforms());
			assertArrayEquals(configs, observation.getTransforms());
			assertEquals("TRANSFORM_UNAVAILABLE", observation.getEvidence());
			assertNull(observation.getSize());
			unresolved++;
			Set<Integer> targets = new LinkedHashSet<>();
			for (int id : configs)
				if (id >= 0)
					targets.add(id);
			for (int id : targets)
			{
				NPCComposition effective = definition(id, null);
				NpcDefinitionObservation known = NpcDefinitionObservation.capture(npc(baseId, base, effective, false),
					new LinkedHashMap<>(), "");
				assertEquals(Integer.valueOf(baseId), known.getBaseId());
				assertEquals(Integer.valueOf(id), known.getId());
				assertEquals("TRANSFORMED_COMPOSITION", known.getEvidence());
				assertEquals(Integer.valueOf(NpcDefinitionTestData.identity(id).get("size").getAsInt()),
					known.getSize());
				resolved++;
			}
		}
		assertEquals(2568, unresolved);
		assertTrue(resolved > 0);
		JsonObject counts = new JsonObject();
		counts.addProperty("archivedTransformDefinitions", unresolved);
		counts.addProperty("controlledResolvedTargetCases", resolved);
		counts.addProperty("liveMechanicCertification", false);
		Path path = Paths.get("build/test-fixtures/npc-definition-contract-counts.json");
		Files.createDirectories(path.getParent());
		Files.write(path, new Gson().toJson(counts).getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void missingBaseContextCannotEstablishANonTransformingBaseButAKnownEffectiveDefinitionSurvives()
	{
		NPCComposition effective = definition(9036, null);
		for (boolean known : new boolean[]
		{ false, true })
		{
			Map<String, String> issues = new LinkedHashMap<>();
			NpcDefinitionObservation observed = NpcDefinitionObservation
				.capture(npc(9036, null, known ? effective : null, true), issues, "");
			assertEquals(Integer.valueOf(9036), observed.getBaseId());
			assertNull(observed.getHasTransforms());
			assertTrue(issues.containsKey("baseComposition"));
			if (known)
			{
				assertEquals(Integer.valueOf(9036), observed.getId());
				assertEquals(Integer.valueOf(5), observed.getSize());
			}
			else
			{
				assertNull(observed.getId());
				assertEquals("DEFINITION_CONTEXT_UNAVAILABLE", observed.getEvidence());
			}
		}
	}

	@Test
	public void failedEffectiveIdDoesNotEraseKnownDefinitionNameOrShape()
	{
		Map<String, String> issues = new LinkedHashMap<>();
		NPCComposition effective = NpcSnapshotCaptureTest.stub(NPCComposition.class, (method, args) ->
		{
			if (method.equals("getId"))
				throw new IllegalStateException("Synthetic unavailable definition ID");
			return method.equals("getName") ? "Corrupted Hunllef" : method.equals("getSize") ? 5 : null;
		});
		NpcDefinitionObservation observed = NpcDefinitionObservation
			.capture(npc(9036, definition(9036, null), effective, false), issues, "");
		assertNull(observed.getId());
		assertEquals(Integer.valueOf(9036), observed.getBaseId());
		assertEquals("Corrupted Hunllef", observed.getDefinitionName());
		assertEquals(Integer.valueOf(5), observed.getSize());
		assertTrue(issues.containsKey("definitionId"));
	}

	@Test
	public void successfullyObservedNoTransformsAllowsTheApiBaseFallback()
	{
		NpcDefinitionObservation observed = NpcDefinitionObservation
			.capture(npc(9036, definition(9036, null), null, false), new LinkedHashMap<>(), "");
		assertEquals(Integer.valueOf(9036), observed.getId());
		assertEquals(Boolean.FALSE, observed.getHasTransforms());
		assertEquals("NPC_API_ID", observed.getEvidence());
	}

	private static NPC npc(int id, NPCComposition base, NPCComposition effective, boolean failedBase)
	{
		return NpcSnapshotCaptureTest.stub(NPC.class, (method, args) ->
		{
			switch (method)
			{
			case "getId":
				return id;
			case "getComposition":
				if (failedBase)
					throw new IllegalStateException("Synthetic unavailable base");
				return base;
			case "getTransformedComposition":
				return effective;
			default:
				return null;
			}
		});
	}

	private static NPCComposition definition(int id, int[] configs)
	{
		JsonObject row = Objects.requireNonNull(NpcDefinitionTestData.identity(id), "Archived definition " + id);
		return NpcSnapshotCaptureTest.stub(NPCComposition.class, (method, args) ->
		{
			switch (method)
			{
			case "getId":
				return id;
			case "getName":
				return row.get("name").isJsonNull() ? null : row.get("name").getAsString();
			case "getSize":
				return row.get("size").getAsInt();
			case "getConfigs":
				return configs;
			default:
				return null;
			}
		});
	}
}
