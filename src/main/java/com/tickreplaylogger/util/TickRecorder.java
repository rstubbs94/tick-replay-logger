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

import com.tickreplaylogger.model.*;
import com.tickreplaylogger.model.world.*;
import lombok.extern.slf4j.Slf4j;
import java.util.*;

/**
 * Owns the one bounded frame window and the current minimal tick state.
 * Journal events are views over the shared original event store in WorldSnapshotRecorder.
 */
@Slf4j
public class TickRecorder
{
	/** The sole bounded frame window; minimal state and optional world samples share tick membership. */
	protected static final class CapturedFrame
	{
		TickData state;
		final List<WorldSnapshot> worlds = new ArrayList<>();
	}

	private final NavigableMap<Integer, CapturedFrame> frames = new TreeMap<>();
	private int capacity;
	private Integer newestTick;
	private int currentTickNumber;

	// Current tick being built (actions accumulate here until tick ends)
	private TickData currentTick;

	public TickRecorder(int capacity)
	{
		this.capacity = RecordingLimits.ticks(capacity);
		log.debug("TickRecorder initialized with capacity: {}", capacity);
	}

	/**
	 * Resize the buffer capacity. Preserves existing data up to the new capacity.
	 */
	public synchronized void resize(int newCapacity)
	{
		capacity = RecordingLimits.ticks(newCapacity);
		trimFrames();
	}

	protected final synchronized void addWorldSnapshot(WorldSnapshot snapshot)
	{
		frames.computeIfAbsent(snapshot.getTick(), key -> new CapturedFrame()).worlds.add(snapshot);
		advanceTick(snapshot.getTick());
	}

	protected final synchronized List<WorldSnapshot> worldSnapshots()
	{
		List<WorldSnapshot> worlds = new ArrayList<>();
		for (CapturedFrame frame : frames.values())
		{
			worlds.addAll(frame.worlds);
		}
		return worlds;
	}

	public final synchronized void advanceTick(int tick)
	{
		newestTick = newestTick == null ? tick : Math.max(newestTick, tick);
		trimFrames();
	}

	protected final int firstRetainedTick()
	{
		return newestTick == null ? 0 : (int) Math.max(Integer.MIN_VALUE, (long) newestTick - capacity + 1);
	}

	/** A frame may contain basic state, a world sample, or both; all are the same bounded tick membership. */
	protected final synchronized int frameCount(int first, int last)
	{
		return frames.subMap(first, true, last, true).size();
	}

	protected final int lastRetainedTick()
	{
		return newestTick == null ? 0 : newestTick;
	}

	private void trimFrames()
	{
		if (newestTick != null)
		{
			frames.headMap(firstRetainedTick(), false).clear();
			trimEvents(firstRetainedTick());
		}
	}

	protected void trimEvents(int firstTick)
	{
	}

	/**
	 * Start a new tick. Call this at the beginning of each GameTick.
	 */
	public void startNewTick()
	{
		currentTickNumber++;
		advanceTick(currentTickNumber);
		currentTick = TickData.builder().tickNumber(currentTickNumber).timestamp(System.currentTimeMillis())
			.activePrayers(new ArrayList<>()).prayerStateKnown(false).unavailableStateFields(new ArrayList<>(Arrays
				.asList("currentHp", "maxHp", "currentPrayer", "maxPrayer", "gameState", "apiTickCount", "gameCycle")))
			.build();
	}

	/**
	 * Finalize the current tick and add it to the buffer.
	 * Call this at the end of each GameTick after state snapshot.
	 */
	public synchronized void finalizeTick()
	{
		if (currentTick == null)
		{
			log.warn("finalizeTick called but no current tick exists");
			return;
		}

		frames.computeIfAbsent(currentTick.getTickNumber(), key -> new CapturedFrame()).state = currentTick;
		trimFrames();

		log.debug("Tick {} recorded. HP: {}/{}, Prayer: {}/{}", currentTick.getTickNumber(), currentTick.getCurrentHp(),
			currentTick.getMaxHp(), currentTick.getCurrentPrayer(), currentTick.getMaxPrayer());
		currentTick = null;
	}

	/**
	 * Update the current tick's state snapshot.
	 */
	public void updateState(int currentHp, int maxHp, int currentPrayer, int maxPrayer, List<String> activePrayers)
	{
		updateState(currentHp, maxHp, currentPrayer, maxPrayer, activePrayers, true);
	}

	public void updateState(int currentHp, int maxHp, int currentPrayer, int maxPrayer, List<String> activePrayers,
		boolean prayerStateKnown)
	{
		updateObservedState(currentHp, maxHp, currentPrayer, maxPrayer, activePrayers, prayerStateKnown);
	}

	public void updateObservedState(Integer currentHp, Integer maxHp, Integer currentPrayer, Integer maxPrayer,
		List<String> activePrayers, boolean prayerStateKnown)
	{
		if (currentTick == null)
		{
			return;
		}
		currentTick.setCurrentHp(observedValue("currentHp", currentHp));
		currentTick.setMaxHp(observedValue("maxHp", maxHp));
		currentTick.setCurrentPrayer(observedValue("currentPrayer", currentPrayer));
		currentTick.setMaxPrayer(observedValue("maxPrayer", maxPrayer));
		currentTick.setActivePrayers(activePrayers != null ? new ArrayList<>(activePrayers) : new ArrayList<>());
		currentTick.setPrayerStateKnown(prayerStateKnown);
	}

	public void setBoundaryContext(String state, Integer apiTick, Integer cycle)
	{
		if (currentTick == null)
		{
			return;
		}
		currentTick.setObservedGameState(state);
		currentTick.setApiTickCount(apiTick);
		currentTick.setGameCycle(cycle);
		String[] fields =
		{ "gameState", "apiTickCount", "gameCycle" };
		Object[] values =
		{ state, apiTick, cycle };
		for (int i = 0; i < fields.length; i++)
		{
			if (values[i] == null)
			{
				markStateUnavailable(fields[i]);
			}
			else
			{
				currentTick.getUnavailableStateFields().remove(fields[i]);
			}
		}
	}

	private int observedValue(String field, Integer value)
	{
		if (value == null)
		{
			markStateUnavailable(field);
		}
		else
		{
			currentTick.getUnavailableStateFields().remove(field);
		}
		return value == null ? 0 : value;
	}

	public void markStateUnavailable(String field)
	{
		if (currentTick != null && !currentTick.getUnavailableStateFields().contains(field))
		{
			currentTick.getUnavailableStateFields().add(field);
		}
	}

	public void addCaptureIssue(String issue)
	{
		if (currentTick != null)
		{
			currentTick.getCaptureIssues().add(issue);
		}
	}

	/**
	 * Set tick duration telemetry for the current tick.
	 */
	public void setTickDuration(long durationMs)
	{
		if (currentTick == null)
		{
			return;
		}
		currentTick.setTickDurationMs(durationMs);
	}

	/**
	 * Set ping telemetry for the current tick.
	 */
	public void setPing(int pingMs)
	{
		if (currentTick == null)
		{
			return;
		}
		currentTick.setPingMs(pingMs);
	}

	/**
	 * Set packet loss percentage for the current tick.
	 */
	public void setPacketLoss(int packetLossPercent)
	{
		if (currentTick == null)
		{
			return;
		}
		currentTick.setPacketLossPercent(packetLossPercent);
	}

	/**
	 * Set movement data for the current tick.
	 */
	public void setMovement(MovementData movement)
	{
		if (currentTick == null)
		{
			return;
		}
		currentTick.setMovement(movement);
		if (movement != null && movement.hasMovement())
		{
			log.debug("Movement recorded: {}", movement.getDescription());
		}
	}

	/**
	 * Get all recorded ticks in chronological order (oldest first).
	 * Returns a copy of the data.
	 */
	public synchronized List<TickData> getTickHistory()
	{
		return journalViews(rawTickHistory());
	}

	/** Frozen processing receives these raw states, without constructing or retaining a second journal. */
	protected synchronized List<TickData> rawTickHistory()
	{
		List<TickData> history = new ArrayList<>();
		for (CapturedFrame frame : frames.values())
		{
			if (frame.state != null)
			{
				history.add(frame.state);
			}
		}
		return history;
	}

	/** Raw journal rows are views of captured events, never a second mutable result store. */
	protected List<TickData> journalViews(List<TickData> states)
	{
		return states;
	}

	/**
	 * Get the number of ticks currently stored.
	 */
	public synchronized int getTickCount()
	{
		return (int) frames.values().stream().filter(frame -> frame.state != null).count();
	}

	/**
	 * Get the current tick number.
	 */
	public int getCurrentTickNumber()
	{
		return currentTickNumber;
	}

	/**
	 * Clear all recorded ticks.
	 */
	public synchronized void clear()
	{
		frames.clear();
		newestTick = null;
		currentTick = null;
		log.debug("TickRecorder cleared");
	}

	/**
	 * Reset tick counter (e.g., on login).
	 */
	public void resetTickCounter()
	{
		currentTickNumber = 0;
		clear();
		log.debug("Tick counter reset");
	}
}
