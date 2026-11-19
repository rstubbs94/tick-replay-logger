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
import com.tickreplaylogger.model.world.ActorInteractionState;
import net.runelite.api.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActorInteractionsObservationTest
{
	@Test
	public void failedSourceFlagDoesNotEraseAnIndependentlyReadableTarget()
	{
		ActorInteractionState state = capture(true, false, false);
		assertEquals("npc:7", state.getActorRef());
		assertEquals("Observed target", state.getActorName());
		assertEquals(Integer.valueOf(7), state.getWorldViewId());
		assertNull(state.getReportedInteracting());
	}

	@Test
	public void failedTargetNameDoesNotEraseKnownIdentityAndWorldView()
	{
		ActorInteractionState state = capture(false, true, false);
		assertEquals("npc:7", state.getActorRef());
		assertEquals(Integer.valueOf(7), state.getWorldViewId());
		assertNull(state.getActorName());
		assertTrue(
			new Gson().toJsonTree(state).getAsJsonObject().getAsJsonObject("observationIssues").has("actorName"));
	}

	@Test
	public void failedTargetReferenceDoesNotEraseKnownNameAndWorldView()
	{
		ActorInteractionState state = capture(false, false, true);
		assertNull(state.getActorRef());
		assertEquals("Observed target", state.getActorName());
		assertEquals(Integer.valueOf(7), state.getWorldViewId());
	}

	private static ActorInteractionState capture(boolean failFlag, boolean failName, boolean failReference)
	{
		WorldView view = NpcSnapshotCaptureTest.stub(WorldView.class, (name, args) -> name.equals("getId") ? 7 : null);
		NPC target = NpcSnapshotCaptureTest.stub(NPC.class, (name, args) ->
		{
			switch (name)
			{
			case "getName":
				if (failName)
					throw new IllegalStateException("Target name unavailable");
				return "Observed target";
			case "getWorldView":
				return view;
			default:
				return null;
			}
		});
		NPC source = NpcSnapshotCaptureTest.stub(NPC.class, (name, args) ->
		{
			switch (name)
			{
			case "isInteracting":
				if (failFlag)
					throw new IllegalStateException("Source flag unavailable");
				return true;
			case "getInteracting":
				return target;
			default:
				return null;
			}
		});
		return ActorInteractions.capture(source, null, true, actor ->
		{
			if (failReference)
				throw new IllegalStateException("Reference unavailable");
			return "npc:7";
		});
	}
}
