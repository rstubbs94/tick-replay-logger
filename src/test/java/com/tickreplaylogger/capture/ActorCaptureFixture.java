/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.tickreplaylogger.model.world.ActorInteractionState;
import java.util.function.Function;
import net.runelite.api.*;

/** These assertions use the same callback sampler as production, without a second acquisition wrapper. */
final class ActorCaptureFixture
{
	private ActorCaptureFixture() { }

	static ActorInteractionState interaction(Actor source, Player local, boolean includeOthers,
		Function<Actor, String> refs)
	{
		return (ActorInteractionState) ActorCallbackObservation.captureSample(source, null, local, includeOthers, refs, null)
			.data().get("interaction");
	}
}
