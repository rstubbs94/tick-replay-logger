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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AreaSoundEffectPlayed;
import net.runelite.api.events.SoundEffectPlayed;

/** Reported callback values, not proof of audio playback, attack execution or damage. */
public final class SoundCallbackObservation
{
	private SoundCallbackObservation()
	{
	}

	public static Map<String, Object> capture(SoundEffectPlayed event, Player local, boolean ready,
		boolean includeOthers, Function<Actor, String> existingRefs)
	{
		return capture(event::getSoundId, event::getDelay, event::isConsumed, event::getSource, null, local, ready,
			includeOthers, existingRefs);
	}

	public static Map<String, Object> capture(AreaSoundEffectPlayed event, Player local, boolean ready,
		boolean includeOthers, Function<Actor, String> existingRefs)
	{
		return capture(event::getSoundId, event::getDelay, event::isConsumed, event::getSource, event, local, ready,
			includeOthers, existingRefs);
	}

	private static Map<String, Object> capture(Supplier<Integer> id, Supplier<Integer> delay,
		Supplier<Boolean> consumed, Supplier<Actor> source, AreaSoundEffectPlayed area, Player local, boolean ready,
		boolean includeOthers, Function<Actor, String> existingRefs)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		Map<String, String> issues = new LinkedHashMap<>();
		result.put("soundId", read(issues, "soundId", id));
		result.put("reportedDelay", read(issues, "reportedDelay", delay));
		result.put("consumedWhenObserved", read(issues, "consumedWhenObserved", consumed));
		result.put("evidence", "API_SOUND_CALLBACK_VALUES");
		result.put("playbackConfirmed", false);
		// The event exposes no native world-view/plane for its positional sound. Never borrow the active scene.
		result.put("scopeKnown", false);
		if (area != null)
		{
			result.put("sceneX", read(issues, "sceneX", area::getSceneX));
			result.put("sceneY", read(issues, "sceneY", area::getSceneY));
			result.put("reportedRange", read(issues, "reportedRange", area::getRange));
			result.put("positionScopeKnown", false);
		}
		Actor actor = read(issues, "source", source);
		result.put("sourcePresent", issues.containsKey("source") ? null : actor != null);
		if (actor != null)
		{
			boolean withheld = actor instanceof Player && (local == null || actor != local) && !includeOthers;
			result.put("sourceDetailsWithheld", withheld);
			if (!withheld && ready)
			{
				Map<String, Object> details = new LinkedHashMap<>();
				details.put("kind", actor == local ? "LOCAL_PLAYER"
					: actor instanceof NPC ? "NPC" : actor instanceof Player ? "PLAYER" : "ACTOR");
				details.put("actorRef", read(issues, "source.actorRef", () -> existingRefs.apply(actor)));
				details.put("referenceEvidence", "EXISTING_REFERENCE_ONLY_NO_NEW_LIFETIME");
				details.put("name", read(issues, "source.name", actor::getName));
				if (actor instanceof NPC)
					details.put("reportedNpcId", read(issues, "source.reportedNpcId", ((NPC) actor)::getId));
				WorldView view = read(issues, "source.worldView", actor::getWorldView);
				details.put("worldViewId", view == null ? null : read(issues, "source.worldViewId", view::getId));
				WorldPoint world = read(issues, "source.worldPosition", actor::getWorldLocation);
				LocalPoint point = read(issues, "source.localPosition", actor::getLocalLocation);
				details.put("worldX", world == null ? null : world.getX());
				details.put("worldY", world == null ? null : world.getY());
				details.put("plane", world == null ? null : world.getPlane());
				details.put("localX", point == null ? null : point.getX());
				details.put("localY", point == null ? null : point.getY());
				result.put("reportedSource", Collections.unmodifiableMap(details));
			}
			else if (!withheld)
				result.put("sourceContextUnavailable", true);
		}
		if (!issues.isEmpty())
			result.put("observationIssues", Collections.unmodifiableMap(issues));
		return Collections.unmodifiableMap(result);
	}

	private static <T> T read(Map<String, String> issues, String field, Supplier<T> getter)
	{
		try
		{
			return getter.get();
		}
		catch (RuntimeException unavailable)
		{
			issues.put(field, unavailable.getClass().getSimpleName());
			return null;
		}
	}
}
