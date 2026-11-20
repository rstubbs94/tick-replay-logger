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

import java.util.*;
import java.util.function.*;

/** Independent current scene reads. Successful samples allocate no diagnostic maps or lists. */
final class SceneFieldObservation
{
	private static final String[] FIELDS =
	{ "definition", "impostorIds", "effectiveDefinition", "id", "name", "localLocation", "worldLocation", "worldView",
		"worldViewId", "minSceneLocation", "maxSceneLocation", "renderable", "renderable1", "renderable2", "varbitId",
		"varpId", "controlValue", "orientation", "modelOrientation", "orientationA", "orientationB", "config",
		"offsetX", "offsetY", "offsetX2", "offsetY2", "location", "startCycle", "z", "animation", "animationId",
		"animationFrame", "finished", "mappedName", "model0.animation", "model0.animationId", "model0.animationFrame",
		"model1.animation", "model1.animationId", "model1.animationFrame", "objectWorldView", "objectWorldViewId",
		"objectPlane" };
	private static final Map<String, Integer> INDEX;
	static
	{
		Map<String, Integer> keys = new HashMap<>();
		for (int i = 0; i < FIELDS.length; i++)
		{
			keys.put(FIELDS[i], i);
		}
		INDEX = Collections.unmodifiableMap(keys);
	}
	private long sampled, available, nullValues;
	private Map<String, String> failures;

	private static long bit(String field)
	{
		Integer index = INDEX.get(field);
		if (index == null)
		{
			throw new IllegalArgumentException("Unregistered scene field: " + field);
		}
		return 1L << index;
	}

	<T> T read(String field, Supplier<T> getter)
	{
		long bit = bit(field);
		sampled |= bit;
		try
		{
			T value = getter.get();
			available |= bit;
			if (value == null)
			{
				nullValues |= bit;
			}
			return value;
		}
		catch (RuntimeException unavailable)
		{
			unavailable(field, unavailable.getClass().getSimpleName());
			return null;
		}
	}

	void unavailable(String field, String reason)
	{
		long bit = bit(field);
		sampled |= bit;
		available &= ~bit;
		nullValues &= ~bit;
		if (failures == null)
		{
			failures = new LinkedHashMap<>();
		}
		failures.put(field, reason);
	}

	boolean known(String field)
	{
		return (available & bit(field)) != 0;
	}

	boolean partial()
	{
		return failures != null;
	}

	Map<String, Boolean> availability()
	{
		if (!partial())
		{
			return null;
		}
		Map<String, Boolean> values = new LinkedHashMap<>();
		for (int i = 0; i < FIELDS.length; i++)
		{
			if ((sampled & (1L << i)) != 0)
			{
				values.put(FIELDS[i], (available & (1L << i)) != 0);
			}
		}
		return Collections.unmodifiableMap(values);
	}

	Map<String, String> issues()
	{
		return partial() ? Collections.unmodifiableMap(new LinkedHashMap<>(failures)) : null;
	}

	List<String> nulls()
	{
		if (!partial() || nullValues == 0)
		{
			return null;
		}
		List<String> values = new ArrayList<>();
		for (int i = 0; i < FIELDS.length; i++)
		{
			if ((nullValues & (1L << i)) != 0)
			{
				values.add(FIELDS[i]);
			}
		}
		return Collections.unmodifiableList(values);
	}
}
