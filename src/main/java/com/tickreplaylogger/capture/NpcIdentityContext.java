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
import net.runelite.api.*;

/** Positive context evidence required before applying base-mode rules to shared IDs. */
public final class NpcIdentityContext
{
	private NpcIdentityContext()
	{
	}

	public static boolean normalWorldObserved(Client client, NPC npc)
	{
		if (client == null || npc == null)
		{
			return false;
		}
		try
		{
			return normalWorldObservedWithoutOverrides(client, npc.getModelOverrides() == null);
		}
		catch (RuntimeException unavailable)
		{
			return false;
		}
	}

	public static boolean normalWorldObservedWithoutOverrides(Client client, boolean noOverridesObserved)
	{
		if (client == null || !noOverridesObserved)
		{
			return false;
		}
		try
		{
			EnumSet<WorldType> types = client.getWorldType();
			if (types == null)
			{
				return false;
			}
			for (WorldType type : types)
			{
				String name = type.name();
				if (name.contains("SEASONAL") || name.contains("DEADMAN") || name.contains("TOURNAMENT")
					|| name.contains("BETA"))
				{
					return false;
				}
			}
			return client.getVarbitValue(10032) == 0; // LEAGUE_TYPE, gameval identity checked by the catalog compiler.
		}
		catch (RuntimeException unavailable)
		{
			return false;
		}
	}
}
