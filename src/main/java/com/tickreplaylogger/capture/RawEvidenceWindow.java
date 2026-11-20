/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import com.tickreplaylogger.model.world.*;
import java.util.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import static com.tickreplaylogger.capture.ResolverEvidence.*;

/** Indexes detached original observations. It neither selects mechanics nor creates evidence facts. */
final class RawEvidenceWindow
{
	private final ResolverV1Recording.EvidenceWindow window;
	private final Gson gson;
	private final Map<Object, JsonObject> payloads = new IdentityHashMap<>();
	private final Map<Long, ObservedEvent> original = new LinkedHashMap<>();
	private final Map<Integer, List<WorldSnapshot>> frames = new HashMap<>();
	private final Map<String, SceneData> scenes = new HashMap<>();
	private final Set<String> ambiguousSceneScopes = new HashSet<>();
	private final Map<String, List<ProjectileSample>> projectileSamples = new LinkedHashMap<>();
	private final Map<String, Receipt> launches = new HashMap<>();
	private final List<Receipt> lifecycles = new ArrayList<>();
	final List<DamageRecord> records = new ArrayList<>();
	private int untrackedSamples;

	@Getter
	@Accessors(fluent = true)
	@RequiredArgsConstructor
	private static final class Observation implements Source
	{
		private final Channel channel;
		private final String key;
		private final Identity identity;
		private final Receipt receipt, sample, related, launch;
		private final JsonObject raw, actor, first;
		private final WorldSnapshot frame;
	}

	@RequiredArgsConstructor
	private static final class ProjectileSample
	{
		final Source source;
		final WorldSnapshot sampleFrame;
	}

	RawEvidenceWindow(ResolverV1Recording.EvidenceWindow window, Gson gson)
	{
		this.window = window;
		this.gson = gson;
		Set<Long> seen = new HashSet<>();
		for (ObservedEvent event : values(window.getObservations()))
		{
			if (event != null && event.getSequence() > 0)
			{
				if (seen.add(event.getSequence()))
				{
					original.put(event.getSequence(), event);
				}
				else
				{
					original.remove(event.getSequence());
				}
			}
		}
		for (SceneData scene : window.getScenes())
		{
			register(scene);
		}
		for (WorldSnapshot frame : window.getTicks())
		{
			index(frame);
		}
		for (ObservedEvent event : original.values())
		{
			Receipt receipt = receipt(event);
			lifecycles.add(receipt);
			JsonObject raw = receipt.raw;
			if (event.getType() != null && event.getType().startsWith("PROJECTILE_") && raw.has("projectile"))
			{
				projectile(object(raw.get("projectile")), receipt, null, raw);
			}
		}
		records.addAll(window.getHits());
		for (ObservedEvent event : original.values())
		{
			if (!"HITSPLAT".equals(event.getType()) || event.getData() == null)
			{
				continue;
			}
			Object context = event.getData().get("attributionContext");
			if (context == null)
			{
				continue;
			}
			DamageRecord hit = context instanceof DamageRecord ? (DamageRecord) context
				: gson.fromJson(json(context), DamageRecord.class);
			if (hit == null || !"RESOURCE_IMPACT".equals(hit.getDirection()) || !"player".equals(hit.getDefenderRef())
				|| !Objects.equals(hit.getObservationSequence(), event.getSequence())
				|| hit.getImpactTick() != event.getTick()
				|| !Objects.equals(hit.getObservedCycle(), event.getGameCycle()))
			{
				continue;
			}
			boolean present = records.stream().anyMatch(prior -> prior.sameNativeCallback(hit));
			if (!present)
			{
				records.add(hit.toBuilder()
					.attributionCandidates(event.getData().containsKey("attributionCandidates")
						? maps(event.getData().get("attributionCandidates"))
						: hit.getAttributionCandidates())
					.observedHazardContacts(event.getData().containsKey("observedHazardContacts")
						? maps(event.getData().get("observedHazardContacts"))
						: hit.getObservedHazardContacts())
					.build());
			}
		}
		for (DamageRecord hit : records)
		{
			for (Map<String, Object> fields : values(hit.getAttributionCandidates()))
			{
				JsonObject witness = json(fields);
				if (!"PROJECTILE_OBSERVATION".equals(text(witness, "kind")))
				{
					continue;
				}
				JsonObject state = object(witness.get("projectile"));
				if (state.size() == 0)
				{
					state = object(witness.get("originalProjectileObservation"));
				}
				Scope scope = scope(state, text(witness, "sceneKey"),
					longValue(object(state.get("sourceHistory")).get("contextEpoch")));
				Receipt sample = new Receipt(null,
					new Clock(integer(state.get("observedTick")), integer(state.get("observedCycle")), null), scope,
					state);
				projectile(state, sample, null, witness);
			}
		}
	}

	private void index(WorldSnapshot frame)
	{
		if (frame == null)
		{
			return;
		}
		frames.computeIfAbsent(frame.getTick(), key -> new ArrayList<>()).add(frame);
		if (frame.getNativeScene() != null)
		{
			register(frame.getNativeScene());
		}
		for (ProjectileState projectile : values(frame.getProjectiles()))
		{
			if (projectile != null)
			{
				projectile(json(projectile), frameReceipt(frame, json(projectile)), frame, null);
			}
		}
		for (WorldSnapshot additional : values(frame.getAdditionalViews()))
		{
			index(additional);
		}
	}

	private void register(SceneData scene)
	{
		if (scene.getSceneKey() == null)
		{
			return;
		}
		SceneData prior = scenes.put(scene.getSceneKey(), scene);
		if (prior != null && prior.getWorldViewId() != null && scene.getWorldViewId() != null
			&& (!Objects.equals(prior.getWorldViewId(), scene.getWorldViewId())
				|| prior.getPlane() != scene.getPlane()))
		{
			ambiguousSceneScopes.add(scene.getSceneKey());
		}
	}

	private JsonObject json(Object value)
	{
		if (value == null)
		{
			return new JsonObject();
		}
		if (value instanceof JsonObject)
		{
			return (JsonObject) value;
		}
		return payloads.computeIfAbsent(value, key -> object(gson.toJsonTree(key)));
	}

	private Receipt receipt(ObservedEvent event)
	{
		if (event == null)
		{
			return null;
		}
		JsonObject raw = json(event.getData()), actor = actor(raw);
		Scope scope = scope(actor, event.getSceneKey(), longValue(raw.get("causalSceneEpoch")));
		if ("GRAPHICS_OBJECT_CREATED".equals(event.getType()) && Boolean.TRUE.equals(bool(raw.get("scopeKnown")))
			&& Boolean.TRUE.equals(bool(raw.get("positionScopeKnown"))))
		{
			String ownScene = text(raw, "nativeSceneKey");
			Integer ownView = integer(raw.get("worldViewId")), ownPlane = integer(raw.get("plane"));
			if (ownScene != null && !ownScene.isEmpty() && ownView != null && ownPlane != null && ownPlane >= 0
				&& ownPlane <= 3 && ownView.equals(integer(raw.get("localWorldViewId"))))
			{
				SceneData own = scenes.get(ownScene);
				boolean conflict = ambiguousSceneScopes.contains(ownScene)
					|| own != null && (own.getWorldViewId() != null && !ownView.equals(own.getWorldViewId())
						|| own.getPlane() != ownPlane);
				scope = new Scope(conflict ? null : ownScene, ownView, ownPlane, scope.epoch);
			}
		}
		SceneData registered = scenes.get(event.getSceneKey());
		if ("SYSTEM_MESSAGE".equals(event.getType()) && !raw.has("actorState")
			&& !ambiguousSceneScopes.contains(event.getSceneKey()) && registered != null
			&& registered.getWorldViewId() != null && registered.getPlane() >= 0 && registered.getPlane() <= 3
			&& registered.getWidth() > 0 && registered.getHeight() > 0)
		{
			scope = new Scope(scope.sceneKey,
				scope.worldViewId == null ? registered.getWorldViewId() : scope.worldViewId,
				scope.plane == null ? registered.getPlane() : scope.plane, scope.epoch);
		}
		return new Receipt(event, new Clock(event.getTick(), event.getGameCycle(), event.getSequence()), scope, raw);
	}

	private Receipt frameReceipt(WorldSnapshot frame, JsonObject raw)
	{
		return new Receipt(
			null, new Clock(frame.getTick(),
				Boolean.TRUE.equals(frame.getGameCycleKnown()) ? frame.getGameCycle() : null, null),
			frameScope(frame), raw);
	}

	private static Scope frameScope(WorldSnapshot frame)
	{
		return new Scope(frame.getSceneKey(), frame.getWorldViewId(),
			Boolean.TRUE.equals(frame.getNativeContextKnown()) ? frame.getPlane() : null, null);
	}

	private static Scope scope(JsonObject raw, String scene, Long epoch)
	{
		JsonObject issues = object(raw.get("observationIssues")), availability = object(raw.get("fieldAvailability"));
		return new Scope(scene != null ? scene : text(raw, "sceneKey"),
			issues.has("worldView") || Boolean.FALSE.equals(bool(availability.get("worldViewId"))) ? null
				: integer(raw.get("worldViewId")),
			Boolean.FALSE.equals(bool(raw.get("planeKnown"))) || issues.has("plane")
				|| Boolean.FALSE.equals(bool(availability.get("plane"))) ? null : integer(raw.get("plane")),
			epoch);
	}

	private static JsonObject actor(JsonObject raw)
	{
		if (raw.get("originalActorState") instanceof JsonObject)
		{
			return object(raw.get("originalActorState"));
		}
		if (raw.get("actorState") instanceof JsonObject)
		{
			return object(raw.get("actorState"));
		}
		return raw;
	}

	private static Identity actorIdentity(JsonObject raw, String ref, String key, Scope scope, String fallbackKind)
	{
		String kind = text(raw, "actorKind"), name = text(raw, "name");
		if ("LOCAL_PLAYER".equals(kind))
		{
			return null;
		}
		kind = "PLAYER".equals(kind) || "OTHER_PLAYER".equals(kind) ? "player"
			: "NPC".equals(kind) ? "npc" : fallbackKind;
		Integer id = "npc".equals(kind) ? integer(raw.get("npcId")) : null;
		if (id != null && id < 0)
		{
			id = null;
		}
		if (object(raw.get("observationIssues")).has("name"))
		{
			name = null;
		}
		return new Identity(kind, id, ref, key, name, integer(raw.get("combatLevel")), scope);
	}

	private void projectile(JsonObject raw, Receipt sample, WorldSnapshot frame, JsonObject launch)
	{
		JsonObject history = object(raw.get("sourceHistory"));
		sample = new Receipt(sample.event, sample.clock,
			new Scope(sample.scope == null ? null : sample.scope.sceneKey, integer(raw.get("worldViewId")),
				Boolean.TRUE.equals(bool(raw.get("samplePlaneKnown"))) ? integer(raw.get("plane")) : null,
				longValue(history.get("contextEpoch"))),
			raw);
		JsonObject first = object(history.get("firstActorSource"));
		if (first.size() == 0)
		{
			JsonObject observed = object(history.get("firstObservedSource"));
			if ("ACTOR".equals(text(observed, "sourceEvidence")))
			{
				first = observed;
			}
		}
		Long instance = longValue(raw.get("observationId"));
		String key = instance == null
			? "projectile-sample:" + sample.clock.tick + ":" + sample.clock.cycle + ":" + sample.clock.sequence + ":"
				+ ++untrackedSamples
			: "projectile:" + instance;
		JsonObject originalLaunch = launch == null ? new JsonObject()
			: object(launch.get("originalProjectileObservation"));
		Receipt launchReceipt = launch == null ? null
			: new Receipt(sample.event,
				new Clock(integer(launch.get("firstSeenTick")), integer(originalLaunch.get("observedCycle")),
					sample.event == null ? null : sample.event.getSequence()),
				new Scope(text(launch, "launchSceneKey"), integer(launch.get("launchWorldViewId")),
					integer(launch.get("launchPlane")),
					longValue(object(originalLaunch.get("sourceHistory")).get("contextEpoch"))),
				launch);
		if (launchReceipt != null && launchReceipt.clock.tick != null && launch.has("originalProjectileObservation"))
		{
			Receipt old = launches.get(key);
			if (old == null || earlier(launchReceipt.clock, old.clock))
			{
				launches.put(key, launchReceipt);
			}
		}
		Scope ownerScope = first.size() == 0 ? sample.scope
			: new Scope(text(first, "sourceSceneKey"), integer(first.get("sourceWorldViewId")),
				integer(first.get("sourcePlane")), longValue(first.get("contextEpoch")));
		String actorKind = text(first, "actorKind");
		String kind = first.size() == 0 ? "projectile"
			: "NPC".equals(actorKind) ? "npc"
				: Arrays.asList("PLAYER", "OTHER_PLAYER", "LOCAL_PLAYER").contains(actorKind) ? "player" : "actor";
		Integer id = "npc".equals(kind) ? integer(first.get("sourceNpcId"))
			: "projectile".equals(kind) ? integer(raw.get("projectileId")) : null;
		if (id != null && id < 0)
		{
			id = null;
		}
		String name = text(first, "sourceObservedName");
		if (name == null)
		{
			name = text(first, "sourceName");
		}
		if (!Boolean.TRUE.equals(bool(first.get("sourceNameKnown"))))
		{
			name = null;
		}
		Identity identity = new Identity(kind, id, text(first, "sourceRef"), key, name,
			integer(first.get("sourceCombatLevel")), ownerScope);
		Receipt owner = first.size() == 0 ? sample
			: new Receipt(null,
				new Clock(integer(first.get("observedTick")), integer(first.get("observedCycle")), null), ownerScope,
				first);
		Source source = new Observation(Channel.PROJECTILE, key, identity, owner, sample, null, launchReceipt, raw,
			new JsonObject(), first, null);
		projectileSamples.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new ProjectileSample(source, frame));
	}

	Hit hit(DamageRecord record)
	{
		return new Impact(record);
	}

	private final class Impact implements Hit
	{
		private final DamageRecord record;
		private final Receipt callback;
		private final List<Source> sources = new ArrayList<>();
		private final WorldSnapshot impact;

		Impact(DamageRecord record)
		{
			this.record = record;
			callback = hp(record);
			impact = changed(record) ? null
				: unique(new Clock(record.getImpactTick(), null, null),
					new Scope(record.getImpactSceneKey(), record.getImpactWorldViewId(), record.getImpactPlane(), null),
					false);
			Set<JsonObject> activityPackets = new HashSet<>();
			for (Map<String, Object> raw : values(record.getObservedHazardContacts()))
			{
				JsonObject witness = json(raw);
				String witnessKind = text(witness, "kind");
				if ("NPC_ACTIVITY_OBSERVATION".equals(witnessKind) || "PLAYER_ACTIVITY_OBSERVATION".equals(witnessKind))
				{
					if (activityPackets.add(witness))
					{
						witness(witness);
					}
					continue;
				}
				if (!Arrays.asList("NPC_CONTACT", "NPC_EMITTER", "GRAPHIC_SURFACE", "OBJECT_SURFACE")
					.contains(witnessKind))
				{
					continue;
				}
				Channel channel = "NPC_EMITTER".equals(text(witness, "kind")) ? Channel.NPC_EMITTER
					: Channel.HP_CONTACT;
				JsonObject actor = actor(witness);
				String kind = text(witness, "kind");
				String namespace = kind != null && kind.startsWith("NPC") ? "npc"
					: "GRAPHIC_SURFACE".equals(kind) ? "graphic" : "object";
				Integer id = integer(witness.get(namespace + "Id"));
				Clock clock = new Clock(integer(witness.get("observedTick")), integer(witness.get("observedCycle")),
					longValue(witness.get("impactSequence")));
				boolean originalHpSample = callback != null
					&& "HITSPLAT_CALLBACK".equals(text(witness, "observationStage"))
					&& Objects.equals(clock.cycle, record.getObservedCycle());
				Scope scope = scope(witness, null, originalHpSample ? callback.scope.epoch : null);
				if (originalHpSample)
				{
					clock = new Clock(clock.tick == null ? record.getImpactTick() : clock.tick, clock.cycle,
						clock.sequence == null ? record.getObservationSequence() : clock.sequence);
				}
				Receipt sample = new Receipt(null, clock, scope, witness);
				String key = "contact:" + record.getObservationSequence() + ":" + sources.size();
				Identity identity = new Identity(namespace, id, text(witness, "actorRef"), key,
					text(witness, "observedName") != null ? text(witness, "observedName") : text(witness, "name"),
					integer(witness.get("combatLevel")), scope);
				sources.add(new Observation(channel, key, identity, sample, sample, callback, null, witness, actor,
					new JsonObject(), null));
			}
			for (Map<String, Object> fields : values(record.getAttributionCandidates()))
			{
				JsonObject witness = json(fields);
				String kind = text(witness, "kind");
				if (("NPC_ACTIVITY_OBSERVATION".equals(kind) || "PLAYER_ACTIVITY_OBSERVATION".equals(kind))
					&& !activityPackets.add(witness))
				{
					continue;
				}
				witness(witness);
			}
			for (ObservedEvent event : original.values())
			{
				event(event);
			}
			for (DamageRecord peer : records)
			{
				if (peer.getImpactTick() != record.getImpactTick()
					|| !("DEALT".equals(peer.getDirection()) || "NPC_RECEIVED".equals(peer.getDirection())))
				{
					continue;
				}
				Receipt trigger = hp(peer);
				if (trigger == null)
				{
					continue;
				}
				JsonObject actor = actor(trigger.raw);
				add(Channel.OUTGOING_TRIGGER, json(peer), actor, trigger, null, actorIdentity(actor,
					peer.getDefenderRef(), "outgoing:" + peer.getObservationSequence(), trigger.scope, "npc"));
			}
			for (List<ProjectileSample> samples : projectileSamples.values())
			{
				ProjectileSample selected = null;
				boolean ambiguous = false;
				for (ProjectileSample candidate : samples)
				{
					Source source = candidate.source;
					if (!before(source.sample(), record, source.sample().event == null))
					{
						continue;
					}
					int comparison = selected == null ? 1
						: compare(source.sample().clock, selected.source.sample().clock);
					if (comparison > 0
						|| comparison == 0 && source.sample().event != null && selected.source.sample().event == null)
					{
						selected = candidate;
						ambiguous = false;
					}
					else if (comparison == 0 && selected.sampleFrame != null && candidate.sampleFrame != null)
					{
						ambiguous = true;
					}
				}
				if (selected == null || ambiguous)
				{
					continue;
				}
				Source chosen = selected.source;
				Receipt owner = chosen.receipt();
				if (owner != null)
				{
					Receipt firstEvent = null;
					for (ProjectileSample candidate : samples)
					{
						Source sample = candidate.source;
						Receipt packet = sample.sample();
						if (packet.event != null && Objects.equals(packet.clock.tick, owner.clock.tick)
							&& Objects.equals(packet.clock.cycle, owner.clock.cycle)
							&& sample.first().equals(chosen.first())
							&& (firstEvent == null || packet.clock.sequence < firstEvent.clock.sequence))
						{
							firstEvent = packet;
						}
					}
					if (firstEvent != null)
					{
						owner = new Receipt(firstEvent.event,
							new Clock(owner.clock.tick, owner.clock.cycle, firstEvent.clock.sequence), owner.scope,
							owner.raw);
					}
				}
				sources.add(new Observation(chosen.channel(), chosen.key(), chosen.identity(), owner, chosen.sample(),
					callback, launches.get(chosen.key()), chosen.raw(), chosen.actor(), chosen.first(),
					owner == null ? null : exactFrame(owner.clock, owner.scope)));
				JsonObject firstObserved = object(object(chosen.raw().get("sourceHistory")).get("firstObservedSource"));
				JsonObject proxy = object(firstObserved.get("proximityCandidate"));
				if (proxy.size() > 0)
				{
					Scope scope = scope(proxy, null, longValue(firstObserved.get("contextEpoch")));
					sources.add(new Observation(Channel.PROJECTILE_ORIGIN, chosen.key() + ":origin",
						actorIdentity(proxy, text(proxy, "actorRef"), chosen.key(), scope, "npc"),
						new Receipt(null,
							new Clock(integer(firstObserved.get("observedTick")),
								integer(firstObserved.get("observedCycle")), null),
							scope, firstObserved),
						chosen.sample(), callback, launches.get(chosen.key()), chosen.raw(), proxy, firstObserved,
						null));
				}
			}
			for (WorldSnapshot frame : frames.getOrDefault(record.getImpactTick(), Collections.emptyList()))
			{
				if (changed(record))
				{
					break;
				}
				Receipt sample = frameReceipt(frame, new JsonObject());
				for (NpcState npc : values(frame.getNpcs()))
				{
					if (npc == null)
					{
						continue;
					}
					JsonObject raw = json(npc);
					add(Channel.FRAME_NPC, raw, raw, sample, frame,
						actorIdentity(raw, npc.getActorRef(), "frame-npc:" + frame.getTick() + ":" + sources.size(),
							scope(raw, frame.getSceneKey(), null), "npc"));
				}
				for (HazardState hazard : values(frame.getHazards()))
				{
					if (hazard == null)
					{
						continue;
					}
					JsonObject raw = json(hazard);
					String kind = "graphicsObject".equals(hazard.getKind()) ? "graphic" : "object";
					add(Channel.FRAME_SURFACE, raw, new JsonObject(), sample, frame,
						new Identity(kind, hazard.getId(), null, "frame-surface:" + sources.size(),
							hazard.getMappedName(), null, scope(raw, frame.getSceneKey(), null)));
				}
			}
			for (int delay = 0; delay <= 1; delay++)
			{
				for (WorldSnapshot frame : frames.getOrDefault(record.getImpactTick() - delay, Collections.emptyList()))
				{
					Receipt sample = frameReceipt(frame, new JsonObject());
					for (ObservedStatusState status : values(frame.getLocalStatuses()))
					{
						if (status == null)
						{
							continue;
						}
						JsonObject raw = json(status);
						add(Channel.STATUS, raw, new JsonObject(), sample, frame,
							new Identity("status", null, null, status.getKey(), status.getLabel(), null, sample.scope));
					}
				}
			}
			if (callback != null)
			{
				add(Channel.TYPED_STATUS, callback.raw, actor(callback.raw), callback, null, new Identity("status",
					null, null, "typed-hit:" + record.getObservationSequence(), null, null, callback.scope));
			}
		}

		private void add(Channel channel, JsonObject raw, JsonObject actor, Receipt receipt, WorldSnapshot frame,
			Identity identity)
		{
			if (identity == null)
			{
				return;
			}
			String key = channel + ":"
				+ (receipt == null ? identity.observationKey
					: receipt.clock.sequence != null ? receipt.clock.sequence
						: receipt.clock.tick + ":" + receipt.clock.cycle + ":" + identity.observationKey)
				+ ":" + identity.kind + ":" + identity.id;
			sources.add(new Observation(channel, key, identity, receipt, receipt, callback, null, raw, actor,
				new JsonObject(), frame));
		}

		private void event(ObservedEvent event)
		{
			if (event.getTick() > record.getImpactTick() || event.getTick() < (long) record.getImpactTick() - 64)
			{
				return;
			}
			Receipt receipt = receipt(event);
			JsonObject raw = receipt.raw, actor = actor(raw);
			String type = event.getType();
			if ("SYSTEM_MESSAGE".equals(type) && event.getTick() == record.getImpactTick())
			{
				add(Channel.MESSAGE, raw, actor, receipt, null,
					new Identity("message", null, null, "message:" + event.getSequence(), null, null, receipt.scope));
				return;
			}
			boolean sameTickLifecycle = event.getTick() == record.getImpactTick()
				&& ("ACTOR_DEATH".equals(type) || "NPC_DESPAWNED".equals(type));
			if (!sameTickLifecycle && !before(receipt, record, false))
			{
				return;
			}
			Channel channel = "GRAPHICS_OBJECT_CREATED".equals(type) ? Channel.GRAPHIC_BIRTH
				: "ACTOR_DEATH".equals(type) || "NPC_DESPAWNED".equals(type) ? Channel.ACTOR_LIFECYCLE
					: "MENU_OPTION".equals(type) ? Channel.INPUT
						: "ANIMATION_CHANGED".equals(type) || "OVERHEAD_TEXT_CHANGED".equals(type)
							|| "ACTOR_GRAPHICS_CHANGED".equals(type) ? Channel.ACTOR_CALLBACK : null;
			if (channel == null)
			{
				return;
			}
			Identity identity = actorIdentity(actor, event.getActorRef(), "event:" + event.getSequence(), receipt.scope,
				actor.has("npcId") ? "npc" : "player");
			if (channel == Channel.GRAPHIC_BIRTH)
			{
				identity = new Identity("graphic", integer(raw.get("id")), null, "graphic:" + event.getSequence(),
					text(raw, "name"), null, receipt.scope);
			}
			if (channel == Channel.INPUT)
			{
				// A request identifier is retained raw. The matcher owns its target namespace and outcome interpretation.
				identity = new Identity("input", integer(raw.get("identifier")), null, "input:" + event.getSequence(),
					null, null, receipt.scope);
			}
			add(channel, raw, actor, receipt, exactFrame(receipt.clock, receipt.scope), identity);
		}

		private void witness(JsonObject raw)
		{
			String kind = text(raw, "kind");
			if ("PROJECTILE_OBSERVATION".equals(kind))
			{
				return;
			}
			if ("NPC_ACTIVITY_OBSERVATION".equals(kind) || "PLAYER_ACTIVITY_OBSERVATION".equals(kind))
			{
				JsonObject actor = object(raw.get("actorState"));
				boolean originalHpSample = callback != null && "HITSPLAT_CALLBACK".equals(text(raw, "observationStage"))
					&& Objects.equals(integer(raw.get("observedTick")), record.getImpactTick())
					&& Objects.equals(integer(raw.get("observedCycle")), record.getObservedCycle())
					&& Objects.equals(longValue(raw.get("impactSequence")), record.getObservationSequence());
				Scope scope = scope(raw, null, originalHpSample ? callback.scope.epoch : null);
				Receipt sample = new Receipt(null, new Clock(integer(raw.get("observedTick")),
					integer(raw.get("observedCycle")), longValue(raw.get("impactSequence"))), scope, raw);
				String key = "hp-pose:" + record.getObservationSequence() + ":" + sources.size();
				Identity identity = actorIdentity(actor, text(raw, "actorRef"), key, scope,
					"NPC_ACTIVITY_OBSERVATION".equals(kind) ? "npc" : "player");
				if (identity != null)
				{
					Integer id = identity.id == null && "npc".equals(identity.kind) ? integer(raw.get("npcId"))
						: identity.id;
					identity = new Identity(identity.kind, id != null && id < 0 ? null : id, identity.actorRef, key,
						identity.name == null ? text(raw, "name") : identity.name,
						identity.combatLevel == null ? integer(raw.get("combatLevel")) : identity.combatLevel, scope);
				}
				add(Channel.HP_ACTOR_POSE, raw, actor, sample, null, identity);
				return;
			}
			Channel channel = "ITEM_POSSESSION_CANDIDATE".equals(kind) ? Channel.INVENTORY
				: "SELF_DAMAGE_INPUT_CANDIDATE".equals(kind) ? Channel.INPUT
					: "ITEM_CONSUMPTION_CANDIDATE".equals(kind) ? Channel.CONSUMPTION
						: "SELF_DAMAGE_RESOURCE_CANDIDATE".equals(kind) ? Channel.RESOURCE_INPUT : null;
			if (channel == null)
			{
				return;
			}
			ObservedEvent input = original.get(longValue(raw.get("inputSequence")));
			Receipt receipt = input == null ? null : receipt(input);
			Scope scope = receipt == null ? scope(raw, null, null) : receipt.scope;
			add(channel, raw, new JsonObject(), receipt, null, new Identity("item", integer(raw.get("itemId")), null,
				text(raw, "sourceRef"), text(raw, "name"), null, scope));
		}

		public DamageRecord record()
		{
			return record;
		}

		public Receipt callback()
		{
			return callback;
		}

		public WorldSnapshot impact()
		{
			return impact;
		}

		public SceneData scene()
		{
			return scenes.get(record.getImpactSceneKey());
		}

		public List<Source> sources()
		{
			return Collections.unmodifiableList(sources);
		}

		public List<Receipt> lifecycles()
		{
			return Collections.unmodifiableList(lifecycles);
		}

		public List<DamageRecord> peers()
		{
			return Collections.unmodifiableList(records);
		}

		public boolean complete()
		{
			return window.getObservations() != null && window.getCaptureGaps().isEmpty();
		}

		public Integer firstTick()
		{
			return window.getFirstTick();
		}

		public Integer lastTick()
		{
			return window.getLastTick();
		}

		public WorldSnapshot exactFrame(Clock clock, Scope scope)
		{
			return changed(record) ? null : unique(clock, scope, true);
		}
	}

	private Receipt hp(DamageRecord hit)
	{
		ObservedEvent event = original.get(hit.getObservationSequence());
		if (event == null || !"HITSPLAT".equals(event.getType()) || event.getTick() != hit.getImpactTick()
			|| !Objects.equals(event.getGameCycle(), hit.getObservedCycle()))
		{
			return null;
		}
		Receipt originalReceipt = receipt(event);
		if (conflict(hit.getImpactSceneKey(), originalReceipt.scope.sceneKey)
			|| conflict(hit.getImpactWorldViewId(), originalReceipt.scope.worldViewId)
			|| conflict(hit.getImpactPlane(), originalReceipt.scope.plane)
			|| conflict(hit.getImpactContextEpoch(), originalReceipt.scope.epoch))
		{
			return null;
		}
		JsonObject raw = json(event.getData());
		Integer observedType = integer(raw.get("hitsplatType"));
		if (!Objects.equals(integer(raw.get("amount")), hit.getAmount())
			|| (Boolean.TRUE.equals(hit.getHitsplatTypeKnown())
				|| hit.getHitsplatTypeKnown() == null && observedType != null)
				&& !Objects.equals(observedType, hit.getHitsplatType()))
		{
			return null;
		}
		boolean recipient = Objects.equals(event.getActorRef(), hit.getDefenderRef());
		if (!recipient && event.getActorRef() == null && "player".equals(hit.getDefenderRef()))
		{
			List<ObservedEventLink> links = new ArrayList<>();
			for (ObservedEventLink link : window.getObservationLinks())
			{
				if (link.getObservationSequence() == event.getSequence())
				{
					links.add(link);
				}
			}
			recipient = links.isEmpty()
				? "PLAYER".equals(text(raw, "receiverKind")) && "LOCAL_PLAYER".equals(text(actor(raw), "actorKind"))
				: links.stream().allMatch(link -> "HITSPLAT".equals(link.getObservationType())
					&& "player".equals(link.getActorRef()) && link.getTick() == event.getTick());
		}
		return recipient ? originalReceipt : null;
	}

	private static boolean conflict(Object recorded, Object observed)
	{
		return recorded != null && observed != null && !recorded.equals(observed);
	}

	private WorldSnapshot unique(Clock clock, Scope scope, boolean exact)
	{
		if (clock == null || clock.tick == null || scope == null || scope.sceneKey == null || scope.worldViewId == null
			|| scope.plane == null || exact && clock.cycle == null)
		{
			return null;
		}
		WorldSnapshot found = null;
		for (WorldSnapshot frame : frames.getOrDefault(clock.tick, Collections.emptyList()))
		{
			if (!Boolean.TRUE.equals(frame.getNativeContextKnown())
				|| !Objects.equals(frame.getSceneKey(), scope.sceneKey)
				|| !Objects.equals(frame.getWorldViewId(), scope.worldViewId) || frame.getPlane() != scope.plane
				|| exact && (!Boolean.TRUE.equals(frame.getGameCycleKnown()) || frame.getGameCycle() != clock.cycle))
			{
				continue;
			}
			if (found != null)
			{
				return null;
			}
			found = frame;
		}
		return found;
	}

	private static boolean before(Receipt sample, DamageRecord hit, boolean strictCycle)
	{
		if (sample == null || sample.clock.tick == null || sample.clock.tick > hit.getImpactTick())
		{
			return false;
		}
		Clock clock = sample.clock;
		if (sample.scope != null && sample.scope.epoch != null && hit.getImpactContextEpoch() != null
			&& !sample.scope.epoch.equals(hit.getImpactContextEpoch()))
		{
			return false;
		}
		if (clock.sequence != null && hit.getObservationSequence() != null
			&& clock.sequence >= hit.getObservationSequence())
		{
			return false;
		}
		if (strictCycle && clock.tick == hit.getImpactTick() && (clock.cycle == null || hit.getObservedCycle() == null))
		{
			return false;
		}
		return clock.cycle == null || hit.getObservedCycle() == null
			|| (strictCycle ? clock.cycle < hit.getObservedCycle() : clock.cycle <= hit.getObservedCycle());
	}

	private static int compare(Clock first, Clock second)
	{
		int tick = Comparator.nullsFirst(Integer::compareTo).compare(first.tick, second.tick);
		if (tick != 0)
		{
			return tick;
		}
		int cycle = Comparator.nullsFirst(Integer::compareTo).compare(first.cycle, second.cycle);
		if (cycle != 0)
		{
			return cycle;
		}
		return Comparator.nullsFirst(Long::compareTo).compare(first.sequence, second.sequence);
	}

	private static boolean earlier(Clock first, Clock second)
	{
		return compare(first, second) < 0;
	}

	private static boolean changed(DamageRecord hit)
	{
		return hit.getImpactContextEpoch() != null && hit.getFinalizationContextEpoch() != null
			&& !hit.getImpactContextEpoch().equals(hit.getFinalizationContextEpoch());
	}

	private static Long longValue(JsonElement value)
	{
		try
		{
			return number(value) == null ? null : number(value).longValueExact();
		}
		catch (ArithmeticException invalid)
		{
			return null;
		}
	}

	private static <T> List<T> values(List<T> values)
	{
		return values == null ? Collections.emptyList() : values;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> maps(Object value)
	{
		return value instanceof List ? (List<Map<String, Object>>) value : null;
	}
}
