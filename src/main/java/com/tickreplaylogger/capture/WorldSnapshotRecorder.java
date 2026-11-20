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

import com.google.gson.*;
import com.tickreplaylogger.model.*;
import com.tickreplaylogger.model.world.*;
import com.tickreplaylogger.util.*;
import java.util.*;
import java.util.function.*;
import lombok.extern.slf4j.*;

/**
 * The single bounded capture owner: minimal tick state, optional world samples, scenes and original events.
 * Journal rows are raw views; interpretation occurs only after a death or manual-capture window is frozen.
 *
 * <p>Scene grids are retained for every scene a buffered snapshot still references (so travel-heavy
 * deaths reconstruct â€” architecture Â§4b), and events are windowed to the buffered tick range.
 */
@Slf4j
public class WorldSnapshotRecorder extends TickRecorder
{
	private final Map<String, SceneData> scenes = new LinkedHashMap<>();
	private final Map<String, Integer> sceneLastUsed = new LinkedHashMap<>();

	// Events accumulate keyed by the tick they belong to; windowed to the buffer on export.
	/** One original callback and its captured impact context share an ID; results never enter this store. */
	private static final class CapturedEvent
	{
		ObservedEvent observation;
		DamageRecord impact;

		int tick()
		{
			return observation == null ? impact.getImpactTick() : observation.getTick();
		}
	}

	private final Map<Long, CapturedEvent> events = new LinkedHashMap<>();
	private final NavigableMap<Integer, List<Long>> impactIds = new TreeMap<>();
	private long anonymousContextId;
	private final List<EffectEvent> effectEvents = new ArrayList<>();
	private final List<DeathRecordingV2.Events.PlayerActionRecord> playerActions = new ArrayList<>();
	public static final int MAX_OBSERVATIONS = 100_000;
	private final Deque<Long> observationIds = new ArrayDeque<>();
	public static final int MAX_OBSERVATION_LINKS = 100_000;
	private final Deque<ObservedEventLink> observationLinks = new ArrayDeque<>();
	private final NavigableMap<Integer, Long> omittedObservationLinks = new TreeMap<>();
	private final NavigableMap<Integer, Long> omittedObservations = new TreeMap<>();
	private final List<DeathRecordingV2.Events.DeathMark> deaths = new ArrayList<>();

	private final Gson gson;

	public WorldSnapshotRecorder(int capacity, Gson gson)
	{
		super(capacity);
		this.gson = Objects.requireNonNull(gson, "gson");
	}

	public synchronized void addSnapshot(WorldSnapshot snapshot)
	{
		for (WorldSnapshot frame : nativeFrames(Collections.singletonList(snapshot)))
		{
			if (frame.getSceneKey() != null)
			{
				sceneLastUsed.merge(frame.getSceneKey(), frame.getTick(), Math::max);
			}
		}
		addWorldSnapshot(snapshot);
	}

	@Override
	protected void trimEvents(int first)
	{
		events.values().removeIf(event -> event.tick() < first);
		impactIds.headMap(first, false).clear();
		effectEvents.removeIf(e -> e.getStartTick() < first);
		playerActions.removeIf(e -> e.getTick() < first);
		observationIds.removeIf(id -> !events.containsKey(id));
		observationLinks.removeIf(e -> e.getTick() < first);
		omittedObservationLinks.headMap(first, false).clear();
		omittedObservations.headMap(first, false).clear();
		deaths.removeIf(e -> e.getTick() < first);
		sceneLastUsed.values().removeIf(tick -> tick < first);
		scenes.keySet().retainAll(sceneLastUsed.keySet());
	}

	public synchronized void putScene(SceneData scene)
	{
		if (scene != null && scene.getSceneKey() != null)
		{
			scenes.put(scene.getSceneKey(), scene);
			sceneLastUsed.put(scene.getSceneKey(), getCurrentTickNumber());
		}
	}

	public synchronized boolean hasScene(String sceneKey)
	{
		return scenes.containsKey(sceneKey);
	}

	public synchronized void addDamage(DamageRecord record)
	{
		if (record == null)
		{
			return;
		}
		Long sequence = record.getObservationSequence();
		long id = sequence != null && sequence > 0 ? sequence : --anonymousContextId;
		CapturedEvent event = events.computeIfAbsent(id, key -> new CapturedEvent());
		if (event.impact != null)
		{
			throw new IllegalArgumentException("An original impact ID can be captured only once");
		}
		if (event.observation != null)
		{
			requireSameCallback(event.observation, record);
		}
		event.impact = record;
		if (record.getImpactSceneKey() != null)
		{
			sceneLastUsed.merge(record.getImpactSceneKey(), record.getImpactTick(), Math::max);
		}
		impactIds.computeIfAbsent(record.getImpactTick(), key -> new ArrayList<>()).add(id);
	}

	/** Context may add independent original observations, but cannot change an observed callback value or clock. */
	private static void requireSameCallback(ObservedEvent original, DamageRecord context)
	{
		Map<String, Object> raw = original.getData();
		Object amount = raw == null ? null : raw.get("amount");
		Object type = raw == null ? null : raw.get("hitsplatType");
		if (!"HITSPLAT".equals(original.getType()) || original.getTick() != context.getImpactTick()
			|| original.getGameCycle() != null && context.getObservedCycle() != null
				&& !original.getGameCycle().equals(context.getObservedCycle())
			|| amount instanceof Number && Double.compare(((Number) amount).doubleValue(), context.getAmount()) != 0
			|| type instanceof Number && Boolean.TRUE.equals(context.getHitsplatTypeKnown())
				&& Double.compare(((Number) type).doubleValue(), context.getHitsplatType()) != 0)
		{
			throw new IllegalArgumentException("Impact context changes its original callback");
		}
	}

	private List<DamageRecord> impacts(int first, int last)
	{
		List<DamageRecord> selected = new ArrayList<>();
		for (List<Long> ids : impactIds.subMap(first, true, last, true).values())
		{
			for (Long id : ids)
			{
				selected.add(events.get(id).impact);
			}
		}
		return selected;
	}

	/** Queue work retains this same immutable receipt, not another set of callback primitives. */
	public synchronized ObservedEvent originalObservation(long sequence)
	{
		CapturedEvent event = events.get(sequence);
		return event == null ? null : event.observation;
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
		long id = observation.getSequence() > 0 ? observation.getSequence() : --anonymousContextId;
		CapturedEvent event = events.computeIfAbsent(id, key -> new CapturedEvent());
		if (event.observation != null)
		{
			throw new IllegalArgumentException("An original callback ID can be captured only once");
		}
		if (event.impact != null)
		{
			requireSameCallback(observation, event.impact);
		}
		event.observation = observation;
		if (observation.getSceneKey() != null)
		{
			sceneLastUsed.merge(observation.getSceneKey(), observation.getTick(), Math::max);
		}
		while (observationIds.size() >= MAX_OBSERVATIONS)
		{
			Long omittedId = observationIds.removeFirst();
			CapturedEvent omitted = events.get(omittedId);
			ObservedEvent raw = omitted.observation;
			omittedObservations.merge(raw.getTick(), 1L, Long::sum);
			// An impact context remains an original fact when its raw callback payload is truncated.
			omitted.observation = null;
			if (omitted.impact == null)
			{
				events.remove(omittedId);
			}
		}
		while (omittedObservations.size() > RecordingLimits.MAX_TICKS)
		{
			omittedObservations.pollFirstEntry();
		}
		observationIds.addLast(id);
	}

	/** Materialize wire aliases at the frozen boundary; raw callbacks and contexts stay unchanged. */
	private List<ObservedEvent> observations(int first, int last)
	{
		List<ObservedEvent> selected = new ArrayList<>();
		for (CapturedEvent event : events.values())
		{
			ObservedEvent original = event.observation;
			if (original == null || original.getTick() < first || original.getTick() > last)
			{
				continue;
			}
			DamageRecord context = event.impact;
			if (context != null && "RESOURCE_IMPACT".equals(context.getDirection()))
			{
				Map<String, Object> data = new LinkedHashMap<>(
					original.getData() == null ? Collections.emptyMap() : original.getData());
				data.put("attributionContext",
					context.toBuilder().attributionCandidates(null).observedHazardContacts(null).build());
				data.put("attributionCandidates", context.getAttributionCandidates());
				data.put("observedHazardContacts", context.getObservedHazardContacts());
				data.put("attributionCaptureStatus", "RETAINED_IMPACT_CONTEXT");
				data.put("resourceOutcomeStatus", "UNRESOLVED");
				original = ObservedEvent.builder().tick(original.getTick()).gameCycle(original.getGameCycle())
					.sequence(original.getSequence()).type(original.getType()).actorRef(original.getActorRef())
					.actorName(original.getActorName()).relatedActorRef(original.getRelatedActorRef())
					.sceneKey(original.getSceneKey()).data(Collections.unmodifiableMap(data)).build();
			}
			selected.add(original);
		}
		return selected;
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
		for (ObservedEvent event : observations(tick, tick))
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
		return getTickCount() == 0 && worldSnapshots().isEmpty();
	}

	public synchronized void clear()
	{
		super.clear();
		scenes.clear();
		sceneLastUsed.clear();
		events.clear();
		impactIds.clear();
		anonymousContextId = 0;
		effectEvents.clear();
		playerActions.clear();
		observationIds.clear();
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
		for (WorldSnapshot tick : worldSnapshots())
		{
			if (tick.getTick() >= fromTick && tick.getTick() <= throughTick)
			{
				ticks.add(tick);
			}
		}
		int minTick = fromTick == Integer.MIN_VALUE ? firstRetainedTick() : fromTick;
		int maxTick = throughTick == Integer.MAX_VALUE ? lastRetainedTick() : throughTick;

		// Raw callbacks can reference scenes even when optional world snapshots are disabled.
		List<SceneData> usedScenes = new ArrayList<>(scenes.values());

		DeathRecordingV2.Events events = DeathRecordingV2.Events.builder().damageEvents(windowDamage(minTick, maxTick))
			.effectEvents(window(effectEvents, EffectEvent::getStartTick, minTick, maxTick))
			.playerActions(window(playerActions, DeathRecordingV2.Events.PlayerActionRecord::getTick, minTick, maxTick))
			.deaths(window(deaths, DeathRecordingV2.Events.DeathMark::getTick, minTick, maxTick))
			.observations(observations(minTick, maxTick))
			.observationLinks(window(observationLinks, ObservedEventLink::getTick, minTick, maxTick))
			.captureGaps(windowCaptureGaps(minTick, maxTick)).build();

		List<TickData> states = window(rawTickHistory(), TickData::getTickNumber, minTick, maxTick);
		return DeathRecordingV2.builder().schemaVersion(DeathRecordingV2.LATEST_SCHEMA_VERSION).meta(meta)
			.scenes(usedScenes).ticks(ticks).events(events).journalTicks(states)
			.summary(
				summary == null ? null
					: summary.toBuilder().ticksCaptured(frameCount(minTick, maxTick))
						.otherPlayersPresent(nativeFrames(ticks).stream()
							.anyMatch(t -> t.getPlayers() != null && !t.getPlayers().isEmpty())
								? Boolean.TRUE
								: meta != null && meta.getCaptureSettings() != null
									&& meta.getCaptureSettings().isOtherPlayers() ? Boolean.FALSE : null)
						.build())
			.build();
	}

	/** Select Pause/Save once: display and completion retain the same original membership and cutoff. */
	public synchronized TickRecording.CapturedWindow captureLiveWindow(DeathRecordingV2.Meta meta)
	{
		List<TickData> states = rawTickHistory();
		if (states.isEmpty())
		{
			return new TickRecording.CapturedWindow(Collections.emptyList(), () -> null);
		}
		int first = states.get(0).getTickNumber();
		int last = states.get(states.size() - 1).getTickNumber();
		DeathRecordingV2.Meta selectedMeta = meta.toBuilder().snapshotTick(last).bufferTicks(states.size()).build();
		DeathRecordingV2 selected = freezeExport(selectedMeta, DeathRecordingV2.Summary.builder().build(), first, last);
		List<TickData> display = RecordingProjection.journal(selected.getJournalTicks(), selected.getEvents());
		DeathRecordingV2 frozen = selected.toBuilder().summary(selected.getSummary().toBuilder()
			.totalDamageTakenInBuffer(TickRecording.calculateTotalDamage(display)).build()).build();
		return new TickRecording.CapturedWindow(display, () -> completeExport(frozen));
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
			if (resolved.getMeta() != null && resolved.getMeta().getDeathTick() != null)
			{
				resolved = RecordingEncounterLabels.prepare(resolved, DamageMap.load(gson));
			}
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
				.journalTicks(RecordingProjection.journal(frozen.getJournalTicks(), frozen.getEvents()))
				.meta((meta == null ? DeathRecordingV2.Meta.builder() : meta.toBuilder()).captureIssues(issues).build())
				.build();
		}
	}

	private List<DamageRecord> windowDamage(int min, int max)
	{
		List<DamageRecord> selected = impacts(min, max);
		selected.removeIf(record -> "RESOURCE_IMPACT".equals(record.getDirection()));
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

	@Override
	protected List<TickData> journalViews(List<TickData> states)
	{
		return RecordingProjection.journal(states,
			DeathRecordingV2.Events.builder().damageEvents(windowDamage(Integer.MIN_VALUE, Integer.MAX_VALUE))
				.effectEvents(effectEvents).playerActions(playerActions)
				.observations(observations(Integer.MIN_VALUE, Integer.MAX_VALUE)).build());
	}

	public synchronized int size()
	{
		return worldSnapshots().size();
	}

	/** Only positive damage received by the local player can be the killing blow. */
	public synchronized List<DamageRecord> positiveReceivedAtTick(int tick)
	{
		List<DamageRecord> out = new ArrayList<>();
		for (DamageRecord record : impacts(tick, tick))
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
		for (DamageRecord record : impacts(tick, tick))
		{
			if (record.getImpactTick() == tick
				&& (record.getDirection() == null || "RECEIVED".equals(record.getDirection())))
			{
				out.add(record);
			}
		}
		return Collections.unmodifiableList(out);
	}

	public synchronized DamageRecord lastDamagingHit()
	{
		List<DamageRecord> selected = impacts(Integer.MIN_VALUE, Integer.MAX_VALUE);
		for (int i = selected.size() - 1; i >= 0; i--)
		{
			DamageRecord record = selected.get(i);
			if (record.getAmount() > 0 && (record.getDirection() == null || "RECEIVED".equals(record.getDirection())))
			{
				return record;
			}
		}
		return null;
	}
}
