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
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiFunction;
import net.runelite.api.Client;
import net.runelite.api.VarbitComposition;
import org.junit.Test;
import static org.junit.Assert.*;

public class VarbitLayoutCaptureTest
{
	@Test
	public void independentlyObservedZerosAreKnownAndEachFieldIsReadOnce()
	{
		Map<String, Integer> reads = new HashMap<>();
		VarbitComposition composition = stub(VarbitComposition.class, (name, args) ->
		{
			reads.merge(name, 1, Integer::sum);
			return 0;
		});
		ObservedVarbitLayout sample = VarbitLayoutCapture.capture(client(composition), 7);
		assertTrue(sample.isLayoutKnown());
		assertEquals(Integer.valueOf(0), sample.getVarpIndex());
		assertEquals(Integer.valueOf(0), sample.getLeastSignificantBit());
		assertEquals(Integer.valueOf(0), sample.getMostSignificantBit());
		assertNull(sample.getObservationIssues());
		assertEquals("CLIENT_VARBIT_COMPOSITION", sample.getEvidence());
		for (String field : Arrays.asList("getIndex", "getLeastSignificantBit", "getMostSignificantBit"))
			assertEquals(Integer.valueOf(1), reads.get(field));
	}

	@Test
	public void oneFailedFieldRetainsIndependentFieldsWithoutAZeroPlaceholder()
	{
		for (String failed : Arrays.asList("getIndex", "getLeastSignificantBit", "getMostSignificantBit"))
		{
			VarbitComposition composition = stub(VarbitComposition.class, (name, args) ->
			{
				if (name.equals(failed))
					throw new IllegalStateException("synthetic");
				return name.equals("getIndex") ? 1044 : name.equals("getLeastSignificantBit") ? 7 : 8;
			});
			ObservedVarbitLayout sample = VarbitLayoutCapture.capture(client(composition), 3844);
			assertFalse(sample.isLayoutKnown());
			assertEquals(1, sample.getObservationIssues().size());
			assertEquals(failed.equals("getIndex") ? null : Integer.valueOf(1044), sample.getVarpIndex());
			assertEquals(failed.equals("getLeastSignificantBit") ? null : Integer.valueOf(7),
				sample.getLeastSignificantBit());
			assertEquals(failed.equals("getMostSignificantBit") ? null : Integer.valueOf(8),
				sample.getMostSignificantBit());
		}
	}

	@Test
	public void missingCompositionCannotBecomeTheZeroLayout()
	{
		for (boolean throwRead : new boolean[]
		{ false, true })
		{
			Client client = stub(Client.class, (name, args) ->
			{
				if (throwRead)
					throw new IllegalStateException("synthetic");
				return null;
			});
			ObservedVarbitLayout sample = VarbitLayoutCapture.capture(client, 9);
			assertFalse(sample.isLayoutKnown());
			assertNull(sample.getVarpIndex());
			assertNull(sample.getLeastSignificantBit());
			assertNull(sample.getMostSignificantBit());
			assertEquals("UNAVAILABLE_CLIENT_VARBIT_COMPOSITION", sample.getEvidence());
		}
	}

	@Test
	public void unsupportedRangesRemainRawAndNegativeSentinelDoesNotReadTheClient()
	{
		Client unread = stub(Client.class, (name, args) ->
		{
			throw new AssertionError("negative sentinel read client");
		});
		assertNull(VarbitLayoutCapture.capture(unread, -1));
		VarbitComposition composition = stub(VarbitComposition.class,
			(name, args) -> name.equals("getIndex") ? 1044 : name.equals("getLeastSignificantBit") ? 7 : 32);
		ObservedVarbitLayout sample = VarbitLayoutCapture.capture(client(composition), 9);
		assertFalse(sample.isLayoutKnown());
		assertEquals(Integer.valueOf(32), sample.getMostSignificantBit());
		assertNotNull(sample.getObservationIssues().get("layout"));
	}

	private static Client client(VarbitComposition composition)
	{
		return stub(Client.class, (name, args) -> name.equals("getVarbit") ? composition : null);
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> values)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]
		{ type }, (proxy, method, args) ->
		{
			if (method.getName().equals("hashCode"))
				return System.identityHashCode(proxy);
			if (method.getName().equals("equals"))
				return proxy == args[0];
			Object value = values.apply(method.getName(), args);
			if (value != null)
				return value;
			if (method.getReturnType() == int.class)
				return 0;
			if (method.getReturnType() == boolean.class)
				return false;
			return null;
		});
	}
}
