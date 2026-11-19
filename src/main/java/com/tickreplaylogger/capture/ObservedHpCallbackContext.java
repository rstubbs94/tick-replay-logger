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

import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.ObservedEvent;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;

/** Binds frozen static world facts to their original local HP callback, never a later tick sample. */
public final class ObservedHpCallbackContext
{
	private static final int MAX_EVENTS = 100000;
	private final Map<Long, Sample> samples = new HashMap<>();

	private static final class Sample
	{
		final int tick, cycle, view, plane, amount, type;
		final String scene;
		final Map<String, Object> context;
		final Integer x, y;

		Sample(ObservedEvent event, int view, int plane, int amount, int type, Map<String, Object> context)
		{
			tick = event.getTick();
			cycle = event.getGameCycle();
			scene = event.getSceneKey();
			this.view = view;
			this.plane = plane;
			this.amount = amount;
			this.type = type;
			this.context = context;
			Map<String, Object> actor = map(map(event.getData()).get("actorState"));
			x = integer(actor.get("worldX"));
			y = integer(actor.get("worldY"));
		}
	}

	public ObservedHpCallbackContext(List<ObservedEvent> events)
	{
		if (events == null || events.size() > MAX_EVENTS)
		{
			return;
		}
		Set<Long> seen = new HashSet<>();
		for (ObservedEvent event : events)
		{
			if (event == null || event.getSequence() <= 0)
			{
				continue;
			}
			if (!seen.add(event.getSequence()))
			{
				samples.remove(event.getSequence());
				continue;
			}
			Map<String, Object> data = map(event.getData()), actor = map(data.get("actorState"));
			Integer view = integer(actor.get("worldViewId")), plane = integer(actor.get("plane"));
			Integer amount = integer(data.get("amount")), type = integer(data.get("hitsplatType"));
			if (!"HITSPLAT".equals(event.getType()) || !"player".equals(event.getActorRef()) || event.getTick() < 0
				|| event.getGameCycle() == null || event.getGameCycle() < 0 || event.getSceneKey() == null
				|| event.getSceneKey().trim().isEmpty() || !"PLAYER".equals(data.get("receiverKind"))
				|| !"LOCAL_PLAYER".equals(actor.get("actorKind"))
				|| !Boolean.TRUE.equals(actor.get("worldPositionKnown"))
				|| !Boolean.TRUE.equals(actor.get("planeKnown")) || view == null || view < 0 || plane == null
				|| plane < 0 || plane > 3 || amount == null || type == null || scopeIssue(data.get("captureIssues"))
				|| scopeIssue(actor.get("observationIssues")))
			{
				continue;
			}
			Map<String, Object> raw = map(data.get("numericContext"));
			if (!"STATIC_WORLD_AT_HP_CALLBACK".equals(raw.get("observationKind"))
				|| !Boolean.TRUE.equals(raw.get("localRecipientKnown"))
				|| !Integer.valueOf(event.getTick()).equals(integer(raw.get("observedTick")))
				|| !event.getGameCycle().equals(integer(raw.get("observedCycle"))))
			{
				// HP/native/position facts are independent of the optional two-scalar mode read.
				samples.put(event.getSequence(), new Sample(event, view, plane, amount, type, Collections.emptyMap()));
				continue;
			}
			Map<String, Object> frozen = new LinkedHashMap<>();
			Object types = raw.get("worldTypes");
			if (types instanceof List && ((List<?>) types).size() <= 64)
			{
				List<String> names = new ArrayList<>();
				for (Object value : (List<?>) types)
				{
					if (!(value instanceof String) || ((String) value).length() > 128)
					{
						names = null;
						break;
					}
					names.add((String) value);
				}
				if (names != null)
				{
					frozen.put("worldTypes", Collections.unmodifiableList(names));
				}
			}
			frozen.put("leagueType", integer(raw.get("leagueType")));
			Map<String, Object> evidence = new LinkedHashMap<>(), rawEvidence = map(raw.get("evidence"));
			for (String key : new String[]
			{ "worldTypes", "LEAGUE_TYPE" })
			{
				if (rawEvidence.get(key) instanceof String)
				{
					evidence.put(key, rawEvidence.get(key));
				}
			}
			frozen.put("evidence", Collections.unmodifiableMap(evidence));
			samples.put(event.getSequence(),
				new Sample(event, view, plane, amount, type, Collections.unmodifiableMap(frozen)));
		}
	}

	/** Missing, contradictory or duplicate original observations never gain later world qualification. */
	public Map<String, Object> forHit(DamageRecord hit)
	{
		Sample sample = matching(hit);
		return sample == null ? Collections.emptyMap() : sample.context;
	}

	private Sample matching(DamageRecord hit)
	{
		if (hit == null || hit.getObservationSequence() == null || !"player".equals(hit.getDefenderRef())
			|| !Boolean.TRUE.equals(hit.getHitsplatTypeKnown()))
		{
			return null;
		}
		Sample sample = samples.get(hit.getObservationSequence());
		return sample != null && sample.tick == hit.getImpactTick()
			&& Objects.equals(sample.cycle, hit.getObservedCycle())
			&& Objects.equals(sample.scene, hit.getImpactSceneKey())
			&& Objects.equals(sample.view, hit.getImpactWorldViewId())
			&& Objects.equals(sample.plane, hit.getImpactPlane()) && sample.amount == hit.getAmount()
			&& sample.type == hit.getHitsplatType() ? sample : null;
	}

	/** Optional geometry adapters must bind their impact point to the same original callback too. */
	public boolean positionMatches(DamageRecord hit)
	{
		if (hit == null || !Boolean.TRUE.equals(hit.getImpactPositionKnown()))
		{
			return false;
		}
		Sample sample = matching(hit);
		return sample != null && sample.x != null && sample.y != null && sample.x == hit.getImpactX()
			&& sample.y == hit.getImpactY();
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

	private static boolean scopeIssue(Object issues)
	{
		if (issues != null && !(issues instanceof Map) && !(issues instanceof List))
		{
			return true;
		}
		Iterable<?> values = issues instanceof Map ? ((Map<?, ?>) issues).keySet()
			: issues instanceof List ? (List<?>) issues : Collections.emptyList();
		for (Object issue : values)
		{
			if (!(issue instanceof String))
			{
				return true;
			}
			String key = (String) issue;
			for (String relevant : new String[]
			{ "worldPosition", "worldView", "worldViewId", "callbackGameState", "callbackCycle",
				"observationActorScope", "observationActorWorld", "observationContext", "primary", "hitsplatType",
				"hitsplat.type", "impactPositionScope" })
			{
				if (key.startsWith(relevant))
				{
					return true;
				}
			}
		}
		return false;
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
