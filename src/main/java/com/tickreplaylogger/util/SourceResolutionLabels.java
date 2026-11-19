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
package com.tickreplaylogger.util;

import static net.runelite.client.util.Text.removeTags;
import com.tickreplaylogger.model.world.SourceResolution;
import java.util.LinkedHashSet;
import java.util.Set;

/** Display only. Naming surviving alternatives never merges actor identities or selects an attack. */
public final class SourceResolutionLabels
{
	private SourceResolutionLabels()
	{
	}

	public static String label(SourceResolution resolution)
	{
		if (resolution == null || resolution.getVersion() != 1 || resolution.getCandidates() == null
			|| resolution.getCandidates().size() > 256 || !("IDENTIFIED".equals(resolution.getStatus())
				|| "ALTERNATIVES".equals(resolution.getStatus()) || "UNIDENTIFIED".equals(resolution.getStatus())))
		{
			return null;
		}
		Set<String> names = new LinkedHashSet<>();
		for (SourceResolution.Candidate candidate : resolution.getCandidates())
		{
			String name = candidate == null ? null : usableName(candidate.getName());
			if (name != null)
			{
				names.add(name);
			}
		}
		if (names.isEmpty())
		{
			return "Unknown";
		}
		return String.join(" or ", names);
	}

	/** API markup and placeholder spelling are display concerns; raw fields remain unchanged. */
	public static String usableName(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String name = removeTags(raw).trim();
		return name.isEmpty() || name.matches("(?i)^(unknown(?: source)?|unavailable|null)$") ? null : name;
	}

}
