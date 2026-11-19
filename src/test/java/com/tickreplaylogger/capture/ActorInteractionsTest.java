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

import com.tickreplaylogger.model.world.ActorInteractionState;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActorInteractionsTest
{
	private Actor source(Actor target, boolean flag)
	{
		return (Actor) Proxy.newProxyInstance(Actor.class.getClassLoader(), new Class<?>[]
		{ Actor.class }, (p, m, args) -> m.getName().equals("getInteracting") ? target
			: m.getName().equals("isInteracting") ? Boolean.valueOf(flag) : null);
	}

	private Player teammate()
	{
		return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]
		{ Player.class }, (p, m, args) -> m.getName().equals("getName") ? "Generated teammate" : null);
	}

	@Test
	public void anUnavailableInteractingActorIsNotAReportedClear()
	{
		assertEquals("INTERACTING_ACTOR_UNAVAILABLE",
			ActorInteractions.capture(source(null, true), null, true, a -> "unused").getEvidence());
		assertEquals("NO_INTERACTION_REPORTED",
			ActorInteractions.capture(source(null, false), null, true, a -> "unused").getEvidence());
	}

	@Test
	public void disabledOtherPlayerCaptureDoesNotResolveOrNameThePartner()
	{
		AtomicInteger calls = new AtomicInteger();
		Player local = teammate();
		ActorInteractionState result = ActorInteractions.capture(source(teammate(), true), local, false, a ->
		{
			calls.incrementAndGet();
			return "private-ref";
		});
		assertEquals("OTHER_PLAYER_CAPTURE_DISABLED", result.getEvidence());
		assertNull(result.getActorRef());
		assertNull(result.getActorName());
		assertEquals(0, calls.get());
	}

	@Test
	public void baselineReferencesSurviveWithoutAnEarlierChangeCallbackAndKeepFlagConflictsExplicit()
	{
		Player target = teammate(), local = teammate();
		ActorInteractionState result = ActorInteractions.capture(source(target, true), local, true,
			a -> "generated-ref");
		assertEquals("generated-ref", result.getActorRef());
		assertEquals("Generated teammate", result.getActorName());
		assertEquals("OTHER_PLAYER", result.getActorKind());
		assertEquals("ACTOR_REFERENCE_FLAG_CONFLICT",
			ActorInteractions.capture(source(target, false), local, true, a -> "generated-ref").getEvidence());
		assertEquals("ACTOR_REFERENCE_PARTIAL",
			ActorInteractions.capture(source(target, true), local, true, a -> null).getEvidence());
	}
}
