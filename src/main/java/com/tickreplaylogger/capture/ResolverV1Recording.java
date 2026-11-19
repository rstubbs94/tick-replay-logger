/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.tickreplaylogger.capture;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tickreplaylogger.model.DamageEvent;
import com.tickreplaylogger.model.TickData;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.DeathRecordingV2;
import com.tickreplaylogger.model.world.HazardState;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.ObservedEventLink;
import com.tickreplaylogger.model.world.ProjectileSourceHistory;
import com.tickreplaylogger.model.world.ProjectileSourceCandidate;
import com.tickreplaylogger.model.world.ProjectileState;
import com.tickreplaylogger.model.world.SourceResolution;
import com.tickreplaylogger.model.world.SceneData;
import com.tickreplaylogger.model.world.WorldSnapshot;
import com.tickreplaylogger.util.SourceResolutionLabels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import lombok.Getter;
import static com.tickreplaylogger.capture.ResolverV1.*;
import net.runelite.api.HitsplatID;

/** Builds normalized associations from a detached recorded window, with no client API access. */
public final class ResolverV1Recording
{
	/**
	 * Membership snapshot of already detached capture DTOs. Construction performs no JSON or client work.
	 * DTO/map contents must be frozen by the capture owner before handing the window to a worker.
	 */
	@Getter
	public static final class EvidenceWindow
	{
		private final List<WorldSnapshot> ticks;
		private final List<SceneData> scenes;
		private final List<ObservedEvent> observations;
		private final List<DamageRecord> hits;
		private final List<DeathRecordingV2.Events.CaptureGap> captureGaps;
		private final List<ObservedEventLink> observationLinks;
		private final int firstTick, lastTick;
		private final long cutoffSequence, evidenceRevision;

		public EvidenceWindow(List<WorldSnapshot> ticks, List<SceneData> scenes, List<ObservedEvent> observations,
			List<DamageRecord> hits, List<DeathRecordingV2.Events.CaptureGap> captureGaps, int firstTick, int lastTick,
			long cutoffSequence, long evidenceRevision)
		{
			this(ticks, scenes, observations, hits, captureGaps, null, firstTick, lastTick, cutoffSequence,
				evidenceRevision);
		}

		public EvidenceWindow(List<WorldSnapshot> ticks, List<SceneData> scenes, List<ObservedEvent> observations,
			List<DamageRecord> hits, List<DeathRecordingV2.Events.CaptureGap> captureGaps,
			List<ObservedEventLink> observationLinks, int firstTick, int lastTick, long cutoffSequence,
			long evidenceRevision)
		{
			if (firstTick > lastTick || cutoffSequence < 0)
			{
				throw new IllegalArgumentException("Invalid evidence window boundary");
			}
			this.firstTick = firstTick;
			this.lastTick = lastTick;
			this.cutoffSequence = cutoffSequence;
			this.evidenceRevision = evidenceRevision;
			this.ticks = selected(ticks, tick -> tick != null && containsTick(tick.getTick()));
			this.scenes = selected(scenes, scene -> scene != null);
			this.observations = observations == null ? null
				: selected(observations,
					event -> event != null && containsTick(event.getTick()) && event.getSequence() <= cutoffSequence);
			this.hits = selected(hits, this::contains);
			this.captureGaps = selected(captureGaps, gap -> gap != null && containsTick(gap.getTick()));
			this.observationLinks = selected(observationLinks, link -> link != null && containsTick(link.getTick())
				&& link.getResolvedTick() <= lastTick && link.getObservationSequence() <= cutoffSequence);
		}

		private boolean containsTick(int tick)
		{
			return tick >= firstTick && tick <= lastTick;
		}

		public boolean contains(DamageRecord hit)
		{
			return hit != null && containsTick(hit.getImpactTick())
				&& (hit.getObservationSequence() == null || hit.getObservationSequence() <= cutoffSequence);
		}

		private static <T> List<T> selected(List<T> values, java.util.function.Predicate<T> keep)
		{
			List<T> result = new ArrayList<>();
			if (values != null)
			{
				for (T value : values)
				{
					if (keep.test(value))
					{
						result.add(value);
					}
				}
			}
			return Collections.unmodifiableList(result);
		}

		private DeathRecordingV2 recording()
		{
			return DeathRecordingV2
				.builder().ticks(ticks).scenes(scenes).events(DeathRecordingV2.Events.builder().damageEvents(hits)
					.observations(observations).captureGaps(captureGaps).observationLinks(observationLinks).build())
				.build();
		}

		private static EvidenceWindow from(DeathRecordingV2 recording)
		{
			DeathRecordingV2.Events events = recording.getEvents();
			return new EvidenceWindow(recording.getTicks(), recording.getScenes(),
				events == null ? null : events.getObservations(), events == null ? null : events.getDamageEvents(),
				events == null ? null : events.getCaptureGaps(), events == null ? null : events.getObservationLinks(),
				Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, 0);
		}

	}

	/** Offline/worker entry. Does not compact or serialize its input, mutate it, or read the client. */
	public static DeathRecordingV2 resolve(DeathRecordingV2 recording, Gson gson)
	{
		if (recording == null)
		{
			return null;
		}
		if (recording.getEvents() == null)
		{
			return recording;
		}
		return new ResolverV1Recording(EvidenceWindow.from(recording), gson).enrich(recording);
	}

	private final ResolverV1 engine;
	private final Gson gson;
	private final Map<Integer, List<ObservedEvent>> events = new HashMap<>();
	private final Map<Integer, List<ObservedEvent>> lifecycleEvents = new HashMap<>();
	private final Map<Integer, List<ObservedEvent>> interactionEvents = new HashMap<>();
	private final Map<String, List<ObservedEvent>> actorLifetimes = new HashMap<>();
	private final Map<String, List<ObservedEvent>> actorPresence = new HashMap<>();
	private final Map<Long, ObservedEvent> original = new HashMap<>();
	private final Map<Long, List<ObservedEventLink>> recipientLinks = new HashMap<>();
	private final Map<Integer, List<WorldSnapshot>> frames = new HashMap<>();
	private final Map<String, SceneData> scenes = new HashMap<>();
	private final Map<Integer, List<DamageRecord>> outgoing = new HashMap<>();
	private final List<DamageRecord> incoming = new ArrayList<>();
	private final Map<DamageRecord, ResolutionResult> interpreted = new java.util.IdentityHashMap<>();
	private boolean batchResolved;
	private JsonObject windowMetadata;
	private final Map<Long, ProjectileTrack> projectiles = new LinkedHashMap<>();
	private final java.util.NavigableMap<Integer, Set<Long>> projectileEnds = new java.util.TreeMap<>();
	private final Set<Long> untimedProjectiles = new LinkedHashSet<>();
	private final List<RecordedProjectile> untrackedProjectiles = new ArrayList<>();
	private final Map<Long, Map<String, Object>> projectileLaunches = new HashMap<>();
	private final ObservedHpCallbackContext contexts;
	private final boolean complete;
	private final boolean eventsKnown;
	private final int observationCount;
	private EvidenceWindow window;
	private List<DeathRecordingV2.Events.CaptureGap> captureGaps;
	private static final int MAX_TICK_EVENTS = 2048;

	/** One original callback or native frame; decoding stays lazy and never reads the client. */
	private static final class RecordedProjectile
	{
		final Object payload;
		final ObservedEvent event;
		final WorldSnapshot frame;
		final Long instance;
		final Integer endCycle;
		ProjectileState decoded;

		RecordedProjectile(Object payload, ObservedEvent event, WorldSnapshot frame)
		{
			this.payload = payload;
			this.event = event;
			this.frame = frame;
			decoded = payload instanceof ProjectileState ? (ProjectileState) payload : null;
			Map<?, ?> fields = payload instanceof Map ? (Map<?, ?>) payload : Collections.emptyMap();
			instance = decoded == null ? longNumber(fields.get("observationId")) : decoded.getObservationId();
			endCycle = decoded == null ? intNumber(fields.get("endCycle")) : decoded.getEndCycle();
		}

		ProjectileState decode(Gson gson)
		{
			if (decoded == null)
			{
				decoded = gson.fromJson(gson.toJsonTree(payload), ProjectileState.class);
			}
			return decoded;
		}
	}

	private final class ProjectileTrack
	{
		final java.util.NavigableMap<Long, RecordedProjectile> callbacks = new java.util.TreeMap<>();
		final java.util.NavigableMap<Integer, List<RecordedProjectile>> snapshots = new java.util.TreeMap<>();

		RecordedProjectile before(DamageRecord hit, boolean callback)
		{
			if (callback)
			{
				Map.Entry<Long, RecordedProjectile> prior = hit.getObservationSequence() == null ? null
					: callbacks.lowerEntry(hit.getObservationSequence());
				return prior != null && beforeImpact(prior.getValue(), hit) ? prior.getValue() : null;
			}
			for (Map.Entry<Integer, List<RecordedProjectile>> entry = snapshots
				.lowerEntry(hit.getObservedCycle()); entry != null; entry = snapshots.lowerEntry(entry.getKey()))
			{
				RecordedProjectile selected = null;
				for (RecordedProjectile sample : entry.getValue())
				{
					if (beforeImpact(sample, hit))
					{
						if (selected != null)
						{
							return null;
						} // Equal-clock native frames cannot choose each other.
						selected = sample;
					}
				}
				if (selected != null)
				{
					return selected;
				}
			}
			return null;
		}
	}

	public ResolverV1Recording(ResolverV1 engine, Gson gson, DeathRecordingV2 recording)
	{
		this.engine = engine;
		this.gson = gson;
		if (recording.getScenes() != null)
		{
			for (SceneData scene : recording.getScenes())
			{
				if (scene != null && scene.getSceneKey() != null)
				{
					scenes.put(scene.getSceneKey(), scene);
				}
			}
		}
		List<ObservedEvent> observations = recording.getEvents() == null ? null
			: recording.getEvents().getObservations();
		captureGaps = recording.getEvents() == null ? null : recording.getEvents().getCaptureGaps();
		if (recording.getEvents() != null && recording.getEvents().getObservationLinks() != null)
		{
			for (ObservedEventLink link : recording.getEvents().getObservationLinks())
			{
				if (link != null)
				{
					recipientLinks.computeIfAbsent(link.getObservationSequence(), key -> new ArrayList<>()).add(link);
				}
			}
		}
		contexts = new ObservedHpCallbackContext(observations);
		eventsKnown = observations != null;
		observationCount = observations == null ? 0 : observations.size();
		complete = observations != null && observations.size() <= WorldSnapshotRecorder.MAX_OBSERVATIONS
			&& (recording.getEvents().getCaptureGaps() == null || recording.getEvents().getCaptureGaps().isEmpty());
		Set<Long> seen = new HashSet<>();
		if (observations != null)
		{
			for (ObservedEvent event : observations)
			{
				if (event == null || event.getSequence() <= 0)
				{
					continue;
				}
				if (!seen.add(event.getSequence()))
				{
					original.remove(event.getSequence());
					continue;
				}
				original.put(event.getSequence(), event);
				if (event.getType() != null && event.getType().startsWith("PROJECTILE_") && event.getData() != null)
				{
					indexProjectile(event.getData().get("projectile"), event, null);
				}
				List<ObservedEvent> list = events.computeIfAbsent(event.getTick(), k -> new ArrayList<>());
				if (list.size() < MAX_TICK_EVENTS)
				{
					list.add(event);
				}
				if ("MENU_OPTION".equals(event.getType()))
				{
					interactionEvents.computeIfAbsent(event.getTick(), k -> new ArrayList<>()).add(event);
				}
				if (Arrays.asList("ACTOR_DEATH", "NPC_DESPAWNED", "GRAPHICS_OBJECT_CREATED").contains(event.getType()))
				{
					List<ObservedEvent> lifecycle = lifecycleEvents.computeIfAbsent(event.getTick(),
						k -> new ArrayList<>());
					if (lifecycle.size() < MAX_TICK_EVENTS)
					{
						lifecycle.add(event);
					}
				}
				if (event.getActorRef() != null
					&& Arrays.asList("NPC_SPAWNED", "NPC_DESPAWNED", "ACTOR_DEATH").contains(event.getType()))
				{
					actorLifetimes.computeIfAbsent(event.getActorRef(), k -> new ArrayList<>()).add(event);
				}
				if (event.getActorRef() != null
					&& Arrays.asList("NPC_SPAWNED", "NPC_DESPAWNED", "ACTOR_DEATH", "NPC_CHANGED", "ANIMATION_CHANGED",
						"OVERHEAD_TEXT_CHANGED", "ACTOR_GRAPHICS_CHANGED", "HITSPLAT").contains(event.getType()))
				{
					actorPresence.computeIfAbsent(event.getActorRef(), k -> new ArrayList<>()).add(event);
				}
			}
		}
		collect(recording.getTicks());
		if (recording.getEvents() != null && recording.getEvents().getDamageEvents() != null)
		{
			for (DamageRecord hit : recording.getEvents().getDamageEvents())
			{
				if (hit != null && ("DEALT".equals(hit.getDirection())
					|| "NPC_RECEIVED".equals(hit.getDirection()) && "LOCAL_PLAYER".equals(hit.getOwnership())))
				{
					outgoing.computeIfAbsent(hit.getImpactTick(), k -> new ArrayList<>()).add(hit);
				}
				if (hit != null && "player".equals(hit.getDefenderRef())
					&& !Arrays.asList("DEALT", "NPC_RECEIVED").contains(hit.getDirection()))
				{
					incoming.add(hit);
				}
			}
		}
		if (observations != null)
		{
			for (ObservedEvent event : observations)
			{
				DamageRecord context = resourceContext(event);
				if (context != null && incoming.stream().noneMatch(hit -> hit.sameOriginalCallback(context)))
				{
					incoming.add(context);
				}
			}
		}
	}

	public ResolverV1Recording(EvidenceWindow window, Gson gson)
	{
		this(ResolverV1.load(gson), gson, window.recording());
		this.window = window;
	}

	public ResolverV1Recording(EvidenceWindow window, Gson gson, DamageMap annotationMap)
	{
		this(window, gson);
		// The canonical interpreter already owns the indexed reference catalog.
	}

	/** Same worker-side adapter/evaluator/projection used by recording completion. */
	public DamageRecord resolveHit(DamageRecord hit)
	{
		if (hit == null || !engine.isAvailable() || window != null && !window.contains(hit)
			|| !"player".equals(hit.getDefenderRef()) || "DEALT".equals(hit.getDirection())
			|| "NPC_RECEIVED".equals(hit.getDirection()))
		{
			return hit;
		}
		ResolutionResult result = interpretation(hit);
		SourceResolution before = hit.getSourceResolution();
		if (before != null && before.getMethod() != null && before.getMethod().startsWith("RECORDED_RESOLVER_V1_"))
		{
			before = null;
		}
		// Legacy verdicts remain a best-effort fallback only when no current cue was admitted.
		if (before != null && result.candidates.isEmpty())
		{
			return hit;
		}
		SourceResolution resolution = projectResult(result);
		return projectAnnotations(hit, resolution, result);
	}

	public String getRulesId()
	{
		return engine.id();
	}

	private ResolutionResult interpretation(DamageRecord requested)
	{
		if (!batchResolved)
		{
			List<JsonObject> inputs = new ArrayList<>();
			for (DamageRecord hit : incoming)
			{
				inputs.add(normalize(hit));
			}
			List<ResolutionResult> results = engine.evaluateBatch(inputs);
			for (int i = 0; i < incoming.size(); i++)
			{
				interpreted.put(incoming.get(i), results.get(i));
			}
			batchResolved = true;
		}
		ResolutionResult result = interpreted.get(requested);
		if (result != null)
		{
			return result;
		}
		for (DamageRecord original : incoming)
		{
			if (sameCaptureFacts(original, requested))
			{
				return interpreted.get(original);
			}
		}
		return engine.evaluate(normalize(requested));
	}

	private static boolean sameCaptureFacts(DamageRecord first, DamageRecord second)
	{
		return first.sameNativeCallback(second) && first.getImpactX() == second.getImpactX()
			&& first.getImpactY() == second.getImpactY()
			&& Objects.equals(first.getImpactPositionKnown(), second.getImpactPositionKnown())
			&& Objects.equals(first.getImpactPrayerStateKnown(), second.getImpactPrayerStateKnown())
			&& Objects.equals(first.getPrayersAtImpact(), second.getPrayersAtImpact())
			&& Objects.equals(first.getDefenderPrayerStateAtImpact(), second.getDefenderPrayerStateAtImpact())
			&& Objects.equals(first.getDefenderGraphicsAtImpact(), second.getDefenderGraphicsAtImpact())
			&& Objects.equals(first.getDefenderIdentityEvidence(), second.getDefenderIdentityEvidence())
			&& Objects.equals(first.getAttributionCandidates(), second.getAttributionCandidates())
			&& Objects.equals(first.getObservedHazardContacts(), second.getObservedHazardContacts())
			&& Objects.equals(first.getCaptureIssues(), second.getCaptureIssues());
	}

	/** Derived display fields share the resolver's admitted sources; primitive callback facts are untouched. */
	private DamageRecord projectAnnotations(DamageRecord hit, SourceResolution resolution, ResolutionResult result)
	{
		DamageRecord defaults = DamageRecord.builder().attackerName("Unknown").attribution("UNKNOWN")
			.attributionEvidence("UNRESOLVED_OBSERVATIONS").damageType("unknown").damageTypeEvidence("UNKNOWN")
			.determination("UNKNOWN").launchTick(hit.getImpactTick()).launchTickEstimated(true)
			.launchStateEvidence("UNAVAILABLE").launchPositionKnown(false).launchPrayerStateKnown(false)
			.damageTypeCandidates(hit.getDamageTypeCandidates() == null ? null : Collections.emptyList()).build();
		DamageRecord.DamageRecordBuilder projected = applyAnnotations(hit, defaults).toBuilder()
			.sourceResolution(resolution);
		JsonObject observedAnnotations = object(result.hitAnnotations);
		String observedPrayer = text(observedAnnotations, "prayerEffect"),
			observedType = text(observedAnnotations, "damageType");
		if (observedPrayer != null)
		{
			projected.prayerEffect(observedPrayer)
				.damagePropertyEvidence(text(observedAnnotations, "damagePropertyEvidence"));
		}
		if (text(observedAnnotations, "damageReferenceKey") != null)
		{
			projected.damageReferenceKey(text(observedAnnotations, "damageReferenceKey"));
		}
		if (observedType != null)
		{
			projected.damageType(observedType).damageTypeEvidence(text(observedAnnotations, "damageTypeEvidence"))
				.attackerName(text(observedAnnotations, "attackerName")).attribution("ENVIRONMENTAL")
				.attributionEvidence("STATUS_HITSPLAT").determination("UNKNOWN");
		}
		if (resolution == null || resolution.getCandidates() == null || resolution.getCandidates().isEmpty())
		{
			return projected.build();
		}
		List<ResolutionCandidate> admitted = new ArrayList<>();
		for (ResolutionCandidate row : result.candidates)
		{
			if ("POSSIBLE".equals(row.status) && resolution.getCandidates().stream()
				.anyMatch(candidate -> projectedSource(candidate, row.source, row.key)))
			{
				admitted.add(row);
			}
		}
		if (admitted.isEmpty())
		{
			return projected.build();
		}
		Set<String> qualifiedCohorts = new HashSet<>();
		for (ResolutionCandidate row : admitted)
		{
			if (Boolean.TRUE.equals(row.componentQualified))
			{
				qualifiedCohorts.add(annotationCohort(row));
			}
		}
		admitted.removeIf(
			row -> !Boolean.TRUE.equals(row.componentQualified) && qualifiedCohorts.contains(annotationCohort(row)));
		List<String> types = new ArrayList<>(), timings = new ArrayList<>(), attacks = new ArrayList<>(),
			timingKeys = new ArrayList<>(), typeKeys = new ArrayList<>(), catalogIds = new ArrayList<>();
		List<String> typeEvidence = new ArrayList<>();
		Set<String> hints = new java.util.LinkedHashSet<>();
		Set<Integer> launches = new java.util.LinkedHashSet<>();
		List<JsonObject> originalLaunches = new ArrayList<>();
		boolean everyOriginalLaunch = true;
		boolean everyLaunch = true, everyPlayer = true;
		boolean everySourcePrayerIneffective = true;
		Set<Integer> attackerLevels = new java.util.LinkedHashSet<>();
		boolean everyAttackerLevel = true;
		String confidence = null, referenceMap = null;
		List<String> scalarEvidence = new ArrayList<>();
		for (ResolutionCandidate row : admitted)
		{
			JsonObject source = object(row.source), cue = object(row.cue);
			JsonObject annotations = object(row.derivedAnnotations);
			Integer level = integer(annotations.get("attackerCombatLevel"));
			String kind = text(source, "kind"), type = text(annotations, "damageType"),
				timing = text(annotations, "determination"), attack = text(annotations, "attackStyleName"),
				timingKey = text(annotations, "determinationRuleKey"), typeKey = text(annotations, "damageTypeRuleKey"),
				mappedRef = text(annotations, "mapRef");
			if (level == null)
			{
				everyAttackerLevel = false;
			}
			else
			{
				attackerLevels.add(level);
			}
			everySourcePrayerIneffective &= "NONE_REVIEWED".equals(text(annotations, "prayerEffect"));
			hints.addAll(strings(annotations.get("candidateStyles")));
			catalogIds.add(text(annotations, "damageTypeCatalogId"));
			typeEvidence.add(text(annotations, "damageTypeEvidence"));
			if (type != null && type.contains("|"))
			{
				type = null;
			}
			types.add(type);
			timings.add("UNKNOWN".equals(timing) ? null : timing);
			attacks.add(attack);
			timingKeys.add(timingKey);
			typeKeys.add(typeKey);
			if (mappedRef != null)
			{
				referenceMap = referenceMap == null || referenceMap.equals(mappedRef) ? mappedRef : "";
			}
			Integer launch = integer(cue.get("launchTick"));
			if (launch == null)
			{
				everyLaunch = false;
			}
			else
			{
				launches.add(launch);
			}
			JsonObject originalLaunch = object(cue.get("launchObservation"));
			if (originalLaunch.size() == 0)
			{
				everyOriginalLaunch = false;
			}
			else
			{
				originalLaunches.add(originalLaunch);
			}
			everyPlayer &= "player".equals(kind);
			String relation = Boolean.TRUE.equals(row.scalarEligible) && row.confidence != null ? row.confidence
				: "UNKNOWN";
			scalarEvidence.add(row.attributionEvidence);
			confidence = confidence == null || confidence.equals(relation) ? relation : "UNKNOWN";
		}
		String label = SourceResolutionLabels.label(resolution);
		SourceResolution.Candidate single = resolution.getCandidates().size() == 1 ? resolution.getCandidates().get(0)
			: null;
		boolean shared = "AMBIGUOUS".equals(result.scalarAssociation);
		boolean scalarEstablished = single != null && !shared && confidence != null && !"UNKNOWN".equals(confidence);
		String observedFamily = family(hit, observedType(hit)), type = consensus(types), timing = consensus(timings),
			attack = consensus(attacks);
		boolean typed = Arrays.asList("poison", "venom", "burn", "bleed").contains(observedFamily);
		if (!typed)
		{
			if (scalarEstablished && label != null)
			{
				projected.attackerName(label);
			}
			else
			{
				projected.attackerName("Unknown");
			}
			if (single != null)
			{
				projected.attackerRef(single.getActorRef()).attackerNpcId(single.getNpcId());
			}
			if (scalarEstablished)
			{
				projected.attribution(confidence).attributionEvidence(consensus(scalarEvidence));
			}
			else if (shared || resolution.getCandidates().size() > 1)
			{
				projected.attribution("UNKNOWN").attributionEvidence(
					shared ? "SHARED_OBSERVATION_ASSIGNMENT_UNESTABLISHED" : "MULTIPLE_POSSIBLE_DAMAGE_SOURCES");
			}
			if (everyPlayer && scalarEstablished)
			{
				projected.pvp(true);
			}
		}
		if (Arrays.asList("poison", "venom", "burn", "bleed").contains(observedFamily))
		{
			projected.damageType(observedFamily).damageTypeEvidence("HITSPLAT_TYPE");
		}
		else if (type != null)
		{
			projected.damageType(type.toLowerCase(java.util.Locale.ROOT))
				.damageTypeEvidence(consensus(typeEvidence) == null ? "SOURCE_REFERENCE" : consensus(typeEvidence));
		}
		if (type != null && "typeless".equalsIgnoreCase(type))
		{
			timing = null;
		}
		if (scalarEstablished && timing != null && !typed)
		{
			projected.determination(timing).determinationEvidence("REVIEWED_ATTACK_CONTEXT")
				.determinationRuleKey(consensus(timingKeys));
		}
		if (attack != null && !typed)
		{
			projected.attackStyleName(attack);
		}
		if (consensus(typeKeys) != null)
		{
			projected.damageTypeRuleKey(consensus(typeKeys)).damageTypeCatalogId(consensus(catalogIds));
		}
		if (referenceMap != null && !referenceMap.isEmpty())
		{
			projected.mapRef(referenceMap);
		}
		if (!hints.isEmpty())
		{
			projected.damageTypeCandidates(new ArrayList<>(hints));
		}
		if (everyAttackerLevel && attackerLevels.size() == 1)
		{
			projected.attackerCombatLevel(attackerLevels.iterator().next());
		}
		if (everySourcePrayerIneffective && observedPrayer == null)
		{
			projected.prayerEffect("NONE_REVIEWED").damagePropertyEvidence("SOURCE_REFERENCE");
		}
		if (scalarEstablished && everyLaunch && launches.size() == 1)
		{
			int launch = launches.iterator().next();
			projectLaunch(projected, hit, launch);
			if (everyOriginalLaunch)
			{
				projectOriginalLaunch(projected, hit, launch, originalLaunches);
			}
		}
		return projected.build();
	}

	private static String annotationCohort(ResolutionCandidate row)
	{
		JsonObject cue = object(row.cue);
		return (text(cue, "cohortKey") == null ? text(cue, "key") : text(cue, "cohortKey")) + "|" + row.sourceKey;
	}

	private static String consensus(List<String> values)
	{
		if (values.isEmpty() || values.get(0) == null)
		{
			return null;
		}
		for (String value : values)
		{
			if (!values.get(0).equals(value))
			{
				return null;
			}
		}
		return values.get(0);
	}

	private static boolean projectedSource(SourceResolution.Candidate candidate, JsonObject source, String rowKey)
	{
		JsonObject scope = object(source.get("scope"));
		return candidate.getActorRef() != null && candidate.getActorRef().equals(text(source, "actorRef"))
			&& (candidate.getNpcId() == null || candidate.getNpcId().equals(integer(source.get("id"))))
			&& Objects.equals(candidate.getSceneKey(), text(scope, "sceneKey"))
			&& Objects.equals(candidate.getWorldViewId(), integer(scope.get("worldViewId")))
			&& Objects.equals(candidate.getPlane(), integer(scope.get("plane")))
			|| candidate.getActorRef() == null && candidate.getFamilyKey() != null
				&& (candidate.getFamilyKey().equals(text(source, "familyKey"))
					|| candidate.getFamilyKey()
						.equals(text(source, "kind") + "-definition:" + integer(source.get("id")))
					|| candidate.getFamilyKey().equals("observed-hit-kind:" + text(source, "statusFamily")))
			|| candidate.getActorRef() == null && candidate.getFamilyKey() == null && rowKey != null
				&& ("hypothesis:" + rowKey).equals(candidate.getKey());
	}

	private void projectLaunch(DamageRecord.DamageRecordBuilder projected, DamageRecord hit, int launch)
	{
		if (launch < 0 || launch > hit.getImpactTick())
		{
			return;
		}
		projected.launchTick(launch).launchTickEstimated(true);
		if (launch == hit.getImpactTick() || finalizationChanged(hit))
		{
			return;
		}
		WorldSnapshot selected = null;
		for (WorldSnapshot frame : frames.getOrDefault(launch, Collections.emptyList()))
		{
			if (frame.getPlayer() != null && Objects.equals(frame.getSceneKey(), hit.getImpactSceneKey())
				&& Objects.equals(frame.getWorldViewId(), hit.getImpactWorldViewId())
				&& Objects.equals(frame.getPlane(), hit.getImpactPlane()))
			{
				if (selected != null)
				{
					return;
				}
				selected = frame;
			}
		}
		if (selected != null)
		{
			com.tickreplaylogger.model.world.PlayerState player = selected.getPlayer();
			projected.launchStateEvidence("SNAPSHOT").overheadAtLaunch(player.getOverheadPrayer())
				.prayersAtLaunch(player.getActivePrayers()).launchPrayerStateKnown(player.getPrayerStateKnown())
				.launchX(player.getWorldX()).launchY(player.getWorldY())
				.launchPositionKnown(player.getWorldPositionKnown());
		}
	}

	private static void projectOriginalLaunch(DamageRecord.DamageRecordBuilder projected, DamageRecord hit, int launch,
		List<JsonObject> samples)
	{
		if (samples.isEmpty())
		{
			return;
		}
		JsonObject first = samples.get(0), original = object(first.get("originalProjectileObservation"));
		Integer tick = integer(first.get("firstSeenTick")), cycle = integer(original.get("observedCycle"));
		if (tick == null || tick != launch || cycle == null || hit.getObservedCycle() == null
			|| cycle > hit.getObservedCycle())
		{
			return;
		}
		for (JsonObject sample : samples)
		{
			for (String field : Arrays.asList("firstSeenTick", "prayersAtLaunch", "overheadAtLaunch",
				"launchPrayerStateKnown", "launchPositionKnown", "launchX", "launchY", "launchPlane", "launchSceneKey",
				"launchWorldViewId"))
			{
				if (!Objects.equals(first.get(field), sample.get(field)))
				{
					return;
				}
			}
		}
		projected.launchStateEvidence("FIRST_PROJECTILE_OBSERVATION").launchTickEstimated(true)
			.prayersAtLaunch(first.has("prayersAtLaunch") && first.get("prayersAtLaunch").isJsonArray()
				? strings(first.get("prayersAtLaunch"))
				: null)
			.overheadAtLaunch(text(first, "overheadAtLaunch"))
			.launchPrayerStateKnown(bool(first.get("launchPrayerStateKnown"))).launchPositionKnown(false);
		if (Boolean.TRUE.equals(bool(first.get("launchPositionKnown")))
			&& Objects.equals(text(first, "launchSceneKey"), hit.getImpactSceneKey())
			&& Objects.equals(integer(first.get("launchWorldViewId")), hit.getImpactWorldViewId())
			&& Objects.equals(integer(first.get("launchPlane")), hit.getImpactPlane())
			&& integer(first.get("launchX")) != null && integer(first.get("launchY")) != null)
		{
			projected.launchX(integer(first.get("launchX"))).launchY(integer(first.get("launchY")))
				.launchPositionKnown(true);
		}
	}

	private void collect(List<WorldSnapshot> ticks)
	{
		if (ticks == null)
		{
			return;
		}
		for (WorldSnapshot frame : ticks)
		{
			if (frame != null)
			{
				frames.computeIfAbsent(frame.getTick(), k -> new ArrayList<>()).add(frame);
				indexFrameProjectiles(frame);
				if (frame.getNativeScene() != null)
				{
					scenes.put(frame.getSceneKey(), frame.getNativeScene());
				}
				if (frame.getAdditionalViews() != null)
				{
					for (WorldSnapshot other : frame.getAdditionalViews())
					{
						if (other != null)
						{
							frames.computeIfAbsent(other.getTick(), k -> new ArrayList<>()).add(other);
							indexFrameProjectiles(other);
							if (other.getNativeScene() != null)
							{
								scenes.put(other.getSceneKey(), other.getNativeScene());
							}
						}
					}
				}
			}
		}
	}

	private void indexFrameProjectiles(WorldSnapshot frame)
	{
		if (!Boolean.TRUE.equals(frame.getNativeContextKnown()) || !Boolean.TRUE.equals(frame.getGameCycleKnown())
			|| frame.getProjectiles() == null)
		{
			return;
		}
		int count = 0;
		for (ProjectileState projectile : frame.getProjectiles())
		{
			if (++count > MAX_TICK_EVENTS)
			{
				break;
			}
			if (projectile != null && Objects.equals(projectile.getWorldViewId(), frame.getWorldViewId())
				&& Boolean.TRUE.equals(projectile.getSamplePlaneKnown())
				&& Objects.equals(projectile.getPlane(), frame.getPlane()))
			{
				indexProjectile(projectile, null, frame);
			}
		}
	}

	private void indexProjectile(Object payload, ObservedEvent event, WorldSnapshot frame)
	{
		if (!(payload instanceof ProjectileState || payload instanceof Map))
		{
			return;
		}
		RecordedProjectile sample = new RecordedProjectile(payload, event, frame);
		if (sample.instance == null)
		{
			untrackedProjectiles.add(sample);
			return;
		}
		ProjectileTrack track = projectiles.computeIfAbsent(sample.instance, key -> new ProjectileTrack());
		if (event != null)
		{
			track.callbacks.put(event.getSequence(), sample);
			if (event.getData().get("originalProjectileObservation") != null)
			{
				projectileLaunches.putIfAbsent(sample.instance, event.getData());
			}
		}
		else
		{
			track.snapshots.computeIfAbsent(frame.getGameCycle(), key -> new ArrayList<>()).add(sample);
		}
		if (sample.endCycle == null)
		{
			untimedProjectiles.add(sample.instance);
		}
		else
		{
			projectileEnds.computeIfAbsent(sample.endCycle, key -> new LinkedHashSet<>()).add(sample.instance);
		}
	}

	private boolean beforeImpact(RecordedProjectile sample, DamageRecord hit)
	{
		Long epoch = sample.event != null && sample.event.getData() != null
			? longNumber(sample.event.getData().get("causalSceneEpoch"))
			: null;
		if (sample.event == null && hit.getImpactContextEpoch() != null
			&& sample.decode(gson).getSourceHistory() != null)
		{
			epoch = sample.decode(gson).getSourceHistory().getContextEpoch();
		}
		if (epoch != null && hit.getImpactContextEpoch() != null && !epoch.equals(hit.getImpactContextEpoch()))
		{
			return false;
		}
		if (sample.event == null)
		{
			return sample.frame.getTick() <= hit.getImpactTick()
				&& sample.frame.getGameCycle() < hit.getObservedCycle();
		}
		ObservedEvent event = sample.event;
		return original.get(event.getSequence()) == event && hit.getObservationSequence() != null
			&& event.getSequence() < hit.getObservationSequence() && event.getTick() <= hit.getImpactTick()
			&& (event.getGameCycle() == null || event.getGameCycle() <= hit.getObservedCycle());
	}

	private void recordedProjectileCues(DamageRecord hit, JsonArray cues, JsonObject normalizedHit)
	{
		if (hit.getObservedCycle() == null)
		{
			return;
		}
		int lower = (int) Math.max(Integer.MIN_VALUE, (long) hit.getObservedCycle() - 30);
		Set<Long> instances = new LinkedHashSet<>();
		for (Set<Long> group : projectileEnds.tailMap(lower, true).values())
		{
			instances.addAll(group);
		}
		instances.addAll(untimedProjectiles);
		int callbacks = 0, snapshots = 0;
		for (Long instance : instances)
		{
			if (cues.size() >= ResolverV1.MAX_CUES || callbacks >= 512 && snapshots >= 512)
			{
				break;
			}
			ProjectileTrack track = projectiles.get(instance);
			RecordedProjectile sample = callbacks < 512 ? track.before(hit, true) : null;
			if (sample != null && sample.endCycle != null && sample.endCycle < lower)
			{
				sample = null;
			}
			if (snapshots < 512)
			{
				RecordedProjectile frame = track.before(hit, false);
				if (frame != null && (frame.endCycle == null || frame.endCycle >= lower) && (sample == null
					|| sample.event.getGameCycle() == null || frame.frame.getGameCycle() > sample.event.getGameCycle()))
				{
					sample = frame;
				}
			}
			if (sample == null || sample.endCycle != null && sample.endCycle < lower)
			{
				continue;
			}
			if (sample.event == null)
			{
				snapshots++;
			}
			else
			{
				callbacks++;
			}
			emitProjectile(sample, hit, cues, normalizedHit);
		}
		for (RecordedProjectile sample : untrackedProjectiles)
		{
			if (cues.size() >= ResolverV1.MAX_CUES || callbacks >= 512 && snapshots >= 512)
			{
				break;
			}
			if ((sample.event == null ? snapshots >= 512 : callbacks >= 512) || !beforeImpact(sample, hit)
				|| sample.endCycle != null && sample.endCycle < lower)
			{
				continue;
			}
			if (sample.event == null)
			{
				snapshots++;
			}
			else
			{
				callbacks++;
			}
			emitProjectile(sample, hit, cues, normalizedHit);
		}
	}

	private void emitProjectile(RecordedProjectile sample, DamageRecord hit, JsonArray cues, JsonObject normalizedHit)
	{
		ObservedEvent event = sample.event;
		projectileCues(sample.decode(gson), hit, cues, event == null ? sample.frame.getSceneKey() : event.getSceneKey(),
			event == null ? sample.frame.getTick() : event.getTick(),
			event == null ? sample.frame.getGameCycle() : event.getGameCycle(),
			event == null ? null : event.getSequence(), normalizedHit);
	}

	private JsonObject observedHit(DamageRecord recorded)
	{
		JsonObject hit = new JsonObject();
		hit.addProperty("sequence", recorded.getObservationSequence());
		hit.addProperty("tick", recorded.getImpactTick());
		hit.addProperty("cycle", recorded.getObservedCycle());
		hit.addProperty("amount", recorded.getAmount());
		hit.addProperty("direction", recorded.getDirection());
		hit.addProperty("recipientRef", recorded.getDefenderRef());
		if (observedType(recorded) != null)
		{
			hit.addProperty("hitsplatType", observedType(recorded));
		}
		hit.add("scope", normalizedHitScope(recorded));
		if (recorded.getImpactContextEpoch() != null)
		{
			hit.addProperty("contextEpoch", recorded.getImpactContextEpoch());
		}
		if (recorded.getFinalizationContextEpoch() != null)
		{
			hit.addProperty("finalizationContextEpoch", recorded.getFinalizationContextEpoch());
		}
		return hit;
	}

	private static JsonObject frameMetadata(WorldSnapshot frame)
	{
		JsonObject sample = scope(frame.getSceneKey(), frame.getWorldViewId(), frame.getPlane());
		sample.addProperty("tick", frame.getTick());
		sample.addProperty("gameCycle", frame.getGameCycle());
		sample.addProperty("gameCycleKnown", frame.getGameCycleKnown());
		sample.addProperty("nativeContextKnown", frame.getNativeContextKnown());
		return sample;
	}

	private WorldSnapshot uniqueFrame(int tick, Predicate<WorldSnapshot> matches)
	{
		WorldSnapshot selected = null;
		for (WorldSnapshot frame : frames.getOrDefault(tick, Collections.emptyList()))
		{
			if (matches.test(frame))
			{
				if (selected != null)
				{
					return null;
				}
				selected = frame;
			}
		}
		return selected;
	}

	/** Window membership and frame summaries are immutable and shared by every impact in the batch. */
	private JsonObject windowMetadata()
	{
		if (windowMetadata != null)
		{
			return windowMetadata;
		}
		JsonObject metadata = new JsonObject();
		metadata.addProperty("complete", complete);
		metadata.addProperty("eventsKnown", eventsKnown);
		metadata.addProperty("eventsTruncated", observationCount > WorldSnapshotRecorder.MAX_OBSERVATIONS);
		JsonArray impacts = new JsonArray();
		for (DamageRecord recorded : incoming)
		{
			impacts.add(observedHit(recorded));
		}
		metadata.add("impactObservations", impacts);
		JsonArray lifecycles = new JsonArray();
		original.values().stream()
			.filter(event -> Arrays
				.asList("NPC_SPAWNED", "NPC_DESPAWNED", "OBJECT_SPAWNED", "OBJECT_DESPAWNED", "GRAPHICS_OBJECT_CREATED")
				.contains(event.getType()))
			.sorted(java.util.Comparator.comparingLong(ObservedEvent::getSequence))
			.forEach(event -> lifecycles.add(gson.toJsonTree(event)));
		metadata.add("sourceLifecycles", lifecycles);
		if (captureGaps != null)
		{
			metadata.add("captureGaps", gson.toJsonTree(captureGaps));
		}
		if (window != null && window.getFirstTick() != Integer.MIN_VALUE)
		{
			metadata.addProperty("windowLowerTick", window.getFirstTick());
			metadata.addProperty("windowUpperTick", window.getLastTick());
		}
		else if (!frames.isEmpty())
		{
			metadata.addProperty("windowLowerTick", Collections.min(frames.keySet()));
			metadata.addProperty("windowUpperTick", Collections.max(frames.keySet()));
		}
		return windowMetadata = metadata;
	}

	public JsonObject normalize(DamageRecord damage)
	{
		JsonObject input = new JsonObject(), hit = observedHit(damage);
		JsonArray cues = new JsonArray();
		input.add("hit", hit);
		input.add("cues", cues);
		for (Map.Entry<String, JsonElement> field : windowMetadata().entrySet())
		{
			input.add(field.getKey(), field.getValue());
		}
		JsonArray interactions = new JsonArray();
		original.values().stream()
			.filter(event -> "MENU_OPTION".equals(event.getType()) && event.getTick() <= damage.getImpactTick()
				&& (long) event.getTick() >= (long) damage.getImpactTick() - 2
				|| "SYSTEM_MESSAGE".equals(event.getType()) && event.getTick() == damage.getImpactTick())
			.sorted(java.util.Comparator.comparingLong(ObservedEvent::getSequence))
			.forEach(event -> interactions.add(gson.toJsonTree(event)));
		input.add("interactionObservations", interactions);
		hit.addProperty("hitsplatTypeKnown", damage.getHitsplatTypeKnown());
		if (damage.getCaptureIssues() != null)
		{
			hit.add("captureIssues", gson.toJsonTree(damage.getCaptureIssues()));
		}
		WorldSnapshot impactSample = finalizationChanged(damage) ? null
			: uniqueFrame(damage.getImpactTick(),
				frame -> frame.getPlayer() != null && !Boolean.FALSE.equals(frame.getNativeContextKnown())
					&& (damage.getImpactSceneKey() == null || damage.getImpactSceneKey().equals(frame.getSceneKey()))
					&& (damage.getImpactWorldViewId() == null
						|| damage.getImpactWorldViewId().equals(frame.getWorldViewId()))
					&& (damage.getImpactPlane() == null || damage.getImpactPlane() == frame.getPlane()));
		if (impactSample != null)
		{
			JsonObject frame = frameMetadata(impactSample);
			if (impactSample.getContext() != null)
			{
				JsonElement context = gson.toJsonTree(impactSample.getContext());
				hit.add("impactContext", context);
				frame.add("context", context.deepCopy());
			}
			hit.add("impactFrame", frame);
			if (impactSample.getHazards() != null)
			{
				hit.add("impactSurfaces", gson.toJsonTree(impactSample.getHazards()));
			}
			if (impactSample.getNpcs() != null)
			{
				hit.add("impactNpcs", gson.toJsonTree(impactSample.getNpcs()));
			}
		}
		if (damage.getObservedHazardContacts() != null)
		{
			hit.add("observedHazardContacts", gson.toJsonTree(damage.getObservedHazardContacts()));
		}
		SceneData impactScene = scenes.get(damage.getImpactSceneKey());
		if (impactScene != null)
		{
			JsonObject scene = scope(impactScene.getSceneKey(), impactScene.getWorldViewId(), impactScene.getPlane());
			scene.addProperty("baseX", impactScene.getBaseX());
			scene.addProperty("baseY", impactScene.getBaseY());
			scene.addProperty("width", impactScene.getWidth());
			scene.addProperty("height", impactScene.getHeight());
			hit.add("sceneMetadata", scene);
		}
		Integer observedType = observedType(damage);
		if (observedType != null)
		{
			hit.addProperty("hitsplatType", observedType);
		}
		String family = family(damage, observedType);
		if (family != null)
		{
			hit.addProperty("family", family);
		}
		if (contexts.positionMatches(damage))
		{
			hit.add("position", point(damage.getImpactX(), damage.getImpactY()));
		}
		JsonObject impactFacts = new JsonObject();
		if (ObservedHpCallbackContext.normal(contexts.forHit(damage), Collections.singletonList("LEAGUE_TYPE"),
			Arrays.asList("LEAGUE", "DEADMAN", "BETA", "TOURNAMENT", "QUEST_SPEEDRUNNING")))
		{
			impactFacts.addProperty("world_mode", "NORMAL");
		}
		if (Boolean.TRUE.equals(damage.getImpactPrayerStateKnown()) && damage.getPrayersAtImpact() != null)
		{
			impactFacts.add("player_prayers", gson.toJsonTree(damage.getPrayersAtImpact()));
		}
		JsonObject hitFacts = new JsonObject();
		hitFacts.add("impact", impactFacts);
		hit.add("facts", hitFacts);
		// No preHP reconstruction from amount+postHP; absence leaves linear bounds unqualified.
		hit.add("inputs", new JsonObject());
		ObservedEvent callback = original.get(damage.getObservationSequence());
		if (callback == null || !"HITSPLAT".equals(callback.getType()) || !recipientMatches(callback, damage)
			|| callback.getTick() != damage.getImpactTick()
			|| !Objects.equals(callback.getGameCycle(), damage.getObservedCycle())
			|| !Objects.equals(callback.getSceneKey(), damage.getImpactSceneKey()) || callback.getData() == null
			|| !Integer.valueOf(damage.getAmount()).equals(intNumber(callback.getData().get("amount")))
			|| Boolean.TRUE.equals(damage.getHitsplatTypeKnown())
				&& !Integer.valueOf(damage.getHitsplatType()).equals(intNumber(callback.getData().get("hitsplatType"))))
		{
			retainedWitnesses(damage, cues, null, hit);
			for (JsonElement value : cues)
			{
				retainContextEpoch(object(value));
			}
			return input;
		}
		hit.add("originalHpCallback", gson.toJsonTree(callback.getData()));
		hit.add("numericContext", gson.toJsonTree(contexts.forHit(damage)));
		retainedWitnesses(damage, cues, callback, hit);
		systemResponseCues(damage, cues);
		JsonObject callbackActor = actor(callback);
		if (!Boolean.FALSE.equals(damage.getImpactPositionKnown()) && recipientMatches(callback, damage)
			&& ("LOCAL_PLAYER".equals(text(callbackActor, "actorKind"))
				|| "SETTLED_LOCAL_REFERENCE_MATCH".equals(damage.getDefenderIdentityEvidence()))
			&& Boolean.TRUE.equals(bool(callbackActor.get("worldPositionKnown")))
			&& Boolean.TRUE.equals(bool(callbackActor.get("planeKnown")))
			&& Objects.equals(integer(callbackActor.get("worldViewId")), damage.getImpactWorldViewId())
			&& Objects.equals(integer(callbackActor.get("plane")), damage.getImpactPlane())
			&& !hasIssues(callbackActor, "worldPosition", "worldView", "plane"))
		{
			hit.add("position", point(integer(callbackActor.get("worldX")), integer(callbackActor.get("worldY"))));
		}
		Set<String> originalSurfaces = new HashSet<>();
		Set<String> originalNpcContacts = new HashSet<>();
		if (damage.getObservedHazardContacts() != null)
		{
			for (Map<String, Object> contact : damage.getObservedHazardContacts())
			{
				if (contact == null || !"HITSPLAT_CALLBACK".equals(contact.get("observationStage"))
					|| !Objects.equals(intNumber(contact.get("observedCycle")), damage.getObservedCycle()))
				{
					continue;
				}
				if ("NPC_EMITTER".equals(contact.get("kind")))
				{
					JsonObject cue = originalNpcEmitter(contact, callback, damage);
					if (cue != null)
					{
						cues.add(cue);
					}
					continue;
				}
				if ("NPC_CONTACT".equals(contact.get("kind")))
				{
					JsonObject cue = originalNpcContact(contact, callback, damage);
					if (cue != null)
					{
						cues.add(cue);
						originalNpcContacts.add(sourceKey(object(cue.get("source"))));
					}
					continue;
				}
				String kind = contact.get("objectId") instanceof Number ? "object"
					: contact.get("graphicId") instanceof Number ? "graphic" : null;
				Integer identifier = intNumber(contact.get("object".equals(kind) ? "objectId" : "graphicId"));
				Integer view = intNumber(contact.get("worldViewId")), plane = intNumber(contact.get("plane"));
				if (kind == null || identifier == null)
				{
					continue;
				}
				JsonObject source = source(kind, identifier,
					contact.get("name") instanceof String ? (String) contact.get("name") : null, null, null,
					scope(contact.get("sceneKey") instanceof String ? (String) contact.get("sceneKey") : null, view,
						plane));
				JsonObject cue = cue(
					"original-contact:" + damage.getObservationSequence() + ":" + kind + ":" + identifier + ":"
						+ witnessKey(
							contact.get("object".equals(kind) ? "objectObservationId" : "graphicObservationId"),
							cues.size()),
					"contact_hazard", source, callback.getTick(), callback.getGameCycle(), callback.getSequence());
				cue.addProperty("contactObserved", true);
				if (intNumber(contact.get("observedCycle")) != null
					&& Objects.equals(intNumber(contact.get("observedCycle")), damage.getObservedCycle()))
				{
					cue.addProperty("sourceObservationSequence", callback.getSequence());
				}
				cue.addProperty("evidenceMode", "ORIGINAL_CONTACT");
				cue.add("witness", gson.toJsonTree(contact));
				cue.addProperty("allowFallback", false);
				if (contact.get("damaging") instanceof Boolean)
				{
					facts(cue, "impact").addProperty("source_active", (Boolean) contact.get("damaging"));
				}
				if (contact.get("state") instanceof String)
				{
					facts(cue, "impact").addProperty("source_state", (String) contact.get("state"));
				}
				// contactWorldX/Y describe the recipient query, never a multi-tile emitter anchor.
				cues.add(cue);
				originalSurfaces.add(kind + ":" + identifier);
			}
		}
		for (ObservedEvent event : events.getOrDefault(damage.getImpactTick(), Collections.emptyList()))
		{
			if (cues.size() >= ResolverV1.MAX_CUES)
			{
				break;
			}
			if (original.get(event.getSequence()) != event)
			{
				continue;
			}
			callbackCues(event, damage, cues);
		}
		for (int delay = 1; delay <= 64 && cues.size() < ResolverV1.MAX_CUES; delay++)
		{
			for (ObservedEvent event : interactionEvents.getOrDefault(damage.getImpactTick() - delay,
				Collections.emptyList()))
			{
				if (cues.size() >= ResolverV1.MAX_CUES)
				{
					break;
				}
				if (original.get(event.getSequence()) == event)
				{
					callbackCues(event, damage, cues);
				}
			}
		}
		// A bounded recorded-cue lookback is an analysis work limit, not a reconstructed timer.
		// Only declared lifecycle sources enter it; unfamiliar despawns do not become explosions.
		for (int delay = 1; delay <= 64 && cues.size() < ResolverV1.MAX_CUES; delay++)
		{
			for (ObservedEvent event : lifecycleEvents.getOrDefault(damage.getImpactTick() - delay,
				Collections.emptyList()))
			{
				if (cues.size() >= ResolverV1.MAX_CUES)
				{
					break;
				}
				if (!Arrays.asList("ACTOR_DEATH", "NPC_DESPAWNED", "GRAPHICS_OBJECT_CREATED").contains(event.getType())
					|| original.get(event.getSequence()) != event)
				{
					continue;
				}
				JsonObject raw = actor(event);
				Integer id = integer(raw.get("npcId"));
				if (id != null && (engine.hasPattern("npc", id, "delayed_effect")
					|| engine.hasEvidenceSource("RETAINED_LIFECYCLE", "npc", id)))
				{
					callbackCues(event, damage, cues);
				}
				else if ("GRAPHICS_OBJECT_CREATED".equals(event.getType()))
				{
					Integer graphic = integer(raw.get("id"));
					if (graphic != null && (engine.hasPattern("graphic", graphic, "delayed_effect")
						|| engine.hasEvidenceSource("RETAINED_LIFECYCLE", "graphic", graphic)))
					{
						graphicCue(event, raw, cues, true);
					}
				}
			}
		}
		for (WorldSnapshot frame : frames.getOrDefault(damage.getImpactTick(), Collections.emptyList()))
		{
			if (finalizationChanged(damage) || !Boolean.TRUE.equals(frame.getNativeContextKnown()))
			{
				continue;
			}
			if (frame.getHazards() != null)
			{
				for (HazardState hazard : frame.getHazards())
				{
					if (cues.size() >= ResolverV1.MAX_CUES)
					{
						break;
					}
					if (hazard == null || Boolean.TRUE.equals(hazard.getFinished())
						|| Boolean.FALSE.equals(hazard.getDamaging()))
					{
						continue;
					}
					String kind = "graphicsObject".equals(hazard.getKind()) ? "graphic" : "object";
					if (originalSurfaces.contains(kind + ":" + hazard.getId()))
					{
						continue;
					}
					JsonObject source = source(kind, hazard.getId(), hazard.getMappedName(), null, null,
						scope(frame.getSceneKey(), hazard.getWorldViewId(), hazard.getPlane()));
					String observation = witnessKey(
						"graphic".equals(kind) ? hazard.getGraphicObservationId() : hazard.getObjectObservationId(),
						cues.size());
					JsonObject cue = cue(
						"surface:" + frame.getTick() + ":" + kind + ":" + hazard.getId() + ":" + observation,
						"contact_hazard", source, frame.getTick(),
						Boolean.TRUE.equals(frame.getGameCycleKnown()) ? frame.getGameCycle() : null, null);
					String mode = sampleBeforeImpact(frame, damage) ? "ORIGINAL_CONTACT" : "SETTLED_REFERENCE";
					cue.addProperty("evidenceMode", mode);
					if ("SETTLED_REFERENCE".equals(mode))
					{
						cue.addProperty("componentBound", false);
						cue.addProperty("numericEligible", false);
					}
					cue.add("witness", gson.toJsonTree(hazard));
					cue.addProperty("observationKind", "SETTLED_SURFACE_SAMPLE");
					if (Boolean.TRUE.equals(hazard.getWorldPositionKnown()))
					{
						cue.add("position", point(hazard.getWorldX(), hazard.getWorldY()));
					}
					JsonObject facts = facts(cue, "source_event");
					if (hazard.getDamaging() != null)
					{
						facts.addProperty("source_active", hazard.getDamaging());
					}
					if (hazard.getState() != null)
					{
						facts.addProperty("source_state", hazard.getState());
					}
					if ("graphic".equals(kind))
					{
						signal(cue, "graphicIds", hazard.getId());
					}
					cue.addProperty("allowFallback", false);
					addChannel(cue, "area_impact", mode);
					cues.add(cue);
				}
			}
			if (frame.getNpcs() != null)
			{
				for (NpcState npc : frame.getNpcs())
				{
					if (npc == null || cues.size() >= ResolverV1.MAX_CUES)
					{
						continue;
					}
					JsonObject rawNpc = object(gson.toJsonTree(npc));
					JsonObject source = OriginalSourceView.snapshot(rawNpc, frame.getSceneKey()).source();
					if (Boolean.TRUE.equals(npc.getInteractingWithPlayer()) && npc.getAnimationId() != null
						&& npc.getAnimationId() >= 0)
					{
						JsonObject cue = cue("settled-activity:" + frame.getTick() + ":" + npc.getActorRef(),
							"direct_attack", source, frame.getTick(), null, null);
						cue.addProperty("targetRef", "player");
						signal(cue, "animationIds", npc.getAnimationId());
						cue.addProperty("settledActivity", true);
						cue.addProperty("evidenceMode", "ACTOR_ACTIVITY");
						cue.add("originalActorState", rawNpc);
						// A settled pose cannot supply original-event timing or numeric inputs, including for actors with specials.
						cue.addProperty("componentBound", false);
						cue.addProperty("numericEligible", false);
						cues.add(cue);
					}
					if (!originalNpcContacts.contains(sourceKey(source)) && Boolean.TRUE.equals(npc.getWorldAreaKnown())
						&& Boolean.TRUE.equals(npc.getWorldAreaWorldViewKnown())
						&& Objects.equals(npc.getWorldAreaWorldViewId(), frame.getWorldViewId()))
					{
						JsonObject cue = cue("body:" + frame.getTick() + ":" + npc.getActorRef(), "contact_hazard",
							source, frame.getTick(),
							Boolean.TRUE.equals(frame.getGameCycleKnown()) ? frame.getGameCycle() : null, null);
						cue.addProperty("evidenceMode",
							sampleBeforeImpact(frame, damage) ? "ORIGINAL_CONTACT" : "SETTLED_REFERENCE");
						cue.add("witness", rawNpc);
						cue.addProperty("observationKind", "SETTLED_NPC_FOOTPRINT");
						if (!sampleBeforeImpact(frame, damage))
						{
							cue.addProperty("componentBound", false);
							cue.addProperty("numericEligible", false);
						}
						cue.add("bounds", gson.toJsonTree(npc.getWorldArea()));
						if (Boolean.TRUE.equals(npc.getWorldPositionKnown()))
						{
							cue.add("position", point(npc.getWorldX(), npc.getWorldY()));
						}
						cue.addProperty("allowFallback", false);
						cues.add(cue);
					}
				}
			}
		}
		recordedProjectileCues(damage, cues, hit);
		statusEnvelopeCues(damage, cues);
		if (Arrays.asList("poison", "venom", "burn", "bleed").contains(family))
		{
			JsonObject status = source("status", null, Character.toUpperCase(family.charAt(0)) + family.substring(1),
				null, null, object(hit.get("scope")));
			status.addProperty("statusFamily", family);
			JsonObject cue = cue("observed-status:" + damage.getObservationSequence(), "status_tick", status,
				damage.getImpactTick(), damage.getObservedCycle(), damage.getObservationSequence());
			cue.addProperty("targetRef", "player");
			cue.addProperty("hitFamilyOnly", true);
			cues.add(cue);
		}
		for (DamageRecord trigger : outgoing.getOrDefault(damage.getImpactTick(), Collections.emptyList()))
		{
			if (trigger.getObservationSequence() == null || damage.getObservationSequence() == null)
			{
				continue;
			}
			ObservedEvent actual = original.get(trigger.getObservationSequence());
			if (actual == null || !"HITSPLAT".equals(actual.getType()) || actual.getTick() != trigger.getImpactTick()
				|| trigger.getObservedCycle() == null || !trigger.getObservedCycle().equals(actual.getGameCycle())
				|| actual.getData() == null
				|| !Integer.valueOf(trigger.getAmount()).equals(intNumber(actual.getData().get("amount")))
				|| Boolean.FALSE.equals(actual.getData().get("isMine"))
				|| Boolean.FALSE.equals(trigger.getHitsplatTypeKnown()))
			{
				continue;
			}
			Integer triggerType = intNumber(actual.getData().get("hitsplatType"));
			if (triggerType == null || family(trigger, triggerType) == null
				|| Boolean.TRUE.equals(trigger.getHitsplatTypeKnown()) && trigger.getHitsplatType() != triggerType)
			{
				continue;
			}
			JsonObject raw = actor(actual), source = rawNpc(raw, actual);
			if (source == null || !Objects.equals(text(source, "actorRef"), trigger.getDefenderRef()))
			{
				continue;
			}
			if (!sameNative(object(source.get("scope")), object(hit.get("scope"))))
			{
				continue;
			}
			Integer targetId = integer(source.get("id"));
			if (targetId == null)
			{
				continue;
			} // The outgoing observation remains recorded; no reference rule can identify an unavailable NPC definition.
			retaliationCue(source, targetId, actual, trigger, damage, cues, false);
			for (WorldSnapshot frame : frames.getOrDefault(damage.getImpactTick(), Collections.emptyList()))
			{
				if (finalizationChanged(damage) || !Boolean.TRUE.equals(frame.getNativeContextKnown())
					|| frame.getNpcs() == null
					|| !sameNative(scope(frame.getSceneKey(), frame.getWorldViewId(), frame.getPlane()),
						object(source.get("scope"))))
				{
					continue;
				}
				for (NpcState npc : frame.getNpcs())
				{
					if (npc == null || npc.getNpcId() == null || npc.getActorRef() == null
						|| npc.getActorRef().equals(trigger.getDefenderRef())
						|| !Boolean.TRUE.equals(npc.getPlaneKnown())
						|| !Objects.equals(npc.getWorldViewId(), frame.getWorldViewId())
						|| npc.getPlane() != frame.getPlane() || !lifetimeAllows(npc.getActorRef(), damage))
					{
						continue;
					}
					retaliationCue(npc(npc, frame), targetId, actual, trigger, damage, cues, true);
				}
			}
		}
		Set<String> originalActors = new HashSet<>();
		Map<Long, JsonObject> latestProjectiles = new HashMap<>();
		for (JsonElement value : cues)
		{
			JsonObject cue = object(value), receipt = object(cue.get("projectileObservation"));
			Long instance = longNumber(object(cue.get("witness")).get("observationId"));
			if (!Boolean.TRUE.equals(bool(cue.get("apiSourceObserved"))) || instance == null
				|| integer(receipt.get("cycle")) == null)
			{
				continue;
			}
			JsonObject prior = latestProjectiles.get(instance),
				previous = prior == null ? new JsonObject() : object(prior.get("projectileObservation"));
			Integer cycle = integer(receipt.get("cycle")), oldCycle = integer(previous.get("cycle"));
			Long sequence = longNumber(receipt.get("sequence")), oldSequence = longNumber(previous.get("sequence"));
			if (prior == null || cycle > oldCycle
				|| cycle.equals(oldCycle)
					&& (sequence != null && (oldSequence == null || sequence > oldSequence) || sequence == null
						&& oldSequence == null && text(object(cue.get("projectileScope")), "sceneKey") != null))
			{
				latestProjectiles.put(instance, cue);
			}
		}
		for (JsonElement element : cues)
		{
			JsonObject cue = object(element), source = object(cue.get("source"));
			if (text(source, "actorRef") == null)
			{
				continue;
			}
			if ("direct_attack".equals(text(cue, "pattern")) && cue.has("sequence")
				|| Boolean.TRUE.equals(bool(cue.get("apiSourceObserved")))
				|| Boolean.TRUE.equals(bool(cue.get("retainedOriginAssociation"))))
			{
				originalActors.add(sourceKey(source));
			}
		}
		JsonArray selected = new JsonArray();
		for (JsonElement element : cues)
		{
			JsonObject cue = object(element);
			Long instance = longNumber(object(cue.get("witness")).get("observationId"));
			if (Boolean.TRUE.equals(bool(cue.get("apiSourceObserved"))) && instance != null
				&& integer(object(cue.get("projectileObservation")).get("cycle")) != null
				&& latestProjectiles.get(instance) != cue)
			{
				continue;
			}
			if (Boolean.TRUE.equals(bool(cue.get("settledActivity")))
				&& originalActors.contains(sourceKey(object(cue.get("source")))))
			{
				continue;
			}
			selected.add(cue);
		}
		input.add("cues", selected);
		for (JsonElement value : selected)
		{
			JsonObject cue = object(value);
			retainContextEpoch(cue);
			sourceStateAtImpact(cue, damage);
		}
		return input;
	}

	/** Copy original lifetime counters; eligibility is evaluated by the shared engine. */
	private void retainContextEpoch(JsonObject cue)
	{
		JsonObject source = object(cue.get("source"));
		Long epoch = observedEpoch(object(cue.get("originalSourceObservation")), "contextEpoch");
		if (epoch == null)
		{
			Long sequence = observedEpoch(cue, "sourceObservationSequence");
			ObservedEvent event = sequence == null ? null : original.get(sequence);
			if (event != null && event.getData() != null)
			{
				epoch = longNumber(event.getData().get("causalSceneEpoch"));
			}
		}
		if (epoch != null)
		{
			JsonObject nativeScope = object(source.get("scope"));
			nativeScope.addProperty("contextEpoch", epoch);
			source.add("scope", nativeScope);
			cue.addProperty("contextEpoch", epoch);
		}
	}

	private static Long observedEpoch(JsonObject sample, String field)
	{
		JsonElement value = sample.get(field);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
			? longNumber(value.getAsNumber())
			: null;
	}

	/** Retained observations remain inputs. Their former selected source/style is not an eligibility verdict. */
	private void retainedWitnesses(DamageRecord hit, JsonArray cues, ObservedEvent callback, JsonObject normalizedHit)
	{
		if (hit.getAttributionCandidates() == null)
		{
			return;
		}
		int index = 0;
		for (Map<String, Object> fields : hit.getAttributionCandidates())
		{
			if (fields == null || ++index > SourceEvidenceResolver.MAX_HYPOTHESES || cues.size() >= ResolverV1.MAX_CUES)
			{
				continue;
			}
			JsonObject witness = object(gson.toJsonTree(fields));
			String kind = text(witness, "kind"), mode;
			if (kind == null)
			{
				continue;
			}
			if (Arrays.asList("NPC_ACTIVITY_OBSERVATION", "PLAYER_ACTIVITY_OBSERVATION").contains(kind))
			{
				JsonObject state = object(witness.get("actorState"));
				Integer sampleTick = intNumber(fields.get("observedTick")),
					sampleCycle = intNumber(fields.get("observedCycle"));
				Long sampleSequence = longNumber(fields.get("impactSequence"));
				if (callback == null || !Objects.equals(sampleTick, hit.getImpactTick())
					|| !Objects.equals(sampleCycle, hit.getObservedCycle())
					|| !Objects.equals(sampleSequence, hit.getObservationSequence()))
				{
					continue;
				}
				JsonObject source = OriginalSourceView.hpActor(witness, sampleSequence, index).source();
				if (source.size() == 0)
				{
					continue;
				}
				JsonObject cue = cue("hp-actor-observation:" + hit.getObservationSequence() + ":" + index,
					"direct_attack", source, sampleTick, sampleCycle, sampleSequence > 0 ? sampleSequence : null);
				cue.addProperty("evidenceMode", text(source, "actorRef") == null ? "RETAINED_NAMED" : "ACTOR_ACTIVITY");
				cue.addProperty("observationKind", "HP_CALLBACK_ACTOR_SAMPLE");
				if ("HITSPLAT_CALLBACK".equals(text(witness, "observationStage")))
				{
					cue.addProperty("sourceObservationSequence", sampleSequence);
				}
				cue.add("witness", witness);
				cue.addProperty("componentBound", false);
				cue.addProperty("numericEligible", false);
				String target = text(object(state.get("interaction")), "actorRef");
				if (target == null)
				{
					target = text(witness, "targetRef");
				}
				if (target != null)
				{
					cue.addProperty("targetRef", target);
				}
				cue.addProperty("allowFallback", target != null && target.equals(hit.getDefenderRef()));
				Integer animation = integer(state.get("animationId"));
				if (animation != null)
				{
					signal(cue, "animationIds", animation);
				}
				cues.add(cue);
				continue;
			}
			if ("PROJECTILE_OBSERVATION".equals(kind) && witness.has("projectile"))
			{
				ProjectileState selected = null;
				for (String field : Arrays.asList("originalProjectileObservation", "projectile"))
				{
					if (!witness.has(field))
					{
						continue;
					}
					ProjectileState projectile = gson.fromJson(witness.get(field), ProjectileState.class);
					if (!projectileSampleBeforeHit(projectile, hit))
					{
						continue;
					}
					if (selected == null || projectile.getObservedCycle() >= selected.getObservedCycle())
					{
						selected = projectile;
					}
				}
				if (selected != null)
				{
					int previous = cues.size();
					projectileCues(selected, hit, cues, text(witness, "sceneKey"), selected.getObservedTick(),
						selected.getObservedCycle(), null, normalizedHit);
					Long launchId = longNumber(
						object(witness.get("originalProjectileObservation")).get("observationId"));
					if (launchId != null && launchId.equals(selected.getObservationId()))
					{
						for (int cue = previous; cue < cues.size(); cue++)
						{
							object(cues.get(cue)).add("launchObservation", witness);
						}
					}
				}
				continue;
			}
			if ("ITEM_POSSESSION_CANDIDATE".equals(kind))
			{
				mode = "INVENTORY_POSSESSION";
			}
			else if ("SELF_DAMAGE_INPUT_CANDIDATE".equals(kind))
			{
				mode = "INPUT_REQUEST";
			}
			else if ("ITEM_CONSUMPTION_CANDIDATE".equals(kind))
			{
				mode = "CONSUMPTION";
			}
			else if ("SELF_DAMAGE_RESOURCE_CANDIDATE".equals(kind))
			{
				mode = "RETAINED_NAMED";
			}
			else
			{
				continue;
			}
			Integer item = integer(witness.get("itemId"));
			if (item == null || item < 0)
			{
				continue;
			}
			Integer view = integer(witness.get("worldViewId")), plane = integer(witness.get("plane"));
			String scene = text(witness, "sceneKey");
			if ("INVENTORY_POSSESSION".equals(mode) && callback != null
				&& Boolean.TRUE.equals(fields.get("localRecipientKnown"))
				&& Objects.equals(intNumber(fields.get("observedTick")), hit.getImpactTick())
				&& Objects.equals(intNumber(fields.get("observedCycle")), hit.getObservedCycle())
				&& Objects.equals(longNumber(fields.get("observationSequence")), hit.getObservationSequence()))
			{
				scene = hit.getImpactSceneKey();
				view = hit.getImpactWorldViewId();
				plane = hit.getImpactPlane();
			}
			JsonObject source = source("item", item, text(witness, "name"), null, null, scope(scene, view, plane));
			String sourceReference = text(witness, "sourceRef");
			if ("INVENTORY_POSSESSION".equals(mode) && text(witness, "ruleKey") != null)
			{
				source.addProperty("familyKey", "item-possession-rule:" + text(witness, "ruleKey"));
			}
			else if ((kind.contains("SELF_DAMAGE") || "CONSUMPTION".equals(mode)) && sourceReference != null)
			{
				source.addProperty("familyKey", "input-rule:" + sourceReference);
			}
			JsonObject cue = cue("retained-witness:" + hit.getObservationSequence() + ":" + index, "interaction_damage",
				source, integer(witness.get("observedTick")), integer(witness.get("observedCycle")),
				longNumber(fields.get("observationSequence")));
			cue.addProperty("evidenceMode", mode);
			cue.add("witness", witness);
			cue.addProperty("allowFallback", false);
			Long inputSequence = longNumber(fields.get("inputSequence"));
			ObservedEvent originalInput = inputSequence == null ? null : original.get(inputSequence);
			if (originalInput != null && originalInput.getTick() <= hit.getImpactTick()
				&& (hit.getObservationSequence() == null || originalInput.getSequence() < hit.getObservationSequence()))
			{
				JsonObject data = object(gson.toJsonTree(originalInput.getData())),
					actor = object(data.get("actorState"));
				Integer inputView = integer(actor.get("worldViewId")), inputPlane = integer(actor.get("plane"));
				if (inputView == null)
				{
					inputView = integer(data.get("worldViewId"));
				}
				if (inputPlane == null)
				{
					inputPlane = integer(data.get("plane"));
				}
				SceneData inputScene = scenes.get(originalInput.getSceneKey());
				if (inputScene != null && "player".equals(originalInput.getActorRef()))
				{
					if (inputView == null)
					{
						inputView = inputScene.getWorldViewId();
					}
					if (inputPlane == null)
					{
						inputPlane = inputScene.getPlane();
					}
				}
				source.add("scope", scope(originalInput.getSceneKey(), inputView, inputPlane));
				cue.addProperty("sourceObservationSequence", originalInput.getSequence());
				if ("INPUT_REQUEST".equals(mode))
				{
					cue.addProperty("tick", originalInput.getTick());
					cue.addProperty("cycle", originalInput.getGameCycle());
					cue.addProperty("sequence", originalInput.getSequence());
				}
			}
			else if ("INPUT_REQUEST".equals(mode) && fields.get("inputTick") instanceof Number)
			{
				cue.addProperty("tick", intNumber(fields.get("inputTick")));
			}
			if (fields.get("option") instanceof String)
			{
				signal(cue, "interactionOptions", (String) fields.get("option"));
			}
			cues.add(cue);
		}
	}

	private static boolean projectileSampleBeforeHit(ProjectileState sample, DamageRecord hit)
	{
		return sample != null && sample.getObservedTick() != null && sample.getObservedCycle() != null
			&& hit.getObservedCycle() != null && sample.getObservedTick() <= hit.getImpactTick()
			&& sample.getObservedCycle() <= hit.getObservedCycle()
			&& (hit.getImpactContextEpoch() == null || sample.getSourceHistory() == null
				|| sample.getSourceHistory().getContextEpoch() == hit.getImpactContextEpoch());
	}

	private void systemResponseCues(DamageRecord hit, JsonArray cues)
	{
		for (ObservedEvent event : events.getOrDefault(hit.getImpactTick(), Collections.emptyList()))
		{
			if (!"SYSTEM_MESSAGE".equals(event.getType()) || event.getData() == null)
			{
				continue;
			}
			JsonObject witness = object(gson.toJsonTree(event.getData()));
			String message = text(witness, "message");
			if (message == null)
			{
				continue;
			}
			for (JsonObject rule : engine.evidenceRules("SYSTEM_RESPONSE"))
			{
				if (cues.size() >= ResolverV1.MAX_CUES)
				{
					return;
				}
				JsonObject selector = object(rule.get("source"));
				for (JsonElement id : array(selector.get("ids")))
				{
					JsonObject source = source(text(selector, "kind"), integer(id), text(rule, "label"), null, null,
						scope(event.getSceneKey(), hit.getImpactWorldViewId(), hit.getImpactPlane()));
					source.addProperty("familyKey", "input-rule:wiki:" + text(rule, "label"));
					JsonObject cue = cue("response:" + event.getSequence() + ":" + text(rule, "id"),
						"interaction_damage", source, event.getTick(), event.getGameCycle(), event.getSequence());
					cue.addProperty("evidenceMode", "SYSTEM_RESPONSE");
					cue.addProperty("sourceObservationSequence", event.getSequence());
					cue.add("witness", witness.deepCopy());
					cue.addProperty("allowFallback", false);
					signal(cue, "messages", message.replaceAll("<[^>]*>", ""));
					if (text(witness, "channel") != null)
					{
						signal(cue, "channels", text(witness, "channel"));
					}
					cues.add(cue);
				}
			}
		}
	}

	private void statusEnvelopeCues(DamageRecord hit, JsonArray cues)
	{
		for (int delay = 0; delay <= 1; delay++)
		{
			for (WorldSnapshot frame : frames.getOrDefault(hit.getImpactTick() - delay, Collections.emptyList()))
			{
				if (frame.getLocalStatuses() == null
					|| !sameNative(scope(frame.getSceneKey(), frame.getWorldViewId(), frame.getPlane()),
						normalizedHitScope(hit)))
				{
					continue;
				}
				for (com.tickreplaylogger.model.world.ObservedStatusState status : frame.getLocalStatuses())
				{
					if (status == null || cues.size() >= ResolverV1.MAX_CUES)
					{
						continue;
					}
					JsonObject source = source("status", null,
						status.getStateLabel() == null ? status.getLabel() : status.getStateLabel(), null, null,
						scope(frame.getSceneKey(), frame.getWorldViewId(), frame.getPlane()));
					source.addProperty("statusFamily", status.getState());
					source.addProperty("familyKey", "status:" + status.getRuleKey() + ":" + status.getState());
					JsonObject cue = cue("status-envelope:" + frame.getTick() + ":" + status.getRuleKey(),
						"status_tick", source, frame.getTick(),
						Boolean.TRUE.equals(frame.getGameCycleKnown()) ? frame.getGameCycle() : null, null);
					cue.addProperty("evidenceMode", "STATUS_ENVELOPE");
					cue.add("witness", gson.toJsonTree(status));
					cue.addProperty("allowFallback", false);
					cues.add(cue);
				}
			}
		}
	}

	private boolean recipientMatches(ObservedEvent callback, DamageRecord hit)
	{
		if ("player".equals(callback.getActorRef()))
		{
			return true;
		}
		if (callback.getActorRef() != null || !"player".equals(hit.getDefenderRef()))
		{
			return false;
		}
		List<ObservedEventLink> links = recipientLinks.getOrDefault(callback.getSequence(), Collections.emptyList());
		if (!links.isEmpty())
		{
			return links.stream()
				.allMatch(link -> "HITSPLAT".equals(link.getObservationType()) && "player".equals(link.getActorRef())
					&& link.getTick() == callback.getTick() && link.getResolvedTick() == callback.getTick()
					&& "SETTLED_LOCAL_REFERENCE_MATCH".equals(link.getEvidence())
					&& (window == null || link.getResolvedTick() <= window.getLastTick()
						&& link.getObservationSequence() <= window.getCutoffSequence()));
		}
		// This receipt is written only after the original object matches the local player at the same boundary.
		return "SETTLED_LOCAL_REFERENCE_MATCH".equals(hit.getDefenderIdentityEvidence());
	}

	private JsonObject originalNpcEmitter(Map<String, Object> raw, ObservedEvent callback, DamageRecord hit)
	{
		Integer id = intNumber(raw.get("npcId"));
		Integer view = intNumber(raw.get("worldViewId"));
		Integer plane = intNumber(raw.get("plane"));
		String ref = raw.get("actorRef") instanceof String ? (String) raw.get("actorRef") : null;
		String scene = raw.get("sceneKey") instanceof String ? (String) raw.get("sceneKey") : null;
		if (id == null || id < 0 || ref == null || scene == null || view == null || plane == null
			|| !scene.equals(callback.getSceneKey()) || !view.equals(hit.getImpactWorldViewId())
			|| !plane.equals(hit.getImpactPlane()) || !"NPC_WORLD_VIEW_READ".equals(raw.get("scopeEvidence"))
			|| !"DECLARED_SOURCE_ASSOCIATION".equals(raw.get("sourceEligibility")))
		{
			return null;
		}
		JsonObject source = source("npc", id,
			raw.get("observedName") instanceof String ? (String) raw.get("observedName") : null,
			intNumber(raw.get("combatLevel")), ref, scope(scene, view, plane));
		JsonObject cue = cue("original-npc-emitter:" + callback.getSequence() + ":" + ref, "area_impact", source,
			callback.getTick(), callback.getGameCycle(), callback.getSequence());
		cue.addProperty("nativePresenceObserved", true);
		cue.addProperty("sourceObservationSequence", callback.getSequence());
		cue.add("witness", gson.toJsonTree(raw));
		cue.addProperty("componentBound", false);
		cue.addProperty("numericEligible", false);
		cue.addProperty("allowFallback", false);
		return cue;
	}

	private JsonObject originalNpcContact(Map<String, Object> raw, ObservedEvent callback, DamageRecord hit)
	{
		Integer id = intNumber(raw.get("npcId")), view = intNumber(raw.get("worldViewId")),
			plane = intNumber(raw.get("plane"));
		Integer x = intNumber(raw.get("worldX")), y = intNumber(raw.get("worldY")),
			size = intNumber(raw.get("observedSize"));
		String ref = raw.get("actorRef") instanceof String ? (String) raw.get("actorRef") : null;
		String scene = raw.get("sceneKey") instanceof String ? (String) raw.get("sceneKey") : null;
		String predicate = raw.get("contactPredicate") instanceof String ? (String) raw.get("contactPredicate") : null;
		if (id == null || id < 0 || ref == null || x == null || y == null || view == null || plane == null
			|| !view.equals(hit.getImpactWorldViewId()) || !plane.equals(hit.getImpactPlane())
			|| scene != null && !scene.equals(callback.getSceneKey())
			|| !"SERVER_REPORTED_TILE".equals(raw.get("defenderPositionEvidence"))
			|| !"NPC_WORLD_VIEW_READ".equals(raw.get("scopeEvidence"))
			|| !"ELIGIBLE_REFERENCE_SURFACE".equals(raw.get("sourceEligibility"))
			|| !Boolean.TRUE.equals(raw.get("damaging")) || size == null || size < 1 || size > 64
			|| !size.equals(intNumber(raw.get("footprintWidth"))) || !size.equals(intNumber(raw.get("footprintHeight")))
			|| !(size == 1 && "SAME_SERVER_TILE".equals(predicate)
				|| size > 1 && "COHERENT_SERVER_FOOTPRINT".equals(predicate)))
		{
			return null;
		}
		JsonObject source = source("npc", id,
			raw.get("observedName") instanceof String ? (String) raw.get("observedName") : null,
			intNumber(raw.get("combatLevel")), ref, scope(scene, view, plane));
		JsonObject cue = cue("original-npc-contact:" + callback.getSequence() + ":" + ref, "contact_hazard", source,
			callback.getTick(), callback.getGameCycle(), callback.getSequence());
		cue.add("position", point(x, y));
		JsonObject bounds = new JsonObject();
		bounds.addProperty("minX", x);
		bounds.addProperty("minY", y);
		bounds.addProperty("maxX", (long) x + size - 1);
		bounds.addProperty("maxY", (long) y + size - 1);
		cue.add("bounds", bounds);
		// Occupied body is an original spatial anchor. Only declared rule geometry can make it a damage footprint.
		cue.addProperty("originalNpcContact", true);
		cue.addProperty("sourceObservationSequence", callback.getSequence());
		cue.addProperty("evidenceMode", "ORIGINAL_CONTACT");
		cue.add("witness", gson.toJsonTree(raw));
		if (!Boolean.TRUE.equals(raw.get("normalWorldContextObserved")))
		{
			cue.addProperty("numericEligible", false);
		}
		return cue;
	}

	private static boolean sampleBeforeImpact(WorldSnapshot frame, DamageRecord hit)
	{
		return Boolean.TRUE.equals(frame.getGameCycleKnown()) && hit.getObservedCycle() != null
			&& frame.getGameCycle() <= hit.getObservedCycle();
	}

	/** A positive, ordered removal witness can establish inactivity; absence and death alone cannot. */
	private void sourceStateAtImpact(JsonObject cue, DamageRecord hit)
	{
		JsonObject source = object(cue.get("source"));
		String ref = text(source, "actorRef");
		Integer id = integer(source.get("id"));
		if (!"npc".equals(text(source, "kind")) || ref == null || id == null || hit.getObservedCycle() == null
			|| hit.getObservationSequence() == null
			|| !sameNative(object(source.get("scope")), normalizedHitScope(hit)))
		{
			return;
		}
		ObservedEvent latest = null;
		for (ObservedEvent event : actorPresence.getOrDefault(ref, Collections.emptyList()))
		{
			if (event.getSequence() >= hit.getObservationSequence() || event.getTick() > hit.getImpactTick())
			{
				continue;
			}
			Long epoch = event.getData() == null ? null : longNumber(event.getData().get("causalSceneEpoch"));
			if (hit.getImpactContextEpoch() != null && !hit.getImpactContextEpoch().equals(epoch))
			{
				continue;
			}
			if (latest == null || event.getSequence() > latest.getSequence())
			{
				latest = event;
			}
		}
		if (latest == null || !"NPC_DESPAWNED".equals(latest.getType()) || original.get(latest.getSequence()) != latest
			|| latest.getTick() >= hit.getImpactTick() || latest.getGameCycle() == null
			|| latest.getGameCycle() > hit.getObservedCycle())
		{
			return;
		}
		JsonObject raw = actor(latest), observed = rawNpc(raw, latest);
		if (observed != null && sameNative(object(source.get("scope")), object(observed.get("scope")))
			&& id.equals(integer(observed.get("id"))) && !hasIssues(raw, "npcId", "worldView", "plane"))
		{
			facts(cue, "impact").addProperty("source_active", false);
		}
	}

	private void retaliationCue(JsonObject source, int targetId, ObservedEvent actual, DamageRecord trigger,
		DamageRecord hit, JsonArray cues, boolean coPresent)
	{
		Integer id = integer(source.get("id"));
		if (id == null || cues.size() >= ResolverV1.MAX_CUES)
		{
			return;
		}
		List<String> rules = engine.retaliationRules(id, targetId, coPresent);
		if (rules.isEmpty())
		{
			return;
		}
		JsonObject cue = cue("retaliation:" + trigger.getObservationSequence() + ":" + text(source, "actorRef"),
			"retaliation", source, actual.getTick(), actual.getGameCycle(), actual.getSequence());
		cue.addProperty("targetRef", "player");
		cue.addProperty("allowFallback", false);
		cue.addProperty("componentBound", true);
		if (!coPresent)
		{
			cue.addProperty("sourceObservationSequence", actual.getSequence());
		}
		cue.add("ruleIds", gson.toJsonTree(rules));
		// Co-present snapshots and post-impact packet order are associations, not earlier numeric witnesses.
		if (coPresent || hit.getObservedCycle() == null || actual.getGameCycle() > hit.getObservedCycle()
			|| actual.getSequence() > hit.getObservationSequence())
		{
			cue.addProperty("numericEligible", false);
		}
		JsonObject f = facts(cue, "source_event");
		f.addProperty("observed_trigger_damage", trigger.getAmount());
		f.addProperty("trigger_target_npc_id", targetId);
		Object style = actual.getData().get("trigger_attack_style");
		if (style instanceof String && Arrays.asList("melee", "ranged", "magic").contains(style))
		{
			f.addProperty("trigger_attack_style", (String) style);
		}
		cues.add(cue);
	}

	private boolean lifetimeAllows(String ref, DamageRecord hit)
	{
		for (ObservedEvent event : actorLifetimes.getOrDefault(ref, Collections.emptyList()))
		{
			if ("NPC_SPAWNED".equals(event.getType())
				&& (event.getTick() > hit.getImpactTick() || event.getTick() == hit.getImpactTick()
					&& (event.getSequence() > hit.getObservationSequence() || event.getGameCycle() != null
						&& hit.getObservedCycle() != null && event.getGameCycle() > hit.getObservedCycle())))
			{
				return false;
			}
			if (!"NPC_SPAWNED".equals(event.getType()) && event.getTick() < hit.getImpactTick())
			{
				return false;
			}
		}
		return true;
	}

	private static boolean sameNative(JsonObject a, JsonObject b)
	{
		return text(a, "sceneKey") != null && text(a, "sceneKey").equals(text(b, "sceneKey"))
			&& integer(a.get("worldViewId")) != null
			&& integer(a.get("worldViewId")).equals(integer(b.get("worldViewId"))) && integer(a.get("plane")) != null
			&& integer(a.get("plane")).equals(integer(b.get("plane")));
	}

	private void callbackCues(ObservedEvent event, DamageRecord hit, JsonArray cues)
	{
		String type = event.getType();
		if (!Arrays.asList("ANIMATION_CHANGED", "OVERHEAD_TEXT_CHANGED", "ACTOR_DEATH", "NPC_DESPAWNED", "MENU_OPTION",
			"GRAPHICS_OBJECT_CREATED").contains(type))
		{
			return;
		}
		JsonObject data = object(gson.toJsonTree(event.getData()));
		if ("GRAPHICS_OBJECT_CREATED".equals(type))
		{
			graphicCue(event, data, cues, false);
			return;
		}
		JsonObject raw = data.get("actorState") instanceof JsonObject ? object(data.get("actorState")) : data;
		JsonObject source = rawObservedActor(raw, event);
		if (source != null && Arrays
			.asList("ANIMATION_CHANGED", "OVERHEAD_TEXT_CHANGED", "ACTOR_DEATH", "NPC_DESPAWNED").contains(type))
		{
			String pattern = "ANIMATION_CHANGED".equals(type) ? "direct_attack"
				: "OVERHEAD_TEXT_CHANGED".equals(type) ? "area_impact" : "delayed_effect";
			JsonObject cue = cue("callback:" + event.getSequence(), pattern, source, event.getTick(),
				event.getGameCycle(), event.getSequence());
			cue.add("originalActorState", raw.deepCopy());
			cue.addProperty("sourceObservationSequence", event.getSequence());
			cue.addProperty("evidenceMode", "ACTOR_ACTIVITY");
			if ("delayed_effect".equals(pattern))
			{
				cue.addProperty("evidenceMode", "RETAINED_LIFECYCLE");
			}
			JsonObject interaction = object(raw.get("interaction"));
			if (text(interaction, "actorRef") != null)
			{
				cue.addProperty("targetRef", text(interaction, "actorRef"));
			}
			if (Boolean.FALSE.equals(bool(interaction.get("reportedInteracting")))
				&& text(interaction, "actorRef") != null)
			{
				cue.addProperty("actorInteractionConflict", true);
				cue.addProperty("componentBound", false);
				cue.addProperty("numericEligible", false);
			}
			Integer animation = integer(raw.get("animationId"));
			// An animation reset/read failure is not positive actor activity. Keep the raw callback,
			// but do not let its unclassified fallback revive an excluded original attack cohort.
			if ("ANIMATION_CHANGED".equals(type) && (animation == null || animation < 0))
			{
				return;
			}
			if ("ANIMATION_CHANGED".equals(type) && !hasIssues(raw, "npcId"))
			{
				cue.addProperty("actorCallbackObserved", true);
			}
			if (animation != null && animation >= 0)
			{
				signal(cue, "animationIds", animation);
			}
			if ("OVERHEAD_TEXT_CHANGED".equals(type) && text(data, "text") != null)
			{
				signal(cue, "messages", text(data, "text"));
			}
			if ("ACTOR_DEATH".equals(type))
			{
				signal(cue, "lifecycleEvents", "actor_death");
			}
			if ("NPC_DESPAWNED".equals(type))
			{
				signal(cue, "lifecycleEvents", "npc_despawn");
			}
			if ("delayed_effect".equals(pattern))
			{
				cue.addProperty("allowFallback", false);
			}
			if (event.getTick() > hit.getImpactTick() || event.getGameCycle() == null || hit.getObservedCycle() == null
				|| event.getGameCycle() > hit.getObservedCycle() || event.getSequence() > hit.getObservationSequence())
			{
				cue.addProperty("componentBound", false);
			}
			cue.addProperty("sourceObservedBeforeImpact",
				event.getSequence() > 0 && hit.getObservationSequence() != null
					&& event.getSequence() < hit.getObservationSequence() && event.getTick() <= hit.getImpactTick()
					&& event.getGameCycle() != null && hit.getObservedCycle() != null
					&& event.getGameCycle() <= hit.getObservedCycle());
			if (Boolean.FALSE.equals(bool(cue.get("componentBound"))))
			{
				cue.addProperty("numericEligible", false);
			}
			originalFrameContext(cue, event.getTick(), event.getGameCycle(), object(source.get("scope")), hit);
			if ("direct_attack".equals(pattern) && !"player".equals(text(cue, "targetRef")))
			{
				cue.addProperty("allowFallback", false);
			}
			cues.add(cue);
			if ("ANIMATION_CHANGED".equals(type))
			{
				addChannel(cue, "area_impact", "ACTOR_ACTIVITY").addProperty("allowFallback", false);
			}
		}
		if ("MENU_OPTION".equals(type))
		{
			if (hit.getObservationSequence() == null || event.getSequence() >= hit.getObservationSequence()
				|| event.getTick() > hit.getImpactTick() || event.getGameCycle() == null
				|| hit.getObservedCycle() == null || event.getGameCycle() > hit.getObservedCycle())
			{
				return;
			}
			Integer id = integer(data.get("itemId"));
			String option = text(data, "option");
			SceneData scene = scenes.get(event.getSceneKey());
			JsonObject localScope = scope(event.getSceneKey(), integer(data.get("worldViewId")),
				integer(data.get("plane")));
			if (scene != null && "player".equals(event.getActorRef()))
			{
				localScope = scope(scene.getSceneKey(), scene.getWorldViewId(), scene.getPlane());
			}
			if (id != null && id >= 0 && engine.hasInteractionRule("item", id, option))
			{
				interactionCue(event, source("item", id, text(data, "target"), null, null, localScope), option, cues);
			}
			JsonObject related = object(data.get("relatedActorState"));
			Integer npcId = integer(related.get("npcId"));
			if (npcId != null && npcId >= 0 && event.getRelatedActorRef() != null
				&& "NPC".equals(text(related, "actorKind")) && !hasIssues(related, "npcId")
				&& engine.hasInteractionRule("npc", npcId, option))
			{
				Integer view = integer(related.get("worldViewId")),
					plane = Boolean.TRUE.equals(bool(related.get("planeKnown"))) ? integer(related.get("plane")) : null;
				String relatedScene = scene != null && Objects.equals(scene.getWorldViewId(), view)
					&& Objects.equals(scene.getPlane(), plane) ? scene.getSceneKey() : null;
				JsonObject cue = interactionCue(event, source("npc", npcId, text(related, "name"),
					integer(related.get("combatLevel")), event.getRelatedActorRef(), scope(relatedScene, view, plane)),
					option, cues);
				if (Boolean.TRUE.equals(bool(related.get("normalWorldContextObserved"))))
				{
					facts(cue, "source_event").addProperty("world_mode", "NORMAL");
				}
			}
			String action = text(data, "menuAction");
			Integer requestId = integer(data.get("identifier"));
			if (requestId != null && requestId >= 0 && action != null
				&& (action.startsWith("GAME_OBJECT_") || "WIDGET_TARGET_ON_GAME_OBJECT".equals(action)
					|| "ITEM_USE_ON_GAME_OBJECT".equals(action))
				&& engine.hasInteractionRule("object", requestId, option))
			{
				interactionCue(event, source("object", requestId, null, null, null, scope(null, null, null)), option,
					cues);
			}
		}
	}

	private JsonObject interactionCue(ObservedEvent event, JsonObject source, String option, JsonArray cues)
	{
		JsonObject cue = cue("input:" + event.getSequence() + ":" + text(source, "kind"), "interaction_damage", source,
			event.getTick(), event.getGameCycle(), event.getSequence());
		cue.addProperty("targetRef", "player");
		signal(cue, "interactionOptions", option);
		cue.addProperty("sourceObservationSequence", event.getSequence());
		facts(cue, "interaction").addProperty("interaction_option", option);
		cue.addProperty("allowFallback", false);
		cues.add(cue);
		return cue;
	}

	/** Context is useful only when the actual source clock and native frame agree exactly. */
	private void originalFrameContext(JsonObject cue, Integer tick, Integer cycle, JsonObject sourceScope,
		DamageRecord hit)
	{
		if (tick == null || cycle == null || finalizationChanged(hit))
		{
			return;
		}
		WorldSnapshot selected = uniqueFrame(tick,
			frame -> Boolean.TRUE.equals(frame.getNativeContextKnown())
				&& Boolean.TRUE.equals(frame.getGameCycleKnown()) && frame.getGameCycle() == cycle
				&& sameNative(sourceScope, scope(frame.getSceneKey(), frame.getWorldViewId(), frame.getPlane())));
		if (selected != null && selected.getContext() != null)
		{
			if (selected.getContext().getActivity() != null)
			{
				facts(cue, "source_event").addProperty("encounter_activity", selected.getContext().getActivity());
			}
			String mode = selected.getContext().getChallengeMode(),
				evidence = selected.getContext().getEvidence() == null ? null
					: selected.getContext().getEvidence().get("challengeMode");
			if (Arrays.asList("NORMAL", "HARD", "QUEST").contains(mode) && evidence != null
				&& evidence.startsWith("OBSERVED_ACTIVE_") && !evidence.contains("UNESTABLISHED")
				&& !evidence.contains("UNAVAILABLE") && !evidence.contains("SELECTED"))
			{
				facts(cue, "source_event").addProperty("encounter_mode", mode);
			}
		}
	}

	private void graphicCue(ObservedEvent event, JsonObject data, JsonArray cues, boolean delayedOnly)
	{
		JsonObject graphic = object(data.get("graphic"));
		if (graphic.size() == 0)
		{
			graphic = object(data.get("observedGraphicState"));
		}
		if (graphic.size() == 0)
		{
			graphic = data;
		}
		Integer id = integer(graphic.get("id"));
		if (id == null)
		{
			id = integer(graphic.get("graphicId"));
		}
		if (id == null || id < 0)
		{
			return;
		}
		String sceneKey = text(graphic, "nativeSceneKey");
		if (sceneKey == null)
		{
			sceneKey = event.getSceneKey();
		}
		Integer view = integer(graphic.get("worldViewId")), plane = integer(graphic.get("plane"));
		JsonObject source = source("graphic", id, text(graphic, "mappedName"), null, null,
			scope(sceneKey, view, plane));
		if (cues.size() >= ResolverV1.MAX_CUES || !(engine.hasPattern("graphic", id, "area_impact")
			|| engine.hasPattern("graphic", id, "contact_hazard") || engine.hasPattern("graphic", id, "delayed_effect")
			|| engine.hasEvidenceSource("ORIGINAL_CONTACT", "graphic", id)
			|| engine.hasEvidenceSource("RETAINED_LIFECYCLE", "graphic", id)))
		{
			return;
		}
		String pattern = delayedOnly ? "delayed_effect" : "area_impact";
		JsonObject cue = cue("graphic-created:" + event.getSequence(), pattern, source, event.getTick(),
			event.getGameCycle(), event.getSequence());
		cue.addProperty("evidenceMode", delayedOnly ? "RETAINED_LIFECYCLE" : "ORIGINAL_CONTACT");
		cue.add("witness", graphic.deepCopy());
		cue.addProperty("observationKind", "GRAPHICS_OBJECT_CREATED");
		cue.addProperty("sourceObservationSequence", event.getSequence());
		signal(cue, "graphicIds", id);
		signal(cue, "lifecycleEvents", "graphic_spawn");
		cue.addProperty("allowFallback", false);
		SceneData scene = scenes.get(sceneKey);
		Integer localView = integer(graphic.get("localWorldViewId")), lx = integer(graphic.get("localX")),
			ly = integer(graphic.get("localY"));
		if (scene != null && Boolean.TRUE.equals(bool(graphic.get("scopeKnown")))
			&& Boolean.TRUE.equals(bool(graphic.get("positionScopeKnown"))) && view != null && view.equals(localView)
			&& view.equals(scene.getWorldViewId()) && Objects.equals(plane, scene.getPlane()) && lx != null
			&& ly != null && lx >= 0 && ly >= 0 && scene.getWidth() > 0 && scene.getWidth() <= 104
			&& scene.getHeight() > 0 && scene.getHeight() <= 104 && lx / 128 < scene.getWidth()
			&& ly / 128 < scene.getHeight()
			&& !hasIssues(graphic, "localX", "localY", "worldView", "plane", "nativeSceneKey", "location"))
		{
			cue.add("position", point(scene.getBaseX() + lx / 128, scene.getBaseY() + ly / 128));
		}
		if (!delayedOnly)
		{
			addChannel(cue, "contact_hazard", "ORIGINAL_CONTACT");
			addChannel(cue, "delayed_effect", "RETAINED_LIFECYCLE");
		}
		cues.add(cue);
	}

	private static boolean finalizationChanged(DamageRecord hit)
	{
		return hit.getImpactContextEpoch() != null && hit.getFinalizationContextEpoch() != null
			&& !hit.getImpactContextEpoch().equals(hit.getFinalizationContextEpoch());
	}

	private JsonObject normalizedHitScope(DamageRecord hit)
	{
		JsonObject nativeScope = scope(hit.getImpactSceneKey(), hit.getImpactWorldViewId(), hit.getImpactPlane());
		if (hit.getImpactContextEpoch() != null)
		{
			nativeScope.addProperty("contextEpoch", hit.getImpactContextEpoch());
		}
		return nativeScope;
	}

	private void projectileCues(ProjectileState projectile, DamageRecord hit, JsonArray cues, String eventScene,
		int eventTick, Integer eventCycle, Long eventSequence, JsonObject normalizedHit)
	{
		JsonObject sampleScope = scope(eventScene, projectile == null ? null : projectile.getWorldViewId(),
			projectile != null && Boolean.TRUE.equals(projectile.getSamplePlaneKnown()) ? projectile.getPlane() : null);
		ObservedEvent sampleEvent = eventSequence == null ? null : original.get(eventSequence);
		Long sampleEpoch = sampleEvent == null || sampleEvent.getData() == null ? null
			: longNumber(sampleEvent.getData().get("causalSceneEpoch"));
		if (sampleEpoch != null)
		{
			sampleScope.addProperty("contextEpoch", sampleEpoch);
		}
		if (projectile != null && projectile.getProjectileId() != null && projectileRoleReadable(projectile))
		{
			JsonObject source = source("projectile", projectile.getProjectileId(), null, null, null, sampleScope);
			JsonObject cue = cue("projectile-role:" + projectile.getObservationId(), "projectile_impact", source,
				eventTick, eventCycle, eventSequence);
			signal(cue, "projectileIds", projectile.getProjectileId());
			cue.addProperty("allowFallback", false);
			if (engine.isNonDamagingActivity(cue, normalizedHit))
			{
				cues.add(cue);
				return;
			}
		}
		retainedProximityCue(projectile, hit, cues);
		if (projectile == null || projectile.getSourceHistory() == null || hit.getObservedCycle() == null
			|| eventCycle != null && eventCycle > hit.getObservedCycle() || cues.size() >= ResolverV1.MAX_CUES)
		{
			return;
		}
		ProjectileSourceHistory.SourceObservation first = projectile.getSourceHistory().firstActor();
		boolean unstableHistory = projectile.getSourceHistory().isAttributionUnestablished()
			|| projectile.getSourceHistory().isDefinitionChanged()
			|| projectile.getSourceHistory().isReferenceChanged();
		if (first == null || !Boolean.TRUE.equals(first.getSourceActorKnown())
			|| !"ACTOR".equals(first.getSourceEvidence())
			|| first.getObservedTick() != null
				&& (first.getObservedTick() > hit.getImpactTick() || first.getObservedTick() > eventTick)
			|| first.getObservedCycle() != null && (first.getObservedCycle() > hit.getObservedCycle()
				|| eventCycle != null && first.getObservedCycle() > eventCycle))
		{
			return;
		}
		String kind = Arrays.asList("PLAYER", "OTHER_PLAYER").contains(first.getActorKind()) ? "player"
			: "NPC".equals(first.getActorKind()) ? "npc" : null;
		if (kind == null)
		{
			return;
		}
		Integer id = "npc".equals(kind) && first.getSourceNpcId() != null && first.getSourceNpcId() >= 0
			? first.getSourceNpcId()
			: null;
		String name = Boolean.TRUE.equals(first.getSourceNameKnown()) ? first.getSourceObservedName() : null;
		if (name == null && "player".equals(kind) && "Other player".equals(first.getSourceName()))
		{
			name = "Other player";
		}
		if (name == null && id != null)
		{
			name = "NPC #" + id;
		}
		JsonObject source = source(kind, id, name, first.getSourceCombatLevel(), first.getSourceRef(),
			scope(first.getSourceSceneKey(), first.getSourceWorldViewId(), first.getSourcePlane()));
		String observationKey = projectile.getObservationId() != null ? "projectile:" + projectile.getObservationId()
			: eventSequence != null ? "projectile-event:" + eventSequence : "projectile-witness:" + cues.size();
		if (first.getSourceRef() == null)
		{
			source.addProperty("observationKey", observationKey);
			source.addProperty("familyKey", "source-observation:" + observationKey);
		}
		JsonObject cue = cue(observationKey, "projectile_impact", source,
			projectile.getEstimatedImpactTick() == null ? eventTick : projectile.getEstimatedImpactTick(),
			projectile.getEndCycle() == null ? eventCycle : projectile.getEndCycle(), eventSequence);
		cue.addProperty("apiSourceObserved", true);
		JsonObject receipt = new JsonObject();
		receipt.addProperty("tick", eventTick);
		receipt.addProperty("cycle", eventCycle);
		receipt.addProperty("sequence", eventSequence);
		cue.add("projectileObservation", receipt);
		cue.addProperty("evidenceMode", "API_PROJECTILE");
		cue.add("projectileScope", sampleScope);
		cue.add("originalSourceObservation", gson.toJsonTree(first));
		cue.add("witness", gson.toJsonTree(projectile));
		projectileLaunchObservation(cue, projectile, hit);
		cue.addProperty("sourceObservedBeforeImpact",
			eventSequence != null && eventSequence > 0 && hit.getObservationSequence() != null
				&& eventSequence < hit.getObservationSequence()
				|| first.getObservedCycle() != null && first.getObservedCycle() < hit.getObservedCycle());
		if (unstableHistory)
		{
			cue.addProperty("unstableOriginalSource", true);
		}
		cue.addProperty("targetRef", projectile.getTargetRef());
		cue.addProperty("clockEvidence",
			projectile.getEndCycle() == null ? "OBSERVATION_CLOCK_ONLY"
				: projectile.getEndCycle() > hit.getObservedCycle() ? "HP_DURING_OBSERVED_FLIGHT"
					: "CLIENT_END_CYCLE_CORRELATION");
		signal(cue, "projectileIds", projectile.getProjectileId());
		originalFrameContext(cue, first.getObservedTick(), first.getObservedCycle(), object(source.get("scope")), hit);
		cue.addProperty("allowFallback", "player".equals(projectile.getTargetRef()));
		addChannel(cue, "area_impact", "API_PROJECTILE").addProperty("allowFallback", false);
		if (engine.hasEvidenceSource("ENDPOINT_COOCCURRENCE", kind, id))
		{
			JsonObject endpoint = addChannel(cue, "area_impact", "ENDPOINT_COOCCURRENCE");
			endpoint.addProperty("key", "endpoint-observation:"
				+ (projectile.getObservationId() == null ? observationKey : projectile.getObservationId()));
			endpoint.addProperty("numericEligible", false);
			endpoint.addProperty("componentBound", false);
			endpoint.addProperty("allowFallback", false);
		}
		cues.add(cue);
	}

	private static boolean projectileRoleReadable(ProjectileState projectile)
	{
		ProjectileSourceHistory history = projectile.getSourceHistory();
		if (history != null
			&& (history.isAttributionUnestablished() || history.isDefinitionChanged() || history.isReferenceChanged()))
		{
			return false;
		}
		if (projectile.getObservationIssues() == null)
		{
			return true;
		}
		for (String key : projectile.getObservationIssues().keySet())
		{
			if (key == null || key.startsWith("projectileId") || key.startsWith("worldView") || key.startsWith("plane"))
			{
				return false;
			}
		}
		return true;
	}

	private void projectileLaunchObservation(JsonObject cue, ProjectileState projectile, DamageRecord hit)
	{
		Map<String, Object> launch = projectileLaunches.get(projectile.getObservationId());
		if (launch != null)
		{
			cue.add("launchObservation", gson.toJsonTree(launch));
		}
		ProjectileSourceHistory.SourceObservation first = projectile.getSourceHistory().getFirstObservedSource();
		if (first != null && first.getObservedTick() != null && first.getObservedTick() >= 0
			&& first.getObservedTick() == projectile.getSpawnTick() && first.getObservedTick() <= hit.getImpactTick())
		{
			cue.addProperty("launchTick", first.getObservedTick());
		}
	}

	private void retainedProximityCue(ProjectileState projectile, DamageRecord hit, JsonArray cues)
	{
		if (projectile == null || projectile.getSourceHistory() == null || !"player".equals(projectile.getTargetRef())
			|| cues.size() >= ResolverV1.MAX_CUES)
		{
			return;
		}
		ProjectileSourceHistory.SourceObservation first = projectile.getSourceHistory().getFirstObservedSource();
		ProjectileSourceCandidate proxy = first == null ? null : first.getProximityCandidate();
		if (proxy == null || proxy.getNpcId() == null || proxy.getActorRef() == null || proxy.getSceneKey() == null
			|| proxy.getWorldViewId() == null || proxy.getPlane() == null
			|| Boolean.TRUE.equals(proxy.getModelOverridesPresent()) || first.getObservedTick() == null
			|| first.getObservedCycle() == null || first.getObservedTick() > hit.getImpactTick()
			|| hit.getObservedCycle() == null || first.getObservedCycle() > hit.getObservedCycle())
		{
			return;
		}
		JsonObject source = source("npc", proxy.getNpcId(), proxy.getName(), proxy.getCombatLevel(),
			proxy.getActorRef(), scope(proxy.getSceneKey(), proxy.getWorldViewId(), proxy.getPlane()));
		JsonObject cue = cue("original-proximity:" + projectile.getObservationId(), "projectile_impact", source,
			first.getObservedTick(), first.getObservedCycle(), null);
		cue.addProperty("targetRef", "player");
		cue.addProperty("evidenceMode", "ACTOR_ACTIVITY");
		cue.add("originalSourceObservation", gson.toJsonTree(first));
		cue.add("witness", gson.toJsonTree(proxy));
		cue.addProperty("componentBound", false);
		cue.addProperty("numericEligible", false);
		cue.addProperty("retainedOriginAssociation", true);
		cues.add(cue);
	}

	private JsonObject actor(ObservedEvent event)
	{
		JsonObject data = object(gson.toJsonTree(event.getData()));
		return data.get("actorState") instanceof JsonObject ? object(data.get("actorState")) : data;
	}

	private JsonObject rawObservedActor(JsonObject raw, ObservedEvent event)
	{
		JsonObject source = OriginalSourceView.actor(raw, event.getActorRef(), event.getSceneKey()).source();
		return source.size() == 0 ? null : source;
	}

	private JsonObject rawNpc(JsonObject raw, ObservedEvent event)
	{
		JsonObject source = rawObservedActor(raw, event);
		return source != null && "npc".equals(text(source, "kind")) ? source : null;
	}

	private JsonObject npc(NpcState npc, WorldSnapshot frame)
	{
		return OriginalSourceView.snapshot(object(gson.toJsonTree(npc)), frame.getSceneKey()).source();
	}

	/** Append data-derived evidence without changing raw measurements or actual dealt ownership. */
	public DeathRecordingV2 enrich(DeathRecordingV2 recording)
	{
		if (!engine.isAvailable() || recording.getEvents() == null)
		{
			return recording;
		}
		List<DamageRecord> hits = new ArrayList<>();
		Map<Long, SourceResolution> resolutions = new LinkedHashMap<>();
		Map<Long, DamageRecord> projectedRecords = new LinkedHashMap<>();
		for (DamageRecord hit : recording.getEvents().getDamageEvents() == null ? Collections.<DamageRecord>emptyList()
			: recording.getEvents().getDamageEvents())
		{
			if (hit == null || !"player".equals(hit.getDefenderRef()) || "DEALT".equals(hit.getDirection())
				|| "NPC_RECEIVED".equals(hit.getDirection()))
			{
				hits.add(hit);
				continue;
			}
			DamageRecord resolved = resolveHit(hit);
			SourceResolution resolution = resolved.getSourceResolution();
			hits.add(resolved);
			if (hit.getObservationSequence() != null)
			{
				resolutions.put(hit.getObservationSequence(), resolution);
				projectedRecords.put(hit.getObservationSequence(), resolved);
			}
		}
		List<ObservedEvent> observations = recording.getEvents().getObservations();
		List<ObservedEvent> updatedObservations = observations == null ? null : new ArrayList<>(observations.size());
		boolean resourcesUpdated = false;
		if (observations != null)
		{
			for (ObservedEvent event : observations)
			{
				DamageRecord context = resourceContext(event);
				if (context == null)
				{
					updatedObservations.add(event);
					continue;
				}
				DamageRecord resolved = resolveHit(context);
				Map<String, Object> fields = new LinkedHashMap<>(event.getData());
				fields.put("attributionContext", resolved.toBuilder().sourceResolution(null).attributionCandidates(null)
					.observedHazardContacts(null).build());
				fields.put("sourceResolution", resolved.getSourceResolution());
				updatedObservations.add(ObservedEvent.builder().tick(event.getTick()).gameCycle(event.getGameCycle())
					.sequence(event.getSequence()).type(event.getType()).actorRef(event.getActorRef())
					.actorName(event.getActorName()).relatedActorRef(event.getRelatedActorRef())
					.sceneKey(event.getSceneKey()).data(Collections.unmodifiableMap(fields)).build());
				resolutions.put(event.getSequence(), resolved.getSourceResolution());
				projectedRecords.put(event.getSequence(), resolved);
				resourcesUpdated = true;
			}
		}
		DeathRecordingV2.Summary summary = recording.getSummary();
		if (summary != null)
		{
			DamageRecord fatal = summary.getFatalHit();
			List<DamageRecord> fatalCandidates = new ArrayList<>();
			List<SourceResolution.Candidate> possible = new ArrayList<>();
			List<SourceResolution.Candidate> excluded = new ArrayList<>();
			if (summary.getFatalHitCandidates() != null)
			{
				for (DamageRecord candidate : summary.getFatalHitCandidates())
				{
					SourceResolution resolution = resolutions.get(candidate.getObservationSequence());
					DamageRecord updated = resolution == null ? candidate
						: projectSummaryAnnotations(candidate,
							projectedRecords.get(candidate.getObservationSequence()));
					fatalCandidates.add(updated);
					append(possible, excluded, updated.getSourceResolution());
				}
			}
			if (fatal != null && resolutions.containsKey(fatal.getObservationSequence()))
			{
				SourceResolution resolution = resolutions.get(fatal.getObservationSequence());
				if (fatalCandidates.isEmpty())
				{
					append(possible, excluded, resolution);
				}
				SourceResolution aggregate = project(possible, excluded, "RECORDED_RESOLVER_V1_FATAL_ALTERNATIVES");
				DamageRecord inspected = projectSummaryAnnotations(fatal,
					projectedRecords.get(fatal.getObservationSequence()));
				boolean unresolvedAlternative = fatalCandidates.size() > 1 && fatalCandidates.stream()
					.anyMatch(candidate -> candidate.getSourceResolution() == null
						|| candidate.getSourceResolution().getCandidates() == null
						|| candidate.getSourceResolution().getCandidates().isEmpty());
				if (unresolvedAlternative || "MULTIPLE_DISTINCT_OR_UNLINKED_IMPACTS".equals(summary.getKillerBasis()))
				{
					inspected = inspected.toBuilder().attackerName("Unknown").attribution("UNKNOWN")
						.attributionEvidence("MULTIPLE_DISTINCT_OR_UNLINKED_IMPACTS").build();
				}
				summary = summary.toBuilder().fatalHit(inspected)
					.fatalHitCandidates(summary.getFatalHitCandidates() == null ? null : fatalCandidates)
					.sourceResolution(aggregate).build();
				// Raw killerName/confidence/basis still describe what was known at death. The display
				// reads aggregate possible names separately; the last callback is not a proven killer.
			}
		}
		DeathRecordingV2.Events before = recording.getEvents();
		DeathRecordingV2.Events after = DeathRecordingV2.Events.builder()
			.damageEvents(before.getDamageEvents() == null ? null : hits).effectEvents(before.getEffectEvents())
			.playerActions(before.getPlayerActions()).deaths(before.getDeaths())
			.observations(resourcesUpdated ? updatedObservations : before.getObservations())
			.observationLinks(before.getObservationLinks()).captureGaps(before.getCaptureGaps()).build();
		DeathRecordingV2 resolved = recording.toBuilder().events(after).summary(summary).build();
		return resolved.toBuilder().journalTicks(projectJournalTicks(recording.getJournalTicks(), resolved)).build();
	}

	/** Copy interpreted annotations only after the exact original callback identity agrees. */
	private static DamageRecord projectSummaryAnnotations(DamageRecord original, DamageRecord projected)
	{
		if (projected == null || !original.sameOriginalCallback(projected))
		{
			return original;
		}
		return applyAnnotations(original, projected);
	}

	/** Applies only derived display fields; the original callback and impact samples stay on the record. */
	private static DamageRecord applyAnnotations(DamageRecord original, DamageRecord projected)
	{
		return original.toBuilder().sourceResolution(projected.getSourceResolution())
			.attackerName(projected.getAttackerName()).attackerRef(projected.getAttackerRef())
			.attackerNpcId(projected.getAttackerNpcId()).attackerCombatLevel(projected.getAttackerCombatLevel())
			.attribution(projected.getAttribution()).attributionEvidence(projected.getAttributionEvidence())
			.damageType(projected.getDamageType()).damageTypeEvidence(projected.getDamageTypeEvidence())
			.damageTypeCandidates(projected.getDamageTypeCandidates())
			.damageReferenceKey(projected.getDamageReferenceKey())
			.damagePropertyEvidence(projected.getDamagePropertyEvidence())
			.damageTypeRuleKey(projected.getDamageTypeRuleKey()).damageTypeCatalogId(projected.getDamageTypeCatalogId())
			.attackStyleName(projected.getAttackStyleName()).prayerEffect(projected.getPrayerEffect())
			.pvp(projected.isPvp()).determination(projected.getDetermination())
			.determinationEvidence(projected.getDeterminationEvidence())
			.determinationRuleKey(projected.getDeterminationRuleKey()).mapRef(projected.getMapRef())
			.launchTick(projected.getLaunchTick()).launchTickEstimated(projected.getLaunchTickEstimated())
			.launchStateEvidence(projected.getLaunchStateEvidence()).overheadAtLaunch(projected.getOverheadAtLaunch())
			.prayersAtLaunch(projected.getPrayersAtLaunch())
			.launchPrayerStateKnown(projected.getLaunchPrayerStateKnown())
			.launchPositionKnown(projected.getLaunchPositionKnown()).launchX(projected.getLaunchX())
			.launchY(projected.getLaunchY()).build();
	}

	/** Journal and rich recording present the same annotations; neither rewrites the received amount. */
	public static DamageEvent projectJournalDamage(DamageEvent original, DamageRecord projected)
	{
		if (original == null || projected == null || original.getDirection() != DamageEvent.DamageDirection.RECEIVED)
		{
			return original;
		}
		String label = SourceResolutionLabels.label(projected.getSourceResolution());
		DamageEvent.DamageType type = DamageEvent.DamageType.fromReference(projected.getDamageType());
		return original.toBuilder().sourceResolution(projected.getSourceResolution())
			.source(label == null ? projected.getAttackerName() : label)
			.damageType(type == DamageEvent.DamageType.UNKNOWN ? original.getDamageType() : type)
			.confidence(projected.getAttribution()).determination(projected.getDetermination()).build();
	}

	public static String projectResourceNote(String original, DamageRecord projected)
	{
		if (original == null || projected == null || !original.startsWith("Impact " + projected.getAmount() + " ("))
		{
			return original;
		}
		int end = original.indexOf(") from ");
		if (end < 0)
		{
			return original;
		}
		String source = SourceResolutionLabels.label(projected.getSourceResolution());
		if (source == null)
		{
			source = SourceResolutionLabels.usableName(projected.getAttackerName());
		}
		return source == null ? original : original.substring(0, end + ") from ".length()) + source;
	}

	/** Apply already resolved annotations to a frozen journal; this does not run inference again. */
	public static List<TickData> projectJournalTicks(List<TickData> ticks, DeathRecordingV2 resolved)
	{
		if (ticks == null || resolved == null || resolved.getEvents() == null)
		{
			return ticks;
		}
		Map<Long, DamageRecord> records = new LinkedHashMap<>();
		Set<Long> ambiguous = new HashSet<>();
		if (resolved.getEvents().getDamageEvents() != null)
		{
			for (DamageRecord hit : resolved.getEvents().getDamageEvents())
			{
				if (hit != null && "player".equals(hit.getDefenderRef())
					&& !Arrays.asList("DEALT", "NPC_RECEIVED").contains(hit.getDirection())
					&& hit.getObservationSequence() != null)
				{
					if (records.putIfAbsent(hit.getObservationSequence(), hit) != null)
					{
						ambiguous.add(hit.getObservationSequence());
					}
				}
			}
		}
		if (resolved.getEvents().getObservations() != null)
		{
			for (ObservedEvent event : resolved.getEvents().getObservations())
			{
				if (event != null && event.getData() != null
					&& event.getData().get("attributionContext") instanceof DamageRecord)
				{
					DamageRecord context = (DamageRecord) event.getData().get("attributionContext");
					if (event.getData().get("sourceResolution") instanceof SourceResolution)
					{
						context = context.toBuilder()
							.sourceResolution((SourceResolution) event.getData().get("sourceResolution")).build();
					}
					if (context.getObservationSequence() != null
						&& records.putIfAbsent(context.getObservationSequence(), context) != null)
					{
						ambiguous.add(context.getObservationSequence());
					}
				}
			}
		}
		for (Long key : ambiguous)
		{
			records.remove(key);
		}
		List<TickData> journal = new ArrayList<>();
		for (TickData tick : ticks)
		{
			if (tick == null)
			{
				journal.add(null);
				continue;
			}
			List<DamageEvent> changes = tick.getDamageEvents() == null ? null : new ArrayList<>();
			if (changes != null)
			{
				for (DamageEvent damage : tick.getDamageEvents())
				{
					DamageRecord hit = damage == null ? null : records.get(damage.getObservationSequence());
					changes.add(hit != null && hit.getImpactTick() == tick.getTickNumber()
						&& hit.getAmount() == damage.getAmount() ? projectJournalDamage(damage, hit) : damage);
				}
			}
			List<String> notes = tick.getEffectNotes() == null ? null : new ArrayList<>(tick.getEffectNotes());
			if (notes != null && tick.getEffectNoteSourceSequences() != null)
			{
				for (Map.Entry<Integer, Long> entry : tick.getEffectNoteSourceSequences().entrySet())
				{
					DamageRecord context = records.get(entry.getValue());
					if (context != null && context.getImpactTick() == tick.getTickNumber() && entry.getKey() >= 0
						&& entry.getKey() < notes.size())
					{
						notes.set(entry.getKey(), projectResourceNote(notes.get(entry.getKey()), context));
					}
				}
			}
			journal.add(tick.toBuilder().damageEvents(changes).effectNotes(notes).build());
		}
		return journal;
	}

	private DamageRecord resourceContext(ObservedEvent event)
	{
		if (event == null || !"HITSPLAT".equals(event.getType()) || event.getData() == null
			|| event.getData().get("attributionContext") == null)
		{
			return null;
		}
		Object raw = event.getData().get("attributionContext");
		DamageRecord context = raw instanceof DamageRecord ? (DamageRecord) raw
			: gson.fromJson(gson.toJsonTree(raw), DamageRecord.class);
		if (context == null || !"RESOURCE_IMPACT".equals(context.getDirection())
			|| !"player".equals(context.getDefenderRef()) || !recipientMatches(event, context)
			|| context.getImpactTick() != event.getTick()
			|| !Objects.equals(context.getObservedCycle(), event.getGameCycle())
			|| !Objects.equals(context.getObservationSequence(), event.getSequence())
			|| !Integer.valueOf(context.getAmount()).equals(intNumber(event.getData().get("amount"))))
		{
			return null;
		}
		Object resolution = event.getData().get("sourceResolution");
		return context.toBuilder()
			.sourceResolution(resolution == null ? context.getSourceResolution()
				: resolution instanceof SourceResolution ? (SourceResolution) resolution
					: gson.fromJson(gson.toJsonTree(resolution), SourceResolution.class))
			.attributionCandidates(witnessList(event.getData().get("attributionCandidates")))
			.observedHazardContacts(witnessList(event.getData().get("observedHazardContacts"))).build();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> witnessList(Object values)
	{
		if (!(values instanceof List))
		{
			return null;
		}
		List<Map<String, Object>> result = new ArrayList<>();
		for (Object value : (List<?>) values)
		{
			if (value instanceof Map)
			{
				result.add((Map<String, Object>) value);
			}
		}
		return result;
	}

	private SourceResolution projectResult(ResolutionResult result)
	{
		return SourceEvidenceResolver.project(result, engine.id());
	}

	private SourceResolution project(List<SourceResolution.Candidate> candidates,
		List<SourceResolution.Candidate> excluded, String method)
	{
		return SourceEvidenceResolver.projectAssociations(candidates, excluded, engine.id(), method);
	}

	private Integer observedType(DamageRecord hit)
	{
		if (Boolean.FALSE.equals(hit.getHitsplatTypeKnown()))
		{
			return null;
		}
		if (Boolean.TRUE.equals(hit.getHitsplatTypeKnown()))
		{
			return hit.getHitsplatType();
		}
		ObservedEvent event = original.get(hit.getObservationSequence());
		if (event == null || !"HITSPLAT".equals(event.getType()) || !"player".equals(event.getActorRef())
			|| event.getTick() != hit.getImpactTick() || !Objects.equals(event.getGameCycle(), hit.getObservedCycle())
			|| !Objects.equals(event.getSceneKey(), hit.getImpactSceneKey()) || event.getData() == null
			|| !Integer.valueOf(hit.getAmount()).equals(intNumber(event.getData().get("amount"))))
		{
			return null;
		}
		Object issues = event.getData().get("captureIssues");
		if (issues instanceof List)
		{
			for (Object issue : (List<?>) issues)
			{
				if (issue instanceof String
					&& (((String) issue).startsWith("hitsplatType") || ((String) issue).startsWith("hitsplat.type")))
				{
					return null;
				}
			}
		}
		return intNumber(event.getData().get("hitsplatType"));
	}

	private static String family(DamageRecord hit, Integer type)
	{
		if (type == null)
		{
			return null;
		}
		if (type == HitsplatID.POISON)
		{
			return "poison";
		}
		if (type == HitsplatID.VENOM)
		{
			return "venom";
		}
		if (type == HitsplatID.BURN)
		{
			return "burn";
		}
		if (type == HitsplatID.BLEED)
		{
			return "bleed";
		}
		if ("HITSPLAT_TYPE".equals(hit.getDamageTypeEvidence())
			&& Arrays.asList("poison", "venom", "burn", "bleed").contains(hit.getDamageType()))
		{
			return hit.getDamageType();
		}
		return Arrays.asList(HitsplatID.DAMAGE_ME, HitsplatID.DAMAGE_OTHER, HitsplatID.DAMAGE_MAX_ME,
			HitsplatID.BLOCK_ME, HitsplatID.BLOCK_OTHER).contains(type) ? "ordinary" : null;
	}

	private static void append(List<SourceResolution.Candidate> into, List<SourceResolution.Candidate> excluded,
		SourceResolution resolution)
	{
		if (resolution == null)
		{
			return;
		}
		if (resolution.getCandidates() != null)
		{
			into.addAll(resolution.getCandidates());
		}
		if (resolution.getExcludedCandidates() != null)
		{
			excluded.addAll(resolution.getExcludedCandidates());
		}
	}

	private static JsonObject source(String kind, Integer id, String name, Integer level, String ref, JsonObject scope)
	{
		JsonObject out = new JsonObject();
		out.addProperty("kind", kind);
		out.addProperty("id", id);
		if (name != null)
		{
			out.addProperty("name", name);
		}
		if (level != null)
		{
			out.addProperty("combatLevel", level);
		}
		if (ref != null)
		{
			out.addProperty("actorRef", ref);
		}
		out.add("scope", scope);
		return out;
	}

	/** Missing lifetime identity gets an observation ordinal, never a fabricated tracked entity ID. */
	private static String witnessKey(Object identity, int ordinal)
	{
		Long instance = longNumber(identity);
		return instance != null && instance > 0 ? "instance:" + instance : "witness:" + ordinal;
	}

	private static JsonObject scope(String scene, Integer view, Integer plane)
	{
		JsonObject out = new JsonObject();
		if (scene != null)
		{
			out.addProperty("sceneKey", scene);
		}
		if (view != null && view >= 0)
		{
			out.addProperty("worldViewId", view);
		}
		if (plane != null && plane >= 0 && plane <= 3)
		{
			out.addProperty("plane", plane);
		}
		return out;
	}

	private static JsonObject point(Integer x, Integer y)
	{
		JsonObject out = new JsonObject();
		if (x != null)
		{
			out.addProperty("x", x);
		}
		if (y != null)
		{
			out.addProperty("y", y);
		}
		return out;
	}

	private static JsonObject cue(String key, String pattern, JsonObject source, Integer tick, Integer cycle,
		Long sequence)
	{
		JsonObject out = new JsonObject();
		out.addProperty("key", key);
		out.addProperty("pattern", pattern);
		out.add("source", source);
		if (tick != null)
		{
			out.addProperty("tick", tick);
		}
		if (cycle != null)
		{
			out.addProperty("cycle", cycle);
		}
		if (sequence != null)
		{
			out.addProperty("sequence", sequence);
		}
		out.add("facts", new JsonObject());
		out.add("signals", new JsonObject());
		return out;
	}

	/** Alternative rule channels share one physical observation and its immutable factual payload. */
	private static JsonObject addChannel(JsonObject cue, String pattern, String mode)
	{
		JsonArray channels = array(cue.get("channels"));
		if (!cue.has("channels"))
		{
			cue.add("channels", channels);
			JsonObject primary = new JsonObject();
			primary.add("pattern", cue.get("pattern"));
			if (cue.has("evidenceMode"))
			{
				primary.add("evidenceMode", cue.get("evidenceMode"));
			}
			channels.add(primary);
			cue.add("cohortKey", cue.get("key"));
		}
		JsonObject channel = new JsonObject();
		channel.addProperty("pattern", pattern);
		if (mode != null)
		{
			channel.addProperty("evidenceMode", mode);
		}
		channels.add(channel);
		return channel;
	}

	private static JsonObject facts(JsonObject cue, String at)
	{
		JsonObject all = object(cue.get("facts"));
		cue.add("facts", all);
		JsonObject value = object(all.get(at));
		all.add(at, value);
		return value;
	}

	private void signal(JsonObject cue, String key, Object value)
	{
		if (value == null)
		{
			return;
		}
		JsonObject signals = object(cue.get("signals"));
		cue.add("signals", signals);
		JsonArray array = array(signals.get(key));
		signals.add(key, array);
		array.add(gson.toJsonTree(value));
	}

	private static List<String> strings(JsonElement value)
	{
		List<String> result = new ArrayList<>();
		for (JsonElement element : array(value))
		{
			if (element.isJsonPrimitive())
			{
				result.add(element.getAsString());
			}
		}
		return result;
	}

	private static JsonObject object(JsonElement value)
	{
		return ResolverV1.object(value);
	}

	private static String text(JsonObject value, String key)
	{
		return ResolverV1.text(value, key);
	}

	private static boolean hasIssues(JsonObject raw, String... fields)
	{
		for (String key : object(raw.get("observationIssues")).keySet())
		{
			for (String field : fields)
			{
				if (key.startsWith(field))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static Long longNumber(Object value)
	{
		try
		{
			return value instanceof Number ? new java.math.BigDecimal(value.toString()).longValueExact() : null;
		}
		catch (ArithmeticException | NumberFormatException invalid)
		{
			return null;
		}
	}

	private static Integer intNumber(Object value)
	{
		Long n = longNumber(value);
		return n != null && n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE ? n.intValue() : null;
	}
}
