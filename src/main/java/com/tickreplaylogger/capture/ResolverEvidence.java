/*
 * Copyright (c) 2026, rstubbs94
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.world.*;
import java.math.*;
import java.util.*;
import lombok.*;

/** Original observation references shared by ingestion, interpretation and presentation. */
final class ResolverEvidence
{
	private ResolverEvidence()
	{
	}

	enum Truth
	{
		TRUE, FALSE, UNKNOWN;

		Truth and(Truth other)
		{
			return this == FALSE || other == FALSE ? FALSE : this == UNKNOWN || other == UNKNOWN ? UNKNOWN : TRUE;
		}

		Truth or(Truth other)
		{
			return this == TRUE || other == TRUE ? TRUE : this == UNKNOWN || other == UNKNOWN ? UNKNOWN : FALSE;
		}
	}

	enum Channel
	{
		ACTOR_CALLBACK, HP_ACTOR_POSE, PROJECTILE, PROJECTILE_ORIGIN, HP_CONTACT, NPC_EMITTER, FRAME_NPC, FRAME_SURFACE,
		GRAPHIC_BIRTH, ACTOR_LIFECYCLE, INPUT, CONSUMPTION, INVENTORY, MESSAGE, RESOURCE_INPUT, STATUS, TYPED_STATUS,
		OUTGOING_TRIGGER
	}

	@RequiredArgsConstructor
	static final class Clock
	{
		final Integer tick, cycle;
		final Long sequence;
	}

	@RequiredArgsConstructor
	static final class Scope
	{
		final String sceneKey;
		final Integer worldViewId, plane;
		final Long epoch;
	}

	@RequiredArgsConstructor
	static final class Identity
	{
		final String kind;
		final Integer id;
		final String actorRef, observationKey, name;
		final Integer combatLevel;
		final Scope scope;
	}

	@RequiredArgsConstructor
	static final class Receipt
	{
		final ObservedEvent event;
		final Clock clock;
		final Scope scope;
		final JsonObject raw;
	}

	interface Source
	{
		Channel channel();

		String key();

		Identity identity();

		Receipt receipt();

		Receipt sample();

		Receipt related();

		Receipt launch();

		JsonObject raw();

		JsonObject actor();

		JsonObject first();

		/** Exact source-owner frame; never a later impact or current frame. */
		WorldSnapshot frame();
	}

	interface Hit
	{
		DamageRecord record();

		Receipt callback();

		WorldSnapshot impact();

		SceneData scene();

		List<Source> sources();

		List<Receipt> lifecycles();

		List<DamageRecord> peers();

		boolean complete();

		default Integer firstTick()
		{
			return null;
		}

		default Integer lastTick()
		{
			return null;
		}

		WorldSnapshot exactFrame(Clock clock, Scope scope);
	}

	static JsonObject object(JsonElement value)
	{
		return value instanceof JsonObject ? value.getAsJsonObject() : new JsonObject();
	}

	static JsonArray array(JsonElement value)
	{
		return value instanceof JsonArray ? value.getAsJsonArray() : new JsonArray();
	}

	static boolean known(JsonElement value)
	{
		return value != null && !value.isJsonNull();
	}

	static String text(JsonObject object, String key)
	{
		JsonElement value = object == null ? null : object.get(key);
		return known(value) && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString()
			: null;
	}

	static Boolean bool(JsonElement value)
	{
		return known(value) && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean()
			: null;
	}

	static BigDecimal number(JsonElement value)
	{
		if (!known(value) || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
		{
			return null;
		}
		try
		{
			return value.getAsBigDecimal();
		}
		catch (NumberFormatException malformed)
		{
			return null;
		}
	}

	static Integer integer(JsonElement value)
	{
		BigDecimal number = number(value);
		try
		{
			return number == null ? null : number.intValueExact();
		}
		catch (ArithmeticException outOfRange)
		{
			return null;
		}
	}

	static boolean equal(JsonElement first, JsonElement second)
	{
		BigDecimal a = number(first), b = number(second);
		return a != null && b != null ? a.compareTo(b) == 0 : Objects.equals(first, second);
	}

	static String sourceKey(Identity source)
	{
		if (source == null || source.kind == null)
		{
			return null;
		}
		String key = source.actorRef != null ? source.actorRef
			: source.observationKey != null ? "observation:" + source.observationKey
				: source.id == null ? null : source.id.toString();
		Scope scope = source.scope;
		return key == null ? null
			: source.kind + ":" + key + ":" + (scope == null ? null : scope.sceneKey) + ":"
				+ (scope == null ? null : scope.worldViewId) + ":" + (scope == null ? null : scope.plane);
	}
}
