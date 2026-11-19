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

import java.lang.reflect.Proxy;
import net.runelite.api.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActorIdentityTest
{
	@Test
	public void namesAndReusedSlotsAreNotLifetimeIdentity()
	{
		ActorIdentity identities = new ActorIdentity();
		NPC first = npc(7, 1), second = npc(7, 1), otherView = npc(7, 2);
		String ref = identities.ref(first, null);
		assertEquals(ref, identities.ref(first, null));
		assertNotEquals(ref, identities.ref(second, null));
		assertNotEquals(ref, identities.ref(otherView, null));
		identities.forget(first);
		assertNotEquals(ref, identities.ref(first, null));
	}

	@Test
	public void nonInstanceChildCoordinatesUseChildBase()
	{
		WorldView view = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[]
		{ WorldView.class },
			(p, m, a) -> m.getName().equals("getBaseX") ? 5000
				: m.getName().equals("getBaseY") ? 6000
					: m.getReturnType() == boolean.class ? false : m.getReturnType() == int.class ? 0 : null);
		Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]
		{ Player.class }, (p, m, a) -> m.getName().equals("getWorldView") ? view
			: m.getName().equals("getLocalLocation") ? new net.runelite.api.coords.LocalPoint(1280, 2560, 1) : null);
		net.runelite.api.coords.WorldPoint point = WorldViews.template(player);
		assertEquals(5010, point.getX());
		assertEquals(6020, point.getY());
	}

	private NPC npc(int index, int viewId)
	{
		WorldView view = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[]
		{ WorldView.class },
			(p, m, a) -> m.getName().equals("getId") ? viewId : m.getReturnType() == int.class ? 0 : null);
		return (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]
		{ NPC.class },
			(p, m, a) -> m.getName().equals("getWorldView") ? view : m.getName().equals("getIndex") ? index : null);
	}
}
