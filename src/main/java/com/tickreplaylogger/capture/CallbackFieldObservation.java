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

/** Independent callback reads. Native dependencies are sampled without entering export data. */
public final class CallbackFieldObservation
{
	private final Map<String, Object> values = new LinkedHashMap<>();
	private final Map<String, Boolean> availability = new LinkedHashMap<>();
	private final Map<String, String> issues = new LinkedHashMap<>();
	private final Set<String> nulls = new LinkedHashSet<>();

	public <T> T sample(String field, Supplier<T> getter)
	{
		try
		{
			T value = getter.get();
			availability.put(field, true);
			if (value == null)
			{
				nulls.add(field);
			}
			return value;
		}
		catch (RuntimeException unavailable)
		{
			unavailable(field, unavailable.getClass().getSimpleName());
			return null;
		}
	}

	/** Only primitive/scalar values and copied arrays belong here, never client API objects. */
	public <T> T value(String field, Supplier<T> getter)
	{
		T value = sample(field, getter);
		values.put(field, value);
		return value;
	}

	public void unavailable(String field, String reason)
	{
		availability.put(field, false);
		nulls.remove(field);
		issues.put(field, reason);
	}

	public boolean known(String field)
	{
		return Boolean.TRUE.equals(availability.get(field));
	}

	public void put(String field, Object value)
	{
		values.put(field, value);
	}

	public Map<String, Object> data()
	{
		Map<String, Object> result = new LinkedHashMap<>(values);
		result.put("fieldObservationVersion", 1);
		result.put("fieldAvailability", new LinkedHashMap<>(availability));
		if (!nulls.isEmpty())
		{
			result.put("nullFields", new ArrayList<>(nulls));
		}
		if (!issues.isEmpty())
		{
			result.put("observationIssues", new LinkedHashMap<>(issues));
			result.put("captureStatus", "PARTIAL_CALLBACK_FIELDS");
		}
		return result;
	}
}
