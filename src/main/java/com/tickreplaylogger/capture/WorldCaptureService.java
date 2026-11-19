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

import com.tickreplaylogger.model.world.ContextBlock;
import com.tickreplaylogger.model.world.HazardState;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.model.world.OtherPlayerState;
import com.tickreplaylogger.model.world.PlayerState;
import com.tickreplaylogger.model.world.ProjectileState;
import com.tickreplaylogger.model.world.SceneData;
import com.tickreplaylogger.model.world.WorldSnapshot;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Actor;
import net.runelite.api.Animation;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameObject;
import net.runelite.api.GraphicsObject;
import net.runelite.api.GroundObject;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * Builds a {@link WorldSnapshot} from the live client each tick, and a {@link SceneData} collision
 * grid on each scene load. Pure reads of the RuneLite API — no mutation, no network. The damage/
 * effect labelling and attribution confidence are applied by the plugin/{@code DamageMap}; this
 * service captures the raw observed world.
 */
public class WorldCaptureService
{
	private static final int HAZARD_RADIUS_TILES = 12;

	private DamageMap damageMap;
	private final EncounterWaveCueTracker encounterWaveCues = new EncounterWaveCueTracker();

	public void resetEncounterWaveCues()
	{
		encounterWaveCues.reset();
	}

	public void observeEncounterWaveCue(Client client, String sceneKey, net.runelite.api.GameState state,
		net.runelite.api.ChatMessageType channel, String message, int tick, Integer cycle, long sequence)
	{
		encounterWaveCues.observe(client, damageMap == null ? null : damageMap.catalog(), sceneKey, state, channel,
			message, tick, cycle, sequence);
	}

	private final TerrainCapture terrainCapture = new TerrainCapture();
	private final ActorIdentity actorIdentity;
	private final ObjectHazards.ObservationIdentities surfaceObservationIds;
	private final net.runelite.api.WorldView fixedView;
	private final Integer fixedPlane;
	private final java.util.IdentityHashMap<net.runelite.api.WorldView, java.util.Map<Integer, WorldCaptureService>> additionalServices = new java.util.IdentityHashMap<>();
	private final java.util.IdentityHashMap<net.runelite.api.WorldView, java.util.Set<Integer>> callbackPlanes = new java.util.IdentityHashMap<>();
	private final java.util.IdentityHashMap<net.runelite.api.WorldEntity, Long> entityIdentities = new java.util.IdentityHashMap<>();
	private long entitySequence, additionalSceneSequence;
	private String additionalScope, additionalSceneKey;
	private SceneData additionalScene;
	private int[][] lastCollision;

	public WorldCaptureService()
	{
		this(new ObjectHazards.ObservationIdentities());
	}

	public WorldCaptureService(ObjectHazards.ObservationIdentities observations)
	{
		this(new ActorIdentity(), new ObservedMechanics(), null, null, observations);
	}

	private WorldCaptureService(ActorIdentity identities, ObservedMechanics observedMechanics,
		net.runelite.api.WorldView view, Integer plane, ObjectHazards.ObservationIdentities observations)
	{
		actorIdentity = identities;
		surfaceObservationIds = java.util.Objects.requireNonNull(observations, "observations");
		mechanics = observedMechanics;
		fixedView = view;
		fixedPlane = plane;
	}

	public String actorRef(Actor actor, Player local)
	{
		return actorIdentity.ref(actor, local);
	}

	public String observedActorRef(Actor actor, Player local, Integer viewId, Integer slot)
	{
		return actorIdentity.observedRef(actor, local, viewId, slot);
	}

	public String existingActorRef(Actor actor, Player local)
	{
		return actorIdentity.existingRef(actor, local);
	}

	public void forgetActor(Actor actor)
	{
		actorIdentity.forget(actor);
	}

	public void resetActors()
	{
		encounterWaveCues.reset();
		actorIdentity.reset();
		mechanics.reset();
		additionalServices.clear();
		callbackPlanes.clear();
		entityIdentities.clear();
		lastCollision = null;
		resetTiming();
	}

	/** A logical map reload can reuse the same API view/scene arrays. Start fresh auxiliary lifetimes. */
	public void sceneLoading()
	{
		encounterWaveCues.reset();
		mechanics.reset();
		additionalServices.clear();
		callbackPlanes.clear();
	}

	public void worldViewLifecycle(Client client, net.runelite.api.WorldView nativeView)
	{
		encounterWaveCues.reset();
		if (nativeView == null)
			return;
		WorldViews.invalidate(nativeView);
		actorIdentity.forgetView(nativeView);
		mechanics.clearView(nativeView);
		additionalServices.remove(nativeView);
		callbackPlanes.remove(nativeView);
		if (nativeView == WorldViews.active(client))
		{
			lastCollision = null;
			resetTiming();
		}
	}

	private net.runelite.api.WorldView view(Client client)
	{
		return fixedView == null ? WorldViews.active(client) : fixedView;
	}

	private int plane(Client client)
	{
		return fixedPlane != null ? fixedPlane : view(client) == null ? client.getPlane() : view(client).getPlane();
	}

	private Iterable<? extends NPC> npcs(Client client)
	{
		return view(client) == null ? client.getNpcs() : view(client).npcs();
	}

	private Iterable<? extends Player> players(Client client)
	{
		return view(client) == null ? client.getPlayers() : view(client).players();
	}

	private net.runelite.api.Scene scene(Client client)
	{
		return view(client) == null ? client.getScene() : view(client).getScene();
	}

	private WorldPoint nativePoint(Client client, LocalPoint local, int plane)
	{
		return view(client) == null ? WorldPoint.fromLocal(client, local)
			: WorldPoint.fromLocal(view(client), local.getX(), local.getY(), plane);
	}

	private CollisionData[] collisions(Client client)
	{
		return view(client) == null ? client.getCollisionMaps() : view(client).getCollisionMaps();
	}

	private final List<String> captureIssues = new ArrayList<>();
	private java.util.Map<String, Object> hazardCoverage = new java.util.LinkedHashMap<>();
	private int mappedObjectReadFailures;
	private final GroundItemTracker groundItemTracker = new GroundItemTracker();

	public GroundItemTracker groundItems(Client client, net.runelite.api.WorldView view, int plane)
	{
		notePlane(view, plane);
		return serviceFor(client, view, plane).groundItemTracker;
	}

	private final ObjectStateTracker objectTracker = new ObjectStateTracker();

	public ObjectStateTracker objects()
	{
		return objectTracker;
	}

	public ObjectStateTracker objects(Client client, net.runelite.api.WorldView view)
	{
		return objects(client, view, view == null ? client.getPlane() : view.getPlane());
	}

	public ObjectStateTracker objects(Client client, net.runelite.api.WorldView view, int plane)
	{
		notePlane(view, plane);
		return serviceFor(client, view, plane).objectTracker;
	}

	public ObservedMechanics mechanics(Client client, net.runelite.api.WorldView view)
	{
		return mechanics(client, view, view == null ? client.getPlane() : view.getPlane());
	}

	public ObservedMechanics mechanics(Client client, net.runelite.api.WorldView view, int plane)
	{
		notePlane(view, plane);
		return serviceFor(client, view, plane).mechanics;
	}

	private void notePlane(net.runelite.api.WorldView view, int plane)
	{
		if (view != null && plane >= 0)
			callbackPlanes.computeIfAbsent(view, value -> new java.util.TreeSet<>()).add(plane);
	}

	public void clearMechanics(NPC npc)
	{
		mechanics.clear(npc);
	}

	private WorldCaptureService serviceFor(Client client, net.runelite.api.WorldView nativeView)
	{
		return serviceFor(client, nativeView, nativeView == null ? client.getPlane() : nativeView.getPlane());
	}

	private WorldCaptureService serviceFor(Client client, net.runelite.api.WorldView nativeView, int plane)
	{
		if (nativeView == null || nativeView == WorldViews.active(client) && plane == nativeView.getPlane())
			return this;
		WorldCaptureService service = additionalServices.computeIfAbsent(nativeView, value -> new java.util.TreeMap<>())
			.computeIfAbsent(plane, value -> new WorldCaptureService(actorIdentity, mechanics, nativeView, value, surfaceObservationIds));
		service.damageMap = damageMap;
		String scope = WorldViews.scope(nativeView, plane);
		if (!java.util.Objects.equals(scope, service.additionalScope))
		{
			if (service.additionalScope != null)
				service.resetTiming();
			service.additionalScope = scope;
			service.additionalScene = null;
			service.additionalSceneKey = "native:" + nativeView.getId() + ":p" + plane + ":"
				+ (++additionalSceneSequence);
		}
		return service;
	}

	public String additionalSceneKey(Client client, net.runelite.api.WorldView nativeView)
	{
		return additionalSceneKey(client, nativeView, nativeView == null ? client.getPlane() : nativeView.getPlane());
	}

	public String additionalSceneKey(Client client, net.runelite.api.WorldView nativeView, int plane)
	{
		notePlane(nativeView, plane);
		WorldCaptureService service = serviceFor(client, nativeView, plane);
		return service == this ? null : service.additionalSceneKey;
	}

	/** Lookup only: the caller has already observed the original native scope signature. */
	public String cachedAdditionalSceneKey(net.runelite.api.WorldView nativeView, int plane, String observedScope)
	{
		java.util.Map<Integer, WorldCaptureService> planes = additionalServices.get(nativeView);
		WorldCaptureService service = planes == null ? null : planes.get(plane);
		return service != null && observedScope != null && observedScope.equals(service.additionalScope)
			? service.additionalSceneKey : null;
	}

	/** Use the callback's original native view/id and separately observed primary frame. */
	public String observedSceneKey(net.runelite.api.WorldView nativeView, int plane, int viewId,
		net.runelite.api.WorldView primaryView, int primaryPlane)
	{
		if (nativeView == primaryView && plane == primaryPlane)
			return null;
		notePlane(nativeView, plane);
		WorldCaptureService service = additionalServices.computeIfAbsent(nativeView, value -> new java.util.TreeMap<>())
			.computeIfAbsent(plane, value -> new WorldCaptureService(actorIdentity, mechanics, nativeView, value, surfaceObservationIds));
		service.damageMap = damageMap;
		String scope = WorldViews.scope(nativeView, plane, viewId);
		if (!java.util.Objects.equals(scope, service.additionalScope))
		{
			if (service.additionalScope != null)
				service.resetTiming();
			service.additionalScope = scope;
			service.additionalScene = null;
			service.additionalSceneKey = "native:" + viewId + ":p" + plane + ":" + (++additionalSceneSequence);
		}
		return service.additionalSceneKey;
	}

	private final ObservedMechanics mechanics;

	public ObservedMechanics mechanics()
	{
		return mechanics;
	}

	// Per-NPC timing state (by scene index), for the action + attack-cooldown model.
	private final java.util.Map<Integer, Integer> npcLastAnim = new java.util.HashMap<>();
	private final java.util.Map<Integer, Integer> npcLastAnimationFrame = new java.util.HashMap<>();
	private final java.util.Map<Integer, Integer> npcLastAttackTick = new java.util.HashMap<>();
	private final java.util.Map<Integer, LocalPoint> npcLastLocal = new java.util.HashMap<>();
	private final java.util.Map<Integer, NPC> npcIdentities = new java.util.HashMap<>();
	private final java.util.Map<Integer, Integer> npcDefinitions = new java.util.HashMap<>();
	private final java.util.Map<Integer, String> npcCycleContexts = new java.util.HashMap<>();

	/** Provide the bundled map so observed hazard graphics get a human name. */
	public void setDamageMap(DamageMap damageMap)
	{
		this.damageMap = damageMap;
	}

	/** Reset per-NPC timing state (on login / scene reload). */
	public void resetTiming()
	{
		// A plane/primary-cache change invalidates timing, not an actor's earlier phase cue.
		clearActorTiming();
		objectTracker.reset();
		groundItemTracker.reset();
		terrainCapture.reset();
	}

	/** A missing actor sample breaks timing inference without inventing object/item lifetimes. */
	public void invalidateActorTiming()
	{
		clearActorTiming();
		for (java.util.Map<Integer, WorldCaptureService> levels : additionalServices.values())
			for (WorldCaptureService service : levels.values())
				service.clearActorTiming();
	}

	private void clearActorTiming()
	{
		npcLastAnim.clear();
		npcLastAnimationFrame.clear();
		npcLastAttackTick.clear();
		npcLastLocal.clear();
		npcIdentities.clear();
		npcDefinitions.clear();
		npcCycleContexts.clear();
	}

	/** Overhead protection/offensive-prayer names, to derive the player's single active overhead. */
	private static final java.util.Set<String> OVERHEADS = java.util.Set.of("PROTECT_FROM_MELEE",
		"PROTECT_FROM_MISSILES", "PROTECT_FROM_MAGIC", "RETRIBUTION", "REDEMPTION", "SMITE");

	/**
	 * Capture the collision grid for the current scene (once per load). Sparse: only non-zero tiles.
	 */
	public SceneData captureScene(Client client, String sceneKey, int loadSeq)
	{
		return captureScene(client, sceneKey, loadSeq, true);
	}

	public SceneData captureScene(Client client, String sceneKey, int loadSeq, boolean includeCollision)
	{
		net.runelite.api.WorldView nativeView = view(client);
		if (nativeView != null && (nativeView.getScene() == null || nativeView.getSizeX() <= 0
			|| nativeView.getSizeY() <= 0 || nativeView.getSizeX() > 1024 || nativeView.getSizeY() > 1024))
			return null;
		int plane = plane(client);
		CollisionData[] maps = includeCollision ? collisions(client) : null;
		List<SceneData.CollisionTile> tiles = new ArrayList<>();
		if (maps != null && plane >= 0 && plane < maps.length && maps[plane] != null)
		{
			int[][] flags = maps[plane].getFlags();
			if (flags != null)
			{
				for (int x = 0; x < flags.length; x++)
				{
					int[] col = flags[x];
					if (col == null)
					{
						continue;
					}
					for (int y = 0; y < col.length; y++)
					{
						if (col[y] != 0)
						{
							tiles.add(SceneData.CollisionTile.builder().x(x).y(y).flag(col[y]).build());
						}
					}
				}
			}
		}
		return SceneData.builder().sceneKey(sceneKey).worldViewId(view(client) == null ? null : view(client).getId())
			.instanced(view(client) == null ? client.isInInstancedRegion() : view(client).isInstance())
			.instanceTemplateChunks(copyChunks(view(client) == null ? null : view(client).getInstanceTemplateChunks()))
			.regionIds(copyInts(view(client) == null ? client.getMapRegions() : view(client).getMapRegions()))
			.plane(plane).baseX(view(client) == null ? client.getBaseX() : view(client).getBaseX())
			.baseY(view(client) == null ? client.getBaseY() : view(client).getBaseY())
			.width(view(client) == null ? 104 : view(client).getSizeX())
			.height(view(client) == null ? 104 : view(client).getSizeY()).collision(tiles).objects(new ArrayList<>()) // optional visual obstacles — v1.1
			.build();
	}

	/** Build the full snapshot for this tick. {@code projectilesThisTick} are those first seen now. */
	public WorldSnapshot captureTick(Client client, int tick, String sceneKey,
		List<ProjectileState> projectilesThisTick, WorldPoint lastPlayerWorld, boolean includeOtherPlayers)
	{
		return captureTick(client, tick, sceneKey, projectilesThisTick, lastPlayerWorld, includeOtherPlayers, true);
	}

	public WorldSnapshot captureTick(Client client, int tick, String sceneKey,
		List<ProjectileState> projectilesThisTick, WorldPoint lastPlayerWorld, boolean includeOtherPlayers,
		boolean includeCollision)
	{
		return captureTick(client, tick, sceneKey, projectilesThisTick, lastPlayerWorld, includeOtherPlayers,
			includeCollision, true);
	}

	public WorldSnapshot captureTick(Client client, int tick, String sceneKey,
		List<ProjectileState> projectilesThisTick, WorldPoint lastPlayerWorld, boolean includeOtherPlayers,
		boolean includeCollision, boolean includeTerrain)
	{
		net.runelite.api.WorldView sampledView;
		try
		{
			sampledView = WorldViews.active(client);
		}
		catch (RuntimeException unavailable)
		{
			return captureWithoutNativeContext(client, tick, projectilesThisTick, lastPlayerWorld, includeOtherPlayers,
				unavailable);
		}
		java.util.IdentityHashMap<net.runelite.api.WorldView, SceneCaptureTraversal> traversals = new java.util.IdentityHashMap<>();
		SceneCaptureTraversal primaryTraversal = SceneCaptureTraversal.capture(client, sampledView);
		traversals.put(sampledView, primaryTraversal);
		WorldSnapshot primary = captureNativeTick(client, tick, sceneKey, projectilesThisTick, lastPlayerWorld,
			includeOtherPlayers, includeCollision, includeTerrain, true, primaryTraversal);
		if (primary == null)
			return null;
		net.runelite.api.WorldView primaryView = WorldViews.active(client);
		WorldViews.Discovery discovered = WorldViews.discover(client);
		List<WorldSnapshot> additional = new ArrayList<>();
		List<String> issues = new ArrayList<>(primary.getCaptureIssues());
		issues.addAll(discovered.issues);
		for (net.runelite.api.WorldView nativeView : discovered.views)
		{
			try
			{
				SceneCaptureTraversal nativeTraversal = traversals.computeIfAbsent(nativeView,
					value -> SceneCaptureTraversal.capture(client, value));
				WorldViews.Planes planes = WorldViews.planes(nativeView, includeOtherPlayers, nativeTraversal);
				for (Integer observed : callbackPlanes.getOrDefault(nativeView, java.util.Collections.emptySet()))
					planes.add(observed, "CALLBACK_SINCE_PREVIOUS_CAPTURE");
				if (projectilesThisTick != null)
					for (ProjectileState projectile : projectilesThisTick)
					{
						Integer sourceLevel = projectile.getSourcePlane() == null ? projectile.getPlane()
							: projectile.getSourcePlane();
						if (java.util.Objects.equals(projectile.getWorldViewId(), nativeView.getId())
							&& sourceLevel != null)
							planes.add(sourceLevel, "PROJECTILE_SOURCE_LEVEL");
						if (java.util.Objects.equals(projectile.getTargetWorldViewId(), nativeView.getId())
							&& projectile.getTargetPlane() != null)
							planes.add(projectile.getTargetPlane(), "PROJECTILE_TARGET_LEVEL");
					}
				issues.addAll(planes.issues);
				if (nativeView == primaryView)
					primary = primary.toBuilder().planeCoverage(planes.coverage(primary.getPlane())).build();
				for (Integer plane : planes.planes)
				{
					if (nativeView == primaryView && plane == primary.getPlane())
					{
						// A secondary cache cannot observe callbacks while this scope is primary.
						java.util.Map<Integer, WorldCaptureService> cached = additionalServices.get(nativeView);
						if (cached != null)
							cached.remove(plane);
						continue;
					}
					try
					{
						WorldCaptureService service = serviceFor(client, nativeView, plane);
						if (service.additionalScene == null)
						{
							SceneData metadata = service.captureScene(client, service.additionalSceneKey, 0, false);
							if (metadata != null)
								service.additionalScene = metadata.toBuilder().collision(null).build();
						}
						List<ProjectileState> projectiles = new ArrayList<>();
						if (projectilesThisTick != null)
							for (ProjectileState projectile : projectilesThisTick)
								if (projectileMatches(projectile, nativeView.getId(), plane))
									projectiles.add(projectile);
						SceneCaptureTraversal traversal = traversals.computeIfAbsent(nativeView,
							value -> SceneCaptureTraversal.capture(client, value));
						WorldSnapshot observed = service.captureNativeTick(client, tick, service.additionalSceneKey,
							projectiles, null, includeOtherPlayers, includeCollision, includeTerrain, false, traversal);
						if (observed != null)
						{
							if (service.additionalScene == null)
							{
								java.util.Map<String, String> status = new java.util.LinkedHashMap<>(
									observed.getCaptureCoverage());
								status.put("sceneMetadata", "unavailable");
								observed = observed.toBuilder().captureCoverage(status).build();
							}
							additional.add(observed.toBuilder().nativeScene(service.additionalScene)
								.planeCoverage(planes.coverage(plane)).build());
						}
					}
					catch (RuntimeException error)
					{
						issues.add("worldPlanes: native view " + nativeView.getId() + " plane " + plane + " "
							+ error.getClass().getSimpleName());
					}
				}
				if (planes.issues.isEmpty() && additionalServices.containsKey(nativeView))
					additionalServices.get(nativeView).keySet().retainAll(planes.planes);
			}
			catch (RuntimeException error)
			{
				issues.add("worldViews: native view " + nativeView.getId() + " " + error.getClass().getSimpleName());
			}
		}
		callbackPlanes.clear();
		// Per-plane pruning would erase cues for actors sampled on another plane.
		if (discovered.issues.isEmpty())
			try
			{
				java.util.Set<NPC> loaded = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
				boolean complete = true;
				for (net.runelite.api.WorldView nativeView : discovered.views)
				{
					if (nativeView.npcs() == null)
					{
						complete = false;
						break;
					}
					for (NPC npc : nativeView.npcs())
						if (npc != null)
							loaded.add(npc);
				}
				if (complete)
					mechanics.prune(loaded);
			}
			catch (RuntimeException unavailable)
			{
				issues.add("npcs: phase membership reconciliation unavailable");
			}
		if (discovered.issues.isEmpty())
			additionalServices.keySet().retainAll(discovered.views);
		java.util.Map<String, String> coverage = new java.util.LinkedHashMap<>(primary.getCaptureCoverage());
		coverage.put("worldViews",
			issues.stream().anyMatch(value -> value.startsWith("worldViews:") || value.startsWith("worldPlanes:"))
				? "PARTIAL_LOADED_NATIVE_PLANE_CHANNELS"
				: "ALL_DISCOVERED_NATIVE_PLANE_CHANNELS");
		List<ProjectileState> primaryProjectiles = new ArrayList<>();
		if (projectilesThisTick != null)
			for (ProjectileState projectile : projectilesThisTick)
			{
				boolean assigned = false;
				for (WorldSnapshot child : additional)
					if (projectileMatches(projectile, child.getWorldViewId(), child.getPlane()))
					{
						assigned = true;
						break;
					}
				if (projectileMatches(projectile, primary.getWorldViewId(), primary.getPlane()) || !assigned)
					primaryProjectiles.add(projectile);
			}
		return primary.toBuilder().projectiles(primaryProjectiles)
			.additionalViews(java.util.Collections.unmodifiableList(additional))
			.captureCoverage(java.util.Collections.unmodifiableMap(coverage))
			.captureIssues(java.util.Collections.unmodifiableList(issues)).build();
	}

	/** Do not substitute a top-level or historical scene when the player's native view read fails. */
	private WorldSnapshot captureWithoutNativeContext(Client client, int tick, List<ProjectileState> projectiles,
		WorldPoint lastWorld, boolean includeOthers, RuntimeException failure)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
			return null;
		return captureUnscopedTick(client, local, tick, projectiles, includeOthers,
			"native context unavailable " + failure.getClass().getSimpleName());
	}

	/** Preserve independent observations when the caller could not reserve a trustworthy scene. */
	public WorldSnapshot captureUnscopedTick(Client client, Player local, int tick, List<ProjectileState> projectiles,
		boolean includeOthers, String reason)
	{
		if (local == null)
			return null;
		captureIssues.clear();
		invalidateActorTiming();
		mechanics.reset();
		captureIssues.add("worldViews: " + reason);
		java.util.Map<String, String> coverage = new java.util.LinkedHashMap<>();
		for (String channel : java.util.List.of("worldViews", "graphicsAndMappedHazards", "sceneObjects", "groundItems",
			"collision", "terrain", "worldEntities", "context", "variables", "serverVariables", "longVariables",
			"serverLongVariables", "clientIntegers", "localStatuses"))
			coverage.put(channel, "unavailable");
		PlayerState player = captureChannel("player", coverage, () -> capturePlayer(client, local, null, includeOthers),
			null);
		List<NpcState> npcs = captureChannel("npcs", coverage, () -> captureUnscopedNpcs(client, local, includeOthers),
			new ArrayList<>());
		coverage.put("npcsScope", "TOP_LEVEL_API_COLLECTION_WITHOUT_REPLAY_FRAME_ASSIGNMENT");
		List<OtherPlayerState> others = includeOthers
			? captureChannel("otherPlayers", coverage, () -> captureOtherPlayers(client, local, true),
				new ArrayList<>())
			: new ArrayList<>();
		if (!includeOthers)
			coverage.put("otherPlayers", "disabled");
		coverage.put("otherPlayersScope", "TOP_LEVEL_API_COLLECTION_WITHOUT_REPLAY_FRAME_ASSIGNMENT");
		Integer cycle = captureChannel("gameCycle", coverage, client::getGameCycle, null);
		Integer apiTick = captureChannel("apiTickCount", coverage, client::getTickCount, null);
		for (String issue : captureIssues)
			coverage.compute(issue.split(":", 2)[0],
				(key, status) -> "unavailable".equals(status) ? status : "partial");
		return WorldSnapshot.builder().tick(tick).wallClockMs(System.currentTimeMillis())
			.gameCycle(cycle == null ? 0 : cycle).gameCycleKnown(cycle != null).apiTickCount(apiTick)
			.nativeContextKnown(false).player(player).players(others).npcs(npcs).hazards(new ArrayList<>())
			.projectiles(projectiles == null ? new ArrayList<>() : projectiles).captureCoverage(coverage)
			.captureIssues(new ArrayList<>(captureIssues)).build();
	}

	private List<NpcState> captureUnscopedNpcs(Client client, Player local, boolean includeOthers)
	{
		List<NpcState> rows = new ArrayList<>();
		try
		{
			Iterable<? extends NPC> actors = client.getNpcs();
			if (actors == null)
			{
				captureIssues.add("npcs: collection unavailable");
				return rows;
			}
			for (NPC npc : actors)
				if (npc != null)
				{
					WorldPoint point = null;
					String issue = null;
					try
					{
						point = npc.getWorldLocation();
					}
					catch (RuntimeException unavailable)
					{
						issue = unavailable.getClass().getSimpleName();
					}
					NpcSnapshotObservation sample = new NpcSnapshotObservation(npc, point, issue);
					sample.state.actorRef(sample.read("actorRef", () -> actorRef(npc, local)))
						.interaction(sample.read("interaction",
							() -> ActorInteractions.captureObserved(sample.target, sample.interactionFlag,
								!sample.issues.containsKey("interactionTarget"), local, includeOthers,
								target -> actorRef(target, local))))
						.interactingNpcIndex(sample.target instanceof NPC
							? sample.read("interactingNpcIndex", () -> ((NPC) sample.target).getIndex())
							: null)
						.interactingWithPlayer(
							sample.issues.containsKey("interactionTarget") ? null : sample.target == local);
					if (!sample.issues.isEmpty())
					{
						sample.state.observationIssues(
							java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(sample.issues)));
						for (java.util.Map.Entry<String, String> item : sample.issues.entrySet())
							captureIssues
								.add("npcs: " + item.getKey() + " (index " + sample.index + "): " + item.getValue());
					}
					rows.add(sample.state.build());
				}
		}
		catch (RuntimeException unavailable)
		{
			captureIssues.add("npcs: collection " + unavailable.getClass().getSimpleName());
		}
		return rows;
	}

	private static boolean projectileMatches(ProjectileState projectile, Integer view, int plane)
	{
		return java.util.Objects.equals(projectile.getWorldViewId(), view)
			&& java.util.Objects.equals(
				projectile.getSourcePlane() == null ? projectile.getPlane() : projectile.getSourcePlane(), plane)
			|| java.util.Objects.equals(projectile.getTargetWorldViewId(), view)
				&& java.util.Objects.equals(projectile.getTargetPlane(), plane);
	}

	private WorldSnapshot captureNativeTick(Client client, int tick, String sceneKey,
		List<ProjectileState> projectilesThisTick, WorldPoint lastPlayerWorld, boolean includeOtherPlayers,
		boolean includeCollision, boolean includeTerrain, boolean includeLocalContext, SceneCaptureTraversal traversal)
	{
		Player lp = client.getLocalPlayer();
		if (lp == null)
		{
			return null;
		}

		captureIssues.clear();
		java.util.Map<String, String> coverage = new java.util.LinkedHashMap<>();
		coverage.put("worldViews", "NATIVE_VIEW_PLANE_CHANNEL");
		coverage.put("projectiles", "SUPPLIED_TICK_AND_CALLBACK_OBSERVATIONS");
		net.runelite.api.WorldView sampledNativeView = captureChannel("nativeView", coverage, () -> view(client), null);
		Integer sampledNativeViewId = sampledNativeView == null ? null
			: captureChannel("nativeViewId", coverage, sampledNativeView::getId, null);
		Integer sampledNativePlane = captureChannel("nativePlane", coverage, () -> plane(client), null);
		PlayerState player = includeLocalContext
			? captureChannel("player", coverage, () -> capturePlayer(client, lp, lastPlayerWorld, includeOtherPlayers),
				null)
			: null;
		if (player != null && captureIssues.stream().anyMatch(issue -> issue.startsWith("player.")))
			coverage.put("player", "partial");
		List<NpcState> npcs = captureChannel("npcs", coverage, () -> captureNpcs(client, lp, tick, includeOtherPlayers),
			new ArrayList<>());
		List<OtherPlayerState> others = includeOtherPlayers
			? captureChannel("otherPlayers", coverage, () -> captureOtherPlayers(client, lp), new ArrayList<>())
			: new ArrayList<>();
		if (!includeOtherPlayers)
			coverage.put("otherPlayers", "disabled");
		objectTracker.enterScene(sceneKey);
		List<HazardState> hazards = captureChannel("graphicsAndMappedHazards", coverage,
			() -> captureHazards(client, lp), new ArrayList<>());
		if (!"available".equals(hazardCoverage.get("graphicsStatus"))
			|| !"available".equals(hazardCoverage.get("mappedObjectsStatus")))
			coverage.put("graphicsAndMappedHazards", "partial");
		java.util.List<com.tickreplaylogger.model.world.ObservedObjectState> objects = captureChannel("sceneObjects",
			coverage, () -> objectTracker.snapshot(client, sceneKey, view(client), plane(client), traversal), null);
		if (objects != null && !Boolean.TRUE.equals(objectTracker.coverage().get("complete")))
			coverage.put("sceneObjects", "partial");
		java.util.List<com.tickreplaylogger.model.world.GroundItemState> groundItems = captureChannel("groundItems",
			coverage, () -> groundItemTracker.snapshot(client, sceneKey, view(client), plane(client), traversal), null);
		if (groundItems != null && !Boolean.TRUE.equals(groundItemTracker.coverage().get("complete")))
			coverage.put("groundItems", "partial");
		Integer apiTick = captureChannel("apiTickCount", coverage, client::getTickCount, null);
		int[] varps = includeLocalContext ? captureChannel("variables", coverage, () ->
		{
			int[] raw = client.getVarps();
			return raw == null ? null : raw.clone();
		}, null) : null;
		java.util.Map<Integer, Integer> clientIntegers = includeLocalContext
			? captureChannel("clientIntegers", coverage, () -> captureClientIntegers(client), null)
			: null;
		int[] serverVarps = includeLocalContext ? captureChannel("serverVariables", coverage, () ->
		{
			int[] raw = client.getServerVarps();
			return raw == null ? null : java.util.Arrays.equals(raw, varps) ? varps : raw.clone();
		}, null) : null;
		long[] longVarps = includeLocalContext ? captureChannel("longVariables", coverage, () ->
		{
			long[] raw = client.getVarpsLong();
			return raw == null ? null : raw.clone();
		}, null) : null;
		long[] serverLongVarps = includeLocalContext ? captureChannel("serverLongVariables", coverage, () ->
		{
			long[] raw = client.getServerVarpsLong();
			return raw == null ? null : java.util.Arrays.equals(raw, longVarps) ? longVarps : raw.clone();
		}, null) : null;
		ContextBlock context = includeLocalContext
			? captureChannel("context", coverage, () -> captureContext(client, lp, sceneKey), null)
			: null;
		List<com.tickreplaylogger.model.world.ObservedStatusState> statuses = includeLocalContext
			? captureChannel("localStatuses", coverage,
				() -> ClientStatusCapture.capture(client, damageMap == null ? null : damageMap.catalog(),
					captureIssues),
				null)
			: null;
		if (!includeLocalContext)
			coverage.put("localStatuses", "disabled");
		java.util.Map<Integer, com.tickreplaylogger.model.world.ObservedVarbitLayout> layouts = includeLocalContext
			? captureChannel("varbitLayouts", coverage, () -> captureVarbitLayouts(client), null)
			: null;
		if (!includeLocalContext)
			coverage.put("varbitLayouts", "disabled");
		else if (layouts != null && layouts.isEmpty()
			&& captureIssues.stream().anyMatch(issue -> issue.startsWith("varbitLayouts:")))
			coverage.put("varbitLayouts", "unavailable");
		List<com.tickreplaylogger.model.world.WorldEntityState> entities = view(client) == null
			|| plane(client) == view(client).getPlane()
				? captureChannel("worldEntities", coverage, () -> captureWorldEntities(client), null)
				: null;
		if (view(client) != null && plane(client) != view(client).getPlane())
			coverage.put("worldEntities", "VIEW_WIDE_POSES_ON_CURRENT_PLANE_FRAME");
		int[][] collision = includeCollision ? captureChannel("collision", coverage, () -> copyCollision(client), null)
			: null;
		terrainCapture.clearCoverage();
		java.util.List<com.tickreplaylogger.model.world.TileSurface> terrain = includeTerrain
			? captureChannel("terrain", coverage, () -> terrainCapture.capture(scene(client), plane(client)), null)
			: null;
		if (!includeTerrain)
			coverage.put("terrain", "disabled");
		if (includeTerrain && terrain != null && !terrainCapture.isNativeGridAvailable())
			captureIssues.add("terrain: plane array unavailable; linked samples retained");
		if (!includeCollision)
			coverage.put("collision", "disabled");
		Integer gameCycle = captureChannel("gameCycle", coverage, client::getGameCycle, null);
		boolean nativeContextKnown = sampledNativeView != null && sampledNativeViewId != null
			&& sampledNativePlane != null && sampledNativePlane >= 0 && sampledNativePlane <= 3
			&& sceneKey != null && !sceneKey.trim().isEmpty();
		Boolean nativeContextWitness = nativeContextKnown ? Boolean.TRUE : null;
		if (nativeContextKnown)
		{
			try
			{
				nativeContextKnown = view(client) == sampledNativeView
					&& sampledNativeView.getId() == sampledNativeViewId && plane(client) == sampledNativePlane;
				if (!nativeContextKnown) captureIssues.add("nativeContext: CHANGED_DURING_SAMPLE");
				nativeContextWitness = nativeContextKnown;
			}
			catch (RuntimeException unavailable)
			{
				nativeContextKnown = false;
				nativeContextWitness = Boolean.FALSE;
				captureIssues.add("nativeContext: " + unavailable.getClass().getSimpleName());
			}
		}
		coverage.put("nativeContext", nativeContextKnown ? "available" : "unavailable");
		for (String issue : captureIssues)
			coverage.compute(issue.split(":", 2)[0],
				(key, status) -> "unavailable".equals(status) ? status : "partial");
		return WorldSnapshot.builder().tick(tick).gameCycle(gameCycle == null ? 0 : gameCycle)
			.gameCycleKnown(gameCycle != null).wallClockMs(System.currentTimeMillis()).sceneKey(sceneKey)
			.nativeContextKnown(nativeContextWitness).worldViewId(sampledNativeViewId)
			.plane(sampledNativePlane == null ? 0 : sampledNativePlane).player(player)
			.npcs(npcs).players(others)
			.projectiles(projectilesThisTick != null ? projectilesThisTick : new ArrayList<>()).hazards(hazards)
			.hazardCoverage(java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(hazardCoverage)))
			.worldEntities(entities).observedObjects(objects).objectCoverage(objectTracker.coverage())
			.observedGroundItems(groundItems).groundItemCoverage(groundItemTracker.coverage()).apiTickCount(apiTick)
			.observedVarps(varps).context(context).localStatuses(statuses).varbitLayouts(layouts)
			.observedNumericVariables(
				com.tickreplaylogger.model.world.NumericVariableState.builder().clientIntegers(clientIntegers)
					.serverVarps(serverVarps).varpsLong(longVarps).serverVarpsLong(serverLongVarps).build())
			.observedCollision(collision).observedTerrain(terrain)
			.terrainCoverage(
				includeTerrain ? terrainCapture.coverage() : java.util.Collections.singletonMap("scope", "DISABLED"))
			.captureCoverage(java.util.Collections.unmodifiableMap(coverage))
			.captureIssues(new ArrayList<>(captureIssues)).build();
	}

	private java.util.Map<Integer, com.tickreplaylogger.model.world.ObservedVarbitLayout> captureVarbitLayouts(
		Client client)
	{
		if (damageMap == null || damageMap.catalog() == null)
			return null;
		com.google.gson.JsonElement configured = damageMap.catalog().contextBindings().get("observedVarbits");
		java.util.Set<Integer> ids = new java.util.TreeSet<>();
		if (configured != null && configured.isJsonObject())
			for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : configured.getAsJsonObject()
				.entrySet())
				ids.add(entry.getValue().getAsInt());
		for (String family : java.util.Arrays.asList("statusValueRules", "encounterModeRules"))
		{
			com.google.gson.JsonElement rules = damageMap.catalog().contextBindings().get(family);
			if (rules != null && rules.isJsonArray())
				for (com.google.gson.JsonElement value : rules.getAsJsonArray())
				{
					com.google.gson.JsonObject row = value.getAsJsonObject();
					if (row.has("namespace") && "varbit".equals(row.get("namespace").getAsString()) && row.has("id"))
						ids.add(row.get("id").getAsInt());
				}
		}
		java.util.Map<Integer, com.tickreplaylogger.model.world.ObservedVarbitLayout> values = new java.util.LinkedHashMap<>();
		int missing = 0, partial = 0;
		for (Integer id : ids)
		{
			com.tickreplaylogger.model.world.ObservedVarbitLayout sample = VarbitLayoutCapture.capture(client, id);
			if (sample == null || !"CLIENT_VARBIT_COMPOSITION".equals(sample.getEvidence()))
			{
				missing++;
				continue;
			}
			values.put(id, sample);
			if (!sample.isLayoutKnown())
				partial++;
		}
		if (missing > 0 || partial > 0)
			captureIssues.add(
				"varbitLayouts: " + missing + " requested compositions unavailable; " + partial + " partial layouts");
		return java.util.Collections.unmodifiableMap(values);
	}

	private java.util.Map<Integer, Integer> captureClientIntegers(Client client)
	{
		java.util.Map<Integer, Object> raw = client.getVarcMap();
		if (raw == null)
			return null;
		if (raw.size() > 32768)
			throw new IllegalStateException("Client variable map exceeds capture bound");
		java.util.Map<Integer, Integer> out = new java.util.TreeMap<>();
		for (java.util.Map.Entry<Integer, Object> entry : raw.entrySet())
		{
			if (entry.getKey() != null && entry.getKey() >= 0 && entry.getValue() instanceof Integer)
				out.put(entry.getKey(), (Integer) entry.getValue());
		}
		return java.util.Collections.unmodifiableMap(out);
	}

	private List<com.tickreplaylogger.model.world.WorldEntityState> captureWorldEntities(Client client)
	{
		net.runelite.api.WorldView parent = view(client);
		if (parent == null || parent.worldEntities() == null)
			return null;
		List<com.tickreplaylogger.model.world.WorldEntityState> out = new ArrayList<>();
		java.util.Set<net.runelite.api.WorldEntity> seen = java.util.Collections
			.newSetFromMap(new java.util.IdentityHashMap<>());
		for (net.runelite.api.WorldEntity entity : parent.worldEntities())
			if (entity != null)
				try
				{
					seen.add(entity);
					long key = entityIdentities.computeIfAbsent(entity, value -> ++entitySequence);
					LocalPoint current = entity.getLocalLocation(), target = entity.getTargetLocation();
					net.runelite.api.WorldView child = entity.getWorldView();
					out.add(com.tickreplaylogger.model.world.WorldEntityState.builder().entityKey(key)
						.parentWorldViewId(parent.getId()).worldViewId(child == null ? null : child.getId())
						.mainLocalX(current == null ? null : current.getX())
						.mainLocalY(current == null ? null : current.getY()).orientation(entity.getOrientation())
						.targetMainLocalX(target == null ? null : target.getX())
						.targetMainLocalY(target == null ? null : target.getY())
						.targetOrientation(entity.getTargetOrientation()).hiddenForOverlap(entity.isHiddenForOverlap())
						.ownerType(entity.getOwnerType()).coordinateEvidence("WORLD_ENTITY_API_TOP_LEVEL_LOCAL_POSE")
						.build());
				}
				catch (RuntimeException error)
				{
					captureIssues.add("worldEntities: " + error.getClass().getSimpleName());
				}
		entityIdentities.keySet().retainAll(seen);
		return java.util.Collections.unmodifiableList(out);
	}

	private java.util.List<NPC> npcList(Client client)
	{
		java.util.List<NPC> result = new ArrayList<>();
		try
		{
			Iterable<? extends NPC> values = npcs(client);
			if (values == null)
			{
				captureIssues.add("npcs: collection unavailable");
				return result;
			}
			for (NPC npc : values)
				if (npc != null)
					result.add(npc);
		}
		catch (RuntimeException error)
		{
			captureIssues.add("npcs: collection " + error.getClass().getSimpleName());
		}
		return result;
	}

	private int[] copyInts(int[] values)
	{
		return values == null ? null : values.clone();
	}

	private int[][][] copyChunks(int[][][] chunks)
	{
		if (chunks == null)
			return null;
		int[][][] copy = new int[chunks.length][][];
		for (int z = 0; z < chunks.length; z++)
		{
			if (chunks[z] == null)
				continue;
			copy[z] = new int[chunks[z].length][];
			for (int x = 0; x < chunks[z].length; x++)
				copy[z][x] = chunks[z][x] == null ? null : chunks[z][x].clone();
		}
		return copy;
	}

	private int[][] copyCollision(Client client)
	{
		CollisionData[] maps = collisions(client);
		int plane = plane(client);
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null)
			return null;
		int[][] flags = maps[plane].getFlags();
		if (flags == null || flags.length == 0)
			return null;
		int height = flags[0] == null ? 0 : flags[0].length;
		if (height == 0)
			return null;
		boolean compatible = lastCollision != null && lastCollision.length == flags.length
			&& lastCollision[0].length == height;
		int[][] copy = compatible ? null : new int[flags.length][];
		for (int x = 0; x < flags.length; x++)
		{
			if (flags[x] == null || flags[x].length != height)
				throw new IllegalStateException("Partial collision grid");
			if (compatible && java.util.Arrays.equals(flags[x], lastCollision[x]))
				continue;
			if (copy == null)
				copy = lastCollision.clone();
			copy[x] = flags[x].clone();
		}
		if (copy != null)
			lastCollision = copy;
		return lastCollision;
	}

	private <T> T captureChannel(String name, java.util.Map<String, String> coverage,
		java.util.function.Supplier<T> read, T fallback)
	{
		try
		{
			T value = read.get();
			coverage.put(name, value == null ? "unavailable" : "available");
			return value == null ? fallback : value;
		}
		catch (RuntimeException error)
		{
			coverage.put(name, "failed");
			return fallback;
		}
	}

	private PlayerState capturePlayer(Client client, Player lp, WorldPoint lastWorld, boolean includeOthers)
	{
		WorldPoint w = null;
		String worldIssue = null;
		try
		{
			w = lp.getWorldLocation();
		}
		catch (RuntimeException unavailable)
		{
			worldIssue = unavailable.getClass().getSimpleName();
		}
		PlayerSnapshotObservation sample = new PlayerSnapshotObservation(lp, w, worldIssue);
		LocalPoint l = sample.local;
		if (w == null)
			captureIssues.add("player: actor plane unavailable");
		SnapshotPrayerObservation prayer = new SnapshotPrayerObservation(client, sample);
		java.util.Map<String, Integer> baseSkills = new java.util.LinkedHashMap<>(),
			boostedSkills = new java.util.LinkedHashMap<>();
		for (Skill skill : Skill.values())
			if (skill != Skill.OVERALL)
			{
				Integer base = sample.read("baseSkillLevels." + skill.name(), () -> client.getRealSkillLevel(skill));
				Integer boosted = sample.read("boostedSkillLevels." + skill.name(),
					() -> client.getBoostedSkillLevel(skill));
				if (base != null)
					baseSkills.put(skill.name(), base);
				if (boosted != null)
					boostedSkills.put(skill.name(), boosted);
			}
		Integer poisonValue = sample.read("poisonValue", () -> client.getVarpValue(VarPlayer.POISON));
		Integer runEnergy = sample.read("runEnergy", client::getEnergy);
		Integer specEnergy = sample.read("specEnergy", () -> client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT));
		ContainerObservation equipment = container(client, InventoryID.EQUIPMENT, sample, true);
		ContainerObservation inventory = container(client, InventoryID.INVENTORY, sample, false);
		com.tickreplaylogger.model.world.ActorInteractionState interaction = sample.read("interaction",
			() -> ActorInteractions.captureObserved(sample.target, sample.interactionFlag,
				!sample.issues.containsKey("interactionTarget"), lp, includeOthers, target -> actorRef(target, lp)));
		Integer targetIndex = sample.target instanceof NPC
			? sample.read("interactingNpcIndex", () -> ((NPC) sample.target).getIndex())
			: null;
		com.tickreplaylogger.model.world.PlayerAppearanceState appearance = sample.read("appearance",
			() -> PlayerAppearance.capture(lp));
		Integer fromX = lastWorld != null ? Integer.valueOf(lastWorld.getX())
			: w == null ? null : Integer.valueOf(w.getX());
		Integer fromY = lastWorld != null ? Integer.valueOf(lastWorld.getY())
			: w == null ? null : Integer.valueOf(w.getY());
		recordPlayerIssues("player", sample);
		return PlayerState.builder().worldViewId(sample.viewId).combatLevel(sample.level).interaction(interaction)
			.observationIssues(sample.issues.isEmpty() ? null : new java.util.LinkedHashMap<>(sample.issues))
			.localPositionKnown(l != null).worldPositionKnown(w != null).planeKnown(w != null)
			.worldX(w == null ? 0 : w.getX()).worldY(w == null ? 0 : w.getY()).plane(w == null ? 0 : w.getPlane())
			.localX(l == null ? 0 : l.getX()).localY(l == null ? 0 : l.getY()).orientation(sample.orientation)
			.currentOrientation(sample.currentOrientation).animationId(sample.animation)
			.animationFrame(sample.animationFrame).poseAnimationId(sample.pose).poseAnimationFrame(sample.poseFrame)
			.graphicIds(sample.graphics).graphicsKnown(sample.graphicsKnown).graphicsState(sample.graphicsState)
			.hp(boostedSkills.get("HITPOINTS")).maxHp(baseSkills.get("HITPOINTS")).prayer(boostedSkills.get("PRAYER"))
			.maxPrayer(baseSkills.get("PRAYER")).activePrayers(prayer.active).reportedActivePrayers(prayer.reported)
			.prayerFlagValues(prayer.flags).prayerStateKnown(prayer.complete && prayer.resolved)
			.overheadPrayer(prayer.overhead).overheadPrayerEvidence(prayer.evidence)
			.prayerStateConflict(prayer.conflict).overheadIcon(sample.icon == null ? null : sample.icon.name())
			.overheadIconKnown(!sample.issues.containsKey("overheadIcon")).overheadText(sample.speech)
			.overheadCycle(sample.overheadCycle).boostedAttack(boostedSkills.get("ATTACK"))
			.boostedStrength(boostedSkills.get("STRENGTH")).boostedDefence(boostedSkills.get("DEFENCE"))
			.boostedRanged(boostedSkills.get("RANGED")).boostedMagic(boostedSkills.get("MAGIC"))
			.baseSkillLevels(baseSkills).boostedSkillLevels(boostedSkills)
			.specEnergy(specEnergy == null ? null : specEnergy / 10).specEnergyRaw(specEnergy)
			.specEnergyKnown(specEnergy != null).runEnergy(runEnergy == null ? null : runEnergy / 100)
			.runEnergyRaw(runEnergy).runEnergyKnown(runEnergy != null).interactingNpcIndex(targetIndex)
			.equipmentItemIds(equipment.ids).equipmentSlotKnown(equipment.known).appearance(appearance)
			.inventoryItemIds(inventory.ids).inventoryQuantities(inventory.quantities)
			.inventorySlotKnown(inventory.known).inventoryQuantityKnown(inventory.quantityKnown)
			.poisonValue(poisonValue == null ? 0 : poisonValue).poisonValueKnown(poisonValue != null).fromX(fromX)
			.fromY(fromY).toX(w == null ? null : w.getX()).toY(w == null ? null : w.getY()).build();
	}

	private void recordPlayerIssues(String channel, PlayerSnapshotObservation sample)
	{
		for (java.util.Map.Entry<String, String> issue : sample.issues.entrySet())
			captureIssues.add(channel + ": " + issue.getKey() + " " + issue.getValue());
	}

	private static final class ContainerObservation
	{
		int[] ids, quantities;
		boolean[] known, quantityKnown;
	}

	private ContainerObservation container(Client client, InventoryID id, PlayerSnapshotObservation sample,
		boolean equipment)
	{
		ContainerObservation out = new ContainerObservation();
		String prefix = equipment ? "equipment" : "inventory";
		ItemContainer observed = sample.read(prefix + "Container", () -> client.getItemContainer(id));
		if (observed == null)
			return out;
		int size = equipment
			? java.util.Arrays.stream(EquipmentInventorySlot.values()).mapToInt(EquipmentInventorySlot::getSlotIdx)
				.max().orElse(-1) + 1
			: 28;
		out.ids = new int[size];
		java.util.Arrays.fill(out.ids, -1);
		out.known = new boolean[size];
		if (!equipment)
		{
			out.quantities = new int[size];
			out.quantityKnown = new boolean[size];
		}
		// Literal slot indexes, including API-defined unused appearance slots; never pack occupied slots.
		for (int slot = 0; slot < size; slot++)
		{
			final int index = slot;
			Item item = sample.read(prefix + "Slot." + index, () -> observed.getItem(index));
			if (sample.issues.containsKey(prefix + "Slot." + index))
				continue;
			Integer itemId = item == null ? -1 : sample.read(prefix + "ItemId." + index, item::getId);
			if (itemId != null)
			{
				out.ids[index] = itemId;
				out.known[index] = true;
			}
			if (!equipment)
			{
				Integer quantity = item == null ? 0 : sample.read(prefix + "Quantity." + index, item::getQuantity);
				if (quantity != null)
				{
					out.quantities[index] = quantity;
					out.quantityKnown[index] = true;
				}
			}
		}
		return out;
	}

	private List<NpcState> captureNpcs(Client client, Player lp, int tick, boolean includeOthers)
	{
		List<NpcState> out = new ArrayList<>();
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		List<NPC> currentNpcs = npcList(client);
		int reportedPlane = plane(client);
		net.runelite.api.WorldView nativeView = view(client);
		boolean retainUnknownPlane = nativeView == null || reportedPlane == nativeView.getPlane();
		for (NPC npc : currentNpcs)
		{
			WorldPoint w = null;
			String worldIssue = null;
			try
			{
				w = npc.getWorldLocation();
			}
			catch (RuntimeException unavailable)
			{
				worldIssue = unavailable.getClass().getSimpleName();
			}
			if (w != null && w.getPlane() != reportedPlane)
				continue;
			if (w == null)
				captureIssues.add("npcs: actor plane unavailable");
			if (w == null && !retainUnknownPlane)
				continue;
			NpcSnapshotObservation sample = new NpcSnapshotObservation(npc, w, worldIssue);
			LocalPoint l = sample.local;
			Integer idx = sample.index;
			if (idx != null)
				seen.add(idx);
			NPC previousNpc = idx == null ? null : npcIdentities.get(idx);
			Integer previousDefinition = idx == null ? null : npcDefinitions.get(idx);
			if (previousNpc != npc || !java.util.Objects.equals(previousDefinition, sample.id))
				forgetNpcTiming(npc, idx);
			if (idx != null)
			{
				npcIdentities.put(idx, npc);
				npcDefinitions.put(idx, sample.id);
			}
			CombatCatalog catalog = damageMap == null ? null : damageMap.catalog();
			boolean normalContext = NpcIdentityContext.normalWorldObservedWithoutOverrides(client,
				!sample.issues.containsKey("modelOverrides") && sample.overrides == null);
			boolean referenceContext = normalContext && catalog != null && sample.id != null
				&& catalog.observedNameCompatible(sample.id, sample.name, !sample.issues.containsKey("name"));
			ObservedMechanics.Phase phase = null;
			if (sample.id == null || sample.level == null || !referenceContext)
				mechanics.clear(npc);
			else
			{
				phase = sample.read("phaseEvaluation", () ->
				{
					mechanics.validateIdentity(catalog, npc, sample.id, true);
					if (!sample.issues.containsKey("overheadText"))
						mechanics.observe(catalog, npc, sample.id, "overheadEquals", sample.speech,
							sample.animation == null ? -1 : sample.animation, currentNpcs, tick,
							"OVERHEAD_TEXT_SNAPSHOT");
					if (sample.animation != null)
						mechanics.observe(catalog, npc, sample.id, "animationIn", null, sample.animation, currentNpcs,
							tick, "ANIMATION_SNAPSHOT");
					mechanics.observe(catalog, npc, sample.id, "npcPresent", null,
						sample.animation == null ? -1 : sample.animation, currentNpcs, tick, "RELATED_NPC_OBSERVED");
					return mechanics.phase(npc);
				});
				if (sample.issues.containsKey("phaseEvaluation"))
					mechanics.clear(npc);
			}
			String animationKind = catalog != null && sample.id != null && sample.animation != null
				? sample.read("animationMeaning",
					() -> catalog.animationKind(sample.id, sample.animation, sample.level, referenceContext))
				: "unknown";
			boolean timingKnown = sample.timingInputsKnown() && !sample.issues.containsKey("animationMeaning");
			boolean attackStart = false, frameReset = false;
			Integer cooldown = null;
			LocalPoint priorPosition = null;
			if (timingKnown)
			{
				String context = sample.level + ":" + normalContext + ":" + referenceContext + ":" + sample.name + ":"
					+ (phase == null ? "" : phase.getRule() + ":" + phase.getLabel());
				String previousContext = npcCycleContexts.put(idx, context);
				if (!java.util.Objects.equals(previousContext, context))
				{
					npcLastAnim.remove(idx);
					npcLastAnimationFrame.remove(idx);
					npcLastAttackTick.remove(idx);
				}
				Integer lastAnim = npcLastAnim.put(idx, sample.animation),
					lastFrame = npcLastAnimationFrame.put(idx, sample.animationFrame);
				frameReset = lastAnim != null && lastAnim.equals(sample.animation) && sample.animationFrame != null
					&& lastFrame != null && sample.animationFrame >= 0 && lastFrame >= 0
					&& sample.animationFrame < lastFrame;
				attackStart = sample.animation != -1 && lastAnim != null
					&& (!lastAnim.equals(sample.animation) || frameReset) && "attack".equals(animationKind);
				if (!"attack".equals(animationKind) && sample.animation != -1)
					npcLastAttackTick.remove(idx);
				if (attackStart)
					npcLastAttackTick.put(idx, tick);
				Integer speed = damageMap == null ? 0
					: sample.read("attackSpeedReference",
						() -> damageMap.attackSpeedForNpc(sample.id, sample.level, referenceContext));
				Integer lastAttack = npcLastAttackTick.get(idx);
				cooldown = speed == null ? null
					: speed > 0 && lastAttack != null ? Math.max(0, speed - (tick - lastAttack)) : 0;
				priorPosition = npcLastLocal.put(idx, l);
			}
			else
				forgetNpcTiming(npc, idx);
			boolean moved = priorPosition != null && l != null && !priorPosition.equals(l);
			String action = sample.animation == null ? (moved ? "MOVING" : null)
				: deriveAction(moved, attackStart, cooldown == null ? 0 : cooldown, sample.animation != -1,
					priorPosition != null);
			Integer targetIndex = sample.target instanceof NPC
				? sample.read("interactingNpcIndex", () -> ((NPC) sample.target).getIndex())
				: null;
			String actionEvidence = catalog != null && sample.id != null && sample.animation != null
				? sample.read("actionEvidence",
					() -> catalog.animationEvidence(sample.id, sample.animation, sample.level, referenceContext))
				: "UNCLASSIFIED_ANIMATION";
			List<String> researchReferences = catalog != null && sample.id != null
				? sample.read("researchReferences", () -> catalog.referenceKeys(sample.id))
				: null;
			sample.state.actorRef(sample.read("actorRef", () -> actorRef(npc, lp)))
				.definitionStateReference(catalog == null ? null
					: sample.read("definitionStateReference",
						() -> catalog.definitionStateReference(sample.id, sample.name,
							!sample.issues.containsKey("name"), sample.level, sample.size, normalContext)))
				.interaction(ActorInteractions.captureObserved(sample.target, sample.interactionFlag,
					!sample.issues.containsKey("interactionTarget"), lp, includeOthers, target -> actorRef(target, lp)))
				.interactingNpcIndex(targetIndex)
				.interactingWithPlayer(
					sample.issues.containsKey("interactionTarget") || lp == null ? null : sample.target == lp)
				.normalWorldContextObserved(normalContext)
				.variantCandidates(catalog != null && sample.id != null
					? sample.read("variantReferences", () -> catalog.variants(sample.id))
					: null)
				.researchReferenceKeys(researchReferences).actionEvidence(actionEvidence)
				.attackOnsetEvidence(
					attackStart ? (frameReset ? "OBSERVED_ANIMATION_FRAME_DECREASE" : "OBSERVED_ANIMATION_ID_CHANGE")
						: null)
				.observedPhase(phase == null ? null : phase.getLabel())
				.phaseRule(phase == null ? null : phase.getRule())
				.phaseEvidence(phase == null ? null : phase.getEvidence())
				.phaseObservedTick(phase == null ? null : phase.getObservedTick())
				.phaseLastObservedTick(phase == null ? null : phase.getLastObservedTick()).derivedAction(action)
				.attackCooldownTicksRemaining(cooldown);
			if (!sample.issues.isEmpty())
			{
				sample.state.observationIssues(
					java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(sample.issues)));
				for (java.util.Map.Entry<String, String> issue : sample.issues.entrySet())
					captureIssues.add("npcs: " + issue.getKey() + " (index " + idx + "): " + issue.getValue());
			}
			out.add(sample.state.build());
		}
		npcLastAnim.keySet().retainAll(seen);
		npcLastAnimationFrame.keySet().retainAll(seen);
		npcLastAttackTick.keySet().retainAll(seen);
		npcLastLocal.keySet().retainAll(seen);
		npcIdentities.keySet().retainAll(seen);
		npcDefinitions.keySet().retainAll(seen);
		npcCycleContexts.keySet().retainAll(seen);
		return out;
	}

	private void forgetNpcTiming(NPC npc, Integer index)
	{
		java.util.Set<Integer> slots = new java.util.HashSet<>();
		if (index != null)
			slots.add(index);
		for (java.util.Map.Entry<Integer, NPC> entry : npcIdentities.entrySet())
			if (entry.getValue() == npc)
				slots.add(entry.getKey());
		for (Integer slot : slots)
		{
			npcLastAnim.remove(slot);
			npcLastAnimationFrame.remove(slot);
			npcLastAttackTick.remove(slot);
			npcLastLocal.remove(slot);
			npcIdentities.remove(slot);
			npcDefinitions.remove(slot);
			npcCycleContexts.remove(slot);
		}
	}

	private List<OtherPlayerState> captureOtherPlayers(Client client, Player lp)
	{
		return captureOtherPlayers(client, lp, false);
	}

	private List<OtherPlayerState> captureOtherPlayers(Client client, Player lp, boolean unscoped)
	{
		List<OtherPlayerState> out = new ArrayList<>();
		int reportedPlane = unscoped ? -1 : plane(client);
		net.runelite.api.WorldView nativeView = unscoped ? null : view(client);
		boolean retainUnknownPlane = nativeView == null || reportedPlane == nativeView.getPlane();
		try
		{
			Iterable<? extends Player> actors = unscoped ? client.getPlayers() : players(client);
			if (actors == null)
			{
				captureIssues.add("otherPlayers: collection unavailable");
				return out;
			}
			for (Player p : actors)
			{
				if (p == null || p == lp)
					continue;
				WorldPoint w = null;
				String worldIssue = null;
				try
				{
					w = p.getWorldLocation();
				}
				catch (RuntimeException unavailable)
				{
					worldIssue = unavailable.getClass().getSimpleName();
				}
				if (!unscoped && w != null && w.getPlane() != reportedPlane)
					continue;
				if (w == null)
				{
					captureIssues.add("otherPlayers: actor plane unavailable");
					if (!retainUnknownPlane)
						continue;
				}
				PlayerSnapshotObservation sample = new PlayerSnapshotObservation(p, w, worldIssue);
				LocalPoint l = sample.local;
				OtherPlayerState.OtherPlayerStateBuilder row = OtherPlayerState.builder()
					.actorRef(sample.read("actorRef", () -> actorRef(p, lp)))
					.playerId(sample.read("playerId", p::getId)).worldViewId(sample.viewId).combatLevel(sample.level)
					.name(sample.read("name", () -> clean(p.getName())))
					.interaction(sample.read("interaction",
						() -> ActorInteractions.captureObserved(sample.target, sample.interactionFlag,
							!sample.issues.containsKey("interactionTarget"), lp, true, target -> actorRef(target, lp))))
					.localPositionKnown(l != null).worldPositionKnown(w != null).planeKnown(w != null)
					.worldX(w == null ? 0 : w.getX()).worldY(w == null ? 0 : w.getY())
					.plane(w == null ? 0 : w.getPlane()).localX(l == null ? 0 : l.getX())
					.localY(l == null ? 0 : l.getY()).orientation(sample.orientation)
					.currentOrientation(sample.currentOrientation).animationId(sample.animation)
					.animationFrame(sample.animationFrame).poseAnimationId(sample.pose)
					.poseAnimationFrame(sample.poseFrame).graphicIds(sample.graphics)
					.graphicsKnown(sample.graphicsKnown).graphicsState(sample.graphicsState)
					.overheadPrayer(sample.icon == null ? null : sample.icon.name())
					.overheadIconKnown(!sample.issues.containsKey("overheadIcon")).overheadText(sample.speech)
					.overheadCycle(sample.overheadCycle).healthRatio(sample.read("healthRatio", p::getHealthRatio))
					.healthScale(sample.read("healthScale", p::getHealthScale))
					.interactingNpcIndex(sample.target instanceof NPC
						? sample.read("interactingNpcIndex", () -> ((NPC) sample.target).getIndex())
						: null)
					.interactingWithPlayer(sample.issues.containsKey("interactionTarget") ? null : sample.target == lp)
					.team(sample.read("team", p::getTeam));
				com.tickreplaylogger.model.world.PlayerAppearanceState appearance = sample.read("appearance",
					() -> PlayerAppearance.capture(p));
				if (appearance != null)
					row.equipmentItemIds(appearance.getEquipmentItemIds()).equipmentEvidence(appearance.getEvidence())
						.appearance(appearance);
				recordPlayerIssues("otherPlayers", sample);
				out.add(
					row.observationIssues(sample.issues.isEmpty() ? null : new java.util.LinkedHashMap<>(sample.issues))
						.build());
			}
		}
		catch (RuntimeException unavailable)
		{
			captureIssues.add("otherPlayers: collection " + unavailable.getClass().getSimpleName());
		}
		return out;
	}

	List<HazardState> captureHazards(Client client, Player lp)
	{
		List<HazardState> out = new ArrayList<>();
		hazardCoverage = new java.util.LinkedHashMap<>();
		mappedObjectReadFailures = 0;
		hazardCoverage.put("version", 1);
		hazardCoverage.put("graphicsScope", "EXPOSED_NATIVE_PLANE_GRAPHICS");
		hazardCoverage.put("graphicsStatus", "unavailable");
		hazardCoverage.put("mappedObjectsStatus", "unavailable");
		hazardCoverage.put("objectScanCompleted", false);
		hazardCoverage.put("objectKinds",
			java.util.List.of("gameObject", "groundObject", "wallObject", "decorativeObject"));
		hazardCoverage.put("definitionPolicy",
			"MAPPED_DEFINITIONS_ONLY; unmapped objects remain in the scene-object channel");
		int graphicsFailures = 0, unpositionedGraphics = 0;
		try
		{
			Iterable<GraphicsObject> graphics = view(client) == null ? client.getGraphicsObjects()
				: view(client).getGraphicsObjects();
			if (graphics == null)
				throw new IllegalStateException("Graphics collection unavailable");
			hazardCoverage.put("graphicsStatus", "available");
			for (GraphicsObject go : graphics)
			{
				if (go == null)
				{
					continue;
				}
				try
				{
					int level = go.getLevel();
					if (level != plane(client))
						continue;
					int id = go.getId();
					SceneFieldObservation fields = new SceneFieldObservation();
					long graphicObservationId = surfaceObservationIds.id(go);
					LocalPoint loc = fields.read("location", go::getLocation);
					if (loc == null)
					{
						unpositionedGraphics++;
						if (fields.known("location"))
							graphicsFailures++;
						captureIssues.add("graphicsAndMappedHazards: graphic position unavailable");
					}
					WorldView nativeView = fields.read("worldView", go::getWorldView);
					Integer viewId = nativeView == null ? null : fields.read("worldViewId", nativeView::getId);
					WorldPoint w = loc == null || !fields.known("worldView") ? null
						: fields.read("worldLocation", () -> nativePoint(client, loc, level));
					if (loc != null && fields.known("worldView") && !fields.known("worldLocation"))
						captureIssues.add("graphicsAndMappedHazards: graphic world coordinates unavailable");
					Integer start = fields.read("startCycle", go::getStartCycle), z = fields.read("z", go::getZ);
					Animation animation = fields.read("animation", go::getAnimation);
					Integer animationId = animation == null ? null : fields.read("animationId", animation::getId);
					Integer animationFrame = fields.read("animationFrame", go::getAnimationFrame);
					Boolean finished = fields.read("finished", go::finished);
					String mappedName = damageMap == null ? null
						: fields.read("mappedName", () -> damageMap.hazardName("graphicsObject", id));
					CombatCatalog.GraphicSurface surface = damageMap == null ? null
						: damageMap.catalog().graphicSurface(id);
					if (fields.partial())
					{
						graphicsFailures++;
						captureIssues.add("graphicsAndMappedHazards: graphic fields " + fields.issues());
					}
					out.add(HazardState.builder().kind("graphicsObject").id(id).graphicObservationId(graphicObservationId)
						.localPositionKnown(loc != null)
						.worldPositionKnown(w != null).worldX(w == null ? 0 : w.getX()).worldY(w == null ? 0 : w.getY())
						.plane(level).localX(loc == null ? 0 : loc.getX()).localY(loc == null ? 0 : loc.getY())
						.mappedName(surface != null && surface.getName() != null ? surface.getName() : mappedName)
						.state(surface == null ? null : surface.getState()).damaging(surface == null ? null : surface.getDamaging())
						.stateEvidence(surface == null ? null : "REVIEWED_GRAPHIC_DEFINITION")
						.sourceEligibility(surface == null || surface.getDamaging() == null ? "UNCLASSIFIED_GRAPHIC_SURFACE"
							: surface.getDamaging() ? "ELIGIBLE_REFERENCE_SURFACE" : "NOT_DAMAGE_SURFACE")
						.surfaceRuleKey(surface == null ? null : surface.getRuleKey()).mapRef(surface == null ? null : surface.getMapRef())
						.startCycle(start).worldViewId(viewId).z(z).animationId(animationId)
						.animationFrame(animationFrame).finished(finished).fieldAvailability(fields.availability())
						.observationIssues(fields.issues()).nullFields(fields.nulls()).build());
				}
				catch (RuntimeException error)
				{
					graphicsFailures++;
					captureIssues.add("graphicsAndMappedHazards: " + error.getClass().getSimpleName());
				}
			}
		}
		catch (RuntimeException error)
		{
			graphicsFailures++;
			captureIssues.add("graphicsAndMappedHazards: graphics collection " + error.getClass().getSimpleName());
		}
		if (graphicsFailures > 0)
			hazardCoverage.put("graphicsStatus",
				"available".equals(hazardCoverage.get("graphicsStatus")) ? "partial" : "unavailable");
		hazardCoverage.put("graphicsReadFailures", graphicsFailures);
		hazardCoverage.put("unpositionedGraphics", unpositionedGraphics);
		LocalPoint pl = null;
		try
		{
			pl = fixedPlane != null && (lp.getWorldView() != fixedView || fixedView.getPlane() != fixedPlane) ? null
				: lp.getLocalLocation();
		}
		catch (RuntimeException error)
		{
			captureIssues.add("graphicsAndMappedHazards: scan center " + error.getClass().getSimpleName());
		}
		hazardCoverage.put("mappedObjectsScope",
			pl == null && fixedView != null ? "EXPOSED_NATIVE_PLANE" : "LOCAL_RADIUS");
		if (pl != null)
		{
			hazardCoverage.put("radiusTiles", HAZARD_RADIUS_TILES);
			hazardCoverage.put("centerSceneX", pl.getSceneX());
			hazardCoverage.put("centerSceneY", pl.getSceneY());
		}
		if (pl == null && fixedView == null)
		{
			hazardCoverage.put("mappedObjectsStatus", "skipped");
			hazardCoverage.put("mappedObjectsUnavailableReason", "LOCAL_POSITION_UNAVAILABLE");
		}
		else
			try
			{
				captureObjectHazards(client, pl, out);
			}
			catch (RuntimeException error)
			{
				mappedObjectReadFailures++;
				hazardCoverage.put("mappedObjectsStatus", "partial");
				captureIssues.add("graphicsAndMappedHazards: object scan " + error.getClass().getSimpleName());
			}
		hazardCoverage.put("mappedObjectReadFailures", mappedObjectReadFailures);
		return out;
	}

	/**
	 * Mapped definitions across all four scene-object types, within the recorded scan scope. Only
	 * mapped ids are captured — the scene is full of ordinary scenery, so an unmapped object is never
	 * a hazard here (observed-over-predicted). Keeps the hazard list small and correct.
	 */
	private void captureObjectHazards(Client client, LocalPoint pl, List<HazardState> out)
	{
		net.runelite.api.Scene observedScene = scene(client);
		if (damageMap == null || observedScene == null)
		{
			hazardCoverage.put("mappedObjectsUnavailableReason",
				damageMap == null ? "REFERENCE_MAP_UNAVAILABLE" : "SCENE_UNAVAILABLE");
			return;
		}
		int plane = plane(client);
		net.runelite.api.Tile[][][] tiles = observedScene.getTiles();
		hazardCoverage.put("sampledPlane", plane);
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			hazardCoverage.put("mappedObjectsUnavailableReason", "PLANE_ARRAY_UNAVAILABLE");
			return;
		}
		int cx = pl == null ? 0 : pl.getSceneX();
		int cy = pl == null ? 0 : pl.getSceneY();
		net.runelite.api.Tile[][] plane0 = tiles[plane];
		if (plane0 == null)
		{
			hazardCoverage.put("mappedObjectsUnavailableReason", "PLANE_ARRAY_UNAVAILABLE");
			return;
		}
		hazardCoverage.put("tileArrayWidth", plane0.length);
		hazardCoverage.put("boundsPolicy", "REQUESTED_SCOPE_CLIPPED_TO_EXPOSED_COLUMNS");
		int visitedSlots = 0, exposedTiles = 0, unavailableColumns = 0;
		java.util.Set<net.runelite.api.TileObject> seen = java.util.Collections
			.newSetFromMap(new java.util.IdentityHashMap<>());
		for (int sx = pl == null ? 0 : Math.max(0, cx - HAZARD_RADIUS_TILES); sx <= (pl == null ? plane0.length - 1
			: Math.min(plane0.length - 1, cx + HAZARD_RADIUS_TILES)); sx++)
		{
			if (sx < 0 || sx >= plane0.length)
			{
				continue;
			}
			if (plane0[sx] == null)
			{
				unavailableColumns++;
				continue;
			}
			for (int sy = pl == null ? 0 : Math.max(0, cy - HAZARD_RADIUS_TILES); sy <= (pl == null
				? plane0[sx].length - 1
				: Math.min(plane0[sx].length - 1, cy + HAZARD_RADIUS_TILES)); sy++)
			{
				if (plane0[sx] == null || sy < 0 || sy >= plane0[sx].length)
				{
					continue;
				}
				visitedSlots++;
				net.runelite.api.Tile tile = plane0[sx][sy];
				if (tile == null)
				{
					continue;
				}
				exposedTiles++;
				captureObjectSlot(client, out, "groundObject", tile::getGroundObject, plane, seen);
				captureObjectSlot(client, out, "wallObject", tile::getWallObject, plane, seen);
				captureObjectSlot(client, out, "decorativeObject", tile::getDecorativeObject, plane, seen);
				GameObject[] gameObjects = null;
				try
				{
					gameObjects = tile.getGameObjects();
				}
				catch (RuntimeException error)
				{
					objectHazardReadFailed(error);
				}
				if (gameObjects != null)
				{
					for (GameObject g : gameObjects)
					{
						if (g != null && seen.add(g))
						{
							addObjectHazard(client, out, "gameObject", g, plane);
						}
					}
				}
			}
		}
		hazardCoverage.put("visitedTileSlots", visitedSlots);
		hazardCoverage.put("exposedTiles", exposedTiles);
		hazardCoverage.put("unavailableColumns", unavailableColumns);
		hazardCoverage.put("objectScanCompleted", true);
		hazardCoverage.put("mappedObjectsStatus", mappedObjectReadFailures == 0 ? "available" : "partial");
	}

	private void captureObjectSlot(Client client, List<HazardState> out, String kind,
		java.util.function.Supplier<? extends net.runelite.api.TileObject> read, int plane,
		java.util.Set<net.runelite.api.TileObject> seen)
	{
		try
		{
			net.runelite.api.TileObject value = read.get();
			if (value != null && seen.add(value))
				addObjectHazard(client, out, kind, value, plane);
		}
		catch (RuntimeException error)
		{
			objectHazardReadFailed(error);
		}
	}

	private void objectHazardReadFailed(RuntimeException error)
	{
		mappedObjectReadFailures++;
		captureIssues.add("graphicsAndMappedHazards: object " + error.getClass().getSimpleName());
	}

	private void addObjectHazard(Client client, List<HazardState> out, String kind, net.runelite.api.TileObject object,
		int plane)
	{
		try
		{
			readObjectHazard(client, out, kind, object, plane);
		}
		catch (RuntimeException error)
		{
			objectHazardReadFailed(error);
		}
	}

	private void readObjectHazard(Client client, List<HazardState> out, String kind, net.runelite.api.TileObject object,
		int plane)
	{
		int baseId = object.getId();
		SceneFieldObservation fields = new SceneFieldObservation();
		LocalPoint loc = fields.read("localLocation", object::getLocalLocation);
		ObjectHazards.Resolved observed = fields.read("definition",
			() -> ObjectHazards.resolve(client, baseId, damageMap));
		if (observed == null && !fields.known("definition") && damageMap != null)
		{
			DamageMap.ObjectHazardRule rule = damageMap.objectHazardRule(baseId);
			String name = rule == null ? damageMap.hazardName("object", baseId) : rule.getName();
			if (name != null)
				observed = new ObjectHazards.Resolved(baseId, baseId, name, "unknown", null, "DEFINITION_UNAVAILABLE",
					rule == null ? null : rule.getMapRef(), "STATE_UNAVAILABLE", rule == null ? null : rule.getKey(),
					false);
		}
		if (observed == null)
			return; // Unmapped scenery still belongs to the independent scene-object channel.
		WorldPoint w = loc == null ? null : fields.read("worldLocation", () -> nativePoint(client, loc, plane));
		if (loc != null && !fields.known("worldLocation"))
			captureIssues.add("graphicsAndMappedHazards: object world coordinates unavailable");
		net.runelite.api.Point min = object instanceof GameObject
			? fields.read("minSceneLocation", ((GameObject) object)::getSceneMinLocation)
			: null;
		net.runelite.api.Point max = object instanceof GameObject
			? fields.read("maxSceneLocation", ((GameObject) object)::getSceneMaxLocation)
			: null;
		WorldView objectView = fields.read("objectWorldView", object::getWorldView);
		Integer objectViewId = objectView == null ? null : fields.read("objectWorldViewId", objectView::getId);
		Integer objectPlane = fields.read("objectPlane", object::getPlane);
		WorldView nativeView = view(client);
		Integer viewId = nativeView == null ? null : fields.read("worldViewId", nativeView::getId);
		if (fields.partial())
		{
			mappedObjectReadFailures++;
			captureIssues.add("graphicsAndMappedHazards: object fields " + fields.issues());
		}
		out.add(HazardState.builder().kind(kind).startCycle(0).worldViewId(viewId).localPositionKnown(loc != null)
			.objectNativeScopeKnown(objectViewId != null && objectPlane != null && objectPlane >= 0 && objectPlane <= 3)
			.objectWorldViewId(objectViewId).objectPlane(objectPlane)
			.worldPositionKnown(w != null).id(observed.getId()).objectKey(objectTracker.reference(kind, object))
			.baseId(observed.getBaseId()).state(observed.getState()).damaging(observed.getDamaging())
			.stateEvidence(observed.getEvidence()).mapRef(observed.getMapRef())
			.sourceEligibility(observed.getSourceEligibility()).surfaceRuleKey(observed.getRuleKey())
			.fieldAvailability(fields.availability()).observationIssues(fields.issues()).nullFields(fields.nulls())
			.minSceneX(min != null ? Integer.valueOf(min.getX())
				: object instanceof GroundObject && loc != null ? Integer.valueOf(loc.getSceneX()) : null)
			.minSceneY(min != null ? Integer.valueOf(min.getY())
				: object instanceof GroundObject && loc != null ? Integer.valueOf(loc.getSceneY()) : null)
			.maxSceneX(max != null ? Integer.valueOf(max.getX())
				: object instanceof GroundObject && loc != null ? Integer.valueOf(loc.getSceneX()) : null)
			.maxSceneY(max != null ? Integer.valueOf(max.getY())
				: object instanceof GroundObject && loc != null ? Integer.valueOf(loc.getSceneY()) : null)
			.worldX(w != null ? w.getX() : 0).worldY(w != null ? w.getY() : 0).plane(plane)
			.localX(loc == null ? 0 : loc.getX()).localY(loc == null ? 0 : loc.getY()).mappedName(observed.getName())
			.startCycle(0).build());
	}

	private ContextBlock captureContext(Client client, Player player, String sceneKey)
	{
		Integer bossHp = null;
		boolean bossHpUnavailable = false;
		try
		{
			bossHp = interactingBossHpRatio(client, player);
		}
		catch (RuntimeException unavailable)
		{
			bossHpUnavailable = true;
		}
		ContextBlock context = EncounterContextCapture
			.capture(client, player, damageMap == null ? null : damageMap.catalog(), bossHp).toBuilder()
			.encounterWaveCue(
				encounterWaveCues.snapshot(client, damageMap == null ? null : damageMap.catalog(), sceneKey))
			.build();
		if (bossHpUnavailable)
			context.getEvidence().put("bossHpRatio", "UNAVAILABLE");
		if (context.getEvidence().values().stream()
			.anyMatch(value -> "UNAVAILABLE".equals(value) || "UNRECOGNIZED_VALUE".equals(value)))
			captureIssues.add("context: some independent observations unavailable or uninterpreted");
		return context;
	}

	/** Health ratio (0-100) of the NPC the player is fighting, or null if none/no health bar. */
	private Integer interactingBossHpRatio(Client client, Player lp)
	{
		Actor target = lp.getInteracting();
		if (!(target instanceof net.runelite.api.NPC))
		{
			return null;
		}
		net.runelite.api.NPC npc = (net.runelite.api.NPC) target;
		int ratio = npc.getHealthRatio();
		int scale = npc.getHealthScale();
		if (ratio < 0 || scale <= 0)
		{
			return null;
		}
		return Math.round(ratio * 100f / scale);
	}

	private int safeVarbit(Client client, int id)
	{
		try
		{
			return client.getVarbitValue(id);
		}
		catch (Exception e)
		{
			return 0;
		}
	}

	// ---- helpers ----

	public int[] captureGraphics(Actor actor)
	{
		return spotAnims(actor);
	}

	private int[] spotAnims(Actor actor)
	{
		List<Integer> ids = new ArrayList<>();
		try
		{
			if (actor.getSpotAnims() != null)
			{
				for (ActorSpotAnim sa : actor.getSpotAnims())
				{
					if (sa != null)
					{
						ids.add(sa.getId());
					}
				}
			}
		}
		catch (Exception ignored)
		{
			// older behaviour: fall back to the single graphic
		}
		int g = actor.getGraphic();
		if (g != -1 && !ids.contains(g))
		{
			ids.add(g);
		}
		int[] arr = new int[ids.size()];
		for (int i = 0; i < arr.length; i++)
		{
			arr[i] = ids.get(i);
		}
		return arr;
	}

	private Integer interactingIndex(Actor actor)
	{
		Actor i = actor.getInteracting();
		return i instanceof NPC ? ((NPC) i).getIndex() : null;
	}

	private List<String> activePrayers(Client client)
	{
		List<String> out = new ArrayList<>();
		for (Prayer p : Prayer.values())
		{
			if (client.isPrayerActive(p))
			{
				out.add(p.name());
			}
		}
		return out;
	}

	private String overheadOf(List<String> prayers)
	{
		for (String p : prayers)
		{
			if (OVERHEADS.contains(p))
			{
				return p;
			}
		}
		return null;
	}

	private String deriveAction(boolean moving, boolean attacking, int cooldown, boolean animating,
		boolean priorPositionKnown)
	{
		if (attacking && moving)
		{
			return "MOVING_AND_ATTACKING";
		}
		if (attacking)
		{
			return "ATTACKING";
		}
		if (moving)
		{
			return "MOVING";
		}
		if (cooldown > 0)
		{
			return "COOLDOWN";
		}
		return animating ? "ANIMATING" : priorPositionKnown ? "STATIONARY" : "UNDETERMINED";
	}

	private String clean(String name)
	{
		if (name == null)
		{
			return null;
		}
		return name.replaceAll("<[^>]*>", "").trim();
	}
}
