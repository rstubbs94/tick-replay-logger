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

import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

/** Positive native scope is an observed producer fact, never an implicit default. */
public class NativeFrameContextCaptureTest
{
	@Test
	public void actualStableNativeScopeHasAPositiveWitness()
	{
		WorldSnapshot frame = new Fixture().capture("native-room");
		assertEquals(Boolean.TRUE, frame.getNativeContextKnown());
		assertEquals(Integer.valueOf(7), frame.getWorldViewId());
		assertEquals(0, frame.getPlane());
		assertEquals("available", frame.getCaptureCoverage().get("nativeContext"));
	}

	@Test
	public void failingNativeReadRetainsIndependentPlayerWithoutPositiveScope()
	{
		Fixture fixture = new Fixture(); fixture.failView = true;
		WorldSnapshot frame = fixture.capture("native-room");
		assertEquals(Boolean.FALSE, frame.getNativeContextKnown());
		assertNotNull(frame.getPlayer());
		assertNull(frame.getWorldViewId());
	}

	@Test
	public void absentSceneIdentityCannotAcquireAPositiveNativeWitness()
	{
		WorldSnapshot frame = new Fixture().capture(null);
		assertNotEquals(Boolean.TRUE, frame.getNativeContextKnown());
		assertEquals(Integer.valueOf(7), frame.getWorldViewId());
	}

	@Test
	public void scopeGetterFailureAtFinalVerificationDoesNotBorrowItsEarlierSuccess()
	{
		Fixture fixture = new Fixture(); fixture.failFinalId = true;
		WorldSnapshot frame = fixture.capture("native-room");
		assertEquals(Boolean.FALSE, frame.getNativeContextKnown());
		assertEquals(Integer.valueOf(7), frame.getWorldViewId());
		assertTrue(frame.getCaptureIssues().stream().anyMatch(issue -> issue.startsWith("nativeContext:")));
	}

	private static final class Fixture
	{
		boolean failView, failFinalId, completedSample;
		final WorldView view = NpcSnapshotCaptureTest.stub(WorldView.class, (name, args) ->
		{
			if (name.equals("getId"))
			{
				if (failFinalId && completedSample) throw new IllegalStateException("Native id unavailable at verification");
				return 7;
			}
			if (name.equals("getBaseX") || name.equals("getBaseY")) return 3150;
			if (name.equals("getSizeX") || name.equals("getSizeY")) return 104;
			return null;
		});
		final Player player = NpcSnapshotCaptureTest.stub(Player.class, (name, args) ->
		{
			if (name.equals("getWorldView"))
			{
				if (failView) throw new IllegalStateException("Native actor view unavailable");
				return view;
			}
			if (name.equals("getWorldLocation")) return new WorldPoint(3200, 3200, 0);
			if (name.equals("getLocalLocation")) return new LocalPoint(6464, 6464, view);
			return null;
		});
		final Client client = NpcSnapshotCaptureTest.stub(Client.class, (name, args) ->
		{
			if (name.equals("getLocalPlayer")) return player;
			if (name.equals("getTopLevelWorldView")) return view;
			if (name.equals("getWorldType")) return Collections.emptySet();
			if (name.equals("getGameCycle")) { completedSample = true; return 100; }
			if (name.equals("getVarps")) return new int[0];
			if (name.equals("getNpcs") || name.equals("getPlayers")) return Collections.emptyList();
			return null;
		});
		WorldSnapshot capture(String scene)
		{
			return new WorldCaptureService().captureTick(client, 1, scene, Collections.emptyList(), null, false, false, false);
		}
	}
}
