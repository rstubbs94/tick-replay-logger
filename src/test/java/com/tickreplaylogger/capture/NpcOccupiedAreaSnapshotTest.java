/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
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
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercise the production per-tick capture path, with no animation/death callback prerequisite. */
public class NpcOccupiedAreaSnapshotTest
{
	@Test
	public void directNativeAreaIsCapturedWithoutReconstructingFromSizeOrRenderedPoint()
	{
		Fixture fixture = new Fixture();
		NpcState npc = fixture.frame(1).getNpcs().get(0);
		assertEquals(Boolean.TRUE, npc.getWorldAreaKnown());
		assertEquals(Boolean.TRUE, npc.getWorldAreaWorldViewKnown());
		assertEquals(Integer.valueOf(7), npc.getWorldAreaWorldViewId());
		assertEquals("ACTOR_GET_WORLD_AREA", npc.getWorldAreaEvidence());
		assertEquals(Integer.valueOf(3210), npc.getWorldArea().get("minX"));
		assertEquals(Integer.valueOf(3220), npc.getWorldArea().get("minY"));
		assertEquals(Integer.valueOf(3214), npc.getWorldArea().get("maxX"));
		assertEquals(Integer.valueOf(3222), npc.getWorldArea().get("maxY"));
		assertEquals(Integer.valueOf(2), npc.getWorldArea().get("plane"));
		assertEquals(Integer.valueOf(1), npc.getSize());
		assertEquals(50000, npc.getLocalX());
		assertEquals(Integer.valueOf(-1), npc.getAnimationId());
		NpcState exported = new Gson().fromJson(new Gson().toJson(npc), NpcState.class);
		assertEquals(npc.getWorldArea(), exported.getWorldArea());
		assertEquals(npc.getWorldAreaWorldViewId(), exported.getWorldAreaWorldViewId());
	}

	@Test
	public void successfulNullDoesNotInventBoundsOrAddAnApiFailure()
	{
		Fixture fixture = new Fixture(); fixture.area = null;
		NpcState npc = fixture.frame(1).getNpcs().get(0);
		assertEquals(Boolean.FALSE, npc.getWorldAreaKnown());
		assertNull(npc.getWorldArea());
		assertEquals("UNAVAILABLE", npc.getWorldAreaEvidence());
		assertFalse(npc.getObservationIssues() != null && npc.getObservationIssues().containsKey("worldArea"));
		assertEquals(Boolean.TRUE, npc.getWorldPositionKnown());
		assertEquals(Integer.valueOf(1), npc.getSize());
	}

	@Test
	public void failedAreaGetterRetainsTheNpcAndIndependentObservedFields()
	{
		Fixture fixture = new Fixture(); fixture.fail.add("getWorldArea");
		WorldSnapshot frame = fixture.frame(1);
		assertEquals(1, frame.getNpcs().size());
		NpcState npc = frame.getNpcs().get(0);
		assertEquals(Boolean.FALSE, npc.getWorldAreaKnown());
		assertEquals("Observed passive actor", npc.getName());
		assertEquals(3210, npc.getWorldX());
		assertEquals("IllegalStateException", npc.getObservationIssues().get("worldArea"));
		assertEquals("partial", frame.getCaptureCoverage().get("npcs"));
	}

	@Test
	public void areaAndDefinitionRemainIndependentOfUnavailableWorldLocation()
	{
		Fixture fixture = new Fixture(); fixture.fail.add("getWorldLocation");
		NpcState npc = fixture.frame(1).getNpcs().get(0);
		assertEquals(Boolean.FALSE, npc.getWorldPositionKnown());
		assertEquals(Boolean.TRUE, npc.getWorldAreaKnown());
		assertEquals(Integer.valueOf(3214), npc.getWorldArea().get("maxX"));
		assertEquals(Integer.valueOf(999966), npc.getNpcId());
	}

	@Test
	public void unavailableOwnWorldViewKeepsBoundsButDoesNotBorrowThePrimaryView()
	{
		Fixture fixture = new Fixture(); fixture.fail.add("getWorldView");
		NpcState npc = fixture.frame(1).getNpcs().get(0);
		assertEquals(Boolean.TRUE, npc.getWorldAreaKnown());
		assertEquals(Boolean.FALSE, npc.getWorldAreaWorldViewKnown());
		assertNull(npc.getWorldAreaWorldViewId());
		assertEquals(Integer.valueOf(2), npc.getWorldArea().get("plane"));
	}

	@Test
	public void invalidDimensionsAndOverflowRemainUnavailableWithoutLosingIdentity()
	{
		for (WorldArea area : new WorldArea[] { new WorldArea(3210, 3220, 0, 3, 2),
			new WorldArea(Integer.MAX_VALUE, 3220, 2, 3, 2) })
		{
			Fixture fixture = new Fixture(); fixture.area = area;
			NpcState npc = fixture.frame(1).getNpcs().get(0);
			assertEquals(Boolean.FALSE, npc.getWorldAreaKnown());
			assertNull(npc.getWorldArea());
			assertNotNull(npc.getObservationIssues().get("worldArea"));
			assertEquals("Observed passive actor", npc.getName());
		}
	}

	@Test
	public void subsequentSamplesDoNotMutateAnEarlierOccupiedArea()
	{
		Fixture fixture = new Fixture();
		NpcState first = fixture.frame(1).getNpcs().get(0);
		fixture.area = new WorldArea(3211, 3220, 5, 3, 2);
		NpcState next = fixture.frame(2).getNpcs().get(0);
		assertEquals(Integer.valueOf(3210), first.getWorldArea().get("minX"));
		assertEquals(Integer.valueOf(3211), next.getWorldArea().get("minX"));
		try { first.getWorldArea().put("minX", 0); fail("Captured bounds must be immutable"); }
		catch (UnsupportedOperationException expected) { }
	}

	private static final class Fixture
	{
		final Set<String> fail = new HashSet<>();
		WorldArea area = new WorldArea(3210, 3220, 5, 3, 2);
		final WorldView view = NpcSnapshotCaptureTest.stub(WorldView.class, (name, args) -> name.equals("getId") ? 7 : null);
		final NPCComposition definition = NpcSnapshotCaptureTest.stub(NPCComposition.class,
			(name, args) -> name.equals("getId") ? 999966 : name.equals("getName") ? "Observed passive actor"
				: name.equals("getSize") ? 1 : null);
		final Player local = NpcSnapshotCaptureTest.stub(Player.class,
			(name, args) -> name.equals("getWorldLocation") ? new WorldPoint(3200, 3200, 2)
				: name.equals("getLocalLocation") ? new LocalPoint(6400, 6400) : null);
		final NPC actor = NpcSnapshotCaptureTest.stub(NPC.class, (name, args) ->
		{
			if (fail.contains(name)) throw new IllegalStateException("Unavailable " + name);
			switch (name)
			{
				case "getWorldArea": return area;
				case "getWorldView": return view;
				case "getWorldLocation": return new WorldPoint(3210, 3220, 2);
				case "getLocalLocation": return new LocalPoint(50000, 60000);
				case "getName": return "Observed passive actor";
				case "getId": return 999966;
				case "getCombatLevel": return 42;
				case "getComposition": case "getTransformedComposition": return definition;
				default: return null;
			}
		});
		final Client client = NpcSnapshotCaptureTest.stub(Client.class,
			(name, args) -> name.equals("getNpcs") ? Collections.singletonList(actor)
				: name.equals("getLocalPlayer") ? local : name.equals("getPlane") ? 2 : null);
		final WorldCaptureService capture = new WorldCaptureService();
		WorldSnapshot frame(int tick)
		{
			return capture.captureTick(client, tick, "occupied-area-room", Collections.emptyList(), null, false, false, false);
		}
	}
}
