/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
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

import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;

/** Small capture eligibility index. Presence never establishes a damage source or activation. */
public final class SourceAssociationNpcIndex
{
	private final Set<Integer> npcIds;

	public SourceAssociationNpcIndex(JsonObject document)
	{
		Set<Integer> ids = new HashSet<>();
		if (document != null && Integer.valueOf(1).equals(ResolverV1.integer(document.get("schemaVersion")))
			&& document.has("rulesSha256") && ResolverV1.array(document.get("npcIds")).size() <= 4096)
		{
			for (JsonElement value : ResolverV1.array(document.get("npcIds")))
			{
				Integer id = ResolverV1.integer(value);
				if (id != null && id >= 0)
				{
					ids.add(id);
				}
			}
		}
		npcIds = Collections.unmodifiableSet(ids);
	}

	public boolean contains(int npcId)
	{
		return npcIds.contains(npcId);
	}

	public static SourceAssociationNpcIndex load(Gson gson)
	{
		try (InputStream input = SourceAssociationNpcIndex.class
			.getResourceAsStream("/com/tickreplaylogger/source-association-npc-ids.json"))
		{
			if (input != null)
			{
				byte[] bytes = input.readNBytes(65537);
				if (bytes.length <= 65536)
				{
					return new SourceAssociationNpcIndex(
						gson.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class));
				}
			}
		}
		catch (RuntimeException | IOException unavailable)
		{
			// Optional source observations remain absent; raw damage capture remains enabled.
		}
		return new SourceAssociationNpcIndex(null);
	}
}
