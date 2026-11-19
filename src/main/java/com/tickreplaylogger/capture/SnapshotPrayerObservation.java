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
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Prayer;

/** A partial flag sample cannot prove an unread flag inactive or resolve competing overheads. */
final class SnapshotPrayerObservation
{
	final Map<String, Boolean> flags = new LinkedHashMap<>();
	final List<String> reported = new ArrayList<>(), active = new ArrayList<>();
	final boolean complete, resolved, conflict;
	final String overhead, evidence;

	SnapshotPrayerObservation(Client client, PlayerSnapshotObservation sample)
	{
		for (Prayer prayer : Prayer.values())
		{
			Boolean value = sample.read("prayerFlags." + prayer.name(), () -> client.isPrayerActive(prayer));
			if (value != null)
			{
				flags.put(prayer.name(), value);
			}
		}
		PrayerObservation observation = PrayerObservation.fromReadings(flags, sample.icon,
			!sample.issues.containsKey("overheadIcon"));
		complete = observation.isFlagsComplete();
		resolved = observation.isOverheadResolved();
		conflict = observation.isConflict();
		reported.addAll(observation.getReported());
		active.addAll(observation.getActive());
		overhead = observation.getOverhead();
		evidence = observation.getEvidence();
	}
}
