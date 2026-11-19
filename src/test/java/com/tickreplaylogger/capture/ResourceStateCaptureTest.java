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

/** Source/API resource precision and independent failure behavior, independent of encounter IDs. */
public class ResourceStateCaptureTest
{
	private final Gson gson = new Gson();
	private int run = 9999, spec = 999;
	private boolean failRun, failSpec;

	@Test
	public void nativeEnergyValuesCannotBeDiscardedByWholePercentDisplayFields()
	{
		WorldSnapshot frame = capture();
		assertNotNull(frame.getPlayer());
		JsonObject player = gson.toJsonTree(frame.getPlayer()).getAsJsonObject();
		assertTrue(player.has("runEnergyRaw"));
		assertTrue(player.has("specEnergyRaw"));
		assertEquals(9999, player.get("runEnergyRaw").getAsInt());
		assertEquals(999, player.get("specEnergyRaw").getAsInt());
		assertTrue(player.get("runEnergyKnown").getAsBoolean());
		assertTrue(player.get("specEnergyKnown").getAsBoolean());
		assertEquals(Integer.valueOf(99), frame.getPlayer().getRunEnergy());
		assertEquals(Integer.valueOf(99), frame.getPlayer().getSpecEnergy());
	}

	@Test
	public void aFailedRunReadCannotErasePoseHpOrSpecialEnergy()
	{
		failRun = true;
		WorldSnapshot frame = capture();
		assertNotNull(frame.getPlayer());
		assertEquals(Integer.valueOf(99), frame.getPlayer().getHp());
		assertEquals(6400, frame.getPlayer().getLocalX());
		JsonObject player = gson.toJsonTree(frame.getPlayer()).getAsJsonObject();
		assertFalse(player.get("runEnergyKnown").getAsBoolean());
		assertFalse(player.has("runEnergyRaw"));
		assertFalse(player.has("runEnergy"));
		assertEquals(999, player.get("specEnergyRaw").getAsInt());
		assertEquals("partial", frame.getCaptureCoverage().get("player"));
	}

	@Test
	public void aFailedSpecialReadCannotErasePoseHpOrRunEnergy()
	{
		failSpec = true;
		WorldSnapshot frame = capture();
		assertNotNull(frame.getPlayer());
		assertEquals(Integer.valueOf(99), frame.getPlayer().getHp());
		JsonObject player = gson.toJsonTree(frame.getPlayer()).getAsJsonObject();
		assertFalse(player.get("specEnergyKnown").getAsBoolean());
		assertFalse(player.has("specEnergyRaw"));
		assertFalse(player.has("specEnergy"));
		assertEquals(9999, player.get("runEnergyRaw").getAsInt());
	}

	@Test
	public void observedZeroAndRawSignedValuesAreNotReplacedByFailurePlaceholders()
	{
		run = 0;
		spec = 0;
		JsonObject player = gson.toJsonTree(capture().getPlayer()).getAsJsonObject();
		assertEquals(0, player.get("runEnergyRaw").getAsInt());
		assertTrue(player.get("runEnergyKnown").getAsBoolean());
		run = -1;
		spec = -1;
		player = gson.toJsonTree(capture().getPlayer()).getAsJsonObject();
		assertEquals(-1, player.get("runEnergyRaw").getAsInt());
		assertEquals(-1, player.get("specEnergyRaw").getAsInt());
	}

	private WorldSnapshot capture()
	{
		WorldCaptureService service = new WorldCaptureService();
		service.setDamageMap(DamageMap.load(gson));
		return service.captureTick(client(), 1, "resources", Collections.emptyList(), null, false, false, false);
	}

	private Client client()
	{
		Player player = stub(Player.class, (n, a) -> n.equals("getWorldLocation") ? new WorldPoint(3200, 3200, 0)
			: n.equals("getLocalLocation") ? new LocalPoint(6400, 6400) : null);
		return stub(Client.class, (n, a) ->
		{
			if (n.equals("getLocalPlayer"))
				return player;
			if (n.equals("getEnergy"))
			{
				if (failRun)
					throw new IllegalStateException("run unavailable");
				return run;
			}
			if (n.equals("getVarpValue") && ((Integer) a[0]) == VarPlayer.SPECIAL_ATTACK_PERCENT)
			{
				if (failSpec)
					throw new IllegalStateException("special unavailable");
				return spec;
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
			String name = m.getName();
			if (name.equals("hashCode"))
				return System.identityHashCode(p);
			if (name.equals("equals"))
				return p == a[0];
			Object value = values.apply(name, a);
			if (value != null)
				return value;
			if (m.getReturnType() == boolean.class)
				return false;
			if (m.getReturnType() == int.class)
				return name.contains("Animation") || name.equals("getGraphic") ? -1 : 0;
			if (m.getReturnType() == long.class)
				return 0L;
			return null;
		});
	}
}
