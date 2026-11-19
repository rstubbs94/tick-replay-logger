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

import com.tickreplaylogger.model.TickData;
import java.util.*;
import java.util.List;

/**
 * Turns a chronological list of ticks into display rows, optionally collapsing runs of
 * unchanged ticks into a single "gap" marker. This is DISPLAY ONLY — recording is unaffected;
 * every tick is still captured and saved at full fidelity.
 *
 * A tick counts as "changed" (and is shown) when it is the first shown tick, has any event
 * (damage taken/dealt, a player action, or movement), or its HP / prayer points / active
 * prayers differ from the last shown tick. Telemetry (tick duration, ping, packet loss) is
 * intentionally ignored — it varies almost every tick and would defeat the collapse.
 */
public final class TickDisplay
{
	private TickDisplay()
	{
	}

	/** A row to render: either a single tick, or a collapsed run of unchanged ticks. */
	public static final class Row
	{
		public final TickData tick; // null for a gap row
		public final int index; // chronological index of the tick (-1 for gap rows)
		public final int gapCount; // number of collapsed ticks (0 for tick rows)

		private Row(TickData tick, int index, int gapCount)
		{
			this.tick = tick;
			this.index = index;
			this.gapCount = gapCount;
		}

		public boolean isGap()
		{
			return tick == null;
		}

		static Row tick(TickData t, int i)
		{
			return new Row(t, i, 0);
		}

		static Row gap(int count)
		{
			return new Row(null, -1, count);
		}
	}

	/**
	 * Build display rows from ticks in chronological order (oldest first). When {@code collapse}
	 * is false, every tick becomes a row. The returned rows are also in chronological order;
	 * newest-first views should iterate them in reverse.
	 */
	public static List<Row> build(List<TickData> chronological, boolean collapse)
	{
		List<Row> rows = new ArrayList<>();
		if (chronological == null || chronological.isEmpty())
		{
			return rows;
		}
		if (!collapse)
		{
			for (int i = 0; i < chronological.size(); i++)
			{
				rows.add(Row.tick(chronological.get(i), i));
			}
			return rows;
		}

		TickData lastShown = null;
		int skipped = 0;
		for (int i = 0; i < chronological.size(); i++)
		{
			TickData t = chronological.get(i);
			if (isChanged(t, lastShown))
			{
				if (skipped > 0)
				{
					rows.add(Row.gap(skipped));
					skipped = 0;
				}
				rows.add(Row.tick(t, i));
				lastShown = t;
			}
			else
			{
				skipped++;
			}
		}
		if (skipped > 0)
		{
			rows.add(Row.gap(skipped));
		}
		return rows;
	}

	/** Whether {@code tick} should be shown given the previously shown tick (null = first). */
	public static boolean isChanged(TickData tick, TickData prevShown)
	{
		if (prevShown == null)
		{
			return true;
		}
		if (hasEvents(tick))
		{
			return true;
		}
		return stateDiffers(tick, prevShown);
	}

	private static boolean hasEvents(TickData t)
	{
		if (t.getCaptureIssues() != null && !t.getCaptureIssues().isEmpty())
		{
			return true;
		}
		if (t.getDamageEvents() != null && !t.getDamageEvents().isEmpty())
		{
			return true;
		}
		if (t.getPlayerActions() != null && !t.getPlayerActions().isEmpty())
		{
			return true;
		}
		return t.getMovement() != null && t.getMovement().hasMovement();
	}

	private static boolean stateDiffers(TickData a, TickData b)
	{
		return !Objects.equals(a.getUnavailableStateFields(), b.getUnavailableStateFields())
			|| a.getCurrentHp() != b.getCurrentHp() || a.getMaxHp() != b.getMaxHp()
			|| a.getCurrentPrayer() != b.getCurrentPrayer() || a.getMaxPrayer() != b.getMaxPrayer()
			|| Boolean.FALSE.equals(a.getPrayerStateKnown()) != Boolean.FALSE.equals(b.getPrayerStateKnown())
			|| !prayersEqual(a.getActivePrayers(), b.getActivePrayers());
	}

	private static boolean prayersEqual(List<String> a, List<String> b)
	{
		int sa = a == null ? 0 : a.size();
		int sb = b == null ? 0 : b.size();
		if (sa != sb)
		{
			return false;
		}
		if (sa == 0)
		{
			return true;
		}
		List<String> copyA = new ArrayList<>(a);
		List<String> copyB = new ArrayList<>(b);
		Collections.sort(copyA);
		Collections.sort(copyB);
		return copyA.equals(copyB);
	}
}
