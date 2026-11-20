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
package com.tickreplaylogger;

import com.google.gson.Gson;
import com.tickreplaylogger.capture.ResolverV1Recording;
import com.tickreplaylogger.model.world.*;
import java.lang.reflect.*;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original API footprint controls for weak projectile-origin proximity observations. */
public class ProjectileCorrelationWindowTest
{
	@Test
	public void originInsideLargeActorUsesFootprintRatherThanSouthwestAnchor() throws Exception
	{
		NPC actor = actor(new WorldPoint(1000, 1000, 1), new WorldArea(1000, 1000, 5, 5, 1));
		assertSame(actor, closest(Collections.singletonList(actor), new WorldPoint(1002, 1000, 1), null));
	}

	@Test
	public void overlappingCandidatesAndDistantOriginsRemainUnresolved() throws Exception
	{
		NPC first = actor(new WorldPoint(1000, 1000, 1), new WorldArea(1000, 1000, 5, 5, 1));
		NPC second = actor(new WorldPoint(1001, 1000, 1), new WorldArea(1001, 1000, 5, 5, 1));
		assertNull(closest(Arrays.asList(first, second), new WorldPoint(1002, 1000, 1), null));
		assertNull(closest(Collections.singletonList(first), new WorldPoint(1008, 1000, 1), null));
	}

	@Test
	public void targetAndOtherPlanesAreNotSourceCandidates() throws Exception
	{
		NPC actor = actor(new WorldPoint(1000, 1000, 1), new WorldArea(1000, 1000, 5, 5, 1));
		assertNull(closest(Collections.singletonList(actor), new WorldPoint(1002, 1000, 1), actor));
		assertNull(closest(Collections.singletonList(actor), new WorldPoint(1002, 1000, 0), null));
	}

	@Test
	public void missingFootprintDoesNotInventALargeArea() throws Exception
	{
		NPC actor = actor(new WorldPoint(1000, 1000, 1), null);
		assertNull(closest(Collections.singletonList(actor), new WorldPoint(1002, 1000, 1), null));
	}

	@Test
	public void actualCyclesRetainTheOwnerDespiteADifferentRoundedArrivalTick()
	{
		DamageRecord hit = projected(4750, 4773, 102, "original-scene", 0);
		assertTrue(hit.getSourceResolution().getCandidates().stream()
			.anyMatch(source -> "Observed projectile owner".equals(source.getName())));
		assertEquals(43, hit.getAmount());
		assertTrue(hit.getSourceResolution().isUnidentifiedPossible());
	}

	@Test
	public void damageDuringObservedFlightDoesNotRequireVisualArrival()
	{
		// The old producer's arrival-only filter lost this legitimate observation.
		DamageRecord hit = projected(4700, 4773, 100, "original-scene", 0);
		assertTrue(hit.getSourceResolution().getCandidates().stream()
			.anyMatch(source -> "Observed projectile owner".equals(source.getName())));
		assertTrue(hit.getSourceResolution().isUnidentifiedPossible());
	}

	@Test
	public void missingImpactClockPreservesTheCallbackWithoutClaimingAnArrival()
	{
		DamageRecord hit = projected(null, 4773, 101, "original-scene", 0);
		assertEquals(43, hit.getAmount());
		assertNull(hit.getObservedCycle());
		assertNotNull(hit.getSourceResolution());
		assertTrue(hit.getSourceResolution().isUnidentifiedPossible());
		assertNotEquals("CONFIRMED", hit.getAttribution());
	}

	@Test
	public void foreignSceneAndPlaneCannotBecomeAQualifiedLocalHpCause()
	{
		for (DamageRecord hit : Arrays.asList(projected(4750, 4773, 102, "different-scene", 0),
			projected(4750, 4773, 102, "original-scene", 1)))
		{
			assertEquals(43, hit.getAmount());
			assertTrue(hit.getSourceResolution().isUnidentifiedPossible());
			assertNotEquals("CONFIRMED", hit.getAttribution());
			for (SourceResolution.Candidate candidate : hit.getSourceResolution().getCandidates())
				assertFalse(candidate.getEvidence().stream()
					.anyMatch(evidence -> evidence.startsWith("SOURCE_BOUND:") || evidence.startsWith("QUALIFIED_MAXIMUM:")));
		}
	}

	private static DamageRecord projected(Integer hitCycle, int endCycle, int estimatedArrival, String scene, int plane)
	{
		ProjectileSourceHistory.SourceObservation source = ProjectileSourceHistory.SourceObservation.builder()
			.actorKind("NPC").sourceRef("npc:original:g1").sourceNpcId(990001).sourceActorKnown(true)
			.sourceName("Observed projectile owner").sourceObservedName("Observed projectile owner").sourceNameKnown(true)
			.sourceEvidence("ACTOR").sourceWorldViewId(0).sourcePlane(0).sourceSceneKey("original-scene")
			.observedTick(99).observedCycle(4690).build();
		ProjectileState projectile = ProjectileState.builder().observationId(5L).projectileId(990002).spawnTick(99)
			.observedTick(99).observedCycle(4690).endCycle(endCycle).estimatedImpactTick(estimatedArrival)
			.sourceActorKnown(true).sourceRef(source.getSourceRef()).sourceNpcId(source.getSourceNpcId())
			.sourceWorldViewId(0).sourcePlane(0).sourceSceneKey("original-scene").worldViewId(0).plane(0)
			.targetRef("player").targetsPlayer(true).targetWorldViewId(0).targetPlane(0)
			.sourceHistory(ProjectileSourceHistory.builder().version(1).firstObservedSource(source).build()).build();
		ObservedEvent launch = ObservedEvent.builder().tick(99).gameCycle(4690).sequence(10).type("PROJECTILE_FIRST_SEEN")
			.sceneKey("original-scene").data(Collections.singletonMap("projectile", projectile)).build();
		Map<String, Object> facts = new LinkedHashMap<>();
		facts.put("amount", 43); facts.put("hitsplatType", 16);
		ObservedEvent impact = ObservedEvent.builder().tick(100).gameCycle(hitCycle).sequence(20).type("HITSPLAT")
			.actorRef("player").sceneKey(scene).data(facts).build();
		DamageRecord hit = DamageRecord.builder().direction("RECEIVED").defenderRef("player").amount(43)
			.hitsplatType(16).hitsplatTypeKnown(true).impactTick(100).observedCycle(hitCycle).observationSequence(20L)
			.impactSceneKey(scene).impactWorldViewId(0).impactPlane(plane).attackerName("Unknown").attribution("UNKNOWN").build();
		ResolverV1Recording.EvidenceWindow window = new ResolverV1Recording.EvidenceWindow(Collections.emptyList(),
			Collections.emptyList(), Arrays.asList(launch, impact), Collections.singletonList(hit), Collections.emptyList(),
			99, 100, 20, 1);
		return ResolverV1Recording.resolve(com.tickreplaylogger.capture.ResolverTestCatalog.recording(window), new Gson())
			.getEvents().getDamageEvents().get(0);
	}

	private static NPC actor(WorldPoint point, WorldArea area)
	{
		return stub(NPC.class, (method, args) ->
		{
			switch (method)
			{
			case "getWorldLocation":
				return point;
			case "getWorldArea":
				return area;
			case "getName":
				return "Observed large actor";
			default:
				return null;
			}
		});
	}

	private static NPC closest(List<NPC> actors, WorldPoint origin, Actor target) throws Exception
	{
		TickReplayLoggerPlugin plugin = new TickReplayLoggerPlugin();
		set(plugin, "client", stub(Client.class, (method, args) -> method.equals("getNpcs") ? actors : null));
		Method method = TickReplayLoggerPlugin.class.getDeclaredMethod("findClosestCombatNpcToPoint", WorldPoint.class,
			Actor.class);
		method.setAccessible(true);
		return (NPC) method.invoke(plugin, origin, target);
	}

	private static void set(Object instance, String name, Object value) throws Exception
	{
		Field field = instance.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(instance, value);
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, java.util.function.BiFunction<String, Object[], Object> read)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (proxy, method, args) ->
		{
			if (method.getName().equals("equals"))
				return proxy == args[0];
			if (method.getName().equals("hashCode"))
				return System.identityHashCode(proxy);
			Object value = read.apply(method.getName(), args);
			if (value != null || !method.getReturnType().isPrimitive())
				return value;
			if (method.getReturnType() == boolean.class)
				return false;
			if (method.getReturnType() == long.class)
				return 0L;
			if (method.getReturnType() == double.class)
				return 0.0;
			if (method.getReturnType() == float.class)
				return 0f;
			return 0;
		});
	}
}
