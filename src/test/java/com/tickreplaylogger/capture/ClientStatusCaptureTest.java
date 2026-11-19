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

import com.google.gson.Gson;
import com.tickreplaylogger.model.world.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiFunction;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClientStatusCaptureTest
{
	private final Gson gson = new Gson();
	private final DamageMap map = DamageMap.load(gson);

	@Test
	public void poisonProtectionBoundariesAreSeparateFromActivePoisonAndCannotCreateDamage()
	{
		int[] values =
		{ Integer.MIN_VALUE, -39, -38, -1, 0, 1, 1000000 };
		String[] expected =
		{ "poison_venom_protection", "poison_venom_protection", "poison_protection", "poison_protection", "inactive",
			"inactive", "inactive" };
		for (int i = 0; i < values.length; i++)
		{
			final int value = values[i];
			Client client = stub(Client.class,
				(n, a) -> n.equals("getVarpValue") && ((Integer) a[0]) == 102 ? value : 0);
			ObservedStatusState protection = ClientStatusCapture.capture(client, map.catalog(), new ArrayList<>())
				.stream().filter(r -> "poison_protection_client_state".equals(r.getKey())).findFirst().get();
			assertEquals(expected[i], protection.getState());
			assertEquals(Integer.valueOf(value), protection.getRawValue());
			assertEquals("protection", protection.getFamily());
		}
	}

	@Test
	public void sharedVariableRulesMustUseOneReadRatherThanDifferentMoments() throws Exception
	{
		CombatCatalog shared = new CombatCatalog();
		com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.addProperty("catalogId", "synthetic-shared-read");
		com.google.gson.JsonObject context = map.catalog().contextBindings().deepCopy();
		com.google.gson.JsonObject duplicate = context.getAsJsonArray("statusValueRules").get(0).getAsJsonObject()
			.deepCopy();
		duplicate.addProperty("key", "synthetic-shared-poison");
		context.getAsJsonArray("statusValueRules").add(duplicate);
		root.add("contextBindings", context);
		java.lang.reflect.Field field = CombatCatalog.class.getDeclaredField("root");
		field.setAccessible(true);
		field.set(shared, root);
		int[] reads =
		{ 0 };
		Client client = stub(Client.class,
			(n, a) -> n.equals("getVarpValue") && ((Integer) a[0]) == 102 ? (++reads[0] == 1 ? -39 : -38) : 0);
		List<ObservedStatusState> samples = ClientStatusCapture.capture(client, shared, new ArrayList<>());
		assertEquals(1, reads[0]);
		assertEquals(samples.get(0).getRawValue(), samples.get(samples.size() - 1).getRawValue());
	}

	@Test
	public void sharedPoisonReadFailureRemainsUnknownForBothInterpretations()
	{
		int[] reads =
		{ 0 };
		Client client = stub(Client.class, (n, a) ->
		{
			if (n.equals("getVarpValue") && ((Integer) a[0]) == 102)
			{
				reads[0]++;
				throw new IllegalStateException("unavailable");
			}
			return 0;
		});
		List<String> issues = new ArrayList<>();
		List<ObservedStatusState> samples = ClientStatusCapture.capture(client, map.catalog(), issues);
		assertEquals(1, reads[0]);
		assertEquals(1, issues.size());
		for (ObservedStatusState sample : samples)
			if (sample.getVariableNamespace().equals("varp") && sample.getVariableId() == 102)
			{
				assertFalse(sample.isValueObserved());
				assertNull(sample.getRawValue());
				assertNull(sample.getActive());
			}
	}

	@Test
	public void poisonProtectionFixtureRetainsTransitionsAndReadRecoveryWithoutInventedHits() throws Exception
	{
		int[] values =
		{ -39, -38, -1, 0, 1, 1000000, -39, -39, -38 };
		int[] sample =
		{ 0, 0 };
		boolean[] fail = new boolean[3];
		Client client = client(sample, fail);
		WorldCaptureService capture = new WorldCaptureService();
		capture.setDamageMap(map);
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(20, new com.google.gson.Gson());
		for (int i = 0; i < values.length; i++)
		{
			sample[0] = values[i];
			fail[0] = i == 7;
			recorder.addSnapshot(capture
				.captureTick(client, 200 + i, "poison-protection", Collections.emptyList(), null, false, false, false)
				.toBuilder().wallClockMs(0).build());
		}
		DeathRecordingV2 recording = recorder.buildExport(
			DeathRecordingV2.Meta.builder().recordingId("synthetic-poison-protection").snapshotTick(208)
				.referenceCatalogId(map.catalog().id()).captureBuildId(CaptureBuild.sourceTreeHash())
				.runeLiteApiVersion(CaptureBuild.apiVersion())
				.runeLiteApiArtifactSha256(CaptureBuild.apiArtifactSha256())
				.runeLiteClientArtifactSha256(CaptureBuild.clientArtifactSha256()).build(),
			DeathRecordingV2.Summary.builder().build());
		assertEquals(9, recording.getTicks().size());
		assertTrue(recording.getEvents().getDamageEvents().isEmpty());
		assertTrue(recording.getEvents().getEffectEvents().isEmpty());
		for (WorldSnapshot frame : recording.getTicks())
			assertEquals(Integer.valueOf(99), frame.getPlayer().getHp());
		Path path = Paths.get("build/test-fixtures/poison-protection.json");
		Files.createDirectories(path.getParent());
		Files.write(path, gson.toJson(recording).getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void boundariesAndIndependentDiseaseValuesDescribeStateWithoutDamage()
	{
		int[] values =
		{ Integer.MIN_VALUE, -30, 0, 1, 999999, 1000000, Integer.MAX_VALUE };
		String[] states =
		{ "inactive", "inactive", "inactive", "poison", "poison", "venom", "venom" };
		for (int i = 0; i < values.length; i++)
		{
			final int poison = values[i];
			Client client = stub(Client.class,
				(n, a) -> n.equals("getVarpValue") ? ((Integer) a[0] == 102 ? poison : 7) : null);
			List<String> issues = new ArrayList<>();
			List<ObservedStatusState> samples = ClientStatusCapture.capture(client, map.catalog(), issues);
			assertEquals(7, samples.size());
			assertEquals(states[i], samples.get(0).getState());
			assertEquals(Integer.valueOf(poison), samples.get(0).getRawValue());
			assertTrue(samples.get(0).isValueObserved());
			assertEquals("player", samples.get(0).getActorRef());
			assertEquals("varp", samples.get(0).getVariableNamespace());
			assertEquals(102, samples.get(0).getVariableId());
			assertEquals("disease", samples.get(1).getState());
			assertTrue(samples.get(1).getActive());
			assertTrue(issues.isEmpty());
		}
	}

	@Test
	public void oneUnavailableVariableDoesNotEraseTheOtherOrCreateAClearState()
	{
		Client client = stub(Client.class, (n, a) ->
		{
			if (n.equals("getVarpValue"))
			{
				if ((Integer) a[0] == 102)
					throw new IllegalStateException("synthetic unavailable status");
				return 7;
			}
			return null;
		});
		List<String> issues = new ArrayList<>();
		List<ObservedStatusState> samples = ClientStatusCapture.capture(client, map.catalog(), issues);
		assertFalse(samples.get(0).isValueObserved());
		assertNull(samples.get(0).getRawValue());
		assertNull(samples.get(0).getActive());
		assertEquals("unknown", samples.get(0).getState());
		assertTrue(samples.get(1).isValueObserved());
		assertEquals("disease", samples.get(1).getState());
		assertEquals(1, issues.size());
	}

	@Test
	public void optionalPoisonAndContextFailuresKeepPlayerPoseResourcesAndIndependentStatus()
	{
		int[] sample =
		{ 1, 7 };
		boolean[] fail =
		{ true, false, true };
		Client client = client(sample, fail);
		WorldCaptureService capture = new WorldCaptureService();
		capture.setDamageMap(map);
		WorldSnapshot frame = capture.captureTick(client, 10, "statuses", Collections.emptyList(), null, false, false,
			false);
		assertNotNull(frame);
		assertNotNull(frame.getPlayer());
		assertEquals(Integer.valueOf(99), frame.getPlayer().getHp());
		assertEquals(6400, frame.getPlayer().getLocalX());
		assertEquals(Boolean.FALSE, frame.getPlayer().getPoisonValueKnown());
		assertEquals(Boolean.TRUE, frame.getPlayer().getLocalPositionKnown());
		assertNotNull(frame.getContext());
		assertNull(frame.getContext().getWorldId());
		assertEquals("UNAVAILABLE", frame.getContext().getEvidence().get("worldId"));
		assertNotNull(frame.getContext().getObservedVariables().get("TOA_CLIENT_RAID_LEVEL"));
		assertEquals("partial", frame.getCaptureCoverage().get("context"));
		assertEquals("partial", frame.getCaptureCoverage().get("localStatuses"));
		assertFalse(frame.getLocalStatuses().get(0).isValueObserved());
		assertEquals("disease", frame.getLocalStatuses().get(1).getState());
		fail[0] = false;
		fail[2] = false;
		sample[0] = 0;
		WorldSnapshot recovered = capture.captureTick(client, 11, "statuses", Collections.emptyList(), null, false,
			false, false);
		assertEquals(Integer.valueOf(99), recovered.getPlayer().getHp());
		assertEquals(Boolean.TRUE, recovered.getPlayer().getPoisonValueKnown());
		assertEquals("inactive", recovered.getLocalStatuses().get(0).getState());
	}

	@Test
	public void unsupportedClientTargetRetainsRawValuesWithoutClaimingAStatus() throws Exception
	{
		CombatCatalog unsupported = new CombatCatalog();
		com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.addProperty("catalogId", "synthetic-other-client");
		com.google.gson.JsonObject context = map.catalog().contextBindings().deepCopy();
		context.getAsJsonArray("statusValueRules").get(0).getAsJsonObject().addProperty("clientArtifactSha256",
			String.join("", Collections.nCopies(64, "0")));
		context.getAsJsonArray("graphicReferenceRules").get(0).getAsJsonObject().addProperty("clientArtifactSha256",
			String.join("", Collections.nCopies(64, "0")));
		root.add("contextBindings", context);
		java.lang.reflect.Field field = CombatCatalog.class.getDeclaredField("root");
		field.setAccessible(true);
		field.set(unsupported, root);
		Client client = stub(Client.class, (n, a) -> n.equals("getVarpValue") ? 1000000 : null);
		ObservedStatusState sample = ClientStatusCapture.capture(client, unsupported, new ArrayList<>()).get(0);
		assertTrue(sample.isValueObserved());
		assertEquals(Integer.valueOf(1000000), sample.getRawValue());
		assertNull(sample.getActive());
		assertEquals("unknown", sample.getState());
		assertEquals("UNSUPPORTED_CLIENT_STATUS_REFERENCE", sample.getEvidence());
		assertEquals(0, unsupported.localGraphicReference(181).size());
		assertTrue(map.catalog().localGraphicReference(181).size() > 0);
	}

	@Test
	public void realCaptureExportFixtureRetainsPreWindowStateFailuresRecoveryAndNoInventedHits() throws Exception
	{
		int[][] values =
		{
			{ 1000000, 0 },
			{ 1000001, 0 },
			{ 21, 7 },
			{ -30, 7 },
			{ 0, 7 },
			{ 0, 0 },
			{ 0, 0 },
			{ 1000000, 0 } };
		int[] sample =
		{ 0, 0 };
		boolean[] fail =
		{ false, false, false };
		Client client = client(sample, fail);
		WorldCaptureService capture = new WorldCaptureService();
		capture.setDamageMap(map);
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(20, new com.google.gson.Gson());
		for (int i = 0; i < values.length; i++)
		{
			sample[0] = values[i][0];
			sample[1] = values[i][1];
			fail[0] = i == 4;
			fail[1] = i == 5;
			fail[2] = i == 4;
			recorder.addSnapshot(
				capture.captureTick(client, 100 + i, "statuses", Collections.emptyList(), null, false, false, false)
					.toBuilder().wallClockMs(0).build());
		}
		DeathRecordingV2 recording = recorder.buildExport(
			DeathRecordingV2.Meta.builder().referenceCatalogId(map.catalog().id())
				.captureBuildId(CaptureBuild.sourceTreeHash()).runeLiteApiVersion(CaptureBuild.apiVersion())
				.runeLiteApiArtifactSha256(CaptureBuild.apiArtifactSha256())
				.runeLiteClientArtifactSha256(CaptureBuild.clientArtifactSha256()).build(),
			DeathRecordingV2.Summary.builder().build());
		assertEquals(8, recording.getTicks().size());
		assertEquals("venom", recording.getTicks().get(0).getLocalStatuses().get(0).getState());
		assertTrue(recording.getEvents().getDamageEvents().isEmpty());
		assertTrue(recording.getEvents().getEffectEvents().isEmpty());
		for (WorldSnapshot frame : recording.getTicks())
			assertEquals(Integer.valueOf(99), frame.getPlayer().getHp());
		Path path = Paths.get("build/test-fixtures/local-status.json");
		Files.createDirectories(path.getParent());
		Files.write(path, gson.toJson(recording).getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void varbitStateUsesItsOwnNamespaceAndRetainsUnexpectedValues()
	{
		int[] buffs =
		{ 1, 2, 1 };
		boolean[] unavailable =
		{ false, false, false };
		Client client = client(new int[]
		{ 1000000, 7 }, new boolean[3], buffs, unavailable);
		List<ObservedStatusState> samples = ClientStatusCapture.capture(client, map.catalog(), new ArrayList<>());
		assertEquals("antifire", samples.get(2).getState());
		assertEquals("super_antifire", samples.get(3).getState());
		assertEquals("stamina", samples.get(4).getState());
		assertEquals("varbit", samples.get(2).getVariableNamespace());
		assertEquals("protection", samples.get(2).getFamily());
		assertEquals("buff", samples.get(4).getFamily());
		buffs[0] = -1;
		buffs[2] = 2;
		unavailable[1] = true;
		List<String> issues = new ArrayList<>();
		samples = ClientStatusCapture.capture(client, map.catalog(), issues);
		assertEquals(Integer.valueOf(-1), samples.get(2).getRawValue());
		assertNull(samples.get(2).getActive());
		assertEquals("UNESTABLISHED_CLIENT_STATUS_VALUE", samples.get(2).getEvidence());
		assertFalse(samples.get(3).isValueObserved());
		assertNull(samples.get(3).getActive());
		assertEquals(Integer.valueOf(2), samples.get(4).getRawValue());
		assertNull(samples.get(4).getActive());
		assertEquals("venom", samples.get(0).getState());
		assertEquals(1, issues.size());
		Client namespaceCollision = stub(Client.class, (n, a) -> n.equals("getVarpValue") ? 12
			: n.equals("getVarbitValue") ? ((Integer) a[0] == 24 ? 12 : 0) : null);
		samples = ClientStatusCapture.capture(namespaceCollision, map.catalog(), new ArrayList<>());
		for (int i = 2; i < 5; i++)
			assertEquals(Boolean.FALSE, samples.get(i).getActive());
		assertNull(map.catalog().localStatusVarpId("stamina_client_state"));
	}

	@Test
	public void teleblockOffsetBoundariesDoNotBecomeDurationImmunityOrAnActiveLowCounter()
	{
		for (int value : new int[]
		{ Integer.MIN_VALUE, -1, 0, 1, 100, 101, Integer.MAX_VALUE })
		{
			Client client = stub(Client.class,
				(n, a) -> n.equals("getVarbitValue") && ((Integer) a[0]) == 4163 ? value : null);
			ObservedStatusState sample = ClientStatusCapture.capture(client, map.catalog(), new ArrayList<>()).get(5);
			assertEquals(Integer.valueOf(value), sample.getRawValue());
			assertEquals("varbit", sample.getVariableNamespace());
			assertEquals(value < 0 ? null : value > 100, sample.getActive());
			assertEquals(value < 0 ? "unknown" : value > 100 ? "teleblock" : "inactive", sample.getState());
		}
	}

	@Test
	public void varbitFixtureRetainsIndependentStatesFailuresAndRecoveryWithoutInventingMitigation() throws Exception
	{
		int[] buffs =
		{ 1, 2, 1 };
		boolean[] unavailable = new boolean[3];
		Client client = client(new int[]
		{ 1000000, 7 }, new boolean[3], buffs, unavailable);
		WorldCaptureService capture = new WorldCaptureService();
		capture.setDamageMap(map);
		WorldSnapshotRecorder recorder = new WorldSnapshotRecorder(10, new com.google.gson.Gson());
		int[][] values =
		{
			{ 1, 2, 1 },
			{ 0, 2, 0 },
			{ -1, 2, 2 },
			{ 1, 0, 1 },
			{ 0, 0, 0 } };
		for (int i = 0; i < values.length; i++)
		{
			System.arraycopy(values[i], 0, buffs, 0, 3);
			unavailable[1] = i == 2;
			recorder.addSnapshot(capture
				.captureTick(client, 200 + i, "buff-statuses", Collections.emptyList(), null, false, false, false)
				.toBuilder().wallClockMs(0).build());
		}
		DeathRecordingV2 recording = recorder.buildExport(
			DeathRecordingV2.Meta.builder().referenceCatalogId(map.catalog().id())
				.captureBuildId(CaptureBuild.sourceTreeHash()).runeLiteApiVersion(CaptureBuild.apiVersion())
				.runeLiteApiArtifactSha256(CaptureBuild.apiArtifactSha256())
				.runeLiteClientArtifactSha256(CaptureBuild.clientArtifactSha256()).build(),
			DeathRecordingV2.Summary.builder().build());
		assertEquals("partial", recording.getTicks().get(2).getCaptureCoverage().get("localStatuses"));
		for (WorldSnapshot frame : recording.getTicks())
			assertEquals(Integer.valueOf(99), frame.getPlayer().getHp());
		assertTrue(recording.getEvents().getDamageEvents().isEmpty());
		assertTrue(recording.getEvents().getEffectEvents().isEmpty());
		Path path = Paths.get("build/test-fixtures/varbit-status.json");
		Files.createDirectories(path.getParent());
		Files.write(path, gson.toJson(recording).getBytes(StandardCharsets.UTF_8));
	}

	private Client client(int[] sample, boolean[] fail)
	{
		return client(sample, fail, new int[3], new boolean[3]);
	}

	private Client client(int[] sample, boolean[] fail, int[] buffs, boolean[] unavailable)
	{
		Player player = stub(Player.class, (n, a) -> n.equals("getWorldLocation") ? new WorldPoint(3200, 3200, 0)
			: n.equals("getLocalLocation") ? new LocalPoint(6400, 6400) : null);
		return stub(Client.class, (n, a) ->
		{
			if (n.equals("getLocalPlayer"))
				return player;
			if (n.equals("getWorld") && fail[2])
				throw new IllegalStateException("synthetic context failure");
			if (n.equals("getVarbitValue"))
			{
				int id = (Integer) a[0], index = id == 3981 ? 0 : id == 6101 ? 1 : id == 25 ? 2 : -1;
				if (index >= 0)
				{
					if (unavailable[index])
						throw new IllegalStateException("synthetic varbit failure");
					return buffs[index];
				}
				return id == 24 ? 12 : 0;
			}
			if (n.equals("getVarpValue"))
			{
				int id = (Integer) a[0];
				if (id == 102 && fail[0] || id == 456 && fail[1])
					throw new IllegalStateException("synthetic status failure");
				return id == 102 ? sample[0] : id == 456 ? sample[1] : 0;
			}
			if (n.equals("getBoostedSkillLevel") || n.equals("getRealSkillLevel"))
				return 99;
			if (n.equals("getGameState"))
				return GameState.LOGGED_IN;
			if (n.equals("getWorldType"))
				return EnumSet.noneOf(WorldType.class);
			return null;
		});
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> values)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (p, m, a) ->
		{
			if (m.getName().equals("hashCode"))
				return System.identityHashCode(p);
			if (m.getName().equals("equals"))
				return p == a[0];
			Object value = values.apply(m.getName(), a);
			if (value != null)
				return value;
			if (m.getReturnType() == boolean.class)
				return false;
			if (m.getReturnType() == int.class)
				return m.getName().contains("Animation") || m.getName().equals("getGraphic") ? -1 : 0;
			if (m.getReturnType() == long.class)
				return 0L;
			return null;
		});
	}
}
