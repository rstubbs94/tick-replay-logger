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

import com.tickreplaylogger.util.RecordingLimits;
import com.google.gson.Gson;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import java.util.function.ToIntFunction;
import lombok.extern.slf4j.Slf4j;

/**
 * Rolling buffer of {@link WorldSnapshot}s plus the scenes they reference and the damage/effect/
 * action events, assembled into a versioned {@link DeathRecordingV2} on export. Raw capture stays
 * in detached objects; interpretation and JSON preparation run on workers.
 *
 * <p>Scene grids are retained for every scene a buffered snapshot still references (so travel-heavy
 * deaths reconstruct â€” architecture Â§4b), and events are windowed to the buffered tick range.
 */
@Slf4j
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
	private boolean observationsTickOrdered = true;
	public static final int MAX_OBSERVATION_LINKS = 100_000;
	private final Deque<ObservedEventLink> observationLinks = new ArrayDeque<>();
	private final NavigableMap<Integer, Long> omittedObservationLinks = new TreeMap<>();
	private final NavigableMap<Integer, Long> omittedObservations = new TreeMap<>();
	private final List<DeathRecordingV2.Events.DeathMark> deaths = new ArrayList<>();

	private final Gson gson;

	public WorldSnapshotRecorder(int capacity, Gson gson)
	{
		this.gson = Objects.requireNonNull(gson, "gson");
		this.capacity = RecordingLimits.ticks(capacity);
	}

	public synchronized void resize(int newCapacity)
	{
		this.capacity = RecordingLimits.ticks(newCapacity);
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
		{
			return;
		}
		damageEvents.removeIf(e -> e.getImpactTick() < first);
		effectEvents.removeIf(e -> e.getStartTick() < first);
		playerActions.removeIf(e -> e.getTick() < first);
		observations.removeIf(e -> e.getTick() < first);
		observationLinks.removeIf(e -> e.getTick() < first);
		omittedObservationLinks.headMap(first, false).clear();
		omittedObservations.headMap(first, false).clear();
		deaths.removeIf(e -> e.getTick() < first);
		Set<String> used = new java.util.HashSet<>();
		for (WorldSnapshot tick : buffer)
		{
			used.add(tick.getSceneKey());
		}
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
		{
			return;
		}
		while (observations.size() >= MAX_OBSERVATIONS)
		{
			ObservedEvent omitted = observations.removeFirst();
			omittedObservations.merge(omitted.getTick(), 1L, Long::sum);
		}
		while (omittedObservations.size() > RecordingLimits.MAX_TICKS)
		{
			omittedObservations.pollFirstEntry();
		}
		if (observations.isEmpty())
		{
			observationsTickOrdered = true;
		}
		else if (observations.peekLast().getTick() > observation.getTick())
		{
			observationsTickOrdered = false;
		}
		observations.addLast(observation);
	}

	public synchronized void addObservationLink(ObservedEventLink link)
	{
		if (link == null)
		{
			return;
		}
		if (link.getTick() != link.getResolvedTick() || link.getObservationSequence() < 1
			|| !"player".equals(link.getActorRef()) || !"SETTLED_LOCAL_REFERENCE_MATCH".equals(link.getEvidence())
			|| !Arrays.asList("HITSPLAT", "ACTOR_DEATH").contains(link.getObservationType()))
		{
			throw new IllegalArgumentException("Unsupported observation recipient link");
		}
		while (observationLinks.size() >= MAX_OBSERVATION_LINKS)
		{
			ObservedEventLink omitted = observationLinks.removeFirst();
			omittedObservationLinks.merge(omitted.getTick(), 1L, Long::sum);
		}
		while (omittedObservationLinks.size() > RecordingLimits.MAX_TICKS)
		{
			omittedObservationLinks.pollFirstEntry();
		}
		observationLinks.addLast(link);
	}

	/** Later context-dependent hits may be HP damage; do not falsely blame an older known hit. */
	public synchronized boolean hasUnresolvedPlayerImpact(int tick, DamageRecord known)
	{
		if (omittedObservations.containsKey(tick))
		{
			return true;
		}
		for (ObservedEvent event : observations)
		{
			if (event.getTick() != tick || !"HITSPLAT".equals(event.getType()) || !"player".equals(event.getActorRef())
				|| event.getData() == null)
			{
				continue;
			}
			Object surface = event.getData().get("resourceSurface"), amount = event.getData().get("amount");
			if (!(amount instanceof Number) || ((Number) amount).intValue() <= 0)
			{
				continue;
			}
			if ("HP".equals(surface) || "SKILL_OR_HP".equals(surface) || "HP_OR_COMBAT_RESOURCE".equals(surface)
				|| "UNCLASSIFIED".equals(surface) || "SANITY".equals(surface) || "STACKS".equals(surface)
				|| "CONTEXTUAL_RESOURCE".equals(surface))
			{
				if (known == null || known.getImpactTick() < tick || known.getObservationSequence() == null
					|| event.getSequence() > known.getObservationSequence())
				{
					return true;
				}
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
		observationLinks.clear();
		omittedObservationLinks.clear();
		omittedObservations.clear();
		deaths.clear();
	}

	/**
	 * Assemble the export for a death: window events to the buffered tick range, keep only the
	 * scenes still referenced, and build the idâ†’name maps from the buffer. Does not clear state.
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
			{
				ticks.add(tick);
			}
		}
		int minTick = fromTick == Integer.MIN_VALUE ? firstRetainedTick() : fromTick;
		int maxTick = throughTick == Integer.MAX_VALUE ? (newestTick == null ? 0 : newestTick) : throughTick;

		// scenes referenced by buffered snapshots
		List<SceneData> usedScenes = new ArrayList<>();
		Set<String> referenced = new java.util.HashSet<>();
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
			.effectEvents(window(effectEvents, EffectEvent::getStartTick, minTick, maxTick))
			.playerActions(window(playerActions, DeathRecordingV2.Events.PlayerActionRecord::getTick, minTick, maxTick))
			.deaths(window(deaths, DeathRecordingV2.Events.DeathMark::getTick, minTick, maxTick))
			.observations(window(observations, ObservedEvent::getTick, minTick, maxTick))
			.observationLinks(window(observationLinks, ObservedEventLink::getTick, minTick, maxTick))
			.captureGaps(windowCaptureGaps(minTick, maxTick)).build();

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
		if (frozen == null)
		{
			return null;
		}
		try
		{
			DeathRecordingV2 resolved = ResolverV1Recording.resolve(frozen, gson);
			return NpcStateEncoding.compact(resolved.toBuilder().idToName(buildIdToName(resolved.getTicks()))
				.ticks(WorldStateExport.compact(resolved.getTicks())).build(), gson);
		}
		catch (RuntimeException unavailable)
		{
			log.warn("Post-event processing failed; preserving original replay observations", unavailable);
			DeathRecordingV2.Meta meta = frozen.getMeta();
			List<String> issues = new ArrayList<>(
				meta == null || meta.getCaptureIssues() == null ? Collections.emptyList() : meta.getCaptureIssues());
			issues.add("POST_EVENT_PROCESSING_UNAVAILABLE: " + unavailable.getClass().getSimpleName());
			return frozen.toBuilder()
				.meta((meta == null ? DeathRecordingV2.Meta.builder() : meta.toBuilder()).captureIssues(issues).build())
				.build();
		}
	}

	private List<DamageRecord> windowDamage(int min, int max)
	{
		List<DamageRecord> selected = window(damageEvents, DamageRecord::getImpactTick, min, max);
		selected.sort(Comparator.comparingInt(DamageRecord::getImpactTick).thenComparingLong(
			hit -> hit.getObservationSequence() == null ? Long.MAX_VALUE : hit.getObservationSequence()));
		return selected;
	}

	/** Retain the same original event objects in capture order within the selected tick range. */
	private static <T> List<T> window(Iterable<T> events, ToIntFunction<T> tick, int min, int max)
	{
		List<T> selected = new ArrayList<>();
		for (T event : events)
		{
			int observed = tick.applyAsInt(event);
			if (observed >= min && observed <= max)
			{
				selected.add(event);
			}
		}
		return selected;
	}

	private List<DeathRecordingV2.Events.CaptureGap> windowCaptureGaps(int min, int max)
	{
		List<DeathRecordingV2.Events.CaptureGap> out = new ArrayList<>();
		for (Map.Entry<Integer, Long> entry : omittedObservations.subMap(min, true, max, true).entrySet())
		{
			out.add(DeathRecordingV2.Events.CaptureGap.builder().tick(entry.getKey()).channel("rawCallbacks")
				.omittedObservations(entry.getValue()).reason("OBSERVATION_BUFFER_LIMIT").build());
		}
		for (Map.Entry<Integer, Long> entry : omittedObservationLinks.subMap(min, true, max, true).entrySet())
		{
			out.add(DeathRecordingV2.Events.CaptureGap.builder().tick(entry.getKey()).channel("observationLinks")
				.omittedObservations(entry.getValue()).reason("OBSERVATION_LINK_BUFFER_LIMIT").build());
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
			{
				frames.addAll(tick.getAdditionalViews());
			}
		}
		return frames;
	}

	public synchronized int size()
	{
		return buffer.size();
	}

	/** Only positive damage received by the local player can be the killing blow. */
	public synchronized List<DamageRecord> positiveReceivedAtTick(int tick)
	{
		List<DamageRecord> out = new ArrayList<>();
		for (DamageRecord record : damageEvents)
		{
			if (record.getImpactTick() == tick && record.getAmount() > 0
				&& (record.getDirection() == null || "RECEIVED".equals(record.getDirection())))
			{
				out.add(record);
			}
		}
		return Collections.unmodifiableList(out);
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
		return Collections.unmodifiableList(out);
	}

	/** Freeze membership only; interpretation and indexing belong to the worker. */
	public synchronized ResolverV1Recording.EvidenceWindow freezeSourceEvidence(int throughTick, long cutoffSequence,
		long revision, List<DamageRecord> additionalContexts)
	{
		int firstTick = firstRetainedTick();
		List<DamageRecord> hits = windowDamage(firstTick, throughTick);
		if (additionalContexts != null)
		{
			for (DamageRecord context : additionalContexts)
			{
				if (context != null && context.getImpactTick() >= firstTick && context.getImpactTick() <= throughTick)
				{
					hits.add(context);
				}
			}
		}
		return new ResolverV1Recording.EvidenceWindow(new ArrayList<>(buffer), new ArrayList<>(scenes.values()),
			window(observations, ObservedEvent::getTick, firstTick, throughTick), hits,
			windowCaptureGaps(firstTick, throughTick),
			window(observationLinks, ObservedEventLink::getTick, firstTick, throughTick), firstTick, throughTick,
			cutoffSequence, revision);
	}

	/** Enrich an original callback by identity. Derived source context never becomes a new HP event. */
	public synchronized void enrichHitsplatObservations(int tick, Map<Long, Map<String, Object>> enrichments)
	{
		if (enrichments == null || enrichments.isEmpty())
		{
			return;
		}
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
				{
					observations.addLast(tail.get(index));
				}
			}
			return;
		}
		// Arbitrary caller order is supported without pretending an unvisited prefix was empty.
		// The recorder's existing MAX_OBSERVATIONS cap bounds this exceptional full scan.
		List<ObservedEvent> updated = new ArrayList<>(observations.size());
		for (ObservedEvent event : observations)
		{
			updated.add(enrichedHitsplat(event, tick, enrichments, "COMPLETE_BUFFER_SCAN"));
		}
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
		{
			return event;
		}
		Map<String, Object> data = new LinkedHashMap<>(
			event.getData() == null ? Collections.emptyMap() : event.getData());
		for (String key : Arrays.asList("attributionContext", "attributionContextEvidence", "sourceResolution",
			"attributionCandidates", "observedHazardContacts", "possibleOutcomeSurfaces", "attributionCaptureStatus",
			"resourceOutcomeStatus"))
		{
			if (derived.containsKey(key))
			{
				data.put(key, derived.get(key));
			}
		}
		data.put("sourceEnrichmentCoverage", coverage);
		return ObservedEvent.builder().tick(event.getTick()).gameCycle(event.getGameCycle())
			.sequence(event.getSequence()).type(event.getType()).actorRef(event.getActorRef())
			.actorName(event.getActorName()).relatedActorRef(event.getRelatedActorRef()).sceneKey(event.getSceneKey())
			.data(Collections.unmodifiableMap(data)).build();
	}

	/** Apply derived records only to their original observed hit; never replace a different callback. */
	public synchronized void applyResolvedDamageRecords(int tick, Map<Long, DamageRecord> resolved)
	{
		if (resolved == null || resolved.isEmpty())
		{
			return;
		}
		for (int index = damageEvents.size() - 1; index >= 0; index--)
		{
			DamageRecord original = damageEvents.get(index);
			DamageRecord replacement = resolved.get(original.getObservationSequence());
			if (original.getImpactTick() == tick && original.getObservationSequence() != null && replacement != null
				&& original.sameNativeCallback(replacement))
			{
				damageEvents.set(index, replacement);
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
