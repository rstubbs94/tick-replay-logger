/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import java.util.Map;
import java.util.function.Supplier;

/** Isolates one optional API read without caching or inferring unavailable fields. */
final class ObservationReads
{
	private ObservationReads()
	{
	}

	static <T> T read(Map<String, String> issues, String field, Supplier<T> getter)
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
