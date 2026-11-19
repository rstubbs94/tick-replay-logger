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
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.*;
import net.runelite.api.*;

public final class ActorInteractions
{
	private ActorInteractions()
	{
	}

	public static ActorInteractionState capture(Actor actor, Player local, boolean includeOthers,
		Function<Actor, String> refs)
	{
		Map<String, String> issues = new LinkedHashMap<>();
		Boolean flag = read(issues, "reportedInteracting", actor::isInteracting);
		Actor target = read(issues, "interactionTarget", actor::getInteracting);
		ActorInteractionState observed = captureObserved(target, flag, !issues.containsKey("interactionTarget"), local,
			includeOthers, refs);
		if (observed.getObservationIssues() != null)
		{
			issues.putAll(observed.getObservationIssues());
		}
		return issues.isEmpty() ? observed
			: observed.toBuilder().observationIssues(Collections.unmodifiableMap(issues)).build();
	}

	public static ActorInteractionState captureEventTarget(Actor target, Player local, boolean includeOthers,
		Function<Actor, String> refs)
	{
		ActorInteractionState observed = captureObserved(target, null, true, local, includeOthers, refs);
		return observed.toBuilder().targetEvidence("EVENT_TARGET_PAYLOAD")
			.evidence(target == null ? "EVENT_TARGET_CLEARED" : observed.getEvidence()).build();
	}

	static ActorInteractionState captureObserved(Actor target, Boolean flag, boolean targetKnown, Player local,
		boolean includeOthers, Function<Actor, String> refs)
	{
		return captureObserved(target, flag, targetKnown, local, includeOthers, refs, null, null);
	}

	static ActorInteractionState captureObserved(Actor target, Boolean flag, boolean targetKnown, Player local,
		boolean includeOthers, Function<Actor, String> refs, Map<String, Object> original,
		Map<String, String> originalIssues)
	{
		ActorInteractionState.ActorInteractionStateBuilder result = ActorInteractionState.builder()
			.reportedInteracting(flag);
		if (!targetKnown)
		{
			return result.evidence("INTERACTION_UNAVAILABLE").build();
		}
		if (target == null)
		{
			return result.evidence(Boolean.TRUE.equals(flag) ? "INTERACTING_ACTOR_UNAVAILABLE"
				: flag == null ? "INTERACTION_FLAG_UNAVAILABLE" : "NO_INTERACTION_REPORTED").build();
		}
		String kind = target == local ? "LOCAL_PLAYER"
			: target instanceof Player ? local == null ? "PLAYER" : "OTHER_PLAYER"
				: target instanceof NPC ? "NPC" : "ACTOR";
		result.actorKind(kind);
		if (target instanceof Player && target != local && !includeOthers)
		{
			return result.evidence(
				local == null ? "LOCAL_IDENTITY_UNAVAILABLE_PLAYER_CAPTURE_DISABLED" : "OTHER_PLAYER_CAPTURE_DISABLED")
				.build();
		}
		Map<String, String> issues = new LinkedHashMap<>();
		String reference = read(issues, "actorRef", () -> refs.apply(target));
		result.actorRef(reference);
		if (original != null)
		{
			result.actorName((String) original.get("name"));
			result.worldViewId((Integer) original.get("worldViewId"));
			if (originalIssues != null)
			{
				for (String field : new String[]
				{ "name", "worldView", "worldViewId" })
				{
					if (originalIssues.containsKey(field))
					{
						issues.put(field.equals("name") ? "actorName" : field, originalIssues.get(field));
					}
				}
			}
		}
		else
		{
			result.actorName(read(issues, "actorName", target::getName));
			WorldView view = read(issues, "worldView", target::getWorldView);
			result.worldViewId(view == null ? null : read(issues, "worldViewId", view::getId));
		}
		if (!issues.isEmpty())
		{
			result.observationIssues(Collections.unmodifiableMap(issues));
		}
		if (reference == null || !issues.isEmpty())
		{
			return result.evidence("ACTOR_REFERENCE_PARTIAL").build();
		}
		return result.evidence(flag == null ? "ACTOR_REFERENCE_FLAG_UNAVAILABLE"
			: flag ? "ACTOR_REFERENCE_OBSERVED" : "ACTOR_REFERENCE_FLAG_CONFLICT").build();
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
