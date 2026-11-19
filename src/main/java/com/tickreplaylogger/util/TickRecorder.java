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
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.capture.ResolverV1Recording;
import lombok.extern.slf4j.Slf4j;
import java.util.*;

/**
 * Circular buffer that records tick data.
 * Maintains only the last N ticks (default 50) to bound memory usage.
 * When the buffer is full, oldest ticks are overwritten.
 */
@Slf4j
public class TickRecorder
{
	private TickData[] buffer;
	private int capacity;
	private int head = 0; // Next write position
	private int count = 0; // Number of ticks stored
	private int currentTickNumber = 0;

	// Current tick being built (actions accumulate here until tick ends)
	private TickData currentTick;
	// Packet/input callbacks arrive before GameTick. Keep them off finalized history.
	private final List<PlayerAction> pendingActions = new ArrayList<>();
	private final List<DamageEvent> pendingDamage = new ArrayList<>();
	private final List<String> pendingEffects = new ArrayList<>();
	private final Map<Integer, Long> pendingEffectSourceSequences = new LinkedHashMap<>();

	public TickRecorder(int capacity)
	{
		this.capacity = RecordingLimits.ticks(capacity);
		this.buffer = new TickData[this.capacity];
		log.debug("TickRecorder initialized with capacity: {}", capacity);
	}

	/**
	 * Resize the buffer capacity. Preserves existing data up to the new capacity.
	 */
	public synchronized void resize(int newCapacity)
	{
		newCapacity = RecordingLimits.ticks(newCapacity);
		if (newCapacity == capacity)
		{
			return;
		}

		// Get current history in order
		List<TickData> history = getTickHistory();

		// Create new buffer
		capacity = newCapacity;
		buffer = new TickData[newCapacity];
		head = 0;
		count = 0;

		// Copy over as many ticks as will fit (most recent ones)
		int startIndex = Math.max(0, history.size() - newCapacity);
		for (int i = startIndex; i < history.size(); i++)
		{
			buffer[head] = history.get(i);
			head = (head + 1) % capacity;
			count++;
		}

		log.debug("TickRecorder resized to capacity: {} ({} ticks preserved)", newCapacity, count);
	}

	/**
	 * Start a new tick. Call this at the beginning of each GameTick.
	 */
	public void startNewTick()
	{
		currentTickNumber++;
		currentTick = TickData.builder().tickNumber(currentTickNumber).timestamp(System.currentTimeMillis())
			.playerActions(new ArrayList<>(pendingActions)).damageEvents(new ArrayList<>(pendingDamage))
			.effectNotes(new ArrayList<>(pendingEffects))
			.effectNoteSourceSequences(new LinkedHashMap<>(pendingEffectSourceSequences))
			.activePrayers(new ArrayList<>()).prayerStateKnown(false).unavailableStateFields(new ArrayList<>(Arrays
				.asList("currentHp", "maxHp", "currentPrayer", "maxPrayer", "gameState", "apiTickCount", "gameCycle")))
			.build();
		pendingActions.clear();
		pendingDamage.clear();
		pendingEffects.clear();
		pendingEffectSourceSequences.clear();
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

		// Add to circular buffer
		currentTick.setEffectNoteSourceSequences(Collections.unmodifiableMap(
			new LinkedHashMap<>(currentTick.getEffectNoteSourceSequences() == null ? Collections.emptyMap()
				: currentTick.getEffectNoteSourceSequences())));
		buffer[head] = currentTick;
		head = (head + 1) % capacity;
		if (count < capacity)
		{
			count++;
		}

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
	 * Add a player action to the current tick.
	 */
	public void addPlayerAction(PlayerAction action)
	{
		if (currentTick == null)
		{
			pendingActions.add(action);
			return;
		}
		currentTick.addPlayerAction(action);
		log.debug("Player action added: {}", action.getDescription());
	}

	/**
	 * Add a damage event to the current tick.
	 */
	public void addDamageEvent(DamageEvent event)
	{
		if (currentTick == null)
		{
			pendingDamage.add(event);
			return;
		}
		currentTick.addDamageEvent(event);
		log.debug("Damage event added: {}", event.getDescription());
	}

	public void addEffectNote(String note)
	{
		if (currentTick == null)
		{
			pendingEffects.add(note);
			return;
		}
		currentTick.addEffectNote(note);
	}

	public void addEffectNote(String note, Long observationSequence)
	{
		if (currentTick == null)
		{
			int index = pendingEffects.size();
			pendingEffects.add(note);
			if (observationSequence != null && observationSequence > 0 && index <= 4095
				&& pendingEffectSourceSequences.size() < 256)
			{
				pendingEffectSourceSequences.put(index, observationSequence);
			}
			return;
		}
		currentTick.addEffectNote(note, observationSequence);
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
		List<TickData> history = new ArrayList<>(count);

		if (count == 0)
		{
			return history;
		}

		// Calculate starting position (oldest tick)
		int start = (count < capacity) ? 0 : head;

		for (int i = 0; i < count; i++)
		{
			int index = (start + i) % capacity;
			if (buffer[index] != null)
			{
				history.add(buffer[index]);
			}
		}

		return history;
	}

	/** Attach retrospective results only to the original received callback in the selected tick. */
	public synchronized void applyReceivedSourceResolutions(int tick, Map<Long, SourceResolution> resolutions)
	{
		applyReceivedSourceResults(tick, resolutions, Collections.emptyMap());
	}

	public synchronized void applyResolvedDamageRecords(int tick, Map<Long, DamageRecord> records)
	{
		if (records == null || records.isEmpty())
		{
			return;
		}
		Map<Long, SourceResolution> resolutions = new LinkedHashMap<>();
		for (Map.Entry<Long, DamageRecord> entry : records.entrySet())
		{
			if (entry.getValue() != null && entry.getValue().getImpactTick() == tick
				&& Objects.equals(entry.getKey(), entry.getValue().getObservationSequence())
				&& (entry.getValue().getDirection() == null || "RECEIVED".equals(entry.getValue().getDirection())
					|| "RESOURCE_IMPACT".equals(entry.getValue().getDirection())))
			{
				resolutions.put(entry.getKey(), entry.getValue().getSourceResolution());
			}
		}
		applyReceivedSourceResults(tick, resolutions, records);
	}

	private void applyReceivedSourceResults(int tick, Map<Long, SourceResolution> resolutions,
		Map<Long, DamageRecord> records)
	{
		if (resolutions == null || resolutions.isEmpty())
		{
			return;
		}
		TickData selected = currentTick != null && currentTick.getTickNumber() == tick ? currentTick : null;
		int selectedIndex = -1;
		for (int offset = 1; selected == null && offset <= count; offset++)
		{
			int index = (head - offset + capacity) % capacity;
			TickData candidate = buffer[index];
			if (candidate != null && candidate.getTickNumber() == tick)
			{
				selected = candidate;
				selectedIndex = index;
			}
		}
		if (selected == null)
		{
			return;
		}
		List<DamageEvent> updated = selected.getDamageEvents() == null ? new ArrayList<>()
			: new ArrayList<>(selected.getDamageEvents());
		List<String> updatedNotes = selected.getEffectNotes() == null ? null
			: new ArrayList<>(selected.getEffectNotes());
		boolean changed = false;
		for (int index = 0; index < updated.size(); index++)
		{
			DamageEvent damage = updated.get(index);
			if (damage.getDirection() != DamageEvent.DamageDirection.RECEIVED
				|| damage.getObservationSequence() == null)
			{
				continue;
			}
			SourceResolution resolution = resolutions.get(damage.getObservationSequence());
			if (resolution != null)
			{
				DamageRecord projected = records.get(damage.getObservationSequence());
				if (projected != null)
				{
					if (projected.getAmount() != damage.getAmount()
						|| "RESOURCE_IMPACT".equals(projected.getDirection()))
					{
						continue;
					}
					updated.set(index, ResolverV1Recording.projectJournalDamage(damage, projected));
				}
				else
				{
					updated.set(index, damage.toBuilder().sourceResolution(resolution).build());
				}
				changed = true;
			}
		}
		if (updatedNotes != null && selected.getEffectNoteSourceSequences() != null)
		{
			for (Map.Entry<Integer, Long> note : selected.getEffectNoteSourceSequences().entrySet())
			{
				DamageRecord projected = records.get(note.getValue());
				int index = note.getKey();
				if (projected == null || index < 0 || index >= updatedNotes.size()
					|| !resolutions.containsKey(note.getValue()))
				{
					continue;
				}
				String before = updatedNotes.get(index);
				String after = ResolverV1Recording.projectResourceNote(before, projected);
				if (!Objects.equals(before, after))
				{
					updatedNotes.set(index, after);
					changed = true;
				}
			}
		}
		if (changed)
		{
			TickData replacement = selected.toBuilder()
				.damageEvents(selected.getDamageEvents() == null ? null : updated).effectNotes(updatedNotes)
				.effectNoteSourceSequences(selected.getEffectNoteSourceSequences() == null ? null
					: selectedIndex >= 0
						? Collections.unmodifiableMap(new LinkedHashMap<>(selected.getEffectNoteSourceSequences()))
						: new LinkedHashMap<>(selected.getEffectNoteSourceSequences()))
				.build();
			if (selectedIndex >= 0)
			{
				buffer[selectedIndex] = replacement;
			}
			else
			{
				currentTick = replacement;
			}
		}
	}

	/**
	 * Get recorded ticks in reverse chronological order (newest first).
	 * This is the order we display in the UI.
	 */
	public List<TickData> getTickHistoryReversed()
	{
		List<TickData> history = getTickHistory();
		List<TickData> reversed = new ArrayList<>(history.size());
		for (int i = history.size() - 1; i >= 0; i--)
		{
			reversed.add(history.get(i));
		}
		return reversed;
	}

	/**
	 * Get the number of ticks currently stored.
	 */
	public synchronized int getTickCount()
	{
		return count;
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
		for (int i = 0; i < capacity; i++)
		{
			buffer[i] = null;
		}
		head = 0;
		count = 0;
		currentTick = null;
		pendingActions.clear();
		pendingDamage.clear();
		pendingEffects.clear();
		pendingEffectSourceSequences.clear();
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
