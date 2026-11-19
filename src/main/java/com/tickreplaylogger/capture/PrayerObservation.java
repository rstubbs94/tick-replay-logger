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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.function.Supplier;
import com.tickreplaylogger.model.world.PrayerState;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.HeadIcon;
import net.runelite.api.Prayer;

/** Preserve raw flags and visible overhead separately; never choose between conflicting flags by enum order. */
@Getter
public final class PrayerObservation
{
	private static final Set<String> OVERHEADS = Set.of("PROTECT_FROM_MELEE", "PROTECT_FROM_MISSILES",
		"PROTECT_FROM_MAGIC", "RETRIBUTION", "REDEMPTION", "SMITE");
	private final List<String> reported = new ArrayList<>();
	private final List<String> active = new ArrayList<>();
	private final Map<String, Boolean> flags = new LinkedHashMap<>();
	private final Map<String, String> observationIssues = new LinkedHashMap<>();
	private String overhead;
	private String icon;
	private String evidence;
	private boolean conflict;
	private boolean flagsComplete, iconKnown, overheadResolved, available;
	private String iconSource = "UNAVAILABLE";
	private PrayerState state;

	private PrayerObservation()
	{
		evidence = "UNAVAILABLE";
	}

	/** A failed read is not an observation that every prayer was inactive. */
	public static PrayerObservation unavailable()
	{
		return new PrayerObservation();
	}

	public PrayerObservation(Client client)
	{
		captureFlags(client);
		net.runelite.api.Player local = read("localPlayer", () -> client.getLocalPlayer());
		iconSource = "DIRECT_LOCAL_PLAYER_SAMPLE";
		if (local != null)
		{
			HeadIcon head = read("overheadIcon", local::getOverheadIcon);
			iconKnown = !observationIssues.containsKey("overheadIcon");
			icon = head == null ? null : head.name();
		}
		else
			observationIssues.put("overheadIcon", "LOCAL_PLAYER_UNAVAILABLE");
		derive();
	}

	/** Use the immutable actor callback sample rather than querying another actor/icon. */
	public static PrayerObservation fromActorState(Client client, Map<String, Object> actorState)
	{
		PrayerObservation sample = new PrayerObservation();
		sample.captureFlags(client);
		sample.iconSource = "CALLBACK_ACTOR_STATE";
		Map<String, Object> fields = actorState == null ? Collections.emptyMap() : actorState;
		Object value = fields.get("overheadIcon");
		if (Boolean.TRUE.equals(fields.get("overheadIconKnown")) && (value == null || value instanceof String))
		{
			sample.iconKnown = true;
			sample.icon = (String) value;
		}
		else
			sample.observationIssues.put("overheadIcon", "CALLBACK_ICON_UNAVAILABLE");
		sample.derive();
		return sample;
	}

	/** Shared interpretation of already observed values, without any further API reads. */
	static PrayerObservation fromReadings(Map<String, Boolean> values, HeadIcon icon, boolean iconKnown)
	{
		PrayerObservation sample = new PrayerObservation();
		sample.flags.putAll(values);
		sample.icon = icon == null ? null : icon.name();
		sample.iconKnown = iconKnown;
		sample.iconSource = "SNAPSHOT_ACTOR_STATE";
		sample.derive();
		return sample;
	}

	private void captureFlags(Client client)
	{
		for (Prayer prayer : Prayer.values())
		{
			Boolean value = read("prayerFlags." + prayer.name(), () -> client.isPrayerActive(prayer));
			if (value != null)
				flags.put(prayer.name(), value);
		}
	}

	private void derive()
	{
		flagsComplete = flags.size() == Prayer.values().length;
		available = !flags.isEmpty() || iconKnown;
		for (Map.Entry<String, Boolean> flag : flags.entrySet())
			if (Boolean.TRUE.equals(flag.getValue()))
				reported.add(flag.getKey());
		List<String> overheadFlags = new ArrayList<>();
		for (String prayer : reported)
			if (OVERHEADS.contains(prayer))
				overheadFlags.add(prayer);
			else
				active.add(prayer);
		String visible = prayerForIconName(icon);
		boolean unmapped = iconKnown && icon != null && visible == null;
		conflict = overheadFlags.size() > 1 || visible != null && (Boolean.FALSE.equals(flags.get(visible))
			|| overheadFlags.stream().anyMatch(flag -> !flag.equals(visible)));
		overhead = visible != null ? visible
			: !unmapped && flagsComplete && overheadFlags.size() == 1 ? overheadFlags.get(0) : null;
		overheadResolved = visible != null || !unmapped && flagsComplete && overheadFlags.size() <= 1;
		evidence = visible != null ? "ACTOR_ICON"
			: unmapped ? "UNMAPPED_ACTOR_ICON"
				: overheadFlags.size() > 1 ? "AMBIGUOUS"
					: !available ? "UNAVAILABLE"
						: !flagsComplete ? "PARTIAL_PRAYER_FLAGS" : overhead != null ? "PRAYER_FLAGS" : "NONE";
		if (overhead != null)
			active.add(overhead);
	}

	public List<String> getReported()
	{
		return Collections.unmodifiableList(reported);
	}

	public List<String> getActive()
	{
		return Collections.unmodifiableList(active);
	}

	public Map<String, Boolean> getFlags()
	{
		return Collections.unmodifiableMap(flags);
	}

	public Map<String, String> getObservationIssues()
	{
		return Collections.unmodifiableMap(observationIssues);
	}

	public PrayerState toState()
	{
		if (state == null)
			state = PrayerState.builder().version(1).flags(Collections.unmodifiableMap(new LinkedHashMap<>(flags)))
				.expectedFlags(Prayer.values().length).flagsComplete(flagsComplete)
				.reportedPrayers(Collections.unmodifiableList(new ArrayList<>(reported)))
				.activePrayers(Collections.unmodifiableList(new ArrayList<>(active))).overheadPrayer(overhead)
				.overheadPrayerKnown(overheadResolved).overheadIcon(icon).overheadIconKnown(iconKnown)
				.overheadEvidence(evidence).overheadConflict(conflict).iconSource(iconSource)
				.observationIssues(observationIssues.isEmpty() ? null
					: Collections.unmodifiableMap(new LinkedHashMap<>(observationIssues)))
				.build();
		return state;
	}

	private <T> T read(String field, Supplier<T> getter)
	{
		try
		{
			return getter.get();
		}
		catch (RuntimeException unavailable)
		{
			observationIssues.put(field, unavailable.getClass().getSimpleName());
			return null;
		}
	}

	static String prayerForIcon(HeadIcon icon)
	{
		return prayerForIconName(icon == null ? null : icon.name());
	}

	private static String prayerForIconName(String icon)
	{
		if (icon == null)
			return null;
		switch (icon)
		{
		case "MELEE":
			return "PROTECT_FROM_MELEE";
		case "RANGED":
			return "PROTECT_FROM_MISSILES";
		case "MAGIC":
			return "PROTECT_FROM_MAGIC";
		case "RETRIBUTION":
			return "RETRIBUTION";
		case "REDEMPTION":
			return "REDEMPTION";
		case "SMITE":
			return "SMITE";
		default:
			return null;
		}
	}
}
