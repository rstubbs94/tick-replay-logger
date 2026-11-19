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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.runelite.api.HitsplatID;

/** Builds normalized associations from a detached recorded window, with no client API access. */
public final class ResolverV1Recording
{
	/** Offline/worker entry. Does not compact or serialize its input, mutate it, or read the client. */
	public static DeathRecordingV2 resolve(DeathRecordingV2 recording, Gson gson)
	{
		if (recording == null)
			return null;
		if (recording.getEvents() == null || recording.getEvents().getDamageEvents() == null
			|| recording.getEvents().getDamageEvents().stream()
				.noneMatch(hit -> hit != null && "player".equals(hit.getDefenderRef())
					&& !"NPC_RECEIVED".equals(hit.getDirection()) && !"DEALT".equals(hit.getDirection())))
			return recording;
		return new ResolverV1Recording(ResolverV1.load(gson), gson, recording).enrich(recording);
	}

	private final ResolverV1 engine;
	private final Gson gson;
	private final Map<Integer, List<ObservedEvent>> events = new HashMap<>();
	private final Map<Integer, List<ObservedEvent>> lifecycleEvents = new HashMap<>();
	private final Map<Integer, List<ObservedEvent>> interactionEvents = new HashMap<>();
	private final Map<String, List<ObservedEvent>> actorLifetimes = new HashMap<>();
	private final Map<String, List<ObservedEvent>> actorPresence = new HashMap<>();
	private final Map<Long, ObservedEvent> original = new HashMap<>();
	private final Map<Integer, List<WorldSnapshot>> frames = new HashMap<>();
	private final Map<String, SceneData> scenes = new HashMap<>();
	private final Map<Integer, List<DamageRecord>> outgoing = new HashMap<>();
	private final Map<Long, java.util.NavigableMap<Long, ObservedEvent>> projectileEvents = new HashMap<>();
	private final java.util.NavigableMap<Integer, Set<Long>> projectileEnds = new java.util.TreeMap<>();
	private final Map<Long, ProjectileState> parsedProjectiles = new HashMap<>();
	private final Map<Long, List<FrameProjectile>> frameProjectiles = new HashMap<>();
	private final java.util.NavigableMap<Integer, Set<Long>> frameProjectileEnds = new java.util.TreeMap<>();
	private final ObservedHpCallbackContext contexts;
	private final boolean complete;
	private static final int MAX_TICK_EVENTS = 2048;

	private static final class FrameProjectile
	{
		final WorldSnapshot frame;
		final ProjectileState projectile;

		FrameProjectile(WorldSnapshot frame, ProjectileState projectile)
		{
			this.frame = frame;
			this.projectile = projectile;
		}
	}

	public ResolverV1Recording(ResolverV1 engine, Gson gson, DeathRecordingV2 recording)
	{
		this.engine = engine;
		this.gson = gson;
		if (recording.getScenes() != null)
			for (SceneData scene : recording.getScenes())
				if (scene != null && scene.getSceneKey() != null)
					scenes.put(scene.getSceneKey(), scene);
		List<ObservedEvent> observations = recording.getEvents() == null ? null
			: recording.getEvents().getObservations();
		contexts = new ObservedHpCallbackContext(observations);
		complete = observations != null && observations.size() <= WorldSnapshotRecorder.MAX_OBSERVATIONS
			&& (recording.getEvents().getCaptureGaps() == null || recording.getEvents().getCaptureGaps().isEmpty());
		Set<Long> seen = new HashSet<>();
		if (observations != null)
			for (ObservedEvent event : observations)
			{
				if (event == null || event.getSequence() <= 0)
					continue;
				if (!seen.add(event.getSequence()))
				{
					original.remove(event.getSequence());
					continue;
				}
				original.put(event.getSequence(), event);
				if (event.getType() != null && event.getType().startsWith("PROJECTILE_") && event.getData() != null)
				{
					Object value = event.getData().get("projectile");
					ProjectileState p = value instanceof ProjectileState ? (ProjectileState) value : null;
					Map<?, ?> raw = value instanceof Map ? (Map<?, ?>) value : Collections.emptyMap();
					Long instance = p != null ? p.getObservationId() : longNumber(raw.get("observationId"));
					Integer end = p != null ? p.getEndCycle() : intNumber(raw.get("endCycle"));
					if (instance != null)
					{
						projectileEvents.computeIfAbsent(instance, k -> new java.util.TreeMap<>())
							.put(event.getSequence(), event);
						if (end != null)
							projectileEnds.computeIfAbsent(end, k -> new HashSet<>()).add(instance);
					}
				}
				List<ObservedEvent> list = events.computeIfAbsent(event.getTick(), k -> new ArrayList<>());
				if (list.size() < MAX_TICK_EVENTS)
					list.add(event);
				if ("MENU_OPTION".equals(event.getType()))
					interactionEvents.computeIfAbsent(event.getTick(), k -> new ArrayList<>()).add(event);
				if (Arrays.asList("ACTOR_DEATH", "NPC_DESPAWNED", "GRAPHICS_OBJECT_CREATED").contains(event.getType()))
				{
					List<ObservedEvent> lifecycle = lifecycleEvents.computeIfAbsent(event.getTick(),
						k -> new ArrayList<>());
					if (lifecycle.size() < MAX_TICK_EVENTS)
						lifecycle.add(event);
				}
				if (event.getActorRef() != null
					&& Arrays.asList("NPC_SPAWNED", "NPC_DESPAWNED", "ACTOR_DEATH").contains(event.getType()))
					actorLifetimes.computeIfAbsent(event.getActorRef(), k -> new ArrayList<>()).add(event);
				if (event.getActorRef() != null
					&& Arrays.asList("NPC_SPAWNED", "NPC_DESPAWNED", "ACTOR_DEATH", "NPC_CHANGED", "ANIMATION_CHANGED",
						"OVERHEAD_TEXT_CHANGED", "ACTOR_GRAPHICS_CHANGED", "HITSPLAT").contains(event.getType()))
					actorPresence.computeIfAbsent(event.getActorRef(), k -> new ArrayList<>()).add(event);
			}
		collect(recording.getTicks());
		if (recording.getEvents() != null && recording.getEvents().getDamageEvents() != null)
			for (DamageRecord hit : recording.getEvents().getDamageEvents())
				if (hit != null && ("DEALT".equals(hit.getDirection())
					|| "NPC_RECEIVED".equals(hit.getDirection()) && "LOCAL_PLAYER".equals(hit.getOwnership())))
					outgoing.computeIfAbsent(hit.getImpactTick(), k -> new ArrayList<>()).add(hit);
	}

	private void collect(List<WorldSnapshot> ticks)
	{
		if (ticks == null)
			return;
		for (WorldSnapshot frame : ticks)
			if (frame != null)
			{
				frames.computeIfAbsent(frame.getTick(), k -> new ArrayList<>()).add(frame);
				indexFrameProjectiles(frame);
				if (frame.getNativeScene() != null)
					scenes.put(frame.getSceneKey(), frame.getNativeScene());
				if (frame.getAdditionalViews() != null)
					for (WorldSnapshot other : frame.getAdditionalViews())
						if (other != null)
						{
							frames.computeIfAbsent(other.getTick(), k -> new ArrayList<>()).add(other);
							indexFrameProjectiles(other);
							if (other.getNativeScene() != null)
								scenes.put(other.getSceneKey(), other.getNativeScene());
						}
			}
	}

	private void indexFrameProjectiles(WorldSnapshot frame)
	{
		if (!Boolean.TRUE.equals(frame.getNativeContextKnown()) || !Boolean.TRUE.equals(frame.getGameCycleKnown())
			|| frame.getProjectiles() == null)
			return;
		int count = 0;
		for (ProjectileState projectile : frame.getProjectiles())
		{
			if (++count > MAX_TICK_EVENTS)
				break;
			if (projectile == null || projectile.getObservationId() == null || projectile.getEndCycle() == null
				|| !Objects.equals(projectile.getWorldViewId(), frame.getWorldViewId())
				|| !Boolean.TRUE.equals(projectile.getSamplePlaneKnown())
				|| !Objects.equals(projectile.getPlane(), frame.getPlane()))
				continue;
			frameProjectiles.computeIfAbsent(projectile.getObservationId(), k -> new ArrayList<>())
				.add(new FrameProjectile(frame, projectile));
			frameProjectileEnds.computeIfAbsent(projectile.getEndCycle(), k -> new HashSet<>())
				.add(projectile.getObservationId());
		}
	}

	public JsonObject normalize(DamageRecord damage)
	{
		JsonObject input = new JsonObject(), hit = new JsonObject();
		JsonArray cues = new JsonArray();
		input.add("hit", hit);
		input.add("cues", cues);
		input.addProperty("complete", complete);
		hit.addProperty("sequence", damage.getObservationSequence());
		hit.addProperty("tick", damage.getImpactTick());
		hit.addProperty("cycle", damage.getObservedCycle());
		hit.addProperty("amount", damage.getAmount());
		hit.addProperty("recipientRef", damage.getDefenderRef());
		Integer observedType = observedType(damage);
		if (observedType != null)
			hit.addProperty("hitsplatType", observedType);
		String family = family(damage, observedType);
		if (family != null)
			hit.addProperty("family", family);
		hit.add("scope", scope(damage.getImpactSceneKey(), damage.getImpactWorldViewId(), damage.getImpactPlane()));
		if (contexts.positionMatches(damage))
			hit.add("position", point(damage.getImpactX(), damage.getImpactY()));
		JsonObject impactFacts = new JsonObject();
		if (ObservedHpCallbackContext.normal(contexts.forHit(damage), Collections.singletonList("LEAGUE_TYPE"),
			Arrays.asList("LEAGUE", "DEADMAN", "BETA", "TOURNAMENT", "QUEST_SPEEDRUNNING")))
			impactFacts.addProperty("world_mode", "NORMAL");
		if (Boolean.TRUE.equals(damage.getImpactPrayerStateKnown()) && damage.getPrayersAtImpact() != null)
			impactFacts.add("player_prayers", gson.toJsonTree(damage.getPrayersAtImpact()));
		JsonObject hitFacts = new JsonObject();
		hitFacts.add("impact", impactFacts);
		hit.add("facts", hitFacts);
		// No preHP reconstruction from amount+postHP; absence leaves linear bounds unqualified.
		hit.add("inputs", new JsonObject());
		ObservedEvent callback = original.get(damage.getObservationSequence());
		if (callback == null || !"HITSPLAT".equals(callback.getType()) || !"player".equals(callback.getActorRef())
			|| callback.getTick() != damage.getImpactTick()
			|| !Objects.equals(callback.getGameCycle(), damage.getObservedCycle())
			|| !Objects.equals(callback.getSceneKey(), damage.getImpactSceneKey()) || callback.getData() == null
			|| !Integer.valueOf(damage.getAmount()).equals(intNumber(callback.getData().get("amount")))
			|| Boolean.TRUE.equals(damage.getHitsplatTypeKnown())
				&& !Integer.valueOf(damage.getHitsplatType()).equals(intNumber(callback.getData().get("hitsplatType"))))
			return input;
		JsonObject callbackActor = actor(callback);
		if (!Boolean.FALSE.equals(damage.getImpactPositionKnown()) && "player".equals(callback.getActorRef())
			&& "LOCAL_PLAYER".equals(text(callbackActor, "actorKind"))
			&& Boolean.TRUE.equals(ResolverV1.bool(callbackActor.get("worldPositionKnown")))
			&& Boolean.TRUE.equals(ResolverV1.bool(callbackActor.get("planeKnown")))
			&& Objects.equals(ResolverV1.integer(callbackActor.get("worldViewId")), damage.getImpactWorldViewId())
			&& Objects.equals(ResolverV1.integer(callbackActor.get("plane")), damage.getImpactPlane())
			&& !hasIssues(callbackActor, "worldPosition", "worldView", "plane"))
			hit.add("position", point(ResolverV1.integer(callbackActor.get("worldX")),
				ResolverV1.integer(callbackActor.get("worldY"))));
		Set<String> originalSurfaces = new HashSet<>();
		Set<String> originalNpcContacts = new HashSet<>();
		if (damage.getObservedHazardContacts() != null)
			for (Map<String, Object> contact : damage.getObservedHazardContacts())
			{
				if (contact == null || !"HITSPLAT_CALLBACK".equals(contact.get("observationStage"))
					|| !Objects.equals(intNumber(contact.get("observedCycle")), damage.getObservedCycle()))
					continue;
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
						originalNpcContacts.add(ResolverV1.sourceKey(object(cue.get("source"))));
					}
					continue;
				}
				String kind = contact.get("objectId") instanceof Number ? "object"
					: contact.get("graphicId") instanceof Number ? "graphic" : null;
				Integer identifier = intNumber(contact.get("object".equals(kind) ? "objectId" : "graphicId"));
				Integer view = intNumber(contact.get("worldViewId")), plane = intNumber(contact.get("plane"));
				if (kind == null || identifier == null || view == null || plane == null
					|| !view.equals(damage.getImpactWorldViewId()) || !plane.equals(damage.getImpactPlane()))
					continue;
				JsonObject source = source(kind, identifier,
					contact.get("name") instanceof String ? (String) contact.get("name") : null, null, null,
					scope(callback.getSceneKey(), view, plane));
				JsonObject cue = cue(
					"original-contact:" + damage.getObservationSequence() + ":" + kind + ":" + identifier,
					"contact_hazard", source, callback.getTick(), callback.getGameCycle(), callback.getSequence());
				cue.addProperty("contactObserved", true);
				cue.addProperty("allowFallback", false);
				if (contact.get("damaging") instanceof Boolean)
					facts(cue, "impact").addProperty("source_active", (Boolean) contact.get("damaging"));
				if (contact.get("state") instanceof String)
					facts(cue, "impact").addProperty("source_state", (String) contact.get("state"));
				// contactWorldX/Y describe the recipient query, never a multi-tile emitter anchor.
				cues.add(cue);
				originalSurfaces.add(kind + ":" + identifier);
			}
		for (ObservedEvent event : events.getOrDefault(damage.getImpactTick(), Collections.emptyList()))
		{
			if (cues.size() >= ResolverV1.MAX_CUES)
				break;
			if (original.get(event.getSequence()) != event)
				continue;
			callbackCues(event, damage, cues);
		}
		for (int delay = 1; delay <= 64 && cues.size() < ResolverV1.MAX_CUES; delay++)
			for (ObservedEvent event : interactionEvents.getOrDefault(damage.getImpactTick() - delay,
				Collections.emptyList()))
			{
				if (cues.size() >= ResolverV1.MAX_CUES)
					break;
				if (original.get(event.getSequence()) == event)
					callbackCues(event, damage, cues);
			}
		// A bounded recorded-cue lookback is an analysis work limit, not a reconstructed timer.
		// Only declared lifecycle sources enter it; unfamiliar despawns do not become explosions.
		for (int delay = 1; delay <= 64 && cues.size() < ResolverV1.MAX_CUES; delay++)
			for (ObservedEvent event : lifecycleEvents.getOrDefault(damage.getImpactTick() - delay,
				Collections.emptyList()))
			{
				if (cues.size() >= ResolverV1.MAX_CUES)
					break;
				if (!Arrays.asList("ACTOR_DEATH", "NPC_DESPAWNED", "GRAPHICS_OBJECT_CREATED").contains(event.getType())
					|| original.get(event.getSequence()) != event)
					continue;
				JsonObject raw = actor(event);
				Integer id = ResolverV1.integer(raw.get("npcId"));
				if (id != null && engine.hasPattern("npc", id, "delayed_effect"))
					callbackCues(event, damage, cues);
				else if ("GRAPHICS_OBJECT_CREATED".equals(event.getType()))
				{
					Integer graphic = ResolverV1.integer(raw.get("id"));
					if (graphic != null && engine.hasPattern("graphic", graphic, "delayed_effect"))
						graphicCue(event, raw, cues, true);
				}
			}
		for (WorldSnapshot frame : frames.getOrDefault(damage.getImpactTick(), Collections.emptyList()))
		{
			if (!Boolean.TRUE.equals(frame.getNativeContextKnown()))
				continue;
			if (frame.getHazards() != null)
				for (HazardState hazard : frame.getHazards())
				{
					if (cues.size() >= ResolverV1.MAX_CUES)
						break;
					if (hazard == null || Boolean.TRUE.equals(hazard.getFinished())
						|| Boolean.FALSE.equals(hazard.getDamaging()))
						continue;
					String kind = "graphicsObject".equals(hazard.getKind()) ? "graphic" : "object";
					if (originalSurfaces.contains(kind + ":" + hazard.getId()))
						continue;
					JsonObject source = source(kind, hazard.getId(), hazard.getMappedName(), null, null,
						scope(frame.getSceneKey(), hazard.getWorldViewId(), hazard.getPlane()));
					for (String pattern : Arrays.asList("contact_hazard", "area_impact"))
					{
						JsonObject cue = cue(
							"surface:" + frame.getTick() + ":" + kind + ":" + hazard.getId() + ":"
								+ hazard.getObjectKey(),
							pattern, source, frame.getTick(),
							Boolean.TRUE.equals(frame.getGameCycleKnown()) ? frame.getGameCycle() : null, null);
						if (!sampleBeforeImpact(frame, damage))
						{
							cue.addProperty("componentBound", false);
							cue.addProperty("numericEligible", false);
						}
						if (Boolean.TRUE.equals(hazard.getWorldPositionKnown()))
							cue.add("position", point(hazard.getWorldX(), hazard.getWorldY()));
						JsonObject facts = facts(cue, "source_event");
						if (hazard.getDamaging() != null)
							facts.addProperty("source_active", hazard.getDamaging());
						if (hazard.getState() != null)
							facts.addProperty("source_state", hazard.getState());
						if ("graphic".equals(kind))
							signal(cue, "graphicIds", hazard.getId());
						cue.addProperty("allowFallback", false);
						cues.add(cue);
					}
				}
			if (frame.getNpcs() != null)
				for (NpcState npc : frame.getNpcs())
				{
					if (npc == null || cues.size() >= ResolverV1.MAX_CUES || npc.getNpcId() == null)
						continue;
					JsonObject source = npc(npc, frame);
					if (Boolean.TRUE.equals(npc.getInteractingWithPlayer()) && npc.getAnimationId() != null
						&& npc.getAnimationId() >= 0)
					{
						JsonObject cue = cue("settled-activity:" + frame.getTick() + ":" + npc.getActorRef(),
							"direct_attack", source, frame.getTick(), null, null);
						cue.addProperty("targetRef", "player");
						signal(cue, "animationIds", npc.getAnimationId());
						cue.addProperty("settledActivity", true);
						// A settled pose cannot supply original-event timing or numeric inputs, including for actors with specials.
						cue.addProperty("componentBound", false);
						cue.addProperty("numericEligible", false);
						if (engine.ordinaryOnly(npc.getNpcId()) && !Boolean.TRUE.equals(npc.getModelOverridesPresent()))
						{
							cue.addProperty("ordinary", true);
						}
						if (Boolean.TRUE.equals(npc.getWorldPositionKnown()))
							cue.add("position", point(npc.getWorldX(), npc.getWorldY()));
						cues.add(cue);
					}
					if (!originalNpcContacts.contains(ResolverV1.sourceKey(source))
						&& Boolean.TRUE.equals(npc.getWorldAreaKnown())
						&& Boolean.TRUE.equals(npc.getWorldAreaWorldViewKnown())
						&& Objects.equals(npc.getWorldAreaWorldViewId(), frame.getWorldViewId()))
					{
						JsonObject cue = cue("body:" + frame.getTick() + ":" + npc.getActorRef(), "contact_hazard",
							source, frame.getTick(), null, null);
						if (!sampleBeforeImpact(frame, damage))
						{
							cue.addProperty("componentBound", false);
							cue.addProperty("numericEligible", false);
						}
						cue.add("bounds", gson.toJsonTree(npc.getWorldArea()));
						if (Boolean.TRUE.equals(npc.getWorldPositionKnown()))
							cue.add("position", point(npc.getWorldX(), npc.getWorldY()));
						cue.addProperty("allowFallback", false);
						cues.add(cue);
					}
				}
		}
		Set<Long> witnessedProjectiles = new HashSet<>();
		if (damage.getObservedCycle() != null && damage.getObservationSequence() != null)
		{
			int low = (int) Math.max(Integer.MIN_VALUE, (long) damage.getObservedCycle() - 30);
			Set<Long> instances = new java.util.LinkedHashSet<>();
			for (Set<Long> group : projectileEnds.tailMap(low, true).values())
			{
				for (Long instance : group)
				{
					Map.Entry<Long, ObservedEvent> prior = projectileEvents.get(instance)
						.lowerEntry(damage.getObservationSequence());
					if (prior == null || prior.getValue().getTick() > damage.getImpactTick()
						|| prior.getValue().getGameCycle() == null
						|| prior.getValue().getGameCycle() > damage.getObservedCycle())
						continue;
					instances.add(instance);
					if (instances.size() >= 512)
						break;
				}
				if (instances.size() >= 512)
					break;
			}
			for (Long instance : instances)
			{
				Map.Entry<Long, ObservedEvent> entry = projectileEvents.get(instance)
					.lowerEntry(damage.getObservationSequence());
				if (entry == null || original.get(entry.getKey()) != entry.getValue())
					continue;
				ObservedEvent event = entry.getValue();
				if (event.getTick() > damage.getImpactTick() || event.getGameCycle() == null
					|| event.getGameCycle() > damage.getObservedCycle())
					continue;
				witnessedProjectiles.add(instance);
				ProjectileState value = parsedProjectiles.get(entry.getKey());
				if (value == null)
				{
					Object raw = event.getData().get("projectile");
					value = raw instanceof ProjectileState ? (ProjectileState) raw
						: gson.fromJson(gson.toJsonTree(raw), ProjectileState.class);
					if (value != null)
						parsedProjectiles.put(entry.getKey(), value);
				}
				projectileCues(value, damage, cues, event.getSceneKey(), event.getTick(), event.getGameCycle(),
					event.getSequence(), hit);
			}
		}
		frameProjectileCues(damage, cues, hit, witnessedProjectiles);
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
				continue;
			ObservedEvent actual = original.get(trigger.getObservationSequence());
			if (actual == null || !"HITSPLAT".equals(actual.getType()) || actual.getTick() != trigger.getImpactTick()
				|| trigger.getObservedCycle() == null || !trigger.getObservedCycle().equals(actual.getGameCycle())
				|| actual.getData() == null
				|| !Integer.valueOf(trigger.getAmount()).equals(intNumber(actual.getData().get("amount")))
				|| Boolean.FALSE.equals(actual.getData().get("isMine"))
				|| Boolean.FALSE.equals(trigger.getHitsplatTypeKnown()))
				continue;
			Integer triggerType = intNumber(actual.getData().get("hitsplatType"));
			if (triggerType == null || family(trigger, triggerType) == null
				|| Boolean.TRUE.equals(trigger.getHitsplatTypeKnown()) && trigger.getHitsplatType() != triggerType)
				continue;
			JsonObject raw = actor(actual), source = rawNpc(raw, actual);
			if (source == null || !Objects.equals(text(source, "actorRef"), trigger.getDefenderRef()))
				continue;
			if (!sameNative(object(source.get("scope")), object(hit.get("scope"))))
				continue;
			Integer targetId = ResolverV1.integer(source.get("id"));
			retaliationCue(source, targetId, actual, trigger, damage, cues, false);
			for (WorldSnapshot frame : frames.getOrDefault(damage.getImpactTick(), Collections.emptyList()))
			{
				if (!Boolean.TRUE.equals(frame.getNativeContextKnown()) || frame.getNpcs() == null
					|| !sameNative(scope(frame.getSceneKey(), frame.getWorldViewId(), frame.getPlane()),
						object(source.get("scope"))))
					continue;
				for (NpcState npc : frame.getNpcs())
				{
					if (npc == null || npc.getNpcId() == null || npc.getActorRef() == null
						|| npc.getActorRef().equals(trigger.getDefenderRef())
						|| !Boolean.TRUE.equals(npc.getPlaneKnown())
						|| !Objects.equals(npc.getWorldViewId(), frame.getWorldViewId())
						|| npc.getPlane() != frame.getPlane() || !lifetimeAllows(npc.getActorRef(), damage))
						continue;
					retaliationCue(npc(npc, frame), targetId, actual, trigger, damage, cues, true);
				}
			}
		}
		Set<String> originalActors = new HashSet<>();
		for (JsonElement element : cues)
		{
			JsonObject cue = object(element), source = object(cue.get("source"));
			if (text(source, "actorRef") == null)
				continue;
			if ("direct_attack".equals(text(cue, "pattern")) && cue.has("sequence")
				|| Boolean.TRUE.equals(ResolverV1.bool(cue.get("apiSourceObserved")))
				|| Boolean.TRUE.equals(ResolverV1.bool(cue.get("retainedOriginAssociation"))))
				originalActors.add(ResolverV1.sourceKey(source));
		}
		JsonArray selected = new JsonArray();
		for (JsonElement element : cues)
		{
			JsonObject cue = object(element);
			if (Boolean.TRUE.equals(ResolverV1.bool(cue.get("settledActivity")))
				&& originalActors.contains(ResolverV1.sourceKey(object(cue.get("source")))))
				continue;
			selected.add(cue);
		}
		input.add("cues", selected);
		for (JsonElement value : cues)
			sourceStateAtImpact(object(value), damage);
		return input;
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
			return null;
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
		if (Boolean.TRUE.equals(raw.get("normalWorldContextObserved")))
		{
			facts(cue, "source_event").addProperty("world_mode", "NORMAL");
			facts(cue, "source_event").addProperty("source_model_overrides", false);
		}
		else
			cue.addProperty("numericEligible", false);
		return cue;
	}

	private static boolean sampleBeforeImpact(WorldSnapshot frame, DamageRecord hit)
	{
		return Boolean.TRUE.equals(frame.getGameCycleKnown()) && hit.getObservedCycle() != null
			&& frame.getGameCycle() <= hit.getObservedCycle();
	}

	private void frameProjectileCues(DamageRecord damage, JsonArray cues, JsonObject normalizedHit, Set<Long> witnessed)
	{
		if (damage.getObservedCycle() == null)
			return;
		int low = (int) Math.max(Integer.MIN_VALUE, (long) damage.getObservedCycle() - 30);
		Set<Long> instances = new HashSet<>();
		for (Set<Long> group : frameProjectileEnds.tailMap(low, true).values())
		{
			for (Long instance : group)
			{
				boolean observed = false;
				for (FrameProjectile sample : frameProjectiles.getOrDefault(instance, Collections.emptyList()))
					if (sample.frame.getTick() <= damage.getImpactTick()
						&& sample.frame.getGameCycle() < damage.getObservedCycle())
					{
						observed = true;
						break;
					}
				if (!observed)
					continue;
				instances.add(instance);
				if (instances.size() >= 512)
					break;
			}
			if (instances.size() >= 512)
				break;
		}
		for (Long instance : instances)
		{
			if (witnessed.contains(instance) || cues.size() >= ResolverV1.MAX_CUES)
				continue;
			FrameProjectile selected = null;
			boolean ambiguous = false;
			for (FrameProjectile sample : frameProjectiles.getOrDefault(instance, Collections.emptyList()))
			{
				if (sample.frame.getTick() > damage.getImpactTick()
					|| sample.frame.getGameCycle() >= damage.getObservedCycle())
					continue;
				if (selected == null || sample.frame.getGameCycle() > selected.frame.getGameCycle())
				{
					selected = sample;
					ambiguous = false;
				}
				else if (sample.frame.getGameCycle() == selected.frame.getGameCycle())
					ambiguous = true;
			}
			if (selected != null && !ambiguous)
				projectileCues(selected.projectile, damage, cues, selected.frame.getSceneKey(),
					selected.frame.getTick(), selected.frame.getGameCycle(), null, normalizedHit);
		}
	}

	/** A positive, ordered removal witness can establish inactivity; absence and death alone cannot. */
	private void sourceStateAtImpact(JsonObject cue, DamageRecord hit)
	{
		JsonObject source = object(cue.get("source"));
		String ref = text(source, "actorRef");
		Integer id = ResolverV1.integer(source.get("id"));
		if (!"npc".equals(text(source, "kind")) || ref == null || id == null || hit.getObservedCycle() == null
			|| hit.getObservationSequence() == null
			|| !sameNative(object(source.get("scope")), normalizedHitScope(hit)))
			return;
		ObservedEvent latest = null;
		for (ObservedEvent event : actorPresence.getOrDefault(ref, Collections.emptyList()))
		{
			if (event.getSequence() >= hit.getObservationSequence() || event.getTick() > hit.getImpactTick())
				continue;
			if (latest == null || event.getSequence() > latest.getSequence())
				latest = event;
		}
		if (latest == null || !"NPC_DESPAWNED".equals(latest.getType()) || original.get(latest.getSequence()) != latest
			|| latest.getTick() >= hit.getImpactTick() || latest.getGameCycle() == null
			|| latest.getGameCycle() > hit.getObservedCycle())
			return;
		JsonObject raw = actor(latest), observed = rawNpc(raw, latest);
		if (observed != null && sameNative(object(source.get("scope")), object(observed.get("scope")))
			&& id.equals(ResolverV1.integer(observed.get("id"))) && !hasIssues(raw, "npcId", "worldView", "plane"))
			facts(cue, "impact").addProperty("source_active", false);
	}

	private void retaliationCue(JsonObject source, int targetId, ObservedEvent actual, DamageRecord trigger,
		DamageRecord hit, JsonArray cues, boolean coPresent)
	{
		Integer id = ResolverV1.integer(source.get("id"));
		if (id == null || cues.size() >= ResolverV1.MAX_CUES)
			return;
		List<String> rules = engine.retaliationRules(id, targetId, coPresent);
		if (rules.isEmpty())
			return;
		JsonObject cue = cue("retaliation:" + trigger.getObservationSequence() + ":" + text(source, "actorRef"),
			"retaliation", source, actual.getTick(), actual.getGameCycle(), actual.getSequence());
		cue.addProperty("targetRef", "player");
		cue.addProperty("allowFallback", false);
		cue.addProperty("componentBound", true);
		cue.add("ruleIds", gson.toJsonTree(rules));
		// Co-present snapshots and post-impact packet order are associations, not earlier numeric witnesses.
		if (coPresent || hit.getObservedCycle() == null || actual.getGameCycle() > hit.getObservedCycle()
			|| actual.getSequence() > hit.getObservationSequence())
			cue.addProperty("numericEligible", false);
		JsonObject f = facts(cue, "source_event");
		f.addProperty("observed_trigger_damage", trigger.getAmount());
		f.addProperty("trigger_target_npc_id", targetId);
		Object style = actual.getData().get("trigger_attack_style");
		if (style instanceof String && Arrays.asList("melee", "ranged", "magic").contains(style))
			f.addProperty("trigger_attack_style", (String) style);
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
				return false;
			if (!"NPC_SPAWNED".equals(event.getType()) && event.getTick() < hit.getImpactTick())
				return false;
		}
		return true;
	}

	private static boolean sameNative(JsonObject a, JsonObject b)
	{
		return text(a, "sceneKey") != null && text(a, "sceneKey").equals(text(b, "sceneKey"))
			&& ResolverV1.integer(a.get("worldViewId")) != null
			&& ResolverV1.integer(a.get("worldViewId")).equals(ResolverV1.integer(b.get("worldViewId")))
			&& ResolverV1.integer(a.get("plane")) != null
			&& ResolverV1.integer(a.get("plane")).equals(ResolverV1.integer(b.get("plane")));
	}

	private void callbackCues(ObservedEvent event, DamageRecord hit, JsonArray cues)
	{
		String type = event.getType();
		if (!Arrays.asList("ANIMATION_CHANGED", "OVERHEAD_TEXT_CHANGED", "ACTOR_DEATH", "NPC_DESPAWNED", "MENU_OPTION",
			"GRAPHICS_OBJECT_CREATED").contains(type))
			return;
		JsonObject data = object(gson.toJsonTree(event.getData()));
		if ("GRAPHICS_OBJECT_CREATED".equals(type))
		{
			graphicCue(event, data, cues, false);
			return;
		}
		JsonObject raw = data.get("actorState") instanceof JsonObject ? object(data.get("actorState")) : data;
		JsonObject source = rawNpc(raw, event);
		if (source != null && Arrays
			.asList("ANIMATION_CHANGED", "OVERHEAD_TEXT_CHANGED", "ACTOR_DEATH", "NPC_DESPAWNED").contains(type))
		{
			String pattern = "ANIMATION_CHANGED".equals(type) ? "direct_attack"
				: "OVERHEAD_TEXT_CHANGED".equals(type) ? "area_impact" : "delayed_effect";
			JsonObject cue = cue("callback:" + event.getSequence(), pattern, source, event.getTick(),
				event.getGameCycle(), event.getSequence());
			JsonObject interaction = object(raw.get("interaction"));
			if (Boolean.TRUE.equals(ResolverV1.bool(interaction.get("reportedInteracting"))))
				cue.addProperty("targetRef", text(interaction, "actorRef"));
			Integer animation = ResolverV1.integer(raw.get("animationId"));
			// An animation reset/read failure is not positive actor activity. Keep the raw callback,
			// but do not let its unclassified fallback revive an excluded original attack cohort.
			if ("ANIMATION_CHANGED".equals(type) && (animation == null || animation < 0))
				return;
			if ("ANIMATION_CHANGED".equals(type) && !hasIssues(raw, "npcId"))
				cue.addProperty("actorCallbackObserved", true);
			if (animation != null && animation >= 0)
				signal(cue, "animationIds", animation);
			if ("OVERHEAD_TEXT_CHANGED".equals(type) && text(data, "text") != null)
				signal(cue, "messages", text(data, "text"));
			if ("ACTOR_DEATH".equals(type))
				signal(cue, "lifecycleEvents", "actor_death");
			if ("NPC_DESPAWNED".equals(type))
				signal(cue, "lifecycleEvents", "npc_despawn");
			if ("delayed_effect".equals(pattern))
				cue.addProperty("allowFallback", false);
			if (Boolean.TRUE.equals(ResolverV1.bool(raw.get("worldPositionKnown"))))
				cue.add("position",
					point(ResolverV1.integer(raw.get("worldX")), ResolverV1.integer(raw.get("worldY"))));
			if (Boolean.TRUE.equals(ResolverV1.bool(raw.get("worldAreaKnown"))))
				cue.add("bounds", object(raw.get("worldArea")));
			if (Boolean.TRUE.equals(ResolverV1.bool(raw.get("normalWorldContextObserved")))
				&& !hasIssues(raw, "normalWorldContextObserved", "worldTypes", "leagueType"))
				facts(cue, "source_event").addProperty("world_mode", "NORMAL");
			Boolean overrides = ResolverV1.bool(raw.get("modelOverridesPresent"));
			if (overrides != null && !hasIssues(raw, "modelOverrides"))
				facts(cue, "source_event").addProperty("source_model_overrides", overrides);
			if ("direct_attack".equals(pattern) && "player".equals(text(cue, "targetRef")) && animation != null
				&& animation >= 0 && engine.ordinaryOnly(ResolverV1.integer(source.get("id")))
				&& Boolean.FALSE.equals(ResolverV1.bool(raw.get("modelOverridesPresent")))
				&& !hasIssues(raw, "npcId", "name", "combatLevel", "normalWorldContextObserved", "modelOverrides",
					"worldView", "plane", "animationId"))
				cue.addProperty("ordinary", true);
			if (!Boolean.FALSE.equals(ResolverV1.bool(raw.get("modelOverridesPresent")))
				|| !Boolean.TRUE.equals(ResolverV1.bool(raw.get("normalWorldContextObserved")))
				|| hasIssues(raw, "npcId", "name", "combatLevel", "normalWorldContextObserved", "worldTypes",
					"leagueType", "modelOverrides", "worldView", "plane"))
				cue.addProperty("numericEligible", false);
			if (event.getTick() > hit.getImpactTick() || event.getGameCycle() == null || hit.getObservedCycle() == null
				|| event.getGameCycle() > hit.getObservedCycle() || event.getSequence() > hit.getObservationSequence())
				cue.addProperty("componentBound", false);
			cue.addProperty("sourceObservedBeforeImpact",
				event.getSequence() > 0 && hit.getObservationSequence() != null
					&& event.getSequence() < hit.getObservationSequence() && event.getTick() <= hit.getImpactTick()
					&& event.getGameCycle() != null && hit.getObservedCycle() != null
					&& event.getGameCycle() <= hit.getObservedCycle());
			if (Boolean.FALSE.equals(ResolverV1.bool(cue.get("componentBound"))))
				cue.addProperty("numericEligible", false);
			if (animation != null)
				facts(cue, "source_event").addProperty("source_animation_id", animation);
			facts(cue, "source_event").add("source_form_id", source.get("id"));
			if (cue.has("targetRef"))
				facts(cue, "source_event").add("source_target", cue.get("targetRef"));
			originalFrameContext(cue, event.getTick(), event.getGameCycle(), object(source.get("scope")));
			if ("direct_attack".equals(pattern) && !"player".equals(text(cue, "targetRef")))
				cue.addProperty("allowFallback", false);
			cues.add(cue);
			if ("ANIMATION_CHANGED".equals(type))
			{
				JsonObject area = cue.deepCopy();
				area.addProperty("pattern", "area_impact");
				area.addProperty("allowFallback", false);
				cues.add(area);
			}
		}
		if ("MENU_OPTION".equals(type))
		{
			if (hit.getObservationSequence() == null || event.getSequence() >= hit.getObservationSequence()
				|| event.getTick() > hit.getImpactTick() || event.getGameCycle() == null
				|| hit.getObservedCycle() == null || event.getGameCycle() > hit.getObservedCycle())
				return;
			Integer id = ResolverV1.integer(data.get("itemId"));
			String option = text(data, "option");
			SceneData scene = scenes.get(event.getSceneKey());
			JsonObject localScope = scope(event.getSceneKey(), ResolverV1.integer(data.get("worldViewId")),
				ResolverV1.integer(data.get("plane")));
			if (scene != null && "player".equals(event.getActorRef()))
				localScope = scope(scene.getSceneKey(), scene.getWorldViewId(), scene.getPlane());
			if (id != null && id >= 0 && engine.hasInteractionRule("item", id, option))
			{
				interactionCue(event, source("item", id, text(data, "target"), null, null, localScope), option, cues);
			}
			JsonObject related = object(data.get("relatedActorState"));
			Integer npcId = ResolverV1.integer(related.get("npcId"));
			if (npcId != null && npcId >= 0 && event.getRelatedActorRef() != null
				&& "NPC".equals(text(related, "actorKind")) && !hasIssues(related, "npcId")
				&& engine.hasInteractionRule("npc", npcId, option))
			{
				Integer view = ResolverV1.integer(related.get("worldViewId")),
					plane = Boolean.TRUE.equals(ResolverV1.bool(related.get("planeKnown")))
						? ResolverV1.integer(related.get("plane"))
						: null;
				String relatedScene = scene != null && Objects.equals(scene.getWorldViewId(), view)
					&& Objects.equals(scene.getPlane(), plane) ? scene.getSceneKey() : null;
				JsonObject cue = interactionCue(event,
					source("npc", npcId, text(related, "name"), ResolverV1.integer(related.get("combatLevel")),
						event.getRelatedActorRef(), scope(relatedScene, view, plane)),
					option, cues);
				if (Boolean.TRUE.equals(ResolverV1.bool(related.get("normalWorldContextObserved"))))
					facts(cue, "source_event").addProperty("world_mode", "NORMAL");
			}
			String action = text(data, "menuAction");
			Integer requestId = ResolverV1.integer(data.get("identifier"));
			if (requestId != null && requestId >= 0 && action != null
				&& (action.startsWith("GAME_OBJECT_") || "WIDGET_TARGET_ON_GAME_OBJECT".equals(action)
					|| "ITEM_USE_ON_GAME_OBJECT".equals(action))
				&& engine.hasInteractionRule("object", requestId, option))
				interactionCue(event, source("object", requestId, null, null, null, scope(null, null, null)), option,
					cues);
		}
	}

	private JsonObject interactionCue(ObservedEvent event, JsonObject source, String option, JsonArray cues)
	{
		JsonObject cue = cue("input:" + event.getSequence() + ":" + text(source, "kind"), "interaction_damage", source,
			event.getTick(), event.getGameCycle(), event.getSequence());
		cue.addProperty("targetRef", "player");
		signal(cue, "interactionOptions", option);
		facts(cue, "interaction").addProperty("interaction_option", option);
		cue.addProperty("allowFallback", false);
		cues.add(cue);
		return cue;
	}

	/** Context is useful only when the actual source clock and native frame agree exactly. */
	private void originalFrameContext(JsonObject cue, Integer tick, Integer cycle, JsonObject sourceScope)
	{
		if (tick == null || cycle == null)
			return;
		WorldSnapshot selected = null;
		for (WorldSnapshot frame : frames.getOrDefault(tick, Collections.emptyList()))
			if (Boolean.TRUE.equals(frame.getNativeContextKnown()) && Boolean.TRUE.equals(frame.getGameCycleKnown())
				&& frame.getGameCycle() == cycle
				&& sameNative(sourceScope, scope(frame.getSceneKey(), frame.getWorldViewId(), frame.getPlane())))
			{
				if (selected != null)
					return;
				selected = frame;
			}
		if (selected != null && selected.getContext() != null)
		{
			if (selected.getContext().getActivity() != null)
				facts(cue, "source_event").addProperty("encounter_activity", selected.getContext().getActivity());
			String mode = selected.getContext().getChallengeMode(),
				evidence = selected.getContext().getEvidence() == null ? null
					: selected.getContext().getEvidence().get("challengeMode");
			if (Arrays.asList("NORMAL", "HARD", "QUEST").contains(mode) && evidence != null
				&& evidence.startsWith("OBSERVED_ACTIVE_") && !evidence.contains("UNESTABLISHED")
				&& !evidence.contains("UNAVAILABLE") && !evidence.contains("SELECTED"))
				facts(cue, "source_event").addProperty("encounter_mode", mode);
		}
	}

	private void graphicCue(ObservedEvent event, JsonObject data, JsonArray cues, boolean delayedOnly)
	{
		JsonObject graphic = object(data.get("graphic"));
		if (graphic.size() == 0)
			graphic = object(data.get("observedGraphicState"));
		if (graphic.size() == 0)
			graphic = data;
		Integer id = ResolverV1.integer(graphic.get("id"));
		if (id == null)
			id = ResolverV1.integer(graphic.get("graphicId"));
		if (id == null || id < 0)
			return;
		String sceneKey = text(graphic, "nativeSceneKey");
		if (sceneKey == null)
			sceneKey = event.getSceneKey();
		Integer view = ResolverV1.integer(graphic.get("worldViewId")), plane = ResolverV1.integer(graphic.get("plane"));
		JsonObject source = source("graphic", id, text(graphic, "mappedName"), null, null,
			scope(sceneKey, view, plane));
		for (String pattern : delayedOnly ? Collections.singletonList("delayed_effect")
			: Arrays.asList("area_impact", "contact_hazard", "delayed_effect"))
		{
			if (!engine.hasPattern("graphic", id, pattern) || cues.size() >= ResolverV1.MAX_CUES)
				continue;
			JsonObject cue = cue("graphic-created:" + event.getSequence(), pattern, source, event.getTick(),
				event.getGameCycle(), event.getSequence());
			signal(cue, "graphicIds", id);
			signal(cue, "lifecycleEvents", "graphic_spawn");
			cue.addProperty("allowFallback", false);
			SceneData scene = scenes.get(sceneKey);
			Integer localView = ResolverV1.integer(graphic.get("localWorldViewId")),
				lx = ResolverV1.integer(graphic.get("localX")), ly = ResolverV1.integer(graphic.get("localY"));
			if (scene != null && Boolean.TRUE.equals(ResolverV1.bool(graphic.get("scopeKnown")))
				&& Boolean.TRUE.equals(ResolverV1.bool(graphic.get("positionScopeKnown"))) && view != null
				&& view.equals(localView) && view.equals(scene.getWorldViewId())
				&& Objects.equals(plane, scene.getPlane()) && lx != null && ly != null && lx >= 0 && ly >= 0
				&& scene.getWidth() > 0 && scene.getWidth() <= 104 && scene.getHeight() > 0 && scene.getHeight() <= 104
				&& lx / 128 < scene.getWidth() && ly / 128 < scene.getHeight()
				&& !hasIssues(graphic, "localX", "localY", "worldView", "plane", "nativeSceneKey", "location"))
				cue.add("position", point(scene.getBaseX() + lx / 128, scene.getBaseY() + ly / 128));
			facts(cue, "source_event").addProperty("graphic_id", id);
			cues.add(cue);
		}
	}

	private JsonObject normalizedHitScope(DamageRecord hit)
	{
		return scope(hit.getImpactSceneKey(), hit.getImpactWorldViewId(), hit.getImpactPlane());
	}

	private void projectileCues(ProjectileState projectile, DamageRecord hit, JsonArray cues, String eventScene,
		int eventTick, Integer eventCycle, Long eventSequence, JsonObject normalizedHit)
	{
		if (projectile != null && projectile.getProjectileId() != null && projectileRoleReadable(projectile))
		{
			JsonObject source = source("projectile", projectile.getProjectileId(), null, null, null,
				scope(eventScene, projectile.getWorldViewId(),
					Boolean.TRUE.equals(projectile.getSamplePlaneKnown()) ? projectile.getPlane() : null));
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
		if (projectile == null || projectile.getSourceHistory() == null || projectile.getEndCycle() == null
			|| hit.getObservedCycle() == null || (long) projectile.getEndCycle() < ((long) hit.getObservedCycle() - 30)
			|| eventCycle == null || eventCycle > hit.getObservedCycle() || cues.size() >= ResolverV1.MAX_CUES)
			return;
		ProjectileSourceHistory.SourceObservation first = projectile.getSourceHistory().firstActor();
		boolean unstableHistory = projectile.getSourceHistory().isAttributionUnestablished()
			|| projectile.getSourceHistory().isDefinitionChanged()
			|| projectile.getSourceHistory().isReferenceChanged();
		if (first == null || !Boolean.TRUE.equals(first.getSourceActorKnown())
			|| !"ACTOR".equals(first.getSourceEvidence()) || first.getSourceRef() == null
			|| first.getSourceNpcId() == null || first.getObservedTick() == null || first.getObservedCycle() == null
			|| first.getObservedTick() > hit.getImpactTick() || first.getObservedCycle() < 0
			|| first.getObservedCycle() > hit.getObservedCycle() || first.getObservedTick() > eventTick
			|| eventCycle == null || first.getObservedCycle() > eventCycle)
			return;
		WorldSnapshot originalFrame = null;
		boolean ambiguousFrame = false;
		for (WorldSnapshot frame : frames.getOrDefault(first.getObservedTick(), Collections.emptyList()))
			if (Boolean.TRUE.equals(frame.getNativeContextKnown())
				&& Objects.equals(frame.getWorldViewId(), first.getSourceWorldViewId())
				&& Objects.equals(frame.getPlane(), first.getSourcePlane())
				&& Boolean.TRUE.equals(frame.getGameCycleKnown())
				&& Objects.equals(frame.getGameCycle(), first.getObservedCycle()))
			{
				if (originalFrame != null)
				{
					ambiguousFrame = true;
					break;
				}
				originalFrame = frame;
			}
		if (ambiguousFrame)
			originalFrame = null;
		NpcState sampled = null;
		boolean ambiguousNpc = false;
		if (originalFrame != null && originalFrame.getNpcs() != null)
			for (NpcState npc : originalFrame.getNpcs())
				if (npc != null && first.getSourceRef().equals(npc.getActorRef()))
				{
					if (sampled != null)
					{
						ambiguousNpc = true;
						break;
					}
					sampled = npc;
				}
		if (ambiguousNpc || sampled != null && (!Objects.equals(sampled.getWorldViewId(), first.getSourceWorldViewId())
			|| !Boolean.TRUE.equals(sampled.getPlaneKnown())
			|| !Objects.equals(sampled.getPlane(), first.getSourcePlane())))
			sampled = null;
		boolean exactSampleModel = sampled != null && Objects.equals(first.getSourceNpcId(), sampled.getNpcId())
			&& first.getSourceCombatLevel() != null && first.getSourceCombatLevel().equals(sampled.getCombatLevel())
			&& Boolean.TRUE.equals(first.getSourceNameKnown()) && first.getSourceObservedName() != null
			&& sampled.getName() != null
			&& ResolverV1.normalize(first.getSourceObservedName()).equals(ResolverV1.normalize(sampled.getName()))
			&& Boolean.FALSE.equals(sampled.getModelOverridesPresent());
		Boolean originalOverrides = first.getSourceModelOverridesPresent();
		if (originalOverrides == null && exactSampleModel && (first.getObservationIssues() == null
			|| !first.getObservationIssues().containsKey("source.modelOverrides")))
			originalOverrides = false;
		boolean originalModel = Boolean.FALSE.equals(originalOverrides) && first.getSourceCombatLevel() != null
			&& Boolean.TRUE.equals(first.getSourceNameKnown()) && first.getSourceObservedName() != null;
		String originalScene = first.getSourceSceneKey() != null ? first.getSourceSceneKey()
			: originalFrame == null ? null : originalFrame.getSceneKey();
		boolean identityOnly = originalScene == null || first.getSourceWorldViewId() == null
			|| first.getSourcePlane() == null || unstableHistory;
		JsonObject source = source("npc", first.getSourceNpcId(), first.getSourceObservedName(),
			first.getSourceCombatLevel(), first.getSourceRef(),
			scope(originalScene, first.getSourceWorldViewId(), first.getSourcePlane()));
		for (String pattern : Arrays.asList("projectile_impact", "area_impact"))
		{
			JsonObject cue = cue("projectile:" + projectile.getObservationId(), pattern, source,
				projectile.getEstimatedImpactTick(), projectile.getEndCycle(), eventSequence);
			cue.addProperty("apiSourceObserved", true);
			cue.addProperty("sourceObservedBeforeImpact",
				eventSequence != null && eventSequence > 0 && hit.getObservationSequence() != null
					&& eventSequence < hit.getObservationSequence()
					|| first.getObservedCycle() < hit.getObservedCycle());
			if (identityOnly)
				cue.addProperty("identityOnly", true);
			if (unstableHistory)
			{
				cue.addProperty("unstableOriginalSource", true);
				cue.addProperty("componentBound", false);
			}
			cue.addProperty("targetRef", projectile.getTargetRef());
			cue.addProperty("clockEvidence",
				projectile.getEndCycle() > hit.getObservedCycle() ? "HP_DURING_OBSERVED_FLIGHT"
					: "CLIENT_END_CYCLE_CORRELATION");
			signal(cue, "projectileIds", projectile.getProjectileId());
			facts(cue, "source_event").addProperty("projectile_id", projectile.getProjectileId());
			originalFrameContext(cue, first.getObservedTick(), first.getObservedCycle(), object(source.get("scope")));
			if (!identityOnly && Boolean.TRUE.equals(first.getSourceNormalWorldContextObserved()))
				facts(cue, "source_event").addProperty("world_mode", "NORMAL");
			if (originalOverrides != null)
				facts(cue, "source_event").addProperty("source_model_overrides", originalOverrides);
			if ("projectile_impact".equals(pattern) && "player".equals(projectile.getTargetRef())
				&& engine.ordinaryOnly(first.getSourceNpcId()))
			{
				cue.addProperty("ordinary", true);
			}
			if (identityOnly || !originalModel || !Boolean.TRUE.equals(first.getSourceNormalWorldContextObserved()))
				cue.addProperty("numericEligible", false);
			if (!identityOnly && Boolean.TRUE.equals(projectile.getDestinationKnown())
				&& projectile.getEndWorldX() != null && projectile.getEndWorldY() != null
				&& Objects.equals(projectile.getTargetWorldViewId(), first.getSourceWorldViewId())
				&& Objects.equals(projectile.getDestinationPlane(), first.getSourcePlane()))
				cue.add("endpoint", point(projectile.getEndWorldX(), projectile.getEndWorldY()));
			if ("area_impact".equals(pattern) || !"player".equals(projectile.getTargetRef()))
				cue.addProperty("allowFallback", false);
			cues.add(cue);
		}
	}

	private static boolean projectileRoleReadable(ProjectileState projectile)
	{
		if (projectile.getObservationIssues() == null)
			return true;
		for (String key : projectile.getObservationIssues().keySet())
			if (key == null || key.startsWith("projectileId") || key.startsWith("worldView") || key.startsWith("plane"))
				return false;
		return true;
	}

	private void retainedProximityCue(ProjectileState projectile, DamageRecord hit, JsonArray cues)
	{
		if (projectile == null || projectile.getSourceHistory() == null || !"player".equals(projectile.getTargetRef())
			|| hit.getSourceResolution() == null || hit.getSourceResolution().getCandidates() == null
			|| cues.size() >= ResolverV1.MAX_CUES)
			return;
		ProjectileSourceHistory.SourceObservation first = projectile.getSourceHistory().getFirstObservedSource();
		ProjectileSourceCandidate proxy = first == null ? null : first.getProximityCandidate();
		if (proxy == null || proxy.getNpcId() == null || proxy.getActorRef() == null || proxy.getSceneKey() == null
			|| proxy.getWorldViewId() == null || proxy.getPlane() == null || !engine.ordinaryOnly(proxy.getNpcId())
			|| Boolean.TRUE.equals(proxy.getModelOverridesPresent()) || first.getObservedTick() == null
			|| first.getObservedCycle() == null || first.getObservedTick() > hit.getImpactTick()
			|| hit.getObservedCycle() == null || first.getObservedCycle() > hit.getObservedCycle())
			return;
		boolean retained = hit.getSourceResolution().getCandidates().stream()
			.anyMatch(candidate -> proxy.getActorRef().equals(candidate.getActorRef())
				&& proxy.getNpcId().equals(candidate.getNpcId()) && proxy.getSceneKey().equals(candidate.getSceneKey())
				&& proxy.getWorldViewId().equals(candidate.getWorldViewId())
				&& proxy.getPlane().equals(candidate.getPlane()));
		if (!retained)
			return;
		JsonObject source = source("npc", proxy.getNpcId(), proxy.getName(), proxy.getCombatLevel(),
			proxy.getActorRef(), scope(proxy.getSceneKey(), proxy.getWorldViewId(), proxy.getPlane()));
		JsonObject cue = cue("original-proximity:" + projectile.getObservationId(), "projectile_impact", source,
			first.getObservedTick(), first.getObservedCycle(), null);
		cue.addProperty("targetRef", "player");
		cue.addProperty("ordinary", true);
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

	private JsonObject rawNpc(JsonObject raw, ObservedEvent event)
	{
		Integer id = ResolverV1.integer(raw.get("npcId"));
		if (id == null || id < 0 || event.getActorRef() == null || "player".equals(event.getActorRef()))
			return null;
		JsonObject scope = scope(event.getSceneKey(), ResolverV1.integer(raw.get("worldViewId")),
			Boolean.TRUE.equals(ResolverV1.bool(raw.get("planeKnown"))) ? ResolverV1.integer(raw.get("plane")) : null);
		if (hasIssues(raw, "worldView", "plane"))
			scope = scope(event.getSceneKey(), null, null);
		return source("npc", id, hasIssues(raw, "name") ? null : text(raw, "name"),
			hasIssues(raw, "combatLevel") ? null : ResolverV1.integer(raw.get("combatLevel")), event.getActorRef(),
			scope);
	}

	private JsonObject npc(NpcState npc, WorldSnapshot frame)
	{
		return source("npc", npc.getNpcId(), npc.getName(), npc.getCombatLevel(), npc.getActorRef(),
			scope(frame.getSceneKey(), npc.getWorldViewId(),
				Boolean.TRUE.equals(npc.getPlaneKnown()) ? npc.getPlane() : null));
	}

	/** Append data-derived evidence without changing raw measurements or actual dealt ownership. */
	public DeathRecordingV2 enrich(DeathRecordingV2 recording)
	{
		if (!engine.isAvailable() || recording.getEvents() == null || recording.getEvents().getDamageEvents() == null)
			return recording;
		List<DamageRecord> hits = new ArrayList<>();
		Map<Long, SourceResolution> resolutions = new LinkedHashMap<>();
		for (DamageRecord hit : recording.getEvents().getDamageEvents())
		{
			if (hit == null || !"player".equals(hit.getDefenderRef()) || "DEALT".equals(hit.getDirection())
				|| "NPC_RECEIVED".equals(hit.getDirection()))
			{
				hits.add(hit);
				continue;
			}
			JsonObject input = normalize(hit), result = engine.resolve(input);
			SourceResolution resolution = merge(hit.getSourceResolution(), result, input, hit);
			hits.add(hit.toBuilder().sourceResolution(resolution).build());
			if (hit.getObservationSequence() != null)
				resolutions.put(hit.getObservationSequence(), resolution);
		}
		List<TickData> journal = new ArrayList<>();
		if (recording.getJournalTicks() != null)
			for (TickData tick : recording.getJournalTicks())
			{
				List<DamageEvent> changes = new ArrayList<>();
				if (tick.getDamageEvents() != null)
					for (DamageEvent damage : tick.getDamageEvents())
						changes.add(damage.getDirection() != DamageEvent.DamageDirection.RECEIVED
							|| !resolutions.containsKey(damage.getObservationSequence()) ? damage
								: damage.toBuilder().sourceResolution(resolutions.get(damage.getObservationSequence()))
									.build());
				journal.add(tick.toBuilder().damageEvents(changes).build());
			}
		DeathRecordingV2.Summary summary = recording.getSummary();
		if (summary != null)
		{
			DamageRecord fatal = summary.getFatalHit();
			List<DamageRecord> fatalCandidates = new ArrayList<>();
			List<SourceEvidenceResolver.Hypothesis> possible = new ArrayList<>();
			List<SourceResolution.Candidate> excluded = new ArrayList<>();
			if (summary.getFatalHitCandidates() != null)
				for (DamageRecord candidate : summary.getFatalHitCandidates())
				{
					SourceResolution resolution = resolutions.get(candidate.getObservationSequence());
					DamageRecord updated = resolution == null ? candidate
						: candidate.toBuilder().sourceResolution(resolution).build();
					fatalCandidates.add(updated);
					append(possible, excluded, updated.getSourceResolution());
				}
			if (fatal != null && resolutions.containsKey(fatal.getObservationSequence()))
			{
				SourceResolution resolution = resolutions.get(fatal.getObservationSequence());
				if (fatalCandidates.isEmpty())
					append(possible, excluded, resolution);
				SourceResolution aggregate = SourceEvidenceResolver.resolve(possible, 0, true, engine.id()).toBuilder()
					.excludedCandidates(excluded).method("RECORDED_RESOLVER_V1_FATAL_ALTERNATIVES").build();
				summary = summary.toBuilder().fatalHit(fatal.toBuilder().sourceResolution(resolution).build())
					.fatalHitCandidates(summary.getFatalHitCandidates() == null ? null : fatalCandidates)
					.sourceResolution(aggregate).build();
				// Raw killerName/confidence/basis still describe what was known at death. The display
				// reads aggregate possible names separately; the last callback is not a proven killer.
			}
		}
		DeathRecordingV2.Events before = recording.getEvents();
		DeathRecordingV2.Events after = DeathRecordingV2.Events.builder().damageEvents(hits)
			.effectEvents(before.getEffectEvents()).playerActions(before.getPlayerActions()).deaths(before.getDeaths())
			.observations(before.getObservations()).observationLinks(before.getObservationLinks())
			.captureGaps(before.getCaptureGaps()).build();
		return recording.toBuilder().events(after).summary(summary)
			.journalTicks(recording.getJournalTicks() == null ? null : journal).build();
	}

	private SourceResolution merge(SourceResolution before, JsonObject result, JsonObject input, DamageRecord hit)
	{
		List<SourceResolution.Candidate> candidates = new ArrayList<>(), excluded = new ArrayList<>();
		if (before != null && before.getCandidates() != null)
			candidates.addAll(before.getCandidates());
		if (before != null && before.getExcludedCandidates() != null)
			excluded.addAll(before.getExcludedCandidates());
		Map<String, JsonObject> cues = new HashMap<>();
		for (JsonElement value : ResolverV1.array(input.get("cues")))
		{
			JsonObject cue = object(value);
			if (text(cue, "key") != null)
				cues.put(text(cue, "key"), cue);
		}
		// Only a linked, activity-only original hypothesis can be retired by a non-damage cue.
		// An independently observed projectile or an unlinked producer hypothesis is not erased.
		candidates.removeIf(candidate -> retireClassifiedActivity(candidate, result, cues)
			|| retireClassifiedProjectiles(candidate, result, cues, hit));
		// Replace only an ordinary-only source when every retained original cohort was excluded.
		// A viable/missing cohort or independent special component prevents actor-level removal.
		Set<String> impossible = new HashSet<>(), viable = new HashSet<>();
		for (JsonElement element : ResolverV1.array(result.get("candidates")))
		{
			JsonObject row = object(element), source = object(row.get("source"));
			String kind = text(source, "kind");
			Integer id = ResolverV1.integer(source.get("id"));
			if (id == null || "npc".equals(kind) && !engine.ordinaryOnly(id))
				continue;
			if (strings(row.get("reasons")).contains("NON_DAMAGING_ACTIVITY"))
			{
				viable.add(text(row, "sourceKey"));
				continue;
			}
			if ("EXCLUDED".equals(text(row, "status"))
				&& Boolean.TRUE.equals(ResolverV1.bool(row.get("identityQualified"))))
				impossible.add(text(row, "sourceKey"));
			else
				viable.add(text(row, "sourceKey"));
		}
		impossible.removeAll(viable);
		candidates.removeIf(candidate -> impossible.contains(candidateSourceKey(candidate)));
		for (JsonElement element : ResolverV1.array(result.get("candidates")))
		{
			JsonObject row = object(element), source = object(row.get("source")), scope = object(source.get("scope"));
			String name = text(source, "name");
			if (SourceResolutionLabels.usableName(name) == null && !"npc".equals(text(source, "kind")))
				name = text(row, "label");
			if (SourceResolutionLabels.usableName(name) == null)
				name = text(source, "kind") + " #" + ResolverV1.integer(source.get("id"));
			List<String> evidence = strings(row.get("evidence"));
			evidence.add("RESOLVER_V1:" + text(row, "ruleId"));
			evidence.add("RESOLVER_V1_RULESET:" + engine.id());
			if (before != null && before.getCatalogId() != null)
				evidence.add("PRIOR_REFERENCE_CATALOG:" + before.getCatalogId());
			if (row.has("maximum"))
				evidence.add("COMPONENT_MAXIMUM:" + row.get("maximum"));
			SourceResolution.Candidate candidate = SourceResolution.Candidate.builder().key(text(row, "key")).name(name)
				.actorRef(text(source, "actorRef"))
				.npcId("npc".equals(text(source, "kind")) ? ResolverV1.integer(source.get("id")) : null)
				.familyKey("status".equals(text(source, "kind")) && text(source, "statusFamily") != null
					? "observed-hit-kind:" + text(source, "statusFamily")
					: text(source, "actorRef") == null && ResolverV1.integer(source.get("id")) != null
						? text(source, "kind") + "-definition:" + ResolverV1.integer(source.get("id"))
						: text(source, "actorRef") == null ? text(row, "sourceKey") : null)
				.sceneKey(text(scope, "sceneKey")).worldViewId(ResolverV1.integer(scope.get("worldViewId")))
				.plane(ResolverV1.integer(scope.get("plane"))).strength("POSSIBLE").evidence(evidence)
				.reasons(strings(row.get("reasons"))).exclusionScope(text(row, "exclusionScope")).build();
			if ("EXCLUDED".equals(text(row, "status")))
				excluded.add(candidate);
			else if (currentWholeSourceExclusion(row, result, cues))
			{
				List<String> reasons = new ArrayList<>(candidate.getReasons());
				reasons.add("QUALIFIED_WHOLE_SOURCE_EXCLUSION");
				excluded.add(candidate.toBuilder().reasons(reasons).exclusionScope("ALL_DIRECT_HP_COMPONENTS").build());
			}
			else if (!priorExclusionApplies(before, candidate, row))
				candidates.add(candidate);
		}
		if (ResolverV1.array(result.get("candidates")).size() == 0)
			return before;
		if (candidates.stream().anyMatch(candidate -> !familyOnly(candidate)))
		{
			List<String> familyEvidence = new ArrayList<>();
			for (SourceResolution.Candidate candidate : candidates)
				if (familyOnly(candidate))
					familyEvidence.add(
						"OBSERVED_HIT_FAMILY:" + candidate.getFamilyKey().substring("observed-hit-kind:".length()));
			candidates.removeIf(ResolverV1Recording::familyOnly);
			if (!familyEvidence.isEmpty())
				for (int index = 0; index < candidates.size(); index++)
				{
					SourceResolution.Candidate candidate = candidates.get(index);
					List<String> evidence = new ArrayList<>();
					if (candidate.getEvidence() != null)
						evidence.addAll(candidate.getEvidence());
					evidence.addAll(familyEvidence);
					candidates.set(index, candidate.toBuilder().evidence(evidence).build());
				}
		}
		if (excluded.size() > 256)
			excluded = new ArrayList<>(excluded.subList(0, 256));
		List<SourceEvidenceResolver.Hypothesis> hypotheses = new ArrayList<>();
		for (SourceResolution.Candidate candidate : candidates)
			hypotheses.add(
				SourceEvidenceResolver.Hypothesis.builder().candidate(candidate).causalAssociationOnly(true).build());
		return SourceEvidenceResolver.resolve(hypotheses, 0, true, engine.id()).toBuilder().excludedCandidates(excluded)
			.method("RECORDED_RESOLVER_V1_COMPONENTS").build();
	}

	private static boolean familyOnly(SourceResolution.Candidate candidate)
	{
		return candidate.getActorRef() == null && candidate.getNpcId() == null && candidate.getFamilyKey() != null
			&& candidate.getFamilyKey().startsWith("observed-hit-kind:") && candidate.getEvidence() != null
			&& candidate.getEvidence().contains("OBSERVED_HIT_FAMILY_ONLY");
	}

	private static boolean currentWholeSourceExclusion(JsonObject row, JsonObject result, Map<String, JsonObject> cues)
	{
		JsonObject source = object(row.get("source"));
		if (!"npc".equals(text(source, "kind")) || text(source, "actorRef") == null
			|| Boolean.TRUE.equals(ResolverV1.bool(row.get("componentQualified"))))
		{
			return false;
		}
		for (JsonElement value : ResolverV1.array(result.get("candidates")))
		{
			JsonObject bound = object(value), boundSource = object(bound.get("source"));
			if (!"EXCLUDED".equals(text(bound, "status"))
				|| !"ALL_DIRECT_HP_COMPONENTS".equals(text(bound, "exclusionScope"))
				|| !Boolean.TRUE.equals(ResolverV1.bool(bound.get("identityQualified")))
				|| !Objects.equals(text(row, "sourceKey"), text(bound, "sourceKey"))
				|| !Objects.equals(source.get("id"), boundSource.get("id"))
				|| !sameNative(object(source.get("scope")), object(boundSource.get("scope"))))
			{
				continue;
			}
			boolean conflicting = false;
			for (String field : Arrays.asList("name", "combatLevel"))
			{
				if (source.has(field) && boundSource.has(field) && !source.get(field).isJsonNull()
					&& !boundSource.get(field).isJsonNull() && !source.get(field).equals(boundSource.get(field)))
				{
					conflicting = true;
				}
			}
			JsonObject cue = rowCue(row, cues), boundCue = rowCue(bound, cues);
			JsonObject facts = object(object(cue == null ? null : cue.get("facts")).get("source_event"));
			JsonObject boundFacts = object(object(boundCue == null ? null : boundCue.get("facts")).get("source_event"));
			for (String field : Arrays.asList("world_mode", "source_model_overrides", "encounter_activity",
				"encounter_mode"))
			{
				if (facts.has(field) && boundFacts.has(field) && !facts.get(field).isJsonNull()
					&& !boundFacts.get(field).isJsonNull() && !facts.get(field).equals(boundFacts.get(field)))
				{
					conflicting = true;
				}
			}
			if (!conflicting)
			{
				return true;
			}
		}
		return false;
	}

	private static boolean priorExclusionApplies(SourceResolution before, SourceResolution.Candidate candidate,
		JsonObject row)
	{
		if (before == null || before.getExcludedCandidates() == null || !scopedCandidate(candidate))
			return false;
		if (before.getCandidates() != null
			&& before.getCandidates().stream().anyMatch(c -> sameScopedSource(c, candidate)))
			return false;
		for (SourceResolution.Candidate previous : before.getExcludedCandidates())
		{
			if (!sameScopedSource(previous, candidate))
				continue;
			if ("ALL_DIRECT_HP_COMPONENTS".equals(previous.getExclusionScope()))
				return true;
			boolean independentPresence = ResolverV1.array(row.get("evidence"))
				.contains(new com.google.gson.JsonPrimitive("PASSIVE_SOURCE_PRESENCE_ONLY"));
			boolean independentSpecial = independentPresence
				|| Boolean.TRUE.equals(ResolverV1.bool(row.get("componentQualified")))
					&& !Boolean.TRUE.equals(ResolverV1.bool(row.get("ordinary")))
					&& !String.valueOf(text(row, "ruleId")).startsWith("observed:");
			if (!independentSpecial)
				return true;
		}
		return false;
	}

	private static boolean scopedCandidate(SourceResolution.Candidate candidate)
	{
		return candidate != null && candidate.getSceneKey() != null && candidate.getWorldViewId() != null
			&& candidate.getPlane() != null && (candidate.getNpcId() == null || candidate.getActorRef() != null);
	}

	private static boolean sameScopedSource(SourceResolution.Candidate a, SourceResolution.Candidate b)
	{
		return scopedCandidate(a) && scopedCandidate(b) && Objects.equals(a.getNpcId(), b.getNpcId())
			&& candidateSourceKey(a) != null && candidateSourceKey(a).equals(candidateSourceKey(b));
	}

	private static JsonObject rowCue(JsonObject row, Map<String, JsonObject> cues)
	{
		String key = text(row, "key"), rule = text(row, "ruleId"), suffix = ":" + rule;
		return key != null && rule != null && key.endsWith(suffix)
			? cues.get(key.substring(0, key.length() - suffix.length()))
			: null;
	}

	private static boolean retireClassifiedActivity(SourceResolution.Candidate candidate, JsonObject result,
		Map<String, JsonObject> cues)
	{
		if (!scopedCandidate(candidate) || candidate.getEvidence() == null)
			return false;
		boolean linked = false;
		for (String evidence : candidate.getEvidence())
		{
			if (evidence == null)
				return false;
			if (!evidence.startsWith("RECORDED_PRE_HIT_NPC_ACTIVITY;"))
			{
				if (evidence.startsWith("OBSERVED_LOCAL_RECIPIENT;")
					|| evidence.startsWith("HIT_CAUSALITY_UNESTABLISHED:") || evidence.startsWith("QUALIFIED_MAXIMUM:")
					|| evidence.startsWith("REFERENCE_MAXIMUM:") || evidence.startsWith("BOUND_")
					|| evidence.startsWith("HITSPLAT_SIGNATURE_UNESTABLISHED:")
					|| evidence.startsWith("SOURCE_IDENTITY_CONSENSUS;"))
					continue;
				return false;
			}
			boolean classified = false;
			for (JsonElement value : ResolverV1.array(result.get("candidates")))
			{
				JsonObject row = object(value), cue = rowCue(row, cues);
				if (cue == null || !decisiveCohortExclusion(row, result, cues)
					|| !Objects.equals(candidateSourceKey(candidate), text(row, "sourceKey"))
					|| !Objects.equals(candidate.getNpcId(), ResolverV1.integer(object(row.get("source")).get("id"))))
					continue;
				Integer tick = ResolverV1.integer(cue.get("tick"));
				Long sequence = cue.has("sequence") ? cue.get("sequence").getAsLong() : null;
				if (tick != null && sequence != null && evidence
					.startsWith("RECORDED_PRE_HIT_NPC_ACTIVITY;tick=" + tick + ";sequence=" + sequence + ";"))
					classified = true;
			}
			if (!classified)
				return false;
			linked = true;
		}
		return linked;
	}

	private static boolean decisiveCohortExclusion(JsonObject row, JsonObject result, Map<String, JsonObject> cues)
	{
		boolean classified = strings(row.get("reasons")).contains("NON_DAMAGING_ACTIVITY");
		if (!"EXCLUDED".equals(text(row, "status"))
			|| !classified && !"ALL_DIRECT_HP_COMPONENTS".equals(text(row, "exclusionScope"))
				&& !(Boolean.TRUE.equals(ResolverV1.bool(row.get("ordinary")))
					&& Boolean.TRUE.equals(ResolverV1.bool(row.get("componentQualified")))
					&& Boolean.TRUE.equals(ResolverV1.bool(row.get("identityQualified")))))
			return false;
		JsonObject cue = rowCue(row, cues);
		String cohort = physicalCue(cue);
		if (cohort == null)
			return false;
		for (JsonElement value : ResolverV1.array(result.get("candidates")))
		{
			JsonObject rival = object(value);
			if ("POSSIBLE".equals(text(rival, "status"))
				&& Objects.equals(text(row, "sourceKey"), text(rival, "sourceKey"))
				&& Objects.equals(ResolverV1.integer(object(row.get("source")).get("id")),
					ResolverV1.integer(object(rival.get("source")).get("id")))
				&& cohort.equals(physicalCue(rowCue(rival, cues))))
				return false;
		}
		return true;
	}

	private static String physicalCue(JsonObject cue)
	{
		if (cue == null)
			return null;
		String key = text(cue, "cohortKey");
		return key == null ? text(cue, "key") : key;
	}

	private boolean retireClassifiedProjectiles(SourceResolution.Candidate candidate, JsonObject result,
		Map<String, JsonObject> cues, DamageRecord hit)
	{
		if (!scopedCandidate(candidate) || candidate.getEvidence() == null || hit.getAttributionCandidates() == null
			|| !Objects.equals(candidate.getSceneKey(), hit.getImpactSceneKey())
			|| !Objects.equals(candidate.getWorldViewId(), hit.getImpactWorldViewId())
			|| !Objects.equals(candidate.getPlane(), hit.getImpactPlane()))
			return false;
		boolean supported = false;
		for (String evidence : candidate.getEvidence())
		{
			if (evidence == null)
				return false;
			if (evidence.startsWith("PROJECTILE_TIMING_CORRELATION")
				|| evidence.startsWith("PROJECTILE_RECORDED_SOURCE_ACTOR_AND_TIMING;"))
			{
				supported = true;
				continue;
			}
			if (evidence.startsWith("RECORDED_PROXIMITY_CANDIDATE;") || evidence.startsWith("ORIGIN_NATIVE_VIEW_")
				|| evidence.startsWith("RETAINED_SOURCE_HISTORY_")
				|| evidence.startsWith("ORIGINAL_SOURCE_MODEL_UNQUALIFIED;")
				|| evidence.startsWith("HIT_CAUSALITY_UNESTABLISHED:") || evidence.startsWith("QUALIFIED_MAXIMUM:")
				|| evidence.startsWith("REFERENCE_MAXIMUM:") || evidence.startsWith("BOUND_")
				|| evidence.startsWith("HITSPLAT_SIGNATURE_UNESTABLISHED:")
				|| evidence.startsWith("SOURCE_IDENTITY_CONSENSUS;"))
				continue;
			return false;
		}
		if (!supported)
			return false;
		boolean linked = false;
		for (Map<String, Object> raw : hit.getAttributionCandidates())
		{
			if (raw == null || !(raw.get("kind") instanceof String)
				|| !((String) raw.get("kind")).startsWith("PROJECTILE_"))
				continue;
			if (!projectileSourceMatches(candidate, raw))
				continue;
			Long instance = longNumber(raw.get("projectileObservationId"));
			Integer id = intNumber(raw.get("projectileId"));
			if (instance == null || id == null)
				return false;
			boolean classified = false;
			for (JsonElement value : ResolverV1.array(result.get("candidates")))
			{
				JsonObject row = object(value), cue = rowCue(row, cues);
				if (cue != null && ("projectile-role:" + instance).equals(physicalCue(cue))
					&& strings(row.get("reasons")).contains("NON_DAMAGING_ACTIVITY")
					&& id.equals(ResolverV1.integer(object(cue.get("source")).get("id"))))
					classified = true;
				if (cue != null && ("projectile:" + instance).equals(physicalCue(cue))
					&& decisiveCohortExclusion(row, result, cues)
					&& Objects.equals(candidateSourceKey(candidate), text(row, "sourceKey"))
					&& Objects.equals(candidate.getNpcId(), ResolverV1.integer(object(row.get("source")).get("id"))))
					classified = true;
			}
			if (!classified)
				return false;
			linked = true;
		}
		return linked;
	}

	private boolean projectileSourceMatches(SourceResolution.Candidate candidate, Map<String, Object> raw)
	{
		if (Objects.equals(candidate.getActorRef(), raw.get("actorRef")) && candidate.getNpcId() != null
			&& candidate.getNpcId().equals(intNumber(raw.get("npcId"))))
			return true;
		JsonObject history = object(gson.toJsonTree(raw.get("sourceHistory"))),
			first = object(history.get("firstObservedSource"));
		JsonObject proxy = object(first.get("proximityCandidate"));
		if (!Boolean.TRUE.equals(ResolverV1.bool(first.get("proximitySampleRetained"))))
			return false;
		return Objects.equals(candidate.getActorRef(), text(proxy, "actorRef")) && candidate.getNpcId() != null
			&& candidate.getNpcId().equals(ResolverV1.integer(proxy.get("npcId")))
			&& Objects.equals(candidate.getSceneKey(), text(proxy, "sceneKey"))
			&& Objects.equals(candidate.getWorldViewId(), ResolverV1.integer(proxy.get("worldViewId")))
			&& Objects.equals(candidate.getPlane(), ResolverV1.integer(proxy.get("plane")));
	}

	private Integer observedType(DamageRecord hit)
	{
		if (Boolean.FALSE.equals(hit.getHitsplatTypeKnown()))
			return null;
		if (Boolean.TRUE.equals(hit.getHitsplatTypeKnown()))
			return hit.getHitsplatType();
		ObservedEvent event = original.get(hit.getObservationSequence());
		if (event == null || !"HITSPLAT".equals(event.getType()) || !"player".equals(event.getActorRef())
			|| event.getTick() != hit.getImpactTick() || !Objects.equals(event.getGameCycle(), hit.getObservedCycle())
			|| !Objects.equals(event.getSceneKey(), hit.getImpactSceneKey()) || event.getData() == null
			|| !Integer.valueOf(hit.getAmount()).equals(intNumber(event.getData().get("amount"))))
			return null;
		Object issues = event.getData().get("captureIssues");
		if (issues instanceof List)
			for (Object issue : (List<?>) issues)
				if (issue instanceof String
					&& (((String) issue).startsWith("hitsplatType") || ((String) issue).startsWith("hitsplat.type")))
					return null;
		return intNumber(event.getData().get("hitsplatType"));
	}

	private static String family(DamageRecord hit, Integer type)
	{
		if (type == null)
			return null;
		if (type == HitsplatID.POISON)
			return "poison";
		if (type == HitsplatID.VENOM)
			return "venom";
		if (type == HitsplatID.BURN)
			return "burn";
		if (type == HitsplatID.BLEED)
			return "bleed";
		if ("HITSPLAT_TYPE".equals(hit.getDamageTypeEvidence())
			&& Arrays.asList("poison", "venom", "burn", "bleed").contains(hit.getDamageType()))
			return hit.getDamageType();
		return Arrays.asList(HitsplatID.DAMAGE_ME, HitsplatID.DAMAGE_OTHER, HitsplatID.DAMAGE_MAX_ME,
			HitsplatID.BLOCK_ME, HitsplatID.BLOCK_OTHER).contains(type) ? "ordinary" : null;
	}

	private static void append(List<SourceEvidenceResolver.Hypothesis> into, List<SourceResolution.Candidate> excluded,
		SourceResolution resolution)
	{
		if (resolution == null)
			return;
		if (resolution.getCandidates() != null)
			for (SourceResolution.Candidate candidate : resolution.getCandidates())
				into.add(SourceEvidenceResolver.Hypothesis.builder().candidate(candidate).causalAssociationOnly(true)
					.build());
		if (resolution.getExcludedCandidates() != null)
			excluded.addAll(resolution.getExcludedCandidates());
	}

	private static String candidateSourceKey(SourceResolution.Candidate candidate)
	{
		String kind = "npc";
		Integer id = candidate.getNpcId();
		if (id == null && candidate.getFamilyKey() != null)
		{
			java.util.regex.Matcher match = java.util.regex.Pattern
				.compile("^(object|graphic|item)-definition:([0-9]+)$").matcher(candidate.getFamilyKey());
			if (!match.matches())
				return null;
			kind = match.group(1);
			try
			{
				id = Integer.valueOf(match.group(2));
			}
			catch (NumberFormatException invalid)
			{
				return null;
			}
		}
		return id == null ? null
			: ResolverV1.sourceKey(source(kind, id, candidate.getName(), null, candidate.getActorRef(),
				scope(candidate.getSceneKey(), candidate.getWorldViewId(), candidate.getPlane())));
	}

	private static JsonObject source(String kind, Integer id, String name, Integer level, String ref, JsonObject scope)
	{
		JsonObject out = new JsonObject();
		out.addProperty("kind", kind);
		out.addProperty("id", id);
		if (name != null)
			out.addProperty("name", name);
		if (level != null)
			out.addProperty("combatLevel", level);
		if (ref != null)
			out.addProperty("actorRef", ref);
		out.add("scope", scope);
		return out;
	}

	private static JsonObject scope(String scene, Integer view, Integer plane)
	{
		JsonObject out = new JsonObject();
		if (scene != null)
			out.addProperty("sceneKey", scene);
		if (view != null && view >= 0)
			out.addProperty("worldViewId", view);
		if (plane != null && plane >= 0 && plane <= 3)
			out.addProperty("plane", plane);
		return out;
	}

	private static JsonObject point(Integer x, Integer y)
	{
		JsonObject out = new JsonObject();
		if (x != null)
			out.addProperty("x", x);
		if (y != null)
			out.addProperty("y", y);
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
			out.addProperty("tick", tick);
		if (cycle != null)
			out.addProperty("cycle", cycle);
		if (sequence != null)
			out.addProperty("sequence", sequence);
		out.add("facts", new JsonObject());
		out.add("signals", new JsonObject());
		return out;
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
			return;
		JsonObject signals = object(cue.get("signals"));
		cue.add("signals", signals);
		JsonArray array = ResolverV1.array(signals.get(key));
		signals.add(key, array);
		array.add(gson.toJsonTree(value));
	}

	private static List<String> strings(JsonElement value)
	{
		List<String> result = new ArrayList<>();
		for (JsonElement element : ResolverV1.array(value))
			if (element.isJsonPrimitive())
				result.add(element.getAsString());
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
			for (String field : fields)
				if (key.startsWith(field))
					return true;
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
