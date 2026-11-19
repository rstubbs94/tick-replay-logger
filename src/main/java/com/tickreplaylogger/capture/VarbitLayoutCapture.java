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

import com.tickreplaylogger.model.world.ObservedVarbitLayout;
import java.util.*;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.VarbitComposition;

/** No bundle fallback: a failed composition/field cannot become an observed zero. */
public final class VarbitLayoutCapture
{
	private VarbitLayoutCapture()
	{
	}

	private static <T> T read(String name, Supplier<T> getter, Map<String, String> issues)
	{
		try
		{
			return getter.get();
		}
		catch (RuntimeException failure)
		{
			issues.put(name, failure.getClass().getSimpleName());
			return null;
		}
	}

	public static ObservedVarbitLayout capture(Client client, int id)
	{
		if (id < 0)
		{
			return null;
		}
		Map<String, String> issues = new LinkedHashMap<>();
		VarbitComposition composition = read("composition", () -> client.getVarbit(id), issues);
		if (composition == null)
		{
			if (issues.isEmpty())
			{
				issues.put("composition", "UNAVAILABLE");
			}
			return ObservedVarbitLayout.builder().varbitId(id).layoutKnown(false)
				.evidence("UNAVAILABLE_CLIENT_VARBIT_COMPOSITION")
				.observationIssues(Collections.unmodifiableMap(issues)).build();
		}
		Integer index = read("varpIndex", composition::getIndex, issues);
		Integer least = read("leastSignificantBit", composition::getLeastSignificantBit, issues);
		Integer most = read("mostSignificantBit", composition::getMostSignificantBit, issues);
		boolean valid = index != null && least != null && most != null && index >= 0 && index < 32768 && least >= 0
			&& least <= most && most < 32;
		if (index != null && least != null && most != null && !valid)
		{
			issues.put("layout", "OUT_OF_SUPPORTED_RANGE");
		}
		return ObservedVarbitLayout.builder().varbitId(id).varpIndex(index).leastSignificantBit(least)
			.mostSignificantBit(most).layoutKnown(valid).evidence("CLIENT_VARBIT_COMPOSITION")
			.observationIssues(issues.isEmpty() ? null : Collections.unmodifiableMap(issues)).build();
	}
}
