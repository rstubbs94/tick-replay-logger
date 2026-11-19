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

import java.util.IdentityHashMap;
import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;

/** Session-local lifetime identity; names and cache slots are not actor lifetimes. */
public final class ActorIdentity
{
	private final IdentityHashMap<Actor, String> identities = new IdentityHashMap<>();
	private long sequence;

	/** Queued observations must not create a new lifetime for an absent/retired actor. */
	public String existingRef(Actor actor, Player local)
	{
		return actor == null ? null : actor == local ? "player" : identities.get(actor);
	}

	public String ref(Actor actor, Player local)
	{
		if (actor == null)
			return null;
		if (actor == local)
			return "player";
		return identities.computeIfAbsent(actor, value ->
		{
			String scope = "unknown";
			try
			{
				WorldView view = value.getWorldView();
				if (view != null)
					scope = String.valueOf(view.getId());
			}
			catch (RuntimeException unavailable)
			{
			}
			String slot = value instanceof NPC ? "npc:unknown"
				: value instanceof Player ? "otherplayer:unknown" : "actor";
			try
			{
				if (value instanceof NPC)
					slot = "npc:" + ((NPC) value).getIndex();
				else if (value instanceof Player)
					slot = "otherplayer:" + ((Player) value).getId();
			}
			catch (RuntimeException unavailable)
			{
			}
			return slot + ":v" + scope + ":g" + (++sequence);
		});
	}

	/** Same lifetime policy, using identity primitives already read by this callback. */
	public String observedRef(Actor actor, Player local, Integer viewId, Integer slot)
	{
		if (actor == null)
			return null;
		if (actor == local)
			return "player";
		return identities.computeIfAbsent(actor, value ->
		{
			String kind = value instanceof NPC ? "npc" : value instanceof Player ? "otherplayer" : "actor";
			String identifier = "actor".equals(kind) ? kind : kind + ":" + (slot == null ? "unknown" : slot);
			return identifier + ":v" + (viewId == null ? "unknown" : viewId) + ":g" + (++sequence);
		});
	}

	public void forget(Actor actor)
	{
		identities.remove(actor);
	}

	public void forgetView(WorldView view)
	{
		identities.keySet().removeIf(actor ->
		{
			try
			{
				return actor.getWorldView() == view;
			}
			catch (RuntimeException unknown)
			{
				return true;
			}
		});
	}

	public void reset()
	{
		identities.clear();
	}
}
