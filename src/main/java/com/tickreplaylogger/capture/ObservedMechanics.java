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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Objects;
import net.runelite.api.NPC;
import lombok.Value;

/** State changes tied to reviewed, actually observed cues; no HP-threshold/rotation simulation. */
public final class ObservedMechanics
{
	@Value
	public static class Phase
	{
		String label;
		String rule;
		String evidence;
		int observedTick;
		int lastObservedTick;
	}

	private final IdentityHashMap<NPC, Phase> phases = new IdentityHashMap<>();
	private final IdentityHashMap<NPC, Scope> scopes = new IdentityHashMap<>();

	private static final class Scope
	{
		final net.runelite.api.WorldView view;
		final net.runelite.api.Scene scene;
		final int baseX, baseY;
		final String chunks;

		Scope(NPC npc)
		{
			view = npc.getWorldView();
			scene = view == null ? null : view.getScene();
			baseX = view == null ? 0 : view.getBaseX();
			baseY = view == null ? 0 : view.getBaseY();
			chunks = view == null ? null : Arrays.deepToString(view.getInstanceTemplateChunks());
		}

		static Scope read(NPC npc)
		{
			try
			{
				return new Scope(npc);
			}
			catch (RuntimeException unavailable)
			{
				return null;
			}
		}

		boolean matches(Scope other)
		{
			return view == other.view && scene == other.scene && baseX == other.baseX && baseY == other.baseY
				&& Objects.equals(chunks, other.chunks);
		}
	}

	public void reset()
	{
		phases.clear();
		scopes.clear();
	}

	public void clear(NPC npc)
	{
		phases.remove(npc);
		scopes.remove(npc);
	}

	public void clearView(net.runelite.api.WorldView view)
	{
		for (NPC npc : new ArrayList<>(scopes.keySet()))
			if (scopes.get(npc).view == view)
				clear(npc);
	}

	public void validateIdentity(CombatCatalog catalog, NPC npc, int definitionId, boolean normalContext)
	{
		Phase phase = phases.get(npc);
		if (phase == null)
			return;
		Scope scope = scopes.get(npc), current = Scope.read(npc);
		if (scope == null || current == null || !scope.matches(current))
		{
			clear(npc);
			return;
		}
		if (!normalContext || catalog == null)
		{
			clear(npc);
			return;
		}
		for (JsonElement entry : catalog.phaseRules())
		{
			JsonObject rule = entry.getAsJsonObject();
			if (!phase.rule.equals(rule.get("key").getAsString()))
				continue;
			for (JsonElement id : rule.getAsJsonArray("npcIds"))
				if (id.getAsInt() == definitionId)
					return;
		}
		clear(npc);
	}

	public void prune(Collection<NPC> current)
	{
		phases.keySet().retainAll(current);
		scopes.keySet().retainAll(current);
	}

	private static String clean(String text)
	{
		return text == null ? "" : text.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT);
	}

	public void observe(CombatCatalog catalog, NPC npc, int definitionId, String operator, String speech, int animation,
		Collection<NPC> nearby, int tick, String evidence)
	{
		if (catalog == null || npc == null)
			return;
		for (JsonElement entry : catalog.phaseRules())
		{
			JsonObject rule = entry.getAsJsonObject();
			if (!operator.equals(rule.get("operator").getAsString()))
				continue;
			boolean actorMatches = false;
			for (JsonElement id : rule.getAsJsonArray("npcIds"))
				if (id.getAsInt() == definitionId)
					actorMatches = true;
			if (!actorMatches)
				continue;
			boolean matched = false;
			for (JsonElement value : rule.getAsJsonArray("values"))
			{
				if (operator.equals("overheadEquals"))
					matched |= clean(value.getAsString()).equals(clean(speech));
				else if (operator.equals("animationIn"))
					matched |= animation == value.getAsInt();
				else if (operator.equals("npcPresent"))
					for (NPC other : nearby)
						if (other != null && other.getWorldView() == npc.getWorldView()
							&& other.getWorldLocation() != null && npc.getWorldLocation() != null
							&& other.getWorldLocation().getPlane() == npc.getWorldLocation().getPlane()
							&& other.getId() == value.getAsInt())
							matched = true;
			}
			if (matched)
			{
				String label = rule.get("phase").getAsString(), key = rule.get("key").getAsString();
				Phase old = phases.get(npc);
				Scope scope = Scope.read(npc);
				if (scope == null)
				{
					clear(npc);
					continue;
				}
				Scope previousScope = scopes.get(npc);
				boolean same = old != null && old.label.equals(label) && old.rule.equals(key) && previousScope != null
					&& previousScope.matches(scope);
				phases.put(npc, new Phase(label, key, evidence, same ? old.observedTick : tick, tick));
				scopes.put(npc, scope);
			}
		}
	}

	public Phase phase(NPC npc)
	{
		return phases.get(npc);
	}
}
