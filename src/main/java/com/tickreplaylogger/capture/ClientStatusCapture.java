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

import com.google.gson.*;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import net.runelite.api.*;

/** Independent local status reads. Failure of one variable does not erase another sample. */
final class ClientStatusCapture
{
	static List<ObservedStatusState> capture(Client client, CombatCatalog catalog, List<String> issues)
	{
		if (catalog == null)
		{
			return null;
		}
		JsonElement configured = catalog.contextBindings().get("statusValueRules");
		if (configured == null || !configured.isJsonArray())
		{
			return null;
		}
		List<ObservedStatusState> out = new ArrayList<>();
		Map<String, Integer> values = new HashMap<>();
		Set<String> failed = new HashSet<>();
		for (JsonElement element : configured.getAsJsonArray())
		{
			JsonObject rule = element.getAsJsonObject();
			String key = rule.get("key").getAsString(), namespace = rule.get("namespace").getAsString();
			int id = rule.get("id").getAsInt();
			ObservedStatusState.ObservedStatusStateBuilder sample = ObservedStatusState.builder().key(key)
				.actorRef("player").label(rule.get("label").getAsString()).family(rule.get("family").getAsString())
				.variableNamespace(namespace).variableId(id).variableSymbol(rule.get("symbol").getAsString())
				.ruleKey(key).catalogId(catalog.id()).evidence(rule.get("evidence").getAsString());
			String variable = namespace + ":" + id;
			if (!values.containsKey(variable) && !failed.contains(variable))
			{
				try
				{
					values.put(variable,
						"varbit".equals(namespace) ? client.getVarbitValue(id) : client.getVarpValue(id));
				}
				catch (RuntimeException unavailable)
				{
					failed.add(variable);
					issues.add("localStatuses: " + key + ": " + unavailable.getClass().getSimpleName());
				}
			}
			if (failed.contains(variable))
			{
				sample.valueObserved(false).state("unknown").stateLabel("Unavailable at this tick");
				out.add(sample.build());
				continue;
			}
			int value = values.get(variable);
			sample.rawValue(value).valueObserved(true);
			try
			{
				JsonObject range = catalog.localStatusCase(key, value);
				if (range.size() == 0)
				{
					sample.state("unknown").stateLabel("Interpretation unavailable for this client target")
						.evidence("UNSUPPORTED_CLIENT_STATUS_REFERENCE");
				}
				else if (range.get("active").isJsonNull())
				{
					sample.state("unknown").stateLabel(range.get("label").getAsString())
						.evidence("UNESTABLISHED_CLIENT_STATUS_VALUE");
				}
				else
				{
					sample.state(range.get("state").getAsString()).stateLabel(range.get("label").getAsString())
						.active(range.get("active").getAsBoolean());
				}
			}
			catch (RuntimeException unavailable)
			{
				sample.state("unknown").stateLabel("Interpretation unavailable")
					.evidence("UNAVAILABLE_STATUS_REFERENCE");
				issues.add("localStatuses: " + key + " reference: " + unavailable.getClass().getSimpleName());
			}
			out.add(sample.build());
		}
		return out;
	}
}
