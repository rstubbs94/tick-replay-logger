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

import com.tickreplaylogger.model.world.ActorGraphicsState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;

/** Sample each channel once, preserving delayed animations and independent getter failures. */
public final class ActorGraphicsObservation
{
	private final ActorGraphicsState state;
	private final int[] ids;
	private final boolean idsKnown;

	public ActorGraphicsObservation(Actor actor)
	{
		Map<String, String> issues = new LinkedHashMap<>();
		Set<Integer> found = new LinkedHashSet<>();
		List<ActorGraphicsState.SpotAnimation> entries = new ArrayList<>();
		Iterable<ActorSpotAnim> collection = read(issues, "graphicsCollection", actor::getSpotAnims);
		boolean collectionKnown = false, entryIdsKnown = true;
		if (collection != null)
		{
			try
			{
				int ordinal = 0;
				for (ActorSpotAnim animation : collection)
				{
					if (animation == null)
					{
						entries.add(ActorGraphicsState.SpotAnimation.builder().ordinal(ordinal++).entryAvailable(false)
							.build());
						entryIdsKnown = false;
						continue;
					}
					Map<String, String> entryIssues = new LinkedHashMap<>();
					Integer id = read(entryIssues, "id", animation::getId);
					if (id == null)
					{
						entryIdsKnown = false;
						issues.put("graphicId", entryIssues.get("id"));
					}
					else if (id >= 0)
						found.add(id);
					Integer start = read(entryIssues, "startCycle", animation::getStartCycle);
					Integer height = read(entryIssues, "height", animation::getHeight);
					Integer frame = read(entryIssues, "frame", animation::getFrame);
					Integer cycle = read(entryIssues, "frameCycle", animation::getCycle);
					entries.add(ActorGraphicsState.SpotAnimation.builder().ordinal(ordinal++).entryAvailable(true)
						.id(id).startCycle(start).height(height).frame(frame).frameCycle(cycle)
						.observationIssues(frozen(entryIssues)).build());
				}
				collectionKnown = true;
			}
			catch (RuntimeException unavailable)
			{
				issues.put("graphicsContents", unavailable.getClass().getSimpleName());
			}
		}
		Integer primary = read(issues, "primaryGraphicId", actor::getGraphic);
		if (primary != null && primary >= 0)
			found.add(primary);
		idsKnown = collectionKnown && entryIdsKnown && primary != null;
		ids = found.isEmpty() && !idsKnown ? null : found.stream().mapToInt(Integer::intValue).toArray();
		state = ActorGraphicsState.builder().version(1).primaryGraphicId(primary).primaryGraphicKnown(primary != null)
			.collectionKnown(collectionKnown).entries(collection == null ? null : Collections.unmodifiableList(entries))
			.observationIssues(frozen(issues)).build();
	}

	public ActorGraphicsState getState()
	{
		return state;
	}

	public int[] getIds()
	{
		return ids == null ? null : ids.clone();
	}

	public boolean isIdsKnown()
	{
		return idsKnown;
	}

	private static Map<String, String> frozen(Map<String, String> issues)
	{
		return issues.isEmpty() ? null : Collections.unmodifiableMap(new LinkedHashMap<>(issues));
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
