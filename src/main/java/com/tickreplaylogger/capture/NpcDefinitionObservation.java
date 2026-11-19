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

import java.util.Map;
import java.util.function.Supplier;
import lombok.Getter;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;

/** Base identity and a nullable effective definition are independent observations. */
@Getter
public final class NpcDefinitionObservation
{
	private final Integer baseId, id, size;
	private final Boolean hasTransforms;
	private final int[] transforms;
	private final String definitionName, evidence;

	private NpcDefinitionObservation(NPC npc, Map<String, String> issues, String prefix)
	{
		baseId = read(issues, prefix + "baseNpcId", npc::getId);
		NPCComposition base = read(issues, prefix + "baseComposition", npc::getComposition);
		transforms = base == null ? null : read(issues, prefix + "definitionTransforms", () ->
		{
			int[] values = base.getConfigs();
			return values == null ? null : values.clone();
		});
		hasTransforms = base == null || issues.containsKey(prefix + "definitionTransforms") ? null : transforms != null;
		NPCComposition effective = read(issues, prefix + "transformedComposition", npc::getTransformedComposition);
		Integer effectiveId = effective == null ? null : read(issues, prefix + "definitionId", effective::getId);
		boolean failed = issues.containsKey(prefix + "transformedComposition")
			|| issues.containsKey(prefix + "definitionId");
		boolean baseEstablished = Boolean.FALSE.equals(hasTransforms);
		id = failed ? null : effective != null ? effectiveId : baseEstablished ? baseId : null;
		evidence = failed || effective == null && Boolean.TRUE.equals(hasTransforms) ? "TRANSFORM_UNAVAILABLE"
			: effective != null ? "TRANSFORMED_COMPOSITION"
				: baseEstablished ? "NPC_API_ID" : "DEFINITION_CONTEXT_UNAVAILABLE";
		if (effective == null && !failed && !baseEstablished && !Boolean.TRUE.equals(hasTransforms))
		{
			issues.put(prefix + "definitionContext", "NO_BASE_OR_EFFECTIVE_COMPOSITION");
		}
		NPCComposition displayed = effective != null ? effective : !failed && baseEstablished ? base : null;
		definitionName = displayed == null ? null : read(issues, prefix + "definitionName", displayed::getName);
		size = displayed == null ? null : read(issues, prefix + "size", displayed::getSize);
	}

	public static NpcDefinitionObservation capture(NPC npc, Map<String, String> issues, String prefix)
	{
		return new NpcDefinitionObservation(npc, issues, prefix);
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
