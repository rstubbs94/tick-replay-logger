/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
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
import com.google.gson.JsonObject;
import com.tickreplaylogger.TickReplayLoggerPlugin;
import com.tickreplaylogger.model.world.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiFunction;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;
import static org.junit.Assert.*;

/** Held-out definitions exercise the entire shared graphic-contact handoff. */
public class GraphicSourceEvidenceTest
{
	private static final Gson GSON = new Gson();
	private static final int GRAPHIC = 999931;

	private DamageMap map() throws Exception
	{
		DamageMap map = DamageMap.load(GSON);
		Field field = CombatCatalog.class.getDeclaredField("root");
		field.setAccessible(true);
		JsonObject root = (JsonObject) field.get(map.catalog());
		root.getAsJsonObject("reviewedBindings").getAsJsonObject("graphicRules").add(String.valueOf(GRAPHIC),
			GSON.fromJson("{\"name\":\"Held-out pool\",\"state\":\"active\",\"damaging\":true,\"key\":\"held-out-pool\",\"mapRef\":\"fixture:evidence\"}", JsonObject.class));
		return map;
	}

	private WorldSnapshot frame(List<HazardState> hazards)
	{
		return WorldSnapshot.builder().tick(2).sceneKey("room").worldViewId(0).plane(0).nativeContextKnown(true)
			.player(PlayerState.builder().worldX(101).worldY(201).build()).hazards(hazards)
			.npcs(Collections.singletonList(NpcState.builder().npcId(999932).name("Recorded actor")
				.actorRef("npc:held-out").worldViewId(0).plane(0).build())).build();
	}

	private DamageRecord hit(List<Map<String, Object>> candidates)
	{
		return DamageRecord.builder().impactTick(2).impactSceneKey("room").impactWorldViewId(0).impactPlane(0)
			.impactX(101).impactY(201).impactPositionKnown(true).observedCycle(100).observationSequence(1L)
			.hitsplatType(16).hitsplatTypeKnown(true).amount(8).attackerName("Unknown")
			.attributionCandidates(candidates).build();
	}

	private Map<String, Object> actor()
	{
		return row("kind", "NPC_ANIMATION_CORRELATION", "actorRef", "npc:held-out", "name", "Recorded actor",
			"npcId", 999932);
	}

	private HazardState graphic(Integer start, Boolean damaging, boolean finished, int x)
	{
		return HazardState.builder().kind("graphicsObject").id(GRAPHIC).mappedName("Held-out pool")
			.worldViewId(0).plane(0).worldX(x).worldY(201).worldPositionKnown(true).startCycle(start)
			.damaging(damaging).finished(finished).build();
	}

	private SourceResolution resolve(WorldSnapshot frame, DamageRecord hit, DamageMap map)
	{
		return new RecordedSourceResolver(Collections.singletonList(frame), Collections.emptyList(), map).resolve(hit);
	}

	@Test
	public void namedContactCannotDisappearWhenAnotherActorHasAnIdentity() throws Exception
	{
		SourceResolution result = resolve(frame(Collections.emptyList()), hit(Arrays.asList(actor(),
			row("kind", "HAZARD_ON_IMPACT_TILE", "name", "Held-out pool"))), map());
		assertEquals("ALTERNATIVES", result.getStatus());
		assertEquals(2, result.getCandidates().size());
		assertTrue(result.isUnidentifiedPossible());
		assertTrue(result.getCandidates().stream().anyMatch(c -> "Held-out pool".equals(c.getName())));
	}

	@Test
	public void structuredGraphicsPreserveDefinitionFamilyAndCompeteWithActors() throws Exception
	{
		Map<String, Object> contact = row("kind", "GRAPHIC_SURFACE", "name", "Held-out pool", "graphicId", GRAPHIC,
			"graphicObservationId", 9L, "worldViewId", 0, "plane", 0, "sourceEligibility", "ELIGIBLE_REFERENCE_SURFACE",
			"sceneKey", "room", "damaging", true, "startCycle", 90, "finished", false);
		SourceResolution result = resolve(frame(Collections.singletonList(graphic(90, true, false, 101))),
			hit(Arrays.asList(actor(), row("kind", "HAZARD_ON_IMPACT_TILE", "name", "Held-out pool")))
				.toBuilder().observedHazardContacts(Collections.singletonList(contact)).build(), map());
		assertEquals("ALTERNATIVES", result.getStatus());
		assertTrue("Complete source scope is not proof of an HP-cause link", result.isUnidentifiedPossible());
		assertEquals(2, result.getCandidates().size());
		SourceResolution.Candidate pool = result.getCandidates().stream().filter(c -> "Held-out pool".equals(c.getName()))
			.findFirst().get();
		assertEquals("graphic-definition:" + GRAPHIC, pool.getFamilyKey());
		assertEquals("room", pool.getSceneKey());
		assertNull(pool.getActorRef());
	}

	@Test
	public void foreignOrUnknownContactScopeCannotBecomeCompleteLocalAttribution() throws Exception
	{
		for (String field : Arrays.asList("worldViewId", "plane", "sceneKey"))
		{
			Map<String, Object> contact = row("kind", "GRAPHIC_SURFACE", "name", "Held-out pool", "graphicId", GRAPHIC,
				"worldViewId", 0, "plane", 0, "sceneKey", "room", "damaging", true, "startCycle", 90);
			contact.put(field, field.equals("sceneKey") ? "other-room" : 1);
			SourceResolution foreign = resolve(frame(Collections.emptyList()), hit(Collections.singletonList(actor()))
				.toBuilder().observedHazardContacts(Collections.singletonList(contact)).build(), map());
			assertEquals(1, foreign.getCandidates().size());
			assertEquals("Recorded actor", foreign.getCandidates().get(0).getName());
			contact.remove(field);
			SourceResolution unknown = resolve(frame(Collections.emptyList()), hit(Collections.singletonList(actor()))
				.toBuilder().observedHazardContacts(Collections.singletonList(contact)).build(), map());
			assertTrue(unknown.isUnidentifiedPossible());
		}
	}

	@Test
	public void invalidSameNamedContactCannotEraseRetainedLocalHazardEvidence() throws Exception
	{
		for (String invalid : Arrays.asList("future", "finished", "foreign", "resource"))
		{
			Map<String, Object> contact = row("kind", "GRAPHIC_SURFACE", "name", "Held-out pool", "graphicId", GRAPHIC,
				"worldViewId", 0, "plane", 0, "sceneKey", "room", "damaging", true, "startCycle", 90);
			if (invalid.equals("future")) contact.put("startCycle", 101);
			if (invalid.equals("finished")) contact.put("finished", true);
			if (invalid.equals("foreign")) contact.put("worldViewId", 1);
			if (invalid.equals("resource")) contact.put("sourceEligibility", "RESOURCE_REFERENCE_ONLY");
			SourceResolution result = resolve(frame(Collections.emptyList()), hit(Arrays.asList(actor(),
				row("kind", "HAZARD_ON_IMPACT_TILE", "name", "Held-out pool"))).toBuilder()
				.observedHazardContacts(Collections.singletonList(contact)).build(), map());
			assertEquals(2, result.getCandidates().size());
			assertTrue(result.isUnidentifiedPossible());
		}
	}

	@Test
	public void activeUnclassifiedGraphicKeepsCoverageOpenWithoutInventingASource() throws Exception
	{
		SourceResolution result = resolve(frame(Collections.singletonList(graphic(90, null, false, 101))),
			hit(Collections.singletonList(actor())), map());
		assertEquals("ALTERNATIVES", result.getStatus());
		assertTrue(result.isUnidentifiedPossible());
		assertEquals(1, result.getCandidates().size());
		assertEquals("Recorded actor", result.getCandidates().get(0).getName());
	}

	@Test
	public void offTileFutureFinishedAndKnownNonDamagingGraphicsDoNotCreateCompetitors() throws Exception
	{
		for (HazardState graphic : Arrays.asList(graphic(101, null, false, 101), graphic(90, null, true, 101),
			graphic(90, null, false, 102), graphic(90, false, false, 101)))
		{
			SourceResolution result = resolve(frame(Collections.singletonList(graphic)), hit(Collections.singletonList(actor())), map());
			assertEquals("ALTERNATIVES", result.getStatus());
			assertTrue("Rejecting the unrelated graphic does not confirm the actor's HP causality", result.isUnidentifiedPossible());
			assertEquals(1, result.getCandidates().size());
			assertEquals("Recorded actor", result.getCandidates().get(0).getName());
			assertTrue(result.getCandidates().get(0).getEvidence().stream()
				.anyMatch(e -> e.startsWith("HIT_CAUSALITY_UNESTABLISHED:")));
		}
	}

	@Test
	public void emptyAvailableHazardChannelIsDifferentFromAFailedChannel() throws Exception
	{
		for (String state : Arrays.asList("available", "partial", "unavailable", "skipped"))
		{
			WorldSnapshot frame = frame(Collections.emptyList()).toBuilder()
				.hazardCoverage(row("graphicsStatus", state, "mappedObjectsStatus", "available")).build();
			DamageMap map = map();
			DamageRecord hit = hit(Collections.singletonList(actor()));
			RecordedSourceResolver resolver = new RecordedSourceResolver(Collections.singletonList(frame), Collections.emptyList(), map);
			Method coverage = RecordedSourceResolver.class.getDeclaredMethod("sourceContextUnavailable", DamageRecord.class, WorldSnapshot.class);
			coverage.setAccessible(true);
			assertEquals("A failed scan remains distinct from an empty successful scan", !"available".equals(state), coverage.invoke(resolver, hit, frame));
			SourceResolution result = resolver.resolve(hit);
			assertTrue("Even available scans do not turn a source association into a unique cause", result.isUnidentifiedPossible());
			assertEquals(1, result.getCandidates().size());
		}
		SourceResolution callbackFailure = resolve(frame(Collections.emptyList()), hit(Collections.singletonList(actor()))
			.toBuilder().captureIssues(Collections.singletonList("impactHazards: unavailable")).build(), map());
		assertTrue(callbackFailure.isUnidentifiedPossible());
		SourceResolution partialObjects = resolve(frame(Collections.emptyList()).toBuilder()
			.hazardCoverage(row("graphicsStatus", "available", "mappedObjectsStatus", "available", "objectScanCompleted", false)).build(),
			hit(Collections.singletonList(actor())), map());
		assertTrue(partialObjects.isUnidentifiedPossible());
	}

	@Test
	public void callbackCannotStampForeignOrUnreadGraphicViewAsLocal() throws Exception
	{
		for (boolean foreign : Arrays.asList(false, true))
		{
			DamageMap map = map();
			LocalPoint tile = new LocalPoint(192, 192);
			WorldView foreignView = stub(WorldView.class, (n, a) -> n.equals("getId") ? 8 : null);
			GraphicsObject graphic = stub(GraphicsObject.class, (n, a) -> n.equals("getId") ? GRAPHIC
				: n.equals("getLocation") ? tile : n.equals("getWorldView") ? (foreign ? foreignView : null)
				: n.equals("getStartCycle") ? 90 : null);
			net.runelite.api.Deque<GraphicsObject> deque = stub(net.runelite.api.Deque.class,
				(n, a) -> n.equals("iterator") ? Collections.singletonList(graphic).iterator() : null);
			WorldView localView = stub(WorldView.class, (n, a) -> n.equals("getGraphicsObjects") ? deque : null);
			Player player = stub(Player.class, (n, a) -> n.equals("getWorldView") ? localView : null);
			Client client = stub(Client.class, (n, a) -> n.equals("getLocalPlayer") ? player
				: n.equals("getTopLevelWorldView") || n.equals("getWorldView") ? localView
				: n.equals("getGameCycle") ? 100 : null);
			TickReplayLoggerPlugin plugin = new TickReplayLoggerPlugin();
			set(plugin, "client", client);
			set(plugin, "damageMap", map);
			Method method = TickReplayLoggerPlugin.class.getDeclaredMethod("hazardsOnTile", LocalPoint.class,
				int.class, List.class, boolean.class);
			method.setAccessible(true);
			List<Map<String, Object>> contacts = new ArrayList<>();
			List<?> names = (List<?>) method.invoke(plugin, tile, 0, contacts, true);
			if (foreign)
			{
				assertTrue(names.isEmpty());
				assertTrue(contacts.isEmpty());
			}
			else
			{
				assertEquals(1, contacts.size());
				assertNull(contacts.get(0).get("worldViewId"));
				assertNull(contacts.get(0).get("sceneKey"));
			}
		}
	}

	@Test
	public void captureAndCallbackCarryTheSameReviewedGraphicClassification() throws Exception
	{
		DamageMap map = map();
		LocalPoint tile = new LocalPoint(192, 192);
		WorldView[] holder = { null };
		GraphicsObject known = stub(GraphicsObject.class, (n, a) -> n.equals("getId") ? GRAPHIC
			: n.equals("getLocation") ? tile : n.equals("getWorldView") ? holder[0] : n.equals("getStartCycle") ? 90 : null);
		GraphicsObject unknown = stub(GraphicsObject.class, (n, a) -> n.equals("getId") ? GRAPHIC + 1
			: n.equals("getLocation") ? tile : n.equals("getWorldView") ? holder[0] : n.equals("getStartCycle") ? 90 : null);
		List<GraphicsObject> graphics = Arrays.asList(known, unknown);
		net.runelite.api.Deque<GraphicsObject> deque = stub(net.runelite.api.Deque.class,
			(n, a) -> n.equals("iterator") ? graphics.iterator() : null);
		holder[0] = stub(WorldView.class, (n, a) -> n.equals("getGraphicsObjects") ? deque
			: n.equals("getBaseX") || n.equals("getBaseY") ? 100 : null);
		Player player = stub(Player.class, (n, a) -> n.equals("getWorldView") ? holder[0]
			: n.equals("getLocalLocation") ? tile : null);
		Client client = stub(Client.class, (n, a) -> n.equals("getLocalPlayer") ? player
			: n.equals("getTopLevelWorldView") || n.equals("getWorldView") ? holder[0]
			: n.equals("getGameCycle") ? 100 : null);
		WorldCaptureService capture = new WorldCaptureService();
		capture.setDamageMap(map);
		List<HazardState> snapshots = capture.captureHazards(client, player);
		Field issues = WorldCaptureService.class.getDeclaredField("captureIssues");
		issues.setAccessible(true);
		assertEquals(String.valueOf(issues.get(capture)), 2, snapshots.size());
		assertEquals(Boolean.TRUE, snapshots.get(0).getDamaging());
		assertEquals("Held-out pool", snapshots.get(0).getMappedName());
		assertEquals("ELIGIBLE_REFERENCE_SURFACE", snapshots.get(0).getSourceEligibility());
		assertNull(snapshots.get(1).getDamaging());
		TickReplayLoggerPlugin plugin = new TickReplayLoggerPlugin();
		set(plugin, "client", client);
		set(plugin, "damageMap", map);
		Method method = TickReplayLoggerPlugin.class.getDeclaredMethod("hazardsOnTile", LocalPoint.class, int.class, List.class, boolean.class);
		method.setAccessible(true);
		List<Map<String, Object>> contacts = new ArrayList<>();
		List<?> names = (List<?>) method.invoke(plugin, tile, 0, contacts, true);
		assertEquals(Collections.singletonList("Held-out pool"), names);
		assertEquals(2, contacts.size());
		Map<String, Object> witness = contacts.get(0);
		assertEquals("GRAPHIC_SURFACE", witness.get("kind"));
		assertEquals(GRAPHIC, witness.get("graphicId"));
		assertEquals(90, witness.get("startCycle"));
		assertEquals(101, witness.get("contactWorldX"));
		assertEquals("SERVER_REPORTED_TILE", witness.get("defenderPositionEvidence"));
		assertEquals("UNCLASSIFIED_GRAPHIC_SURFACE", contacts.get(1).get("sourceEligibility"));
		List<Map<String, Object>> second = new ArrayList<>();
		method.invoke(plugin, tile, 0, second, true);
		assertEquals(witness.get("graphicObservationId"), second.get(0).get("graphicObservationId"));
	}

	private static Map<String, Object> row(Object... values)
	{
		Map<String, Object> row = new LinkedHashMap<>();
		for (int i = 0; i < values.length; i += 2)
			row.put((String) values[i], values[i + 1]);
		return row;
	}

	private static void set(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> read)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (p, m, a) ->
		{
			Object value = read.apply(m.getName(), a);
			if (value != null)
				return value;
			if (m.getReturnType() == int.class) return 0;
			if (m.getReturnType() == long.class) return 0L;
			if (m.getReturnType() == boolean.class) return false;
			return null;
		});
	}
}
