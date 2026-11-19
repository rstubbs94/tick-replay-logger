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

/** Optional context failures must not delete unrelated raid observations or imply negative state. */
public class EncounterContextFailureTest
{
	private final Gson gson = new Gson();
	private final CombatCatalog catalog = CombatCatalog.load(gson);
	private final Map<Integer, Integer> values = new HashMap<>();
	private final Set<String> failures = new HashSet<>();
	private int region = 15700;

	private ContextBlock capture()
	{
		return EncounterContextCapture.capture(client(), player(), catalog, null);
	}

	@Test
	public void worldIdentityFailureCannotEraseObservedRaidState()
	{
		values.put(14380, 300);
		failures.add("getWorld");
		ContextBlock c = capture();
		assertEquals("toa", c.getActivity());
		assertEquals(Integer.valueOf(300), c.getRaidLevel());
		assertNull(c.getWorldId());
		assertEquals("UNAVAILABLE", c.getEvidence().get("worldId"));
		assertEquals(Integer.valueOf(230), c.getGameRevision());
	}

	@Test
	public void failedInstanceReadCannotTreatRenderedCoordinatesAsTemplateRaidPresence()
	{
		values.put(14380, 300);
		failures.add("isInInstancedRegion");
		ContextBlock c = capture();
		assertNull(c.isInInstance());
		assertNull(c.getTemplateRegionId());
		assertNull(c.getActivity());
		assertNull(c.getRaidLevel());
		assertEquals(Integer.valueOf(300), c.getObservedVariables().get("TOA_CLIENT_RAID_LEVEL"));
	}

	@Test
	public void failedWildernessReadStaysUnknownWithoutErasingTheRaid()
	{
		failures.add("wilderness");
		ContextBlock c = capture();
		assertNull(c.isInWilderness());
		assertEquals("toa", c.getActivity());
		assertEquals("UNAVAILABLE", c.getEvidence().get("wilderness"));
		assertFalse(gson.toJsonTree(c).getAsJsonObject().has("inWilderness"));
	}

	@Test
	public void unexpectedWildernessValueIsRetainedWithoutBecomingOutsideWilderness()
	{
		values.put(5963, 7);
		ContextBlock c = capture();
		assertNull(c.isInWilderness());
		assertEquals(Integer.valueOf(7), c.getObservedVariables().get("INSIDE_WILDERNESS"));
		assertEquals("UNRECOGNIZED_VALUE", c.getEvidence().get("wilderness"));
	}

	@Test
	public void failedTemplateAndWorldLocationReadsCannotEraseIndependentRaidVariables()
	{
		region = 12850;
		values.put(5432, 1);
		values.put(5424, 3);
		failures.add("getWorldLocation");
		ContextBlock c = capture();
		assertEquals("cox", c.getActivity());
		assertEquals(Integer.valueOf(3), c.getTeamSize());
		assertNull(c.getTemplateRegionId());
		assertEquals("UNAVAILABLE", c.getEvidence().get("templateRegionId"));
	}

	@Test
	public void failedBossHpReadCannotDiscardTheContextChannel()
	{
		failures.add("getInteracting");
		values.put(14380, 300);
		WorldCaptureService service = new WorldCaptureService();
		service.setDamageMap(DamageMap.load(gson));
		WorldSnapshot tick = service.captureTick(client(), 1, "context", Collections.emptyList(), null, false, false,
			false);
		assertNotNull(tick.getContext());
		assertEquals("toa", tick.getContext().getActivity());
		assertEquals(Integer.valueOf(300), tick.getContext().getRaidLevel());
		assertNull(tick.getContext().getBossHpRatio());
		assertEquals("UNAVAILABLE", tick.getContext().getEvidence().get("bossHpRatio"));
		assertEquals("partial", tick.getCaptureCoverage().get("context"));
	}

	@Test
	public void actualContextFixtureRetainsPartialFieldsRecoveryAndScopeChanges() throws Exception
	{
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(7, new com.google.gson.Gson());
		WorldCaptureService service = new WorldCaptureService();
		service.setDamageMap(DamageMap.load(gson));
		for (int tick = 1; tick <= 7; tick++)
		{
			failures.clear();
			values.clear();
			region = 15700;
			values.put(14380, 150);
			values.put(14347, 30);
			values.put(14348, 15);
			if (tick == 2)
			{
				values.put(14380, 0);
				failures.addAll(Arrays.asList("getWorld", "getRevision", "wilderness", "getInteracting"));
			}
			if (tick == 3)
			{
				values.put(14380, 300);
				failures.add("isInInstancedRegion");
			}
			if (tick == 4)
			{
				values.put(14380, 300);
				values.put(5963, 7);
			}
			if (tick >= 5)
			{
				region = 12850;
				values.put(14380, 300);
			}
			if (tick == 5)
			{
				values.put(5432, 1);
				values.put(5424, 3);
				values.put(9540, 5);
				values.put(6385, 1);
			}
			if (tick == 6)
				values.put(5963, 1);
			WorldSnapshot snapshot = service.captureTick(client(), tick, "context-fixture", Collections.emptyList(),
				null, false, false, false);
			assertNotNull(snapshot);
			assertNotNull(snapshot.getContext());
			recorder.addSnapshot(snapshot);
		}
		DeathRecordingV2.Meta meta = DeathRecordingV2.Meta.builder().recordingId("synthetic-encounter-context")
			.snapshotTick(7).referenceCatalogId(catalog.id()).captureBuildId(CaptureBuild.sourceTreeHash())
			.runeLiteApiVersion(CaptureBuild.apiVersion()).runeLiteApiArtifactSha256(CaptureBuild.apiArtifactSha256())
			.runeLiteClientArtifactSha256(CaptureBuild.clientArtifactSha256()).build();
		JsonObject out = gson.toJsonTree(recorder.buildExport(meta, DeathRecordingV2.Summary.builder().build()))
			.getAsJsonObject();
		for (JsonElement value : out.getAsJsonArray("ticks"))
			value.getAsJsonObject().remove("wallClockMs");
		JsonArray ticks = out.getAsJsonArray("ticks");
		assertFalse(ticks.get(1).getAsJsonObject().getAsJsonObject("context").has("inWilderness"));
		assertEquals(3, ticks.get(4).getAsJsonObject().getAsJsonObject("context").get("teamSize").getAsInt());
		assertEquals("wilderness",
			ticks.get(5).getAsJsonObject().getAsJsonObject("context").get("activity").getAsString());
		assertFalse(ticks.get(6).getAsJsonObject().getAsJsonObject("context").has("raidLevel"));
		java.nio.file.Path path = java.nio.file.Paths.get("build/test-fixtures/encounter-context.json");
		java.nio.file.Files.createDirectories(path.getParent());
		java.nio.file.Files.write(path, gson.toJson(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private Player player()
	{
		return stub(Player.class, (n, a) ->
		{
			if (failures.contains(n))
				throw new IllegalStateException("synthetic optional failure");
			return n.equals("getWorldLocation") ? new WorldPoint((region >> 8) * 64 + 32, (region & 255) * 64 + 32, 0)
				: n.equals("getLocalLocation") ? new LocalPoint(6400, 6400) : null;
		});
	}

	private Client client()
	{
		Player p = player();
		return stub(Client.class, (n, a) ->
		{
			if (failures.contains(n))
				throw new IllegalStateException("synthetic optional failure");
			if (n.equals("getLocalPlayer"))
				return p;
			if (n.equals("getWorld"))
				return 301;
			if (n.equals("getRevision"))
				return 230;
			if (n.equals("getVarbitValue"))
			{
				int id = (Integer) a[0];
				if (id == 5963 && failures.contains("wilderness"))
					throw new IllegalStateException("synthetic optional failure");
				return values.getOrDefault(id, 0);
			}
			if (n.equals("getBoostedSkillLevel") || n.equals("getRealSkillLevel"))
				return 99;
			if (n.equals("getWorldType"))
				return EnumSet.noneOf(WorldType.class);
			if (n.equals("getGameState"))
				return GameState.LOGGED_IN;
			return null;
		});
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> values)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (p, m, a) ->
		{
			String n = m.getName();
			if (n.equals("hashCode"))
				return System.identityHashCode(p);
			if (n.equals("equals"))
				return p == a[0];
			Object v = values.apply(n, a);
			if (v != null)
				return v;
			if (m.getReturnType() == boolean.class)
				return false;
			if (m.getReturnType() == int.class)
				return n.contains("Animation") || n.equals("getGraphic") ? -1 : 0;
			if (m.getReturnType() == long.class)
				return 0L;
			return null;
		});
	}
}
