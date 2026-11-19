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
import java.util.*;
import java.util.function.BiFunction;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeHazardCoordinatesTest
{
	@Test
	public void graphicsInAChildViewUseThatNativeOriginAndPlane()
	{
		WorldView[] child =
		{ null };
		GraphicsObject graphic = stub(GraphicsObject.class,
			(n, a) -> n.equals("getLocation") ? new LocalPoint(1280, 2560)
				: n.equals("getLevel") ? 1 : n.equals("getWorldView") ? child[0] : null);
		net.runelite.api.Deque<GraphicsObject> graphics = stub(net.runelite.api.Deque.class,
			(n, a) -> n.equals("iterator") ? Collections.singleton(graphic).iterator() : null);
		child[0] = stub(WorldView.class, (n, a) -> n.equals("getBaseX") ? 5000
			: n.equals("getBaseY") ? 6000
				: n.equals("getPlane") ? 1 : n.equals("getId") ? 2 : n.equals("getGraphicsObjects") ? graphics : null);
		Player player = stub(Player.class, (n, a) -> n.equals("getWorldView") ? child[0] : null);
		Client client = stub(Client.class, (n, a) -> n.equals("getLocalPlayer") ? player
			: n.equals("getBaseX") ? 3000 : n.equals("getBaseY") ? 3100 : null);
		com.tickreplaylogger.model.world.HazardState observed = new WorldCaptureService().captureHazards(client, player)
			.get(0);
		assertEquals(5010, observed.getWorldX());
		assertEquals(6020, observed.getWorldY());
		assertEquals(1, observed.getPlane());
		assertEquals(Integer.valueOf(2), observed.getWorldViewId());
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> read)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (p, m, a) ->
		{
			Object value = read.apply(m.getName(), a);
			if (value != null)
				return value;
			if (m.getReturnType() == int.class)
				return 0;
			if (m.getReturnType() == boolean.class)
				return false;
			return null;
		});
	}
}
