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
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.tickreplaylogger.capture;

import com.tickreplaylogger.model.world.ActorInteractionState;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.SourceResolution.Candidate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;

/** Same-tick recorded activity hints, never an attack detector, timer, model or HP-causality proof. */
public final class ObservedActorActivityResolver
{
	private static final int MAX_INPUT = 65536, MAX_CUES = 4096;
	private final LegacyCombatCatalog catalog;
	private final Map<Integer, List<Activity>> ticks = new HashMap<>();
	private Integer truncatedThroughTick;
	private boolean unordered;

	public static final class Result
	{
		private final List<Candidate> candidates;
		private final boolean incomplete;
		private final Map<String, NpcState> sourceSamples;

		Result(List<Candidate> candidates, boolean incomplete)
		{
			this(candidates, incomplete, Collections.emptyMap());
		}

		Result(List<Candidate> candidates, boolean incomplete, Map<String, NpcState> sourceSamples)
		{
			this.candidates = Collections.unmodifiableList(candidates);
			this.incomplete = incomplete;
			this.sourceSamples = Collections.unmodifiableMap(new LinkedHashMap<>(sourceSamples));
		}

		public List<Candidate> getCandidates()
		{
			return candidates;
		}

		public boolean isIncomplete()
		{
			return incomplete;
		}

		public Map<String, NpcState> getSourceSamples()
		{
			return sourceSamples;
		}
	}

	private static final class Activity
	{
		final int tick;
		final long sequence;
		final Integer cycle, view, plane, animation, id, targetView;
		final String ref, name, scene, targetRef, targetKind;
		final Boolean reported;
		final boolean scopeKnown, requiredReadsKnown, loggedIn;
		final NpcState sourceSample;

		Activity(ObservedEvent event)
		{
			Map<String, Object> data = event.getData();
			tick = event.getTick();
			sequence = event.getSequence();
			cycle = event.getGameCycle();
			ref = text(event.getActorRef());
			scene = text(event.getSceneKey());
			view = integer(data.get("worldViewId"));
			plane = integer(data.get("plane"));
			animation = integer(data.get("animationId"));
			Integer observedId = integer(data.get("npcId"));
			id = observedId == null || observedId < 0 ? null : observedId;
			String observedName = text(data.get("name"));
			name = com.tickreplaylogger.util.SourceResolutionLabels.usableName(observedName);
			Object interaction = data.get("interaction");
			if (interaction instanceof ActorInteractionState)
			{
				ActorInteractionState target = (ActorInteractionState) interaction;
				reported = target.getReportedInteracting();
				targetRef = text(target.getActorRef());
				targetKind = text(target.getActorKind());
				targetView = target.getWorldViewId();
				requiredReadsKnown = !failed(data, "animationId", "reportedInteracting", "interactionTarget",
					"worldView", "worldViewId", "worldPosition")
					&& !failedIssues(target.getObservationIssues(), "actorRef", "worldView", "worldViewId");
			}
			else
			{
				Map<?, ?> target = interaction instanceof Map ? (Map<?, ?>) interaction : Collections.emptyMap();
				reported = target.get("reportedInteracting") instanceof Boolean
					? (Boolean) target.get("reportedInteracting")
					: null;
				targetRef = text(target.get("actorRef"));
				targetKind = text(target.get("actorKind"));
				targetView = integer(target.get("worldViewId"));
				requiredReadsKnown = !failed(data, "animationId", "reportedInteracting", "interactionTarget",
					"worldView", "worldViewId", "worldPosition")
					&& !failedIssues(target.get("observationIssues"), "actorRef", "worldView", "worldViewId");
			}
			scopeKnown = view != null && plane != null && plane >= 0 && plane <= 3 && scene != null
				&& Boolean.TRUE.equals(data.get("planeKnown")) && Boolean.TRUE.equals(data.get("worldPositionKnown"))
				&& !Boolean.FALSE.equals(data.get("scopeKnown"))
				&& !Boolean.FALSE.equals(data.get("positionScopeKnown"));
			loggedIn = "LOGGED_IN_ACTOR_FIELDS".equals(data.get("callbackMetadataEvidence"));
			Map<String, String> issues = new LinkedHashMap<>();
			if (data.get("observationIssues") instanceof Map)
				for (Map.Entry<?, ?> issue : ((Map<?, ?>) data.get("observationIssues")).entrySet())
					if (issue.getKey() instanceof String && issue.getValue() instanceof String)
						issues.put((String) issue.getKey(), (String) issue.getValue());
					else
						issues.put("callbackModel", "UNAVAILABLE");
			sourceSample = NpcState.builder().actorRef(ref).npcId(id).name(name).worldViewId(view)
				.plane(plane == null ? 0 : plane).planeKnown(Boolean.TRUE.equals(data.get("planeKnown")))
				.worldPositionKnown(Boolean.TRUE.equals(data.get("worldPositionKnown")))
				.combatLevel(integer(data.get("combatLevel"))).definitionEvidence(text(data.get("definitionEvidence")))
				.normalWorldContextObserved(data.get("normalWorldContextObserved") instanceof Boolean
					? (Boolean) data.get("normalWorldContextObserved")
					: null)
				.modelOverridesPresent(
					data.get("modelOverridesPresent") instanceof Boolean ? (Boolean) data.get("modelOverridesPresent")
						: null)
				.observationIssues(Collections.unmodifiableMap(issues)).build();
		}
	}

	public ObservedActorActivityResolver(List<ObservedEvent> events, Object catalog)
	{
		this.catalog = LegacyCombatCatalog.from(catalog);
		if (events == null)
			return;
		int input = 0, indexed = 0;
		Integer previousTick = null;
		for (ListIterator<ObservedEvent> it = events.listIterator(events.size()); it.hasPrevious();)
		{
			ObservedEvent event = it.previous();
			if (++input > MAX_INPUT)
			{
				truncatedThroughTick = event == null ? Integer.MAX_VALUE : event.getTick();
				break;
			}
			if (event == null)
			{
				unordered = true;
				continue;
			}
			if (previousTick != null && event.getTick() > previousTick)
				unordered = true;
			previousTick = event.getTick();
			if (!"ANIMATION_CHANGED".equals(event.getType()) || event.getData() == null
				|| !"NPC".equals(event.getData().get("actorKind")))
				continue;
			if (++indexed > MAX_CUES)
			{
				truncatedThroughTick = event.getTick();
				break;
			}
			ticks.computeIfAbsent(event.getTick(), ignored -> new ArrayList<>()).add(new Activity(event));
		}
	}

	public Result resolve(DamageRecord hit)
	{
		List<Candidate> output = new ArrayList<>();
		if (hit == null || catalog == null || !"player".equals(hit.getDefenderRef())
			|| !Arrays.asList("RECEIVED", "RESOURCE_IMPACT").contains(hit.getDirection())
			|| !Boolean.TRUE.equals(hit.getHitsplatTypeKnown())
			|| !Arrays.asList("HP", "BLOCK").contains(catalog.hitsplatSurface(hit.getHitsplatType()))
			|| catalog.hitsplatDamageType(hit.getHitsplatType()) != null)
			return new Result(output, false);
		boolean incomplete = unordered || truncatedThroughTick != null && hit.getImpactTick() <= truncatedThroughTick;
		Map<List<Object>, Candidate> distinct = new LinkedHashMap<>();
		Map<String, NpcState> sourceSamples = new LinkedHashMap<>();
		for (Activity activity : ticks.getOrDefault(hit.getImpactTick(), Collections.emptyList()))
		{
			if (activity.sequence <= 0 || hit.getObservationSequence() == null || hit.getObservationSequence() <= 0
				|| activity.sequence >= hit.getObservationSequence())
				continue;
			if (activity.animation != null && activity.animation < 0
				|| activity.targetRef != null && !"player".equals(activity.targetRef)
				|| activity.targetKind != null && !"LOCAL_PLAYER".equals(activity.targetKind)
				|| Boolean.FALSE.equals(activity.reported))
				continue;
			// A known foreign scope is not projected into the recipient's coordinates.
			if (different(activity.scene, hit.getImpactSceneKey())
				|| different(activity.view, hit.getImpactWorldViewId())
				|| different(activity.plane, hit.getImpactPlane()))
				continue;
			if (activity.ref == null || "player".equals(activity.ref) || !activity.loggedIn
				|| !activity.requiredReadsKnown || !activity.scopeKnown || activity.animation == null
				|| !Boolean.TRUE.equals(activity.reported) || !"player".equals(activity.targetRef)
				|| !"LOCAL_PLAYER".equals(activity.targetKind) || activity.targetView == null
				|| !activity.targetView.equals(activity.view) || hit.getImpactSceneKey() == null
				|| hit.getImpactWorldViewId() == null || hit.getImpactPlane() == null || activity.cycle == null
				|| activity.cycle < 0 || hit.getObservedCycle() == null || hit.getObservedCycle() < 0
				|| activity.cycle > hit.getObservedCycle())
			{
				incomplete = true;
				continue;
			}
			NpcState source = activity.sourceSample;
			List<Object> identity = Arrays.asList(activity.ref, activity.scene, activity.view, activity.plane,
				activity.cycle, activity.id, activity.name, source.getCombatLevel(), source.getDefinitionEvidence(),
				source.getNormalWorldContextObserved(), source.getModelOverridesPresent(),
				source.getObservationIssues());
			if (distinct.containsKey(identity))
				continue;
			String name = activity.name != null ? activity.name
				: activity.id == null ? "Recorded NPC" : "NPC #" + activity.id;
			Candidate candidate = Candidate.builder()
				.key("callback-activity:" + activity.tick + ":" + activity.sequence).name(name).actorRef(activity.ref)
				.npcId(activity.id).sceneKey(activity.scene).worldViewId(activity.view).plane(activity.plane)
				.strength("POSSIBLE")
				.evidence(Arrays.asList(
					"RECORDED_PRE_HIT_NPC_ACTIVITY;tick=" + activity.tick + ";sequence=" + activity.sequence + ";cycle="
						+ activity.cycle + ";animationId=" + activity.animation,
					"OBSERVED_LOCAL_RECIPIENT; unclassified activity and damage share a recorded tick; attack identity and causality are not established"))
				.build();
			distinct.put(identity, candidate);
			sourceSamples.put(candidate.getKey(), source);
			if (distinct.size() >= SourceEvidenceResolver.MAX_HYPOTHESES)
			{
				incomplete = true;
				break;
			}
		}
		output.addAll(distinct.values());
		return new Result(output, incomplete, sourceSamples);
	}

	private static boolean different(Object first, Object second)
	{
		return first != null && second != null && !Objects.equals(first, second);
	}

	private static String text(Object value)
	{
		return value instanceof String && !((String) value).trim().isEmpty() ? ((String) value).trim() : null;
	}

	private static boolean failed(Map<String, Object> data, String... fields)
	{
		return failedIssues(data.get("observationIssues"), fields);
	}

	private static boolean failedIssues(Object issues, String... fields)
	{
		if (!(issues instanceof Map))
			return false;
		for (String field : fields)
			if (((Map<?, ?>) issues).containsKey(field))
				return true;
		return false;
	}

	private static Integer integer(Object value)
	{
		if (!(value instanceof Number))
			return null;
		try
		{
			return new BigDecimal(value.toString()).intValueExact();
		}
		catch (NumberFormatException | ArithmeticException unavailable)
		{
			return null;
		}
	}
}
