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
package com.tickreplaylogger.model;

import com.tickreplaylogger.model.world.*;
import java.util.*;
import lombok.*;

/**
 * Represents a damage event - either damage received or dealt.
 * Critically includes the SOURCE of the damage for post-death analysis.
 */
@Data
@Builder(toBuilder = true)
public class DamageEvent
{
	private final DamageDirection direction; // RECEIVED or DEALT
	private final int amount;
	private final DamageType damageType;
	private final String source; // Who/what caused the damage
	private final String confidence; // attribution confidence for received damage, or null
	private final String determination; // LAUNCH | IMPACT | UNKNOWN reactability, or null
	private final Long observationSequence;
	private volatile SourceResolution sourceResolution;

	public enum DamageDirection
	{
		RECEIVED, // Player took damage
		DEALT // Player dealt damage
	}

	public enum DamageType
	{
		MELEE, RANGED, MAGIC, TYPELESS, // Requires evidence for the individual source; environmental alone is insufficient
		POISON, VENOM, BURN, BLEED, UNKNOWN;

		/** Unsupported reference values must not interrupt capture of the observed hit. */
		public static DamageType fromReference(String value)
		{
			if (value == null)
			{
				return UNKNOWN;
			}
			try
			{
				return valueOf(value.toUpperCase(Locale.ROOT));
			}
			catch (IllegalArgumentException ignored)
			{
				return UNKNOWN;
			}
		}
	}

	/**
	 * Create a damage received event with an attribution confidence and a reactability determination
	 * (LAUNCH = prayer had to be up before it fired; IMPACT = reactable mid-flight; UNKNOWN).
	 */
	public static DamageEvent received(int amount, DamageType type, String source, String confidence,
		String determination)
	{
		return DamageEvent.builder().direction(DamageDirection.RECEIVED).amount(amount).damageType(type)
			.source(source != null ? source : "Unknown source").confidence(confidence).determination(determination)
			.build();
	}

	/**
	 * Create a damage dealt event
	 */
	public static DamageEvent dealt(int amount, DamageType type, String target)
	{
		return DamageEvent.builder().direction(DamageDirection.DEALT).amount(amount).damageType(type)
			.source(target != null ? target : "Unknown target").build();
	}

}
