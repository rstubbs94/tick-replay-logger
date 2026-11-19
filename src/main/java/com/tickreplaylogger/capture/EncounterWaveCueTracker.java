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

import static net.runelite.client.util.Text.removeTags;
import com.google.gson.*;
import com.tickreplaylogger.model.world.EncounterWaveCue;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;

/** Retains a source-qualified system-message anchor across a trimmed recording window.
 * Missing observations/lifetime boundaries invalidate the anchor. A cue is never a hit predicate.
 */
final class EncounterWaveCueTracker
{
	private EncounterWaveCue cue;
	private String scope, sceneKey, catalogId;
	private Integer apiTick;

	void reset()
	{
		cue = null;
		scope = null;
		sceneKey = null;
		catalogId = null;
		apiTick = null;
	}

	private Scope refresh(Client client, CombatCatalog catalog, String scene, GameState observedState)
	{
		try
		{
			if (observedState != GameState.LOGGED_IN || catalog == null || scene == null)
			{
				reset();
				return null;
			}
			Player player = client.getLocalPlayer();
			WorldView view = player == null ? null : player.getWorldView();
			WorldPoint template = player == null ? null : WorldViews.template(player);
			if (view == null || view.getScene() == null || !view.isInstance() || !view.getScene().isInstance()
				|| template == null)
			{
				reset();
				return null;
			}
			int tick = client.getTickCount(), world = client.getWorld(), plane = view.getPlane();
			if (tick < 0 || world <= 0 || template.getPlane() != plane)
			{
				reset();
				return null;
			}
			String next = world + ":" + WorldViews.scope(view, plane) + ":" + template.getRegionID();
			if (!Objects.equals(scope, next) || !Objects.equals(sceneKey, scene)
				|| !Objects.equals(catalogId, catalog.id()) || apiTick == null || tick < apiTick
				|| (long) tick > (long) apiTick + 1)
			{
				cue = null;
			}
			scope = next;
			sceneKey = scene;
			catalogId = catalog.id();
			apiTick = tick;
			return new Scope(world, view.getId(), plane, template.getRegionID(), tick);
		}
		catch (RuntimeException unavailable)
		{
			reset();
			return null;
		}
	}

	void observe(Client client, CombatCatalog catalog, String scene, GameState observedState, ChatMessageType channel,
		String message, int tick, Integer cycle, long sequence)
	{
		Scope observed = refresh(client, catalog, scene, observedState);
		if (observed == null || channel != ChatMessageType.GAMEMESSAGE || message == null || message.length() > 2048)
		{
			return;
		}
		String clean = removeTags(message).trim();
		JsonArray rules = catalog.contextBindings().getAsJsonArray("waveCueRules");
		if (rules == null)
		{
			return;
		}
		EncounterWaveCue selected = null;
		for (JsonElement value : rules)
		{
			JsonObject rule = value.getAsJsonObject();
			if (!"SYSTEM_MESSAGE_WAVE_CUE".equals(rule.get("operator").getAsString())
				|| !rule.get("requireInstance").getAsBoolean()
				|| rule.get("templateRegionId").getAsInt() != observed.region
				|| !"GAMEMESSAGE".equals(rule.get("channel").getAsString()))
			{
				continue;
			}
			String kind = null;
			Integer wave = null;
			Matcher start = Pattern.compile(rule.get("startPattern").getAsString()).matcher(clean);
			Matcher end = Pattern.compile(rule.get("completionPattern").getAsString()).matcher(clean);
			if (start.matches())
			{
				kind = "START";
				wave = Integer.valueOf(start.group(1));
			}
			else if (end.matches())
			{
				kind = "COMPLETE";
				wave = Integer.valueOf(end.group(1));
			}
			else if (clean.contains(rule.get("finalStartContains").getAsString()))
			{
				kind = "START";
				wave = rule.get("maxWave").getAsInt();
			}
			if (wave == null || wave < rule.get("minWave").getAsInt() || wave > rule.get("maxWave").getAsInt())
			{
				continue;
			}
			// Ambiguous bindings do not select one interpretation.
			if (selected != null)
			{
				cue = null;
				return;
			}
			selected = EncounterWaveCue.builder().ruleKey(rule.get("key").getAsString())
				.activity(rule.get("activity").getAsString()).sourceHash(rule.get("sourceHash").getAsString())
				.referenceCatalogId(catalog.id()).sceneKey(scene).worldId(observed.world).worldViewId(observed.view)
				.plane(observed.plane).templateRegionId(observed.region).waveNumber(wave).cueKind(kind)
				.channel(channel.name()).message(message).observedTick(tick).observedApiTick(observed.tick)
				.observedGameCycle(cycle).sequence(sequence).build();
		}
		if (selected != null)
		{
			cue = selected;
		}
	}

	EncounterWaveCue snapshot(Client client, CombatCatalog catalog, String scene)
	{
		try
		{
			refresh(client, catalog, scene, client.getGameState());
		}
		catch (RuntimeException unavailable)
		{
			reset();
		}
		return cue;
	}

	private static final class Scope
	{
		final int world, view, plane, region, tick;

		Scope(int world, int view, int plane, int region, int tick)
		{
			this.world = world;
			this.view = view;
			this.plane = plane;
			this.region = region;
			this.tick = tick;
		}
	}
}
