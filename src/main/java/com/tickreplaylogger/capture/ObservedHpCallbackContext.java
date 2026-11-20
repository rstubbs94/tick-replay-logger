/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.tickreplaylogger.capture;

import java.util.*;
import java.math.BigDecimal;

/** Qualifies the recorded static world facts required by reference limits. */
public final class ObservedHpCallbackContext
{
	private ObservedHpCallbackContext()
	{
	}

	/** Only these two scalar facts are currently captured. No other required variable is defaulted. */
	public static boolean normal(Map<String, Object> context, List<String> zeros, List<String> excludedTypes)
	{
		Map<String, Object> evidence = map(context == null ? null : context.get("evidence"));
		if (context == null || zeros == null || excludedTypes == null || !(context.get("worldTypes") instanceof List)
			|| !"OBSERVED_CLIENT_WORLD_TYPES".equals(evidence.get("worldTypes")))
		{
			return false;
		}
		for (String variable : zeros)
		{
			if (!"LEAGUE_TYPE".equals(variable) || !"OBSERVED_CLIENT_VARBIT".equals(evidence.get(variable))
				|| !Integer.valueOf(0).equals(integer(context.get("leagueType"))))
			{
				return false;
			}
		}
		for (Object type : (List<?>) context.get("worldTypes"))
		{
			if (!(type instanceof String))
			{
				return false;
			}
			for (String excluded : excludedTypes)
			{
				if (((String) type).contains(excluded))
				{
					return false;
				}
			}
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value)
	{
		return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
	}

	private static Integer integer(Object value)
	{
		if (!(value instanceof Number))
		{
			return null;
		}
		try
		{
			return new BigDecimal(value.toString()).intValueExact();
		}
		catch (ArithmeticException | NumberFormatException invalid)
		{
			return null;
		}
	}
}
