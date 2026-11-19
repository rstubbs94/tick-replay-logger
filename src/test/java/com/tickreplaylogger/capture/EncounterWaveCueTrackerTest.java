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
import java.lang.reflect.Proxy;
import java.util.function.BiFunction;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class EncounterWaveCueTrackerTest
{
	private final EncounterWaveCueTracker tracker = new EncounterWaveCueTracker();
	private final CombatCatalog catalog = CombatCatalog.load(new Gson());
	private int tick = 100, world = 301, region = 7216;
	private boolean instance = true, failTick;
	private GameState state = GameState.LOGGED_IN;
	private final Tile[][][] tiles = new Tile[1][1][1];
	private final int[][][] chunks = new int[1][1][1];
	private final Scene scene = stub(Scene.class, (n, a) -> n.equals("isInstance") ? instance
		: n.equals("getTiles") ? tiles : n.equals("getInstanceTemplateChunks") ? template() : null);
	private WorldView view = makeView();
	private final Player player = stub(Player.class,
		(n, a) -> n.equals("getWorldView") ? view : n.equals("getLocalLocation") ? new LocalPoint(64, 64) : null);
	private final Client client = stub(Client.class,
		(n, a) -> n.equals("getGameState") ? state
			: n.equals("getLocalPlayer") ? player
				: n.equals("getWorld") ? world : n.equals("getTickCount") ? readTick() : null);

	private int[][][] template()
	{
		chunks[0][0][0] = (((region >> 8) * 8) << 14) | (((region & 255) * 8) << 3);
		return chunks;
	}

	private int readTick()
	{
		if (failTick)
			throw new IllegalStateException("missing tick");
		return tick;
	}

	private WorldView makeView()
	{
		return stub(WorldView.class,
			(n, a) -> n.equals("getId") ? -1
				: n.equals("isInstance") ? instance
					: n.equals("getScene") ? scene
						: n.equals("getInstanceTemplateChunks") ? template()
							: n.equals("getSizeX") || n.equals("getSizeY") ? 8 : null);
	}

	private void message(ChatMessageType channel, String message)
	{
		tracker.observe(client, catalog, "scene", state, channel, message, 40, 200, 7);
	}

	private com.tickreplaylogger.model.world.EncounterWaveCue cue()
	{
		return tracker.snapshot(client, catalog, "scene");
	}

	@Test
	public void neverDefaultsToWaveOneAndKeepsOriginalCueAsTicksAdvance()
	{
		assertEquals(7216, WorldViews.template(player).getRegionID());
		assertNull(cue());
		message(ChatMessageType.GAMEMESSAGE, "<col=ef1020>Wave: 5</col>");
		assertNotNull(cue());
		assertEquals(5, cue().getWaveNumber());
		assertEquals("START", cue().getCueKind());
		assertEquals("<col=ef1020>Wave: 5</col>", cue().getMessage());
		assertEquals(7, cue().getSequence());
		tick++;
		assertEquals(100, cue().getObservedApiTick());
		assertEquals(40, cue().getObservedTick());
	}

	@Test
	public void completionKeepsCompletedWaveAndSolIsAnObservedFinalStart()
	{
		message(ChatMessageType.GAMEMESSAGE, "Wave 5 completed! Duration: 0:20");
		assertEquals("COMPLETE", cue().getCueKind());
		assertEquals(5, cue().getWaveNumber());
		message(ChatMessageType.GAMEMESSAGE, "Sol Heredit jumps down from his seat to face you!");
		assertEquals(12, cue().getWaveNumber());
		assertEquals("START", cue().getCueKind());
	}

	@Test
	public void otherChatMalformedNumbersAndOtherRegionsCannotCreateCues()
	{
		for (ChatMessageType type : new ChatMessageType[]
		{ ChatMessageType.PUBLICCHAT, ChatMessageType.PRIVATECHAT, ChatMessageType.SPAM })
		{
			message(type, "Wave: 5");
			assertNull(cue());
		}
		for (String text : new String[]
		{ "Wave: 0", "Wave: 13", "Wave: -1", "Wave: 99999999999999999999999", "Wave: 5 extra", "Wave 5 complete!",
			"Someone said Wave: 5" })
		{
			message(ChatMessageType.GAMEMESSAGE, text);
			assertNull(cue());
		}
		region = 7215;
		message(ChatMessageType.GAMEMESSAGE, "Wave: 5");
		assertNull(cue());
		region = 7216;
		instance = false;
		message(ChatMessageType.GAMEMESSAGE, "Wave: 5");
		assertNull(cue());
	}

	@Test
	public void worldSceneAndReusedNumericViewLifetimesNeverCarryCue()
	{
		message(ChatMessageType.GAMEMESSAGE, "Wave: 5");
		world++;
		assertNull(cue());
		message(ChatMessageType.GAMEMESSAGE, "Wave: 5");
		view = makeView();
		assertNull(cue());
		message(ChatMessageType.GAMEMESSAGE, "Wave: 5");
		assertNull(tracker.snapshot(client, catalog, "new-scene"));
		message(ChatMessageType.GAMEMESSAGE, "Wave: 5");
		tracker.reset();
		assertNull(cue());
	}

	@Test
	public void unavailableSkippedAndBackwardTicksCannotRecoverAnOldAnchor()
	{
		message(ChatMessageType.GAMEMESSAGE, "Wave: 5");
		failTick = true;
		assertNull(cue());
		failTick = false;
		assertNull(cue());
		message(ChatMessageType.GAMEMESSAGE, "Wave: 5");
		tick += 2;
		assertNull(cue());
		message(ChatMessageType.GAMEMESSAGE, "Wave: 5");
		tick--;
		assertNull(cue());
		message(ChatMessageType.GAMEMESSAGE, "Wave: 5");
		state = GameState.LOADING;
		assertNull(cue());
		state = GameState.LOGGED_IN;
		assertNull(cue());
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> values)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (p, m, a) ->
		{
			String n = m.getName();
			if (n.equals("hashCode"))
				return System.identityHashCode(p);
			if (n.equals("equals"))
				return p == a[0];
			Object value = values.apply(n, a);
			if (value != null)
				return value;
			if (m.getReturnType() == boolean.class)
				return false;
			if (m.getReturnType() == int.class)
				return 0;
			return null;
		});
	}
}
