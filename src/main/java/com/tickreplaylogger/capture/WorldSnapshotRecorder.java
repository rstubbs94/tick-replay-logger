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

import com.google.gson.Gson;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.DeathRecordingV2;
import com.tickreplaylogger.model.world.EffectEvent;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.ObservedEventLink;
import com.tickreplaylogger.model.world.SceneData;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rolling buffer of {@link WorldSnapshot}s plus the scenes they reference and the damage/effect/
 * action events, assembled into a versioned {@link DeathRecordingV2} on export. All raw capture is
 * kept as compact objects; JSON work happens only at export (§6).
 *
 * <p>Scene grids are retained for every scene a buffered snapshot still references (so travel-heavy
 * deaths reconstruct — architecture §4b), and events are windowed to the buffered tick range.
 */
public class WorldSnapshotRecorder
{
	private int capacity;
	private Integer newestTick;
	private final Deque<WorldSnapshot> buffer = new ArrayDeque<>();
	private final Map<String, SceneData> scenes = new LinkedHashMap<>();

	// Events accumulate keyed by the tick they belong to; windowed to the buffer on export.
	private final List<DamageRecord> damageEvents = new ArrayList<>();
	private final List<EffectEvent> effectEvents = new ArrayList<>();
	private final List<DeathRecordingV2.Events.PlayerActionRecord> playerActions = new ArrayList<>();
	public static final int MAX_OBSERVATIONS = 100_000;
	private final Deque<ObservedEvent> observations = new ArrayDeque<>();
	private final Map<Integer, Deque<ObservedEvent>> projectileDefinitionObservations = new HashMap<>();
	private final Map<Long, Deque<ObservedEvent>> projectileInstanceObservations = new HashMap<>();
	private static final int PROJECTILE_QUERY_LIMIT = 4096;
	private boolean observationsTickOrdered = true;
	public static final int MAX_OBSERVATION_LINKS = 100_000;
	private final Deque<ObservedEventLink> observationLinks = new ArrayDeque<>();
	private final java.util.NavigableMap<Integer, Long> omittedObservationLinks = new java.util.TreeMap<>();
	private final java.util.NavigableMap<Integer, Long> omittedObservations = new java.util.TreeMap<>();
	private final List<DeathRecordingV2.Events.DeathMark> deaths = new ArrayList<>();

	private final Gson gson;

	public WorldSnapshotRecorder(int capacity, Gson gson)
	{
		this.gson = java.util.Objects.requireNonNull(gson, "gson");
		this.capacity = com.tickreplaylogger.util.RecordingLimits.ticks(capacity);
	}

	public synchronized void resize(int newCapacity)
	{
		this.capacity = com.tickreplaylogger.util.RecordingLimits.ticks(newCapacity);
		trim();
	}

	public synchronized void addSnapshot(WorldSnapshot snapshot)
	{
		buffer.addLast(snapshot);
		newestTick = newestTick == null ? snapshot.getTick() : Math.max(newestTick, snapshot.getTick());
		trim();
	}

	/** Journal boundaries retain/expire events independently of rich snapshot availability. */
	public synchronized void advanceTick(int tick)
	{
		newestTick = newestTick == null ? tick : Math.max(newestTick, tick);
		trim();
	}

	private int firstRetainedTick()
	{
		return newestTick == null ? 0 : (int) Math.max(Integer.MIN_VALUE, (long) newestTick - capacity + 1);
	}

	private void trim()
	{
		int first = firstRetainedTick();
		while (!buffer.isEmpty() && (buffer.size() > capacity || buffer.getFirst().getTick() < first))
		{
			buffer.removeFirst();
		}
		if (newestTick == null)
			return;
		damageEvents.removeIf(e -> e.getImpactTick() < first);
		effectEvents.removeIf(e -> e.getStartTick() < first);
		playerActions.removeIf(e -> e.getTick() < first);
		observations.removeIf(e ->
		{
			if (e.getTick() >= first)
				return false;
			unindexProjectileObservation(e);
			return true;
		});
		observationLinks.removeIf(e -> e.getTick() < first);
		omittedObservationLinks.headMap(first, false).clear();
		omittedObservations.headMap(first, false).clear();
		deaths.removeIf(e -> e.getTick() < first);
		java.util.Set<String> used = new java.util.HashSet<>();
		for (WorldSnapshot tick : buffer)
			used.add(tick.getSceneKey());
		scenes.keySet().retainAll(used);
	}

	public synchronized void putScene(SceneData scene)
	{
		if (scene != null && scene.getSceneKey() != null)
		{
			scenes.put(scene.getSceneKey(), scene);
		}
	}

	public synchronized boolean hasScene(String sceneKey)
	{
		return scenes.containsKey(sceneKey);
	}

	public synchronized void addDamage(DamageRecord record)
	{
		if (record != null)
		{
			damageEvents.add(record);
		}
	}

	public synchronized void addEffect(EffectEvent event)
	{
		if (event != null)
		{
			effectEvents.add(event);
		}
	}

	public synchronized void addPlayerAction(int tick, String type, String detail)
	{
		playerActions
			.add(DeathRecordingV2.Events.PlayerActionRecord.builder().tick(tick).type(type).detail(detail).build());
	}

	public synchronized void addPlayerAction(DeathRecordingV2.Events.PlayerActionRecord action)
	{
		playerActions.add(action);
	}

	public synchronized void addObservation(ObservedEvent observation)
	{
		if (observation == null)
			return;
		while (observations.size() >= MAX_OBSERVATIONS)
		{
			ObservedEvent omitted = observations.removeFirst();
			unindexProjectileObservation(omitted);
			omittedObservations.merge(omitted.getTick(), 1L, Long::sum);
		}
		while (omittedObservations.size() > com.tickreplaylogger.util.RecordingLimits.MAX_TICKS)
			omittedObservations.pollFirstEntry();
		if (observations.isEmpty())
			observationsTickOrdered = true;
		else if (observations.peekLast().getTick() > observation.getTick())
			observationsTickOrdered = false;
		observations.addLast(observation);
		indexProjectileObservation(observation);
	}

	private void indexProjectileObservation(ObservedEvent event)
	{
		Long instance = ObservedProjectileEndpointResolver.observationId(event);
		if (instance == null || instance <= 0)
			return;
		projectileInstanceObservations.computeIfAbsent(instance, ignored -> new ArrayDeque<>()).addLast(event);
		Integer definition = ObservedProjectileEndpointResolver.projectileId(event);
		if (definition != null && definition >= 0)
			projectileDefinitionObservations.computeIfAbsent(definition, ignored -> new ArrayDeque<>()).addLast(event);
	}

	private void unindexProjectileObservation(ObservedEvent event)
	{
		Long instance = ObservedProjectileEndpointResolver.observationId(event);
		if (instance == null)
			return;
		removeIndexedEvent(projectileInstanceObservations, instance, event);
		Integer definition = ObservedProjectileEndpointResolver.projectileId(event);
		if (definition != null)
			removeIndexedEvent(projectileDefinitionObservations, definition, event);
	}

	private static <T> void removeIndexedEvent(Map<T, Deque<ObservedEvent>> index, T key, ObservedEvent event)
	{
		Deque<ObservedEvent> events = index.get(key);
		if (events == null)
			return;
		java.util.Iterator<ObservedEvent> iterator = events.iterator();
		while (iterator.hasNext())
			if (iterator.next() == event)
			{
				iterator.remove();
				break;
			}
		if (events.isEmpty())
			index.remove(key);
	}

	public static final class ProjectileObservationBatch
	{
		private final List<ObservedEvent> events;
		private final boolean complete;

		private ProjectileObservationBatch(List<ObservedEvent> events, boolean complete)
		{
			this.events = java.util.Collections.unmodifiableList(events);
			this.complete = complete;
		}

		public List<ObservedEvent> getEvents()
		{
			return events;
		}

		public boolean isComplete()
		{
			return complete;
		}
	}

	/** Referenced projectile timelines plus the current tail; never copy the complete raw journal. */
	public synchronized ProjectileObservationBatch projectileObservationsForSourceResolution(java.util.Set<Integer> ids,
		int tick, List<ObservedEvent> currentTail)
	{
		java.util.Set<Long> instances = new java.util.LinkedHashSet<>();
		int visited = 0;
		boolean complete = omittedObservations.isEmpty();
		outer: for (Integer id : ids == null ? java.util.Collections.<Integer>emptySet() : ids)
		{
			Deque<ObservedEvent> events = projectileDefinitionObservations.get(id);
			if (events == null)
				continue;
			java.util.Iterator<ObservedEvent> iterator = events.descendingIterator();
			while (iterator.hasNext())
			{
				if (++visited > PROJECTILE_QUERY_LIMIT)
				{
					complete = false;
					break outer;
				}
				ObservedEvent event = iterator.next();
				if (event.getTick() <= tick)
					instances.add(ObservedProjectileEndpointResolver.observationId(event));
			}
		}
		Map<Long, ObservedEvent> merged = new LinkedHashMap<>();
		visited = 0;
		outer: for (Long instance : instances)
		{
			Deque<ObservedEvent> events = projectileInstanceObservations.get(instance);
			if (events == null)
				continue;
			java.util.Iterator<ObservedEvent> iterator = events.descendingIterator();
			while (iterator.hasNext())
			{
				if (++visited > PROJECTILE_QUERY_LIMIT)
				{
					complete = false;
					break outer;
				}
				ObservedEvent event = iterator.next();
				if (event.getTick() <= tick)
				{
					ObservedEvent previous = merged.putIfAbsent(event.getSequence(), event);
					if (event.getSequence() <= 0 || previous != null && previous != event)
						complete = false;
				}
			}
		}
		if (currentTail != null)
			for (ObservedEvent event : currentTail)
			{
				if (event == null)
				{
					complete = false;
					continue;
				}
				ObservedEvent previous = merged.putIfAbsent(event.getSequence(), event);
				if (previous != null && previous != event)
					complete = false;
			}
		List<ObservedEvent> out = new ArrayList<>(merged.values());
		out.sort(java.util.Comparator.comparingLong(ObservedEvent::getSequence));
		return new ProjectileObservationBatch(out, complete);
	}

	public synchronized void addObservationLink(ObservedEventLink link)
	{
		if (link == null)
			return;
		if (link.getTick() != link.getResolvedTick() || link.getObservationSequence() < 1
			|| !"player".equals(link.getActorRef()) || !"SETTLED_LOCAL_REFERENCE_MATCH".equals(link.getEvidence())
			|| !java.util.Arrays.asList("HITSPLAT", "ACTOR_DEATH").contains(link.getObservationType()))
			throw new IllegalArgumentException("Unsupported observation recipient link");
		while (observationLinks.size() >= MAX_OBSERVATION_LINKS)
		{
			ObservedEventLink omitted = observationLinks.removeFirst();
			omittedObservationLinks.merge(omitted.getTick(), 1L, Long::sum);
		}
		while (omittedObservationLinks.size() > com.tickreplaylogger.util.RecordingLimits.MAX_TICKS)
			omittedObservationLinks.pollFirstEntry();
		observationLinks.addLast(link);
	}

	/** Later context-dependent hits may be HP damage; do not falsely blame an older known hit. */
	public synchronized boolean hasUnresolvedPlayerImpact(int tick, DamageRecord known)
	{
		if (omittedObservations.containsKey(tick))
			return true;
		for (ObservedEvent event : observations)
		{
			if (event.getTick() != tick || !"HITSPLAT".equals(event.getType()) || !"player".equals(event.getActorRef())
				|| event.getData() == null)
				continue;
			Object surface = event.getData().get("resourceSurface"), amount = event.getData().get("amount");
			if (!(amount instanceof Number) || ((Number) amount).intValue() <= 0)
				continue;
			if ("HP".equals(surface) || "SKILL_OR_HP".equals(surface) || "HP_OR_COMBAT_RESOURCE".equals(surface)
				|| "UNCLASSIFIED".equals(surface) || "SANITY".equals(surface) || "STACKS".equals(surface)
				|| "CONTEXTUAL_RESOURCE".equals(surface))
			{
				if (known == null || known.getImpactTick() < tick || known.getObservationSequence() == null
					|| event.getSequence() > known.getObservationSequence())
					return true;
			}
		}
		return false;
	}

	public synchronized void addDeath(DeathRecordingV2.Events.DeathMark death)
	{
		deaths.add(death);
	}

	public synchronized boolean isEmpty()
	{
		return buffer.isEmpty();
	}

	public synchronized void clear()
	{
		newestTick = null;
		buffer.clear();
		scenes.clear();
		damageEvents.clear();
		effectEvents.clear();
		playerActions.clear();
		observations.clear();
		projectileDefinitionObservations.clear();
		projectileInstanceObservations.clear();
		observationLinks.clear();
		omittedObservationLinks.clear();
		omittedObservations.clear();
		deaths.clear();
	}

	/**
	 * Assemble the export for a death: window events to the buffered tick range, keep only the
	 * scenes still referenced, and build the id→name maps from the buffer. Does not clear state.
	 */
	public DeathRecordingV2 buildExport(DeathRecordingV2.Meta meta, DeathRecordingV2.Summary summary)
	{
		return buildExport(meta, summary, Integer.MIN_VALUE, Integer.MAX_VALUE);
	}

	/** Export the selected journal window, excluding later ticks even if the live buffer advances. */
	public DeathRecordingV2 buildExport(DeathRecordingV2.Meta meta, DeathRecordingV2.Summary summary, int fromTick,
		int throughTick)
	{
		return completeExport(freezeExport(meta, summary, fromTick, throughTick));
	}

	/**
	 * Select a stable window of finished capture DTOs under the recorder lock. Finished DTOs and
	 * their captured values are never modified by later ticks; only the rolling collections change.
	 * Compaction and JSON serialization must happen after this method releases the lock.
	 */
	public synchronized DeathRecordingV2 freezeExport(DeathRecordingV2.Meta meta, DeathRecordingV2.Summary summary,
		int fromTick, int throughTick)
	{
		List<WorldSnapshot> ticks = new ArrayList<>();
		for (WorldSnapshot tick : buffer)
		{
			if (tick.getTick() >= fromTick && tick.getTick() <= throughTick)
				ticks.add(tick);
		}
		int minTick = fromTick == Integer.MIN_VALUE ? firstRetainedTick() : fromTick;
		int maxTick = throughTick == Integer.MAX_VALUE ? (newestTick == null ? 0 : newestTick) : throughTick;

		// scenes referenced by buffered snapshots
		List<SceneData> usedScenes = new ArrayList<>();
		java.util.Set<String> referenced = new java.util.HashSet<>();
		for (WorldSnapshot ws : ticks)
		{
			if (ws.getSceneKey() != null)
			{
				referenced.add(ws.getSceneKey());
			}
		}
		for (String key : referenced)
		{
			SceneData s = scenes.get(key);
			if (s != null)
			{
				usedScenes.add(s);
			}
		}

		DeathRecordingV2.Events events = DeathRecordingV2.Events.builder().damageEvents(windowDamage(minTick, maxTick))
			.effectEvents(windowEffects(minTick, maxTick)).playerActions(windowActions(minTick, maxTick))
			.deaths(windowDeaths(minTick, maxTick)).observations(windowObservations(minTick, maxTick))
			.observationLinks(windowObservationLinks(minTick, maxTick)).captureGaps(windowCaptureGaps(minTick, maxTick))
			.build();

		return DeathRecordingV2.builder().schemaVersion(DeathRecordingV2.LATEST_SCHEMA_VERSION).meta(meta)
			.scenes(usedScenes).ticks(ticks).events(events)
			.summary(
				summary == null ? null
					: summary.toBuilder().ticksCaptured(ticks.size())
						.otherPlayersPresent(nativeFrames(ticks).stream()
							.anyMatch(t -> t.getPlayers() != null && !t.getPlayers().isEmpty())
								? Boolean.TRUE
								: meta != null && meta.getCaptureSettings() != null
									&& meta.getCaptureSettings().isOtherPlayers() ? Boolean.FALSE : null)
						.build())
			.build();
	}

	/** Complete a detached window on a worker, without blocking capture or reading client APIs. */
	public DeathRecordingV2 completeExport(DeathRecordingV2 frozen)
	{
		DeathRecordingV2 resolved = ResolverV1Recording.resolve(frozen, gson);
		return NpcStateEncoding.compact(resolved.toBuilder().idToName(buildIdToName(resolved.getTicks()))
			.ticks(WorldStateExport.compact(resolved.getTicks())).build(), gson);
	}

	private List<DamageRecord> windowDamage(int min, int max)
	{
		List<DamageRecord> out = new ArrayList<>();
		for (DamageRecord d : damageEvents)
		{
			if (d.getImpactTick() >= min && d.getImpactTick() <= max)
			{
				out.add(d);
			}
		}
		out.sort(java.util.Comparator.comparingInt(DamageRecord::getImpactTick)
			.thenComparingLong(d -> d.getObservationSequence() == null ? Long.MAX_VALUE : d.getObservationSequence()));
		return out;
	}

	private List<DeathRecordingV2.Events.CaptureGap> windowCaptureGaps(int min, int max)
	{
		List<DeathRecordingV2.Events.CaptureGap> out = new ArrayList<>();
		for (Map.Entry<Integer, Long> entry : omittedObservations.subMap(min, true, max, true).entrySet())
			out.add(DeathRecordingV2.Events.CaptureGap.builder().tick(entry.getKey()).channel("rawCallbacks")
				.omittedObservations(entry.getValue()).reason("OBSERVATION_BUFFER_LIMIT").build());
		for (Map.Entry<Integer, Long> entry : omittedObservationLinks.subMap(min, true, max, true).entrySet())
			out.add(DeathRecordingV2.Events.CaptureGap.builder().tick(entry.getKey()).channel("observationLinks")
				.omittedObservations(entry.getValue()).reason("OBSERVATION_LINK_BUFFER_LIMIT").build());
		return out;
	}

	private List<ObservedEventLink> windowObservationLinks(int min, int max)
	{
		List<ObservedEventLink> out = new ArrayList<>();
		for (ObservedEventLink link : observationLinks)
			if (link.getTick() >= min && link.getTick() <= max)
				out.add(link);
		return out;
	}

	private List<ObservedEvent> windowObservations(int min, int max)
	{
		List<ObservedEvent> out = new ArrayList<>();
		for (ObservedEvent e : observations)
			if (e.getTick() >= min && e.getTick() <= max)
				out.add(e);
		return out;
	}

	private List<DeathRecordingV2.Events.DeathMark> windowDeaths(int min, int max)
	{
		List<DeathRecordingV2.Events.DeathMark> out = new ArrayList<>();
		for (DeathRecordingV2.Events.DeathMark e : deaths)
			if (e.getTick() >= min && e.getTick() <= max)
				out.add(e);
		return out;
	}

	private List<EffectEvent> windowEffects(int min, int max)
	{
		List<EffectEvent> out = new ArrayList<>();
		for (EffectEvent e : effectEvents)
		{
			if (e.getStartTick() >= min && e.getStartTick() <= max)
			{
				out.add(e);
			}
		}
		return out;
	}

	private List<DeathRecordingV2.Events.PlayerActionRecord> windowActions(int min, int max)
	{
		List<DeathRecordingV2.Events.PlayerActionRecord> out = new ArrayList<>();
		for (DeathRecordingV2.Events.PlayerActionRecord a : playerActions)
		{
			if (a.getTick() >= min && a.getTick() <= max)
			{
				out.add(a);
			}
		}
		return out;
	}

	private Map<String, Map<String, String>> buildIdToName(List<WorldSnapshot> ticks)
	{
		Map<String, String> npc = new HashMap<>();
		for (WorldSnapshot ws : nativeFrames(ticks))
		{
			if (ws.getNpcs() == null)
			{
				continue;
			}
			for (NpcState n : ws.getNpcs())
			{
				if (n.getName() != null && n.getNpcId() != null)
				{
					npc.put(String.valueOf(n.getNpcId()), n.getName());
				}
			}
		}
		Map<String, Map<String, String>> maps = new HashMap<>();
		maps.put("npc", npc);
		return maps;
	}

	private static List<WorldSnapshot> nativeFrames(List<WorldSnapshot> ticks)
	{
		List<WorldSnapshot> frames = new ArrayList<>();
		for (WorldSnapshot tick : ticks)
		{
			frames.add(tick);
			if (tick.getAdditionalViews() != null)
				frames.addAll(tick.getAdditionalViews());
		}
		return frames;
	}

	public synchronized int size()
	{
		return buffer.size();
	}

	/** The buffered snapshot for a given tick, or null. Used to read defender state at a launch tick. */
	public synchronized WorldSnapshot snapshotAtTick(int tick)
	{
		for (WorldSnapshot ws : buffer)
		{
			if (ws.getTick() == tick)
			{
				return ws;
			}
		}
		return null;
	}

	/** The most recently added damage event, including a zero-damage block, or null. */
	public synchronized DamageRecord lastDamage()
	{
		return damageEvents.isEmpty() ? null : damageEvents.get(damageEvents.size() - 1);
	}

	/** Only positive damage received by the local player can be the killing blow. */
	public synchronized List<DamageRecord> positiveReceivedAtTick(int tick)
	{
		List<DamageRecord> out = new ArrayList<>();
		for (DamageRecord record : damageEvents)
			if (record.getImpactTick() == tick && record.getAmount() > 0
				&& (record.getDirection() == null || "RECEIVED".equals(record.getDirection())))
				out.add(record);
		return java.util.Collections.unmodifiableList(out);
	}

	/** Original received observations, including zeroes, for one completed tick. */
	public synchronized List<DamageRecord> receivedAtTick(int tick)
	{
		List<DamageRecord> out = new ArrayList<>();
		for (DamageRecord record : damageEvents)
		{
			if (record.getImpactTick() == tick
				&& (record.getDirection() == null || "RECEIVED".equals(record.getDirection())))
			{
				out.add(record);
			}
		}
		return java.util.Collections.unmodifiableList(out);
	}

	/** Finished capture values only; no live actor or scene API objects leave the recorder. */
	public synchronized List<WorldSnapshot> snapshotsForSourceResolution()
	{
		return java.util.Collections.unmodifiableList(new ArrayList<>(buffer));
	}

	public synchronized List<SceneData> scenesForSourceResolution()
	{
		return java.util.Collections.unmodifiableList(new ArrayList<>(scenes.values()));
	}

	public synchronized List<ObservedEvent> observationsForSourceResolution()
	{
		return java.util.Collections.unmodifiableList(new ArrayList<>(observations));
	}

	/** Same-tick cues need only the ordered tail, not another copy of the full callback history. */
	public synchronized List<ObservedEvent> observationsAtTickForSourceResolution(int tick)
	{
		if (!observationsTickOrdered)
			return java.util.Collections.unmodifiableList(windowObservations(tick, tick));
		List<ObservedEvent> out = new ArrayList<>();
		java.util.Iterator<ObservedEvent> iterator = observations.descendingIterator();
		while (iterator.hasNext())
		{
			ObservedEvent event = iterator.next();
			if (event.getTick() < tick)
				break;
			if (event.getTick() == tick)
				out.add(event);
		}
		java.util.Collections.reverse(out);
		return java.util.Collections.unmodifiableList(out);
	}

	public static final class LifecycleObservationBatch
	{
		private final List<ObservedEvent> events;
		private final boolean complete;

		private LifecycleObservationBatch(List<ObservedEvent> events, boolean complete)
		{
			this.events = java.util.Collections.unmodifiableList(events);
			this.complete = complete;
		}

		public List<ObservedEvent> getEvents()
		{
			return events;
		}

		public boolean isComplete()
		{
			return complete;
		}
	}

	/** Bounded reverse lookback for explicit retained death cues; other past callbacks are never copied. */
	public synchronized LifecycleObservationBatch retainedLifecycleObservationsForSourceResolution(int tick,
		int maximumDelay, List<ObservedEvent> currentTail)
	{
		List<ObservedEvent> out = new ArrayList<>();
		if (currentTail != null)
			out.addAll(currentTail);
		if (tick < 0 || maximumDelay < 0 || maximumDelay > MechanismRules.MAX_RETAINED_LIFECYCLE_TICKS
			|| !observationsTickOrdered || currentTail == null)
			return new LifecycleObservationBatch(out, false);
		int min = Math.max(0, tick - maximumDelay), visited = 0;
		boolean complete = omittedObservations.subMap(min, true, tick, true).isEmpty();
		java.util.Iterator<ObservedEvent> iterator = observations.descendingIterator();
		while (iterator.hasNext())
		{
			if (++visited > 8192)
			{
				complete = false;
				break;
			}
			ObservedEvent event = iterator.next();
			if (event.getTick() < min)
				break;
			if (event.getTick() < tick && "ACTOR_DEATH".equals(event.getType()))
				out.add(event);
		}
		out.sort(java.util.Comparator.comparingLong(ObservedEvent::getSequence));
		return new LifecycleObservationBatch(out, complete);
	}

	public synchronized List<DeathRecordingV2.Events.CaptureGap> captureGapsForSourceResolution()
	{
		return java.util.Collections.unmodifiableList(windowCaptureGaps(Integer.MIN_VALUE, Integer.MAX_VALUE));
	}

	public synchronized List<DeathRecordingV2.Events.CaptureGap> captureGapsAtTickForSourceResolution(int tick)
	{
		return java.util.Collections.unmodifiableList(windowCaptureGaps(tick, tick));
	}

	public synchronized List<DeathRecordingV2.Events.CaptureGap> retainedLifecycleGapsForSourceResolution(int tick,
		int maximumDelay)
	{
		if (tick < 0 || maximumDelay < 0 || maximumDelay > MechanismRules.MAX_RETAINED_LIFECYCLE_TICKS)
			return java.util.Collections.emptyList();
		return java.util.Collections.unmodifiableList(windowCaptureGaps(Math.max(0, tick - maximumDelay), tick));
	}

	/** Enrich an original callback by identity. Derived source context never becomes a new HP event. */
	public synchronized void enrichHitsplatObservations(int tick, Map<Long, Map<String, Object>> enrichments)
	{
		if (enrichments == null || enrichments.isEmpty())
			return;
		if (observationsTickOrdered)
		{
			List<ObservedEvent> tail = new ArrayList<>();
			try
			{
				while (!observations.isEmpty() && observations.peekLast().getTick() >= tick)
				{
					tail.add(enrichedHitsplat(observations.peekLast(), tick, enrichments, "COMPLETE_TICK_TAIL"));
					observations.removeLast();
				}
			}
			finally
			{
				for (int index = tail.size() - 1; index >= 0; index--)
					observations.addLast(tail.get(index));
			}
			return;
		}
		// Arbitrary caller order is supported without pretending an unvisited prefix was empty.
		// The recorder's existing MAX_OBSERVATIONS cap bounds this exceptional full scan.
		List<ObservedEvent> updated = new ArrayList<>(observations.size());
		for (ObservedEvent event : observations)
			updated.add(enrichedHitsplat(event, tick, enrichments, "COMPLETE_BUFFER_SCAN"));
		observations.clear();
		observations.addAll(updated);
	}

	private static ObservedEvent enrichedHitsplat(ObservedEvent event, int tick,
		Map<Long, Map<String, Object>> enrichments, String coverage)
	{
		Map<String, Object> derived = event.getTick() == tick && "HITSPLAT".equals(event.getType())
			? enrichments.get(event.getSequence())
			: null;
		if (derived == null)
			return event;
		Map<String, Object> data = new LinkedHashMap<>(
			event.getData() == null ? java.util.Collections.emptyMap() : event.getData());
		for (String key : java.util.Arrays.asList("attributionContext", "attributionContextEvidence",
			"sourceResolution", "attributionCandidates", "observedHazardContacts", "possibleOutcomeSurfaces",
			"attributionCaptureStatus", "resourceOutcomeStatus"))
			if (derived.containsKey(key))
				data.put(key, derived.get(key));
		data.put("sourceEnrichmentCoverage", coverage);
		return ObservedEvent.builder().tick(event.getTick()).gameCycle(event.getGameCycle())
			.sequence(event.getSequence()).type(event.getType()).actorRef(event.getActorRef())
			.actorName(event.getActorName()).relatedActorRef(event.getRelatedActorRef()).sceneKey(event.getSceneKey())
			.data(java.util.Collections.unmodifiableMap(data)).build();
	}

	/** Replace derived interpretations by callback identity, leaving the original records intact. */
	public synchronized void applySourceResolutions(int tick, Map<Long, SourceResolution> resolutions)
	{
		if (resolutions == null || resolutions.isEmpty())
		{
			return;
		}
		for (int index = damageEvents.size() - 1; index >= 0; index--)
		{
			DamageRecord record = damageEvents.get(index);
			if (record.getImpactTick() != tick || record.getObservationSequence() == null
				|| record.getDirection() != null && !"RECEIVED".equals(record.getDirection()))
			{
				continue;
			}
			SourceResolution resolution = resolutions.get(record.getObservationSequence());
			if (resolution != null)
			{
				damageEvents.set(index, record.toBuilder().sourceResolution(resolution).build());
			}
		}
	}

	public synchronized DamageRecord lastDamagingHit()
	{
		for (int i = damageEvents.size() - 1; i >= 0; i--)
		{
			if (damageEvents.get(i).getAmount() > 0 && (damageEvents.get(i).getDirection() == null
				|| "RECEIVED".equals(damageEvents.get(i).getDirection())))
			{
				return damageEvents.get(i);
			}
		}
		return null;
	}
}
