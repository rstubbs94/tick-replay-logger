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
import com.tickreplaylogger.model.world.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiFunction;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class EncounterModeCaptureTest
{
	private final Gson gson = new Gson();
	private final CombatCatalog catalog = CombatCatalog.load(gson);
	private int level = 300, region = 15700, league;
	private boolean failLevel, failWorldTypes;
	private EnumSet<WorldType> types = EnumSet.noneOf(WorldType.class);

	private JsonObject mode()
	{
		JsonObject c = gson.toJsonTree(context()).getAsJsonObject();
		assertTrue("A qualified level interpretation must be exported separately", c.has("modeByRaidLevel"));
		return c.getAsJsonObject("modeByRaidLevel");
	}

	@Test
	public void exactBoundaryLevelsClassifyWithoutAddingActiveInvocationsOrDamage()
	{
		int[] levels =
		{ 0, 149, 150, 299, 300, 600, Integer.MAX_VALUE };
		String[] expected =
		{ "entry", "entry", "normal", "normal", "expert", "expert", "expert" };
		for (int i = 0; i < levels.length; i++)
		{
			level = levels[i];
			JsonObject m = mode();
			assertTrue(m.get("known").getAsBoolean());
			assertEquals(expected[i], m.get("state").getAsString());
			assertEquals(level, m.get("rawValue").getAsInt());
			ContextBlock c = context();
			assertNull(c.getInvocations());
			assertNull(c.getChallengeMode());
		}
	}

	@Test
	public void failedNegativeOrSpecialWorldSamplesNeverBecomeDefaultEntryMode()
	{
		failLevel = true;
		assertFalse(mode().get("known").getAsBoolean());
		assertFalse(mode().has("rawValue"));
		failLevel = false;
		level = -1;
		assertFalse(mode().get("known").getAsBoolean());
		assertEquals(-1, mode().get("rawValue").getAsInt());
		level = 300;
		failWorldTypes = true;
		assertFalse(mode().get("known").getAsBoolean());
		failWorldTypes = false;
		types = EnumSet.of(WorldType.SEASONAL);
		assertFalse(mode().get("known").getAsBoolean());
		types = EnumSet.noneOf(WorldType.class);
		league = 1;
		assertFalse(mode().get("known").getAsBoolean());
	}

	@Test
	public void anOutsidePresetIsNotARecordedToAMode()
	{
		region = 12850;
		JsonObject c = gson.toJsonTree(context()).getAsJsonObject();
		assertFalse(c.has("modeByRaidLevel"));
		assertEquals(300, c.getAsJsonObject("observedVariables").get("TOA_CLIENT_RAID_LEVEL").getAsInt());
	}

	@Test
	public void changedClientTargetCannotApplyThePinnedReference()
	{
		assertTrue(mode().get("known").getAsBoolean());
		CombatCatalog altered = CombatCatalog.referenceView(RuntimeCatalog.load(gson).section("references").deepCopy());
		altered.contextBindings().getAsJsonArray("encounterModeRules").get(0).getAsJsonObject()
			.addProperty("clientArtifactSha256", String.join("", Collections.nCopies(64, "0")));
		ContextBlock.ModeReference rejected = EncounterModeCapture.interpret(context(), altered);
		assertFalse(rejected.isKnown());
		assertEquals("UNSUPPORTED_CLIENT_MODE_REFERENCE", rejected.getEvidence());
		assertTrue("The shared reviewed reference must remain usable", mode().get("known").getAsBoolean());
	}

	@Test
	public void disagreeingLevelFieldsCannotChooseACategory()
	{
		ContextBlock c = context();
		Map<String, Integer> variables = new HashMap<>(c.getObservedVariables());
		variables.put("TOA_CLIENT_RAID_LEVEL", 299);
		ContextBlock.ModeReference m = EncounterModeCapture
			.interpret(c.toBuilder().observedVariables(variables).build(), catalog);
		assertFalse(m.isKnown());
		assertEquals("CONFLICTING_RAID_LEVEL_SAMPLES", m.getEvidence());
	}

	@Test
	public void actualModeFixtureRetainsBoundaryCategoriesUnknownsAndRecovery() throws Exception
	{
		int[] levels =
		{ 0, 149, 150, 299, 300, 600, -1, 300, 300, 300, 300, 300, 300 };
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(levels.length, new com.google.gson.Gson());
		WorldCaptureService service = new WorldCaptureService();
		service.setDamageMap(DamageMap.load(gson));
		for (int i = 0; i < levels.length; i++)
		{
			level = levels[i];
			region = i == 11 ? 12850 : 15700;
			league = i == 10 ? 1 : 0;
			failLevel = i == 7;
			failWorldTypes = i == 8;
			types = i == 9 ? EnumSet.of(WorldType.SEASONAL) : EnumSet.noneOf(WorldType.class);
			WorldSnapshot frame = service.captureTick(client(), i + 1, "mode-contract", Collections.emptyList(), null,
				false, false, false);
			assertNotNull(frame);
			assertNotNull(frame.getContext());
			recorder.addSnapshot(frame);
		}
		DeathRecordingV2.Meta meta = DeathRecordingV2.Meta.builder().recordingId("synthetic-raid-level-mode")
			.snapshotTick(levels.length).referenceCatalogId(catalog.id()).captureBuildId(CaptureBuild.sourceTreeHash())
			.runeLiteApiVersion(CaptureBuild.apiVersion()).runeLiteApiArtifactSha256(CaptureBuild.apiArtifactSha256())
			.runeLiteClientArtifactSha256(CaptureBuild.clientArtifactSha256()).build();
		JsonObject out = gson.toJsonTree(recorder.buildExport(meta, DeathRecordingV2.Summary.builder().build()))
			.getAsJsonObject();
		for (JsonElement row : out.getAsJsonArray("ticks"))
			row.getAsJsonObject().remove("wallClockMs");
		JsonArray frames = out.getAsJsonArray("ticks");
		assertEquals("entry", frames.get(0).getAsJsonObject().getAsJsonObject("context")
			.getAsJsonObject("modeByRaidLevel").get("state").getAsString());
		assertFalse(frames.get(7).getAsJsonObject().getAsJsonObject("context").getAsJsonObject("modeByRaidLevel")
			.get("known").getAsBoolean());
		assertFalse(frames.get(11).getAsJsonObject().getAsJsonObject("context").has("modeByRaidLevel"));
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/raid-level-mode.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private ContextBlock context()
	{
		return EncounterContextCapture.capture(client(), player(), catalog, null);
	}

	private Player player()
	{
		return stub(Player.class,
			(n, a) -> n.equals("getWorldLocation")
				? new WorldPoint((region >> 8) * 64 + 32, (region & 255) * 64 + 32, 0)
				: n.equals("getLocalLocation") ? new LocalPoint(6400, 6400) : null);
	}

	private Client client()
	{
		Player p = player();
		return stub(Client.class, (n, a) ->
		{
			if (n.equals("getLocalPlayer"))
				return p;
			if (n.equals("getGameState"))
				return GameState.LOGGED_IN;
			if (n.equals("getWorld"))
				return 301;
			if (n.equals("getRevision"))
				return 230;
			if (n.equals("getBoostedSkillLevel") || n.equals("getRealSkillLevel"))
				return 99;
			if (n.equals("getVarbitValue"))
			{
				int id = (Integer) a[0];
				if (id == 14380)
				{
					if (failLevel)
						throw new IllegalStateException("synthetic failed level");
					return level;
				}
				return id == 10032 ? league : 0;
			}
			if (n.equals("getWorldType"))
			{
				if (failWorldTypes)
					throw new IllegalStateException("synthetic failed world types");
				return types;
			}
			return null;
		});
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> read)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (p, m, a) ->
		{
			String n = m.getName();
			if (n.equals("hashCode"))
				return System.identityHashCode(p);
			if (n.equals("equals"))
				return p == a[0];
			Object v = read.apply(n, a);
			if (v != null)
				return v;
			if (m.getReturnType() == boolean.class)
				return false;
			if (m.getReturnType() == int.class)
				return 0;
			return null;
		});
	}
}
