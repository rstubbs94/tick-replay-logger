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
package com.tickreplaylogger;

import com.tickreplaylogger.capture.DamageMap;
import com.tickreplaylogger.capture.CallbackFieldObservation;
import com.tickreplaylogger.capture.ObjectHazards;
import com.tickreplaylogger.capture.PrayerObservation;
import com.tickreplaylogger.capture.WorldCaptureService;
import com.tickreplaylogger.capture.WorldSnapshotRecorder;
import com.tickreplaylogger.model.DamageEvent;
import com.tickreplaylogger.model.TickRecording;
import com.tickreplaylogger.model.MovementData;
import com.tickreplaylogger.model.PlayerAction;
import com.tickreplaylogger.model.TickData;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.DeathRecordingV2;
import com.tickreplaylogger.model.world.EffectEvent;
import com.tickreplaylogger.model.world.ProjectileState;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.ui.TickReplayLoggerPanel;
import com.tickreplaylogger.util.RecordingStorage;
import com.tickreplaylogger.util.TickRecorder;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Projectile;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.VarPlayer;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import com.google.gson.Gson;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@PluginDescriptor(name = "Tick Replay Logger", description = "Records tick-by-tick combat actions for post-death analysis and replay", tags =
{ "combat", "pvm", "learning", "death", "trace", "analysis", "tick", "replay",
	"logger" }, internalName = "tick-replay-logger", legacyDataDirectory = "tick-replay-logger")
public class TickReplayLoggerPlugin extends Plugin
{
	private static final int DEFAULT_TICK_CAPACITY = 50;
	private static final int DEFAULT_MAX_DEATHS = 3;

	@Inject
	private Client client;

	@Inject
	private TickReplayLoggerConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private net.runelite.client.game.WorldService worldService;

	@Inject
	private java.util.concurrent.ScheduledExecutorService executorService;

	@Inject
	private Gson gson;

	@Inject
	private net.runelite.client.callback.ClientThread clientThread;

	private TickRecorder tickRecorder;
	private RecordingStorage recordingStorage;
	private TickReplayLoggerPanel panel;
	private NavigationButton navButton;

	// --- Rich death-reconstruction capture (WorldSnapshot buffer feeding the versioned export) ---
	private WorldCaptureService captureService;
	private final ObjectHazards.ObservationIdentities surfaceObservationIds = new ObjectHazards.ObservationIdentities();
	private WorldSnapshotRecorder worldRecorder;
	private DamageMap damageMap;
	// Attributed NPC id/spotanim bookkeeping for labelling + effect dedupe.
	private int lastAttributionNpcId = -1;
	private Integer lastAttributionNpcLevel;
	private boolean lastAttributionNormalContext;
	private int lastAttributionProjectileId = -1;
	private DamageMap.AttackRule lastAttributionAttackRule;
	private String lastAttributionMapRef;
	private boolean projectileMatchAmbiguous;
	private String lastAttributionAttackName;
	private NPC pickpocketTarget;
	private int pickpocketTargetTick;
	private final List<String> failedPickpockets = new ArrayList<>();
	// RuneLite's CrowdsourcingThieving uses this server-message format too.
	private static final java.util.regex.Pattern PICKPOCKET_FAILURE = java.util.regex.Pattern
		.compile("You fail to pick (.+)'s pocket\\.");
	private final java.util.Set<Integer> activeEffectSpotanims = new java.util.HashSet<>();
	private Integer lastEffectGraphicSampleTick;
	private long lastEffectGraphicSampleEpoch = Long.MIN_VALUE;
	private final List<ProjectileState> projectilesThisTick = new ArrayList<>();
	private final java.util.IdentityHashMap<Projectile, Long> projectileIds = new java.util.IdentityHashMap<>();
	private final java.util.IdentityHashMap<Projectile, Integer> projectileSpawnTicks = new java.util.IdentityHashMap<>();
	private final java.util.IdentityHashMap<Projectile, LocalPoint> projectileTargets = new java.util.IdentityHashMap<>();
	private final java.util.IdentityHashMap<Projectile, ProjectileState> projectileCallbackStates = new java.util.IdentityHashMap<>();
	private final com.tickreplaylogger.capture.ProjectileSourceTracker projectileSourceTracker = new com.tickreplaylogger.capture.ProjectileSourceTracker();
	private com.tickreplaylogger.capture.SourceAssociationNpcIndex sourceAssociationNpcs =
		new com.tickreplaylogger.capture.SourceAssociationNpcIndex(null);
	private final Map<Long, PendingProjectile> projectileSourceLinks = new java.util.HashMap<>();
	private List<ProjectileState> settledProjectileSamples;
	private long nextProjectileId;
	private long observationSequence;
	private boolean gameTickInProgress;
	private boolean captureSessionObserved;
	private String lastAttributionActorRef;
	private String lastAttributionEvidence;
	// Correlates a matched projectile's spawn tick to a hitsplat's impact tick (launchâ†”impact Â§3).
	private int sceneLoadSeq = 0;
	private String currentSceneKey = null;
	private boolean needSceneCapture = false;
	private String sceneScopeSignature;
	private boolean primarySceneReserved;
	private long causalSceneEpoch;
	private WorldPoint lastCaptureWorld = null;

	// --- Damage source attribution system ---

	// Tracks recent NPCs that have targeted the player, keyed by NPC index
	private final Map<Integer, AttackerRecord> recentAttackers = new LinkedHashMap<>();
	private static final int ATTACKER_EXPIRY_TICKS = 15; // ~9 seconds

	// Tracks incoming projectiles for correlating with hitsplats
	private final List<PendingProjectile> pendingProjectiles = new ArrayList<>();
	private final List<String> projectileCaptureIssues = new ArrayList<>();
	private final java.util.Set<Projectile> processedProjectiles = new java.util.HashSet<>();

	// Game tick counter for recency calculations
	private int currentGameTick = 0;

	private final List<PendingHit> pendingHits = new ArrayList<>();
	private final Map<Long, DamageRecord> resourceImpactContexts = new LinkedHashMap<>();
	private static final int MAX_RESOURCE_IMPACTS_PER_TICK = 256;
	private int resourceImpactQueueCount;
	/** Scoped to one callback lookup or deferred batch, restored even if an optional read fails. */
	private NativeHitScope activeHitScope;

	private static final class NativeHitScope
	{
		final Player localPlayer;
		final net.runelite.api.WorldView view;
		final net.runelite.api.Scene scene;
		final Integer viewId, baseX, baseY, plane, width, height;
		final boolean known;

		NativeHitScope(Player localPlayer, net.runelite.api.WorldView view, net.runelite.api.Scene scene,
			Integer viewId, Integer baseX, Integer baseY, Integer plane, Integer width, Integer height)
		{
			this.localPlayer = localPlayer;
			this.view = view;
			this.scene = scene;
			this.viewId = viewId;
			this.baseX = baseX;
			this.baseY = baseY;
			this.plane = plane;
			this.width = width;
			this.height = height;
			known = view != null && scene != null && baseX != null && baseY != null && plane != null && plane >= 0
				&& width != null && width > 0 && height != null && height > 0;
		}
	}

	private NativeHitScope readNativeHitScope(Player localPlayer, net.runelite.api.WorldView view, Integer originalId,
		boolean idAlreadyObserved, List<String> issues, String prefix)
	{
		if (view == null)
			return new NativeHitScope(localPlayer, null, null, null, null, null, null, null, null);
		Integer id = originalId;
		if (!idAlreadyObserved)
			try
			{
				id = view.getId();
			}
			catch (RuntimeException unavailable)
			{
				/* Raw ID availability does not choose a damage source. */}
		return new NativeHitScope(localPlayer, view, readHitContext(prefix + "Scene", view::getScene, issues), id,
			readHitContext(prefix + "BaseX", view::getBaseX, issues),
			readHitContext(prefix + "BaseY", view::getBaseY, issues),
			readHitContext(prefix + "Plane", view::getPlane, issues),
			readHitContext(prefix + "Width", view::getSizeX, issues),
			readHitContext(prefix + "Height", view::getSizeY, issues));
	}

	private <T> T inNativeHitScope(NativeHitScope scope, java.util.function.Supplier<T> read)
	{
		NativeHitScope previous = activeHitScope;
		activeHitScope = scope;
		try
		{
			return read.get();
		}
		finally
		{
			activeHitScope = previous;
		}
	}

	private int unresolvedImpactTick = -1, unresolvedImpactCount;
	private final List<Map<String, Object>> unresolvedImpactObservations = new ArrayList<>();
	private PendingDeath pendingDeath;
	private volatile String capturedRsn;
	private volatile int capturedWorld;

	/** Immutable impact observation; attacker evidence is resolved after packet processing. */
	private static class PendingHit
	{
		final int amount;
		final int hitsplatType;
		final DamageEvent.DamageType damageType;
		final List<String> prayers;
		final WorldPoint position;
		final LocalPoint localPosition;
		final List<String> observedHazards;
		final List<java.util.Map<String, Object>> hazardWitnesses;
		final int baseX;
		final int baseY;
		final net.runelite.api.WorldView worldView;
		final net.runelite.api.Scene scene;
		final long epoch;
		final int plane;
		final Integer observedCycle;
		final long sequence;
		final PrayerObservation prayerObservation;
		final com.tickreplaylogger.model.world.ActorGraphicsState graphicsObservation;
		NativeHitScope nativeScope;
		String impactSceneKey;
		final List<String> captureIssues = new ArrayList<>();
		String defenderIdentityEvidence = "CALLBACK_LOCAL_REFERENCE_MATCH";
		boolean hpOutcomeKnown = true;
		List<String> possibleOutcomeSurfaces = java.util.Collections.emptyList();
		Map<String, Object> inventorySourceObservation;

		PendingHit(int amount, int hitsplatType, DamageEvent.DamageType damageType, List<String> prayers,
			WorldPoint position, LocalPoint localPosition, List<String> observedHazards, int baseX, int baseY,
			Integer observedCycle, long sequence, PrayerObservation prayerObservation,
			net.runelite.api.WorldView worldView, net.runelite.api.Scene scene,
			List<java.util.Map<String, Object>> hazardWitnesses, long epoch, int plane,
			com.tickreplaylogger.model.world.ActorGraphicsState graphicsObservation)
		{
			this.amount = amount;
			this.hitsplatType = hitsplatType;
			this.damageType = damageType;
			this.prayers = new ArrayList<>(prayers);
			this.position = position;
			this.localPosition = localPosition;
			this.observedHazards = new ArrayList<>(observedHazards);
			this.hazardWitnesses = new ArrayList<>();
			for (java.util.Map<String, Object> witness : hazardWitnesses)
				this.hazardWitnesses.add(new java.util.LinkedHashMap<>(witness));
			this.baseX = baseX;
			this.baseY = baseY;
			this.worldView = worldView;
			this.scene = scene;
			this.epoch = epoch;
			this.plane = plane;
			this.observedCycle = observedCycle;
			this.sequence = sequence;
			this.prayerObservation = prayerObservation;
			this.graphicsObservation = graphicsObservation;
		}
	}

	/** Primitive callback facts survive independently of optional enrichment reads. */
	private static class CallbackContext
	{
		GameState state;
		Player localPlayer;
		Integer cycle;
		boolean allowed, ready;
		final List<String> issues = new ArrayList<>();
		final java.util.IdentityHashMap<Actor, com.tickreplaylogger.capture.ActorCallbackObservation.Sample> actorSamples = new java.util.IdentityHashMap<>();
		final java.util.IdentityHashMap<Actor, String> actorReferences = new java.util.IdentityHashMap<>();
		boolean primaryAttempted, primaryKnown, localViewSampled;
		net.runelite.api.WorldView primaryView, localView;
		Integer primaryPlane, primaryViewId;
		String primarySceneKey;
	}

	private CallbackContext callbackContext()
	{
		CallbackContext c = new CallbackContext();
		c.state = readHitContext("callbackGameState", client::getGameState, c.issues);
		if (c.state == GameState.LOGGED_IN)
			captureSessionObserved = true;
		c.ready = c.state == GameState.LOGGED_IN;
		c.allowed = c.ready || captureSessionObserved && (c.state == null || java.util.Arrays
			.asList(GameState.LOADING, GameState.CONNECTION_LOST, GameState.HOPPING, GameState.UNKNOWN)
			.contains(c.state));
		if (!c.allowed)
			return c;
		if (c.ready)
		{
			c.localPlayer = readHitContext("callbackLocalPlayer", client::getLocalPlayer, c.issues);
			if (c.localPlayer == null)
				c.issues.add("callbackLocalPlayer: unavailable");
		}
		else
			c.issues.add("callbackWorldContext: unavailable");
		c.cycle = readHitContext("callbackGameCycle", client::getGameCycle, c.issues);
		return c;
	}

	private static class PendingDeath
	{
		final long timestamp;
		final String name;
		final int world;
		final boolean worldKnown;
		final WorldPoint position;
		final List<String> issues;

		PendingDeath(String name, Integer world, WorldPoint position, List<String> issues)
		{
			this.timestamp = System.currentTimeMillis();
			this.name = name;
			this.world = world == null ? 0 : world;
			this.worldKnown = world != null;
			this.position = position;
			this.issues = new ArrayList<>(issues);
		}
	}

	private static class UnassignedHit
	{
		Actor actor;
		int amount, type;
		String surface;
		Integer hp;
		long sequence;
		CallbackContext context;
		com.tickreplaylogger.model.world.ActorGraphicsState graphicsObservation;
	}

	private static class UnassignedDeath
	{
		Actor actor;
		long sequence;
		CallbackContext context;
		PendingDeath death;
	}

	private final List<UnassignedHit> unassignedHits = new ArrayList<>();
	private final List<UnassignedDeath> unassignedDeaths = new ArrayList<>();

	// True while a genuine login/logout is in progress. Only a real login resets the tick
	// counter and buffer; crossing a loading boundary (region change, instance, teleport)
	// must PRESERVE recorded ticks. GameState.LOGGED_IN fires after every loading screen, so
	// we distinguish a fresh session (preceded by LOGGING_IN / LOGIN_SCREEN) from a map load.
	private boolean loginPending = false;

	// Bias constant for projectile proximity fallback (in world tiles)
	private static final int WORLD_DISTANCE_BIAS_TILES = 1;

	/**
	 * Record of an NPC that has recently targeted or attacked the player.
	 */
	private static class AttackerRecord
	{
		final String name;
		final int npcIndex;
		final int tickRecorded;
		final NPC actor;
		final net.runelite.api.WorldView view;
		final net.runelite.api.Scene scene;
		final long epoch;
		final int plane;

		AttackerRecord(String name, int npcIndex, int tickRecorded, NPC actor, net.runelite.api.WorldView view,
			net.runelite.api.Scene scene, long epoch, int plane)
		{
			this.name = name;
			this.npcIndex = npcIndex;
			this.tickRecorded = tickRecorded;
			this.actor = actor;
			this.view = view;
			this.scene = scene;
			this.epoch = epoch;
			this.plane = plane;
		}
	}

	/**
	 * A projectile in flight targeting (or near) the player, awaiting hitsplat correlation.
	 */
	private static class PendingProjectile
	{
		final String sourceName;
		final int projectileId;
		final int spawnTick;
		final int estimatedArrivalTick;
		final int travelTicks;
		final boolean hadSourceActor;
		final boolean sourceIsPlayer;
		final int sourceNpcId;
		final Integer sourceNpcLevel;
		final boolean normalContext;
		final boolean timingKnown;
		final Projectile observedObject;
		Integer removedObservedTick;
		ProjectileState latestObservation;
		boolean sourceSamplingUnavailable;
		final String sourceRef;
		final Long projectileObservationId;
		final int endCycle;
		final net.runelite.api.WorldView worldView;
		final net.runelite.api.Scene scene;
		final long epoch;
		final int plane;

		final java.util.Set<Long> ambiguousForSequences = new java.util.LinkedHashSet<>();

		PendingProjectile(String sourceName, int projectileId, int spawnTick, int estimatedArrivalTick, int travelTicks,
			boolean hadSourceActor, boolean sourceIsPlayer, int sourceNpcId, String sourceRef, Integer sourceNpcLevel,
			int endCycle, net.runelite.api.WorldView worldView, net.runelite.api.Scene scene, boolean normalContext,
			long epoch, int plane, boolean timingKnown, Projectile observedObject, Long projectileObservationId)
		{
			this.sourceName = sourceName;
			this.projectileId = projectileId;
			this.spawnTick = spawnTick;
			this.estimatedArrivalTick = estimatedArrivalTick;
			this.travelTicks = travelTicks;
			this.hadSourceActor = hadSourceActor;
			this.sourceIsPlayer = sourceIsPlayer;
			this.sourceNpcId = sourceNpcId;
			this.sourceNpcLevel = sourceNpcLevel;
			this.normalContext = normalContext;
			this.timingKnown = timingKnown;
			this.observedObject = observedObject;
			this.projectileObservationId = projectileObservationId;
			this.sourceRef = sourceRef;
			this.endCycle = endCycle;
			this.worldView = worldView;
			this.scene = scene;
			this.epoch = epoch;
			this.plane = plane;
		}
	}

	// Attribution result of the most recent determineDamageSource() call (for the rich DamageRecord).
	private String lastAttributionConfidence = "UNKNOWN";
	private java.util.List<java.util.Map<String, Object>> lastAttributionCandidates = new ArrayList<>();
	private int lastAttributionLaunchTick = 0;
	private boolean lastAttributionSourceIsPlayer = false;

	// --- Telemetry: tick duration and ping ---
	private long lastTickTimeMs = 0;
	private volatile int currentPingMs = -1;
	private java.util.concurrent.ScheduledFuture<?> pingFuture;

	// Packet loss tracking â€” rolling window of recent ping results (true = success, false = timeout)
	private static final int PACKET_LOSS_WINDOW = 10;
	private final java.util.LinkedList<Boolean> pingResults = new java.util.LinkedList<>();

	// Track player position for movement detection
	private WorldPoint lastPlayerPosition = null;
	private String lastMovementScope = null;

	// Track prayers for detecting forced deactivation (drained/disabled by NPC)
	private List<String> lastTickActivePrayers = new ArrayList<>();
	private boolean lastTickPrayerStateKnown;
	private int lastTickPrayerPoints = 0;
	private boolean lastTickPrayerPointsKnown;
	private final java.util.Set<String> manuallyDeactivatedPrayers = new java.util.HashSet<>();

	// Mutually exclusive prayer groups (only one can be active at a time)
	// All overhead prayers are mutually exclusive (protection, Retribution, Redemption, Smite)
	private static final java.util.Set<String> OVERHEAD_PRAYERS = java.util.Set.of("Protect From Melee",
		"Protect From Missiles", "Protect From Magic", "Retribution", "Redemption", "Smite");

	// Defence boosting prayers (conflict with each other)
	private static final java.util.Set<String> DEFENCE_PRAYERS = java.util.Set.of("Thick Skin", "Rock Skin",
		"Steel Skin", "Chivalry", "Piety", "Rigour", "Augury");

	// Attack boosting prayers (conflict with Chivalry/Piety)
	private static final java.util.Set<String> ATTACK_PRAYERS = java.util.Set.of("Clarity of Thought",
		"Improved Reflexes", "Incredible Reflexes", "Chivalry", "Piety");

	// Strength boosting prayers (conflict with Chivalry/Piety)
	private static final java.util.Set<String> STRENGTH_PRAYERS = java.util.Set.of("Burst of Strength",
		"Superhuman Strength", "Ultimate Strength", "Chivalry", "Piety");

	// Ranged boosting prayers (conflict with Rigour)
	private static final java.util.Set<String> RANGED_PRAYERS = java.util.Set.of("Sharp Eye", "Hawk Eye", "Eagle Eye",
		"Rigour");

	// Magic boosting prayers (conflict with Augury)
	private static final java.util.Set<String> MAGIC_PRAYERS = java.util.Set.of("Mystic Will", "Mystic Lore",
		"Mystic Might", "Augury");

	// All conflict groups for checking switches
	private static final java.util.List<java.util.Set<String>> PRAYER_CONFLICT_GROUPS = java.util.List
		.of(OVERHEAD_PRAYERS, DEFENCE_PRAYERS, ATTACK_PRAYERS, STRENGTH_PRAYERS, RANGED_PRAYERS, MAGIC_PRAYERS);

	// Track special attack energy for detecting spec usage
	private int lastSpecialAttackPercent = -1;

	// Track pending self-damage from potions (divine, overload, etc.)
	// This prevents double-counting when the hitsplat fires on the next tick
	private String pendingSelfDamageSource;
	private final com.tickreplaylogger.capture.SelfDamageInputs selfDamageInputs = new com.tickreplaylogger.capture.SelfDamageInputs();
	private net.runelite.api.Scene selfDamageScopeScene;
	private net.runelite.api.WorldView selfDamageScopeView;
	private boolean selfDamageScopeKnown;
	private int selfDamageScopeBaseX, selfDamageScopeBaseY, selfDamageScopePlane;
	private long selfDamageScopeEpoch;
	private int pendingSelfDamageTick;
	private int pendingSelfDamageItemId;
	private int pendingSelfDamageSlot;
	private Integer pendingSelfDamageQuantity;

	// Previous-tick boosted combat levels [att,str,def,range,mage] for stat-drain effect detection.
	private int[] lastBoostedCombat = null;

	// DoT onset detection: previous poison varp (0 clear, >0 poisoned, >=1,000,000 venomed).
	private int lastPoisonVarp = -1;
	private boolean poisonBaselineObserved;
	private Integer lastPoisonSampleTick;

	// Perf guard: warn (throttled) when a tick's world capture exceeds this many ms of the 600ms budget.
	private static final long CAPTURE_BUDGET_WARN_MS = 20;
	private int lastCaptureWarnTick = -100;

	@Override
	protected void startUp() throws Exception
	{
		boolean replacingCapture = worldRecorder != null;
		// Freeze this process's provenance before a later development rebuild replaces resources.
		com.tickreplaylogger.capture.CaptureBuild.sourceTreeHash();
		tickRecorder = new TickRecorder(config.ticksToRecord());
		recordingStorage = new RecordingStorage(config.maxDeathsToStore(), gson, getPluginDirectory());
		captureService = new WorldCaptureService(surfaceObservationIds);
		damageMap = DamageMap.load(gson);
		sourceAssociationNpcs = com.tickreplaylogger.capture.SourceAssociationNpcIndex.load(gson);
		captureService.setDamageMap(damageMap);
		worldRecorder = new WorldSnapshotRecorder(config.ticksToRecord(), gson);
		if (replacingCapture && clientThread != null)
		{
			WorldSnapshotRecorder startingRecorder = worldRecorder;
			clientThread.invokeLater(() ->
			{
				if (worldRecorder == startingRecorder)
				{
					resetTransientTracking();
					tickRecorder.clear();
					startingRecorder.clear();
				}
			});
		}

		// Create panel
		panel = new TickReplayLoggerPanel(recordingStorage, spriteManager, gson, config, damageMap);
		panel.setTickRecorder(tickRecorder);
		panel.setLiveSnapshotPreparer(this::prepareLiveExport);
		panel.setLiveViewEnabled(config.enableLiveView());
		TickReplayLoggerPanel startingPanel = panel;
		recordingStorage.ready().whenComplete((ignored, error) -> startingPanel.storageCompleted(error));

		// Create navigation button with panel icon
		final BufferedImage icon = ImageUtil.loadImageResource(TickReplayLoggerPlugin.class, "panel_icon.png");
		navButton = NavigationButton.builder().tooltip("Tick Replay Logger").icon(icon).priority(6).panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);

		// Start background ping polling (every 2 seconds)
		pingFuture = executorService.scheduleWithFixedDelay(this::pingCurrentWorld, 1000, 2000,
			java.util.concurrent.TimeUnit.MILLISECONDS);

		log.debug("Tick Replay Logger plugin started! Recording last {} ticks, storing {} deaths.",
			config.ticksToRecord(), config.maxDeathsToStore());
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientToolbar.removeNavigation(navButton);
		TickReplayLoggerPanel closingPanel = panel;
		if (closingPanel != null)
		{
			closingPanel.dispose();
		}
		if (recordingStorage != null)
		{
			recordingStorage.close();
		}
		if (pingFuture != null)
		{
			pingFuture.cancel(true);
			pingFuture = null;
		}

		WorldSnapshotRecorder closingRecorder = worldRecorder;
		TickRecorder closingJournal = tickRecorder;
		WorldCaptureService closingCapture = captureService;
		Runnable releaseCapture = () ->
		{
			if (closingRecorder != null)
				closingRecorder.clear();
			if (closingJournal != null)
				closingJournal.clear();
			if (closingCapture != null)
				closingCapture.resetActors();
			// An immediate re-enable has its own new models. Never clear those from an old callback.
			if (worldRecorder == closingRecorder && panel == closingPanel)
			{
				resetTransientTracking();
				captureService = null;
				worldRecorder = null;
				panel = null;
				navButton = null;
			}
		};
		if (clientThread == null)
			releaseCapture.run();
		else
			clientThread.invokeLater(releaseCapture);
		log.debug("Tick Replay Logger plugin stopped!");
	}

	/**
	 * Ping the current game world server on a background thread.
	 * Uses RuneLite's built-in Ping utility (same as world hopper).
	 */
	private void pingCurrentWorld()
	{
		try
		{
			// Respect the config toggle: no network activity when ping tracking is disabled
			if (!config.trackPing())
			{
				return;
			}

			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}

			net.runelite.http.api.worlds.WorldResult worldResult = worldService.getWorlds();
			if (worldResult == null)
			{
				return;
			}

			net.runelite.http.api.worlds.World currentWorld = worldResult.findWorld(client.getWorld());
			if (currentWorld == null)
			{
				return;
			}

			int ping = net.runelite.client.plugins.worldhopper.ping.Ping.ping(currentWorld, true);
			if (ping >= 0)
			{
				currentPingMs = ping;
				recordPingResult(true);
			}
			else
			{
				recordPingResult(false);
			}
		}
		catch (Exception e)
		{
			log.debug("Ping measurement failed: {}", e.getMessage());
		}
	}

	/**
	 * Record a ping attempt result for packet loss calculation.
	 */
	private synchronized void recordPingResult(boolean success)
	{
		pingResults.addLast(success);
		while (pingResults.size() > PACKET_LOSS_WINDOW)
		{
			pingResults.removeFirst();
		}
	}

	/**
	 * Calculate packet loss percentage from the rolling window.
	 * @return 0-100 packet loss percentage, or 0 if no data
	 */
	private synchronized int getPacketLossPercent()
	{
		if (pingResults.isEmpty())
		{
			return 0;
		}
		int failures = 0;
		for (Boolean success : pingResults)
		{
			if (!success)
			{
				failures++;
			}
		}
		return (failures * 100) / pingResults.size();
	}

	/**
	 * Reset ping/packet-loss state. Synchronized because {@link #pingResults} is also touched
	 * by the background ping thread; the surrounding reset fields are client-thread only.
	 */
	private synchronized void clearPingData()
	{
		pingResults.clear();
		currentPingMs = -1;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN && captureService != null)
			captureService.resetEncounterWaveCues();
		if (java.util.Arrays.asList(GameState.STARTING, GameState.LOGIN_SCREEN, GameState.LOGIN_SCREEN_AUTHENTICATOR,
			GameState.LOGGING_IN).contains(event.getGameState()))
			captureSessionObserved = false;
		switch (event.getGameState())
		{
		case LOGGING_IN:
			// A genuine login is starting; the next LOGGED_IN is a fresh session
			loginPending = true;
			break;

		case LOADING:
			causalSceneEpoch++;
			pickpocketTarget = null;
			failedPickpockets.clear();
			if (captureService != null)
				captureService.sceneLoading();
			needSceneCapture = true;
			primarySceneReserved = false;
			break;
		case CONNECTION_LOST:
		case HOPPING:
			causalSceneEpoch++;
			lastPlayerPosition = null;
			lastMovementScope = null;
			lastCaptureWorld = null;
			if (captureService != null)
				captureService.invalidateActorTiming();
			needSceneCapture = true;
			primarySceneReserved = false;
			break;

		case LOGIN_SCREEN:
			// Logged out â€” clear everything and treat the next login as fresh
			loginPending = true;
			if (tickRecorder != null)
			{
				tickRecorder.clear();
				log.debug("Player logged out - tick recorder cleared");
			}
			if (worldRecorder != null)
			{
				worldRecorder.clear();
			}
			resetTransientTracking();
			break;

		case LOGGED_IN:
			if (loginPending)
			{
				// Fresh login: restart the tick counter and buffer for a new session
				loginPending = false;
				if (tickRecorder != null)
				{
					tickRecorder.resetTickCounter();
					log.debug("Player logged in - tick recorder reset");
				}
				if (worldRecorder != null)
				{
					worldRecorder.clear();
				}
				resetTransientTracking();
			}
			else
			{
				// Region change / instance load / reconnect: PRESERVE recorded ticks.
				// Only drop the last position so the load isn't logged as a huge movement.
				lastPlayerPosition = null;
				log.debug("Map load - preserving recorded ticks");
			}
			// A scene (re)loaded â€” capture its collision grid on the next tick (Â§4b).
			needSceneCapture = true;
			primarySceneReserved = false;
			captureSessionObserved = true;
			break;

		default:
			break;
		}
	}

	@Subscribe
	public void onWorldViewLoaded(net.runelite.api.events.WorldViewLoaded event)
	{
		worldViewLifecycle("WORLD_VIEW_LOADED", event.getWorldView());
	}

	@Subscribe
	public void onWorldViewUnloaded(net.runelite.api.events.WorldViewUnloaded event)
	{
		worldViewLifecycle("WORLD_VIEW_UNLOADED", event.getWorldView());
	}

	private void worldViewLifecycle(String type, net.runelite.api.WorldView view)
	{
		// A view can reload without a top-level LOADING transition. Earlier causal hypotheses
		// remain observations but cannot establish a hit across this lifetime boundary.
		causalSceneEpoch++;
		CallbackFieldObservation fields = new CallbackFieldObservation();
		if (view == null)
			fields.value("worldViewId", () -> null);
		else
			fields.value("worldViewId", view::getId);
		String status = "available";
		try
		{
			if (captureService != null)
				captureService.worldViewLifecycle(client, view);
		}
		catch (RuntimeException unavailable)
		{
			status = "partial";
			log.debug("World view capture reset incomplete", unavailable);
		}
		fields.put("causalSceneEpoch", causalSceneEpoch);
		fields.put("captureResetStatus", status);
		fields.put("scopeKnown", false);
		observe(type, null, null, fields.data());
	}

	/**
	 * Reset the per-session transient tracking state (combat attribution, telemetry, prayer/
	 * movement bookkeeping). Does NOT touch the recorded tick buffer.
	 */
	private void resetTransientTracking()
	{
		captureSessionObserved = false;
		unassignedHits.clear();
		unassignedDeaths.clear();
		capturedRsn = null;
		capturedWorld = 0;
		unresolvedImpactTick = -1;
		unresolvedImpactCount = 0;
		unresolvedImpactObservations.clear();
		projectileIds.clear();
		projectileSpawnTicks.clear();
		projectileTargets.clear();
		projectileCallbackStates.clear();
		projectileSourceTracker.reset();
		projectileSourceLinks.clear();
		settledProjectileSamples = null;
		nextProjectileId = 0;
		observationSequence = 0;
		pickpocketTarget = null;
		failedPickpockets.clear();
		recentAttackers.clear();
		pendingProjectiles.clear();
		processedProjectiles.clear();
		pendingHits.clear();
		resourceImpactContexts.clear();
		resourceImpactQueueCount = 0;
		pendingDeath = null;
		currentGameTick = 0;
		lastTickTimeMs = 0;
		clearPingData();
		lastPlayerPosition = null;
		lastMovementScope = null;
		lastCaptureWorld = null;
		lastTickActivePrayers.clear();
		lastTickPrayerStateKnown = false;
		lastTickPrayerPoints = 0;
		lastTickPrayerPointsKnown = false;
		manuallyDeactivatedPrayers.clear();
		lastSpecialAttackPercent = -1;
		pendingSelfDamageSource = null;
		selfDamageInputs.clear();
		selfDamageScopeKnown = false;
		projectilesThisTick.clear();
		currentSceneKey = null;
		sceneScopeSignature = null;
		primarySceneReserved = false;
		if (captureService != null)
			captureService.resetActors();
		lastBoostedCombat = null;
		lastAttributionNpcId = -1;
		lastAttributionNpcLevel = null;
		lastAttributionActorRef = null;
		lastAttributionEvidence = "UNMATCHED";
		lastAttributionCandidates = new ArrayList<>();

		activeEffectSpotanims.clear();
		lastPoisonVarp = -1;
		poisonBaselineObserved = false;
		lastAttributionConfidence = "UNKNOWN";
		lastAttributionLaunchTick = 0;
		lastAttributionSourceIsPlayer = false;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals("tickreplaylogger"))
		{
			return;
		}
		if (clientThread != null && !client.isClientThread())
		{
			clientThread.invokeLater(() ->
			{
				if (panel != null)
					applyConfigChanged(event);
			});
			return;
		}
		applyConfigChanged(event);
	}

	private void applyConfigChanged(ConfigChanged event)
	{
		if (event.getKey().equals("captureWorldSnapshots") && captureService != null)
			captureService.resetEncounterWaveCues();

		if (event.getKey().equals("ticksToRecord"))
		{
			int newCapacity = config.ticksToRecord();
			if (tickRecorder != null)
			{
				tickRecorder.resize(newCapacity);
			}
			if (worldRecorder != null)
			{
				worldRecorder.resize(newCapacity);
			}
		}
		else if (event.getKey().equals("maxDeathsToStore"))
		{
			int newMax = config.maxDeathsToStore();
			if (recordingStorage != null)
			{
				TickReplayLoggerPanel currentPanel = panel;
				recordingStorage.setMaxDeaths(newMax).whenComplete((ignored, error) ->
				{
					if (currentPanel != null)
						currentPanel.storageCompleted(error);
				});
			}
		}
		else if (event.getKey().equals("enableLiveView") && panel != null)
		{
			panel.setLiveViewEnabled(config.enableLiveView());
		}
		else if (event.getKey().equals("captureEffects"))
		{
			poisonBaselineObserved = false;
			lastBoostedCombat = null;
			activeEffectSpotanims.clear();
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		CallbackContext c = callbackContext();
		if (!c.allowed)
			return;
		Actor actor = event.getActor();
		if (actor == null)
			return;
		boolean local = actor == c.localPlayer;
		Map<String, Object> actorState = callbackActorData(actor, c);
		com.tickreplaylogger.capture.ActorCallbackObservation.Sample original = c.actorSamples.get(actor);
		String name = local && actorState.get("name") instanceof String ? (String) actorState.get("name") : null;
		Integer world = local ? readHitContext("deathWorld", client::getWorld, c.issues) : null;
		WorldPoint position = local && original != null ? original.world() : null;
		if (local)
		{
			retainActorSampleIssue(original, "name", "deathPlayerName", c.issues);
			retainActorSampleIssue(original, "worldPosition", "deathWorldPosition", c.issues);
		}
		PendingDeath death = new PendingDeath(name, world, position, c.issues);
		if (local && pendingDeath == null)
			pendingDeath = death;
		long sequence = observe("ACTOR_DEATH", actor, null, actorState, c);
		if (local)
			addDeathMark("player", c.cycle, sequence, c.issues, "CALLBACK_LOCAL_REFERENCE_MATCH");
		else if (c.localPlayer == null && actor instanceof Player)
		{
			UnassignedDeath deferred = new UnassignedDeath();
			deferred.actor = actor;
			deferred.sequence = sequence;
			deferred.context = c;
			deferred.death = death;
			unassignedDeaths.add(deferred);
		}
		else if (captureActor(actor))
		{
			String ref = readHitContext("deathActorRef", () -> callbackActorRef(actor, c), c.issues);
			addDeathMark(ref, c.cycle, sequence, c.issues, "CALLBACK_ACTOR_REFERENCE");
		}
	}

	private void addDeathMark(String ref, Integer cycle, long sequence, List<String> issues, String identityEvidence)
	{
		if (worldRecorder == null || !config.captureWorldSnapshots())
			return;
		worldRecorder.addDeath(DeathRecordingV2.Events.DeathMark.builder().tick(eventTick()).actorRef(ref)
			.gameCycle(cycle).sequence(sequence).captureIssues(issues.isEmpty() ? null : new ArrayList<>(issues))
			.actorIdentityEvidence(identityEvidence).build());
	}

	/** Match only the actor object actually read at this same delivered boundary. Never bridge identity across ticks. */
	private void settleUnassignedIntake(Player local)
	{
		for (UnassignedHit hit : unassignedHits)
		{
			if (local == null || hit.actor != local)
			{
				tickRecorder.markStateUnavailable("callbackIdentity");
				continue;
			}
			CallbackContext c = hit.context;
			c.issues.add("defenderIdentity: SETTLED_LOCAL_REFERENCE_MATCH");
			recordRecipientLink("HITSPLAT", hit.sequence);
			noteUnresolvedImpact(hit.amount, hit.type, hit.surface, hit.hp, hit.sequence, c.cycle, c.issues);
			if (config.trackDamageReceived()
				&& ("HP".equals(hit.surface) || "BLOCK".equals(hit.surface) && hit.amount == 0
					|| damageMap == null || damageMap.catalog().possibleOutcomeSurfaces(hit.type).contains("HP")))
			{
				PendingHit pending = new PendingHit(hit.amount, hit.type,
					DamageEvent.DamageType
						.fromReference(damageMap == null ? null : damageMap.catalog().hitsplatDamageType(hit.type)),
					java.util.Collections.emptyList(), null, null, java.util.Collections.emptyList(), 0, 0, c.cycle,
					hit.sequence, PrayerObservation.unavailable(), null, null, java.util.Collections.emptyList(),
					causalSceneEpoch, -1, hit.graphicsObservation);
				pending.captureIssues.addAll(c.issues);
				pending.hpOutcomeKnown = "HP".equals(hit.surface) || "BLOCK".equals(hit.surface) && hit.amount == 0;
				pending.possibleOutcomeSurfaces = damageMap == null ? java.util.Arrays.asList("HP", "UNKNOWN")
					: damageMap.catalog().possibleOutcomeSurfaces(hit.type);
				pending.defenderIdentityEvidence = "SETTLED_LOCAL_REFERENCE_MATCH";
				if (!pending.hpOutcomeKnown && resourceImpactQueueCount++ >= MAX_RESOURCE_IMPACTS_PER_TICK)
				{
					tickRecorder.addCaptureIssue("resourceImpacts: attribution queue limit; original callback retained");
					continue;
				}
				pendingHits.add(pending);
			}
		}
		for (UnassignedDeath death : unassignedDeaths)
		{
			if (local != null && death.actor == local)
			{
				recordRecipientLink("ACTOR_DEATH", death.sequence);
				if (pendingDeath == null)
					pendingDeath = death.death;
				addDeathMark("player", death.context.cycle, death.sequence, death.context.issues,
					"SETTLED_LOCAL_REFERENCE_MATCH");
			}
			else
				tickRecorder.markStateUnavailable("callbackIdentity");
		}
		unassignedHits.clear();
		unassignedDeaths.clear();
	}

	private void recordRecipientLink(String type, long sequence)
	{
		if (worldRecorder == null || !config.captureWorldSnapshots())
			return;
		worldRecorder.addObservationLink(com.tickreplaylogger.model.world.ObservedEventLink.builder()
			.tick(currentGameTick).observationSequence(sequence).observationType(type).actorRef("player")
			.resolvedTick(currentGameTick).evidence("SETTLED_LOCAL_REFERENCE_MATCH").build());
	}

	private static boolean distinctFatalSources(List<DamageRecord> candidates)
	{
		if (candidates.size() < 2)
			return false;
		java.util.Set<String> sources = new java.util.LinkedHashSet<>();
		for (DamageRecord hit : candidates)
		{
			if (hit.getAttackerRef() != null && !hit.getAttackerRef().trim().isEmpty())
				sources.add("actor:" + hit.getAttackerRef());
			else if ("STATUS_HITSPLAT".equals(hit.getAttributionEvidence()))
				sources.add("status:" + hit.getDamageType() + ":" + hit.getAttackerName());
			else
				return true; // Equal display names do not identify a shared actor or causal hit.
		}
		return sources.size() > 1;
	}

	private void recordDeath(PendingDeath deathContext)
	{
		log.debug("PLAYER DIED! Recording settled death trace...");

		// Get the tick history
		List<TickData> tickHistory = tickRecorder.getTickHistory();

		if (tickHistory.isEmpty())
		{
			log.warn("No tick data recorded before death");
			return;
		}

		// Use the final observed positive HP hit, preserving its source uncertainty. A recent
		// engager cannot replace a missing or ambiguous fatal observation.
		String killer = "Unknown";
		DamageEvent fatalJournalHit = null;
		List<DamageEvent> journalCandidates = new ArrayList<>();
		for (int i = 0; i < tickHistory.size(); i++)
		{
			if (tickHistory.get(i).getTickNumber() != currentGameTick)
				continue;
			List<DamageEvent> evs = tickHistory.get(i).getDamageEvents();
			if (evs == null)
				continue;
			for (int j = 0; j < evs.size(); j++)
			{
				DamageEvent de = evs.get(j);
				if (de.getDirection() == DamageEvent.DamageDirection.RECEIVED && de.getAmount() > 0)
				{
					fatalJournalHit = de;
					journalCandidates.add(de);
					killer = de.getSource() != null ? de.getSource() : "Unknown";
				}
			}
		}
		List<Map<String, Object>> unresolved = unresolvedImpactTick == currentGameTick
			? new ArrayList<>(unresolvedImpactObservations)
			: new ArrayList<>();
		int unresolvedCount = unresolvedImpactTick == currentGameTick ? unresolvedImpactCount : 0;
		String fatalBasis = unresolvedCount > 0 ? "UNRESOLVED_IMPACT_IN_DEATH_TICK"
			: journalCandidates.isEmpty() ? "NO_POSITIVE_RECEIVED_RECORD_IN_DEATH_TICK"
				: journalCandidates.size() > 1 ? "LAST_POSITIVE_RECEIVED_JOURNAL_RECORD_IN_DEATH_TICK"
					: "UNIQUE_POSITIVE_RECEIVED_JOURNAL_RECORD_IN_DEATH_TICK";
		boolean mixedJournal = journalCandidates.size() > 1 && !journalCandidates.stream()
			.allMatch(hit -> (hit.getDamageType() == DamageEvent.DamageType.POISON
				|| hit.getDamageType() == DamageEvent.DamageType.VENOM)
				&& hit.getDamageType() == journalCandidates.get(0).getDamageType()
				&& java.util.Objects.equals(hit.getSource(), journalCandidates.get(0).getSource()));
		String killerBasis = unresolvedCount > 0 ? "UNRESOLVED_IMPACT_IN_DEATH_TICK"
			: mixedJournal ? "MULTIPLE_DISTINCT_OR_UNLINKED_IMPACTS"
				: journalCandidates.isEmpty() ? "NO_POSITIVE_RECEIVED_RECORD_IN_DEATH_TICK"
					: "SELECTED_RECORD_SOURCE_ONLY";
		String killerConfidence = fatalJournalHit == null || fatalJournalHit.getConfidence() == null ? "UNKNOWN"
			: fatalJournalHit.getConfidence();
		if (unresolvedCount > 0 || mixedJournal)
		{
			killer = "Unknown";
			killerConfidence = "UNKNOWN";
		}
		// Without a fatal observed HP hit, attacker history does not identify a killer.

		// Try to get location
		WorldPoint location = deathContext.position;
		String locationStr = location != null ? "(" + location.getX() + ", " + location.getY() + ")" : "Unknown";

		// Calculate total damage received
		int totalDamage = TickRecording.calculateTotalDamage(tickHistory);

		// Build the rich versioned export (the full Phase-2 payload â€” scenes, per-tick snapshots, events,
		// summary) from the WorldSnapshot buffer FIRST, so it can be stored on the death record and the
		// single "Copy" action emits the complete data.
		DeathRecordingV2 frozenExport = null;
		WorldSnapshotRecorder exportingRecorder = worldRecorder;
		if (worldRecorder != null && config.captureWorldSnapshots())
		{
			try
			{
				DeathRecordingV2.Meta meta = DeathRecordingV2.Meta.builder()
					.recordingId(java.util.UUID.randomUUID().toString()).pluginVersion("1.0.0")
					.captureBuildId(com.tickreplaylogger.capture.CaptureBuild.sourceTreeHash())
					.runeLiteApiVersion(com.tickreplaylogger.capture.CaptureBuild.apiVersion())
					.runeLiteArtifactIdentityScope(com.tickreplaylogger.capture.CaptureBuild.artifactIdentityScope())
					.runeLiteRuntimeVersion(com.tickreplaylogger.capture.CaptureBuild.runtimeVersion())
					.runeLiteRuntimeReferenceVerification(
						com.tickreplaylogger.capture.CaptureBuild.runtimeReferenceVerification())
					.runeLiteCompiledApiVersion(com.tickreplaylogger.capture.CaptureBuild.compiledApiVersion())
					.runeLiteCompiledApiArtifactSha256(
						com.tickreplaylogger.capture.CaptureBuild.compiledApiArtifactSha256())
					.runeLiteCompiledClientArtifactSha256(
						com.tickreplaylogger.capture.CaptureBuild.compiledClientArtifactSha256())
					.runeLiteApiArtifactSha256(com.tickreplaylogger.capture.CaptureBuild.apiArtifactSha256())
					.runeLiteClientArtifactSha256(com.tickreplaylogger.capture.CaptureBuild.clientArtifactSha256())
					.dataMapVersion(damageMap != null ? damageMap.getVersion() : "unknown")
					.referenceCatalogId(damageMap == null ? null : damageMap.catalog().id())
					.exportedAtMs(System.currentTimeMillis()).rsn(deathContext.name).accountType(null)
					.world(deathContext.world).worldKnown(deathContext.worldKnown)
					.captureIssues(deathContext.issues.isEmpty() ? null : new ArrayList<>(deathContext.issues))
					.deathTick(currentGameTick)
					.bufferTicks(com.tickreplaylogger.util.RecordingLimits.ticks(config.ticksToRecord()))
					.captureRadius(0).captureSettings(captureSettings()).build();
				// Callback order selects a record for inspection, not a proven lethal server roll.
				DamageRecord fatal = worldRecorder.lastDamagingHit();
				List<DamageRecord> fatalCandidates = worldRecorder.positiveReceivedAtTick(currentGameTick);
				boolean missingFatal = fatal == null || fatal.getImpactTick() != currentGameTick;
				if (missingFatal)
				{
					fatal = null;
					killer = "Unknown (no HP impact linked to death tick)";
				}
				boolean unresolvedFatal = unresolvedCount > 0
					|| worldRecorder.hasUnresolvedPlayerImpact(currentGameTick, fatal);
				if (unresolvedFatal)
				{
					fatal = null;
					killer = "Unknown (HP/resource impact unresolved)";
				}
				String killerName = killer;
				killerConfidence = unresolvedFatal || missingFatal ? "UNKNOWN" : lastAttributionConfidence;
				if (fatal != null && fatal.getAttackerName() != null)
				{
					killerName = fatal.getAttackerName();
					killerConfidence = fatal.getAttribution();
				}
				boolean mixedFatal = distinctFatalSources(fatalCandidates);
				if (mixedFatal && !unresolvedFatal && !missingFatal)
				{
					killerName = "Unknown";
					killerConfidence = "UNKNOWN";
				}
				killerBasis = unresolvedFatal ? "UNRESOLVED_IMPACT_IN_DEATH_TICK"
					: missingFatal ? "NO_POSITIVE_RECEIVED_RECORD_IN_DEATH_TICK"
						: mixedFatal ? "MULTIPLE_DISTINCT_OR_UNLINKED_IMPACTS" : "SELECTED_RECORD_SOURCE_ONLY";
				DeathRecordingV2.Summary summary = DeathRecordingV2.Summary.builder().killerName(killerName)
					.killerConfidence(killerConfidence).killerBasis(killerBasis)
					.unresolvedImpactObservations(unresolved).unresolvedImpactObservationCount(unresolvedCount)
					.fatalHit(fatal).sourceResolution(fatal == null ? null : fatal.getSourceResolution())
					.fatalHitCandidates(fatalCandidates)
					.fatalHitBasis(unresolvedFatal ? "UNRESOLVED_IMPACT_IN_DEATH_TICK"
						: missingFatal ? "NO_POSITIVE_RECEIVED_RECORD_IN_DEATH_TICK"
							: fatalCandidates.size() > 1 ? "LAST_POSITIVE_RECEIVED_CALLBACK_IN_DEATH_TICK"
								: "UNIQUE_POSITIVE_RECEIVED_RECORD_IN_DEATH_TICK")
					// IMPACT = reactable mid-flight; LAUNCH = not; UNKNOWN/none = leave null.
					.reactable(fatal == null || fatalCandidates.size() > 1 ? null
						: "IMPACT".equals(fatal.getDetermination()) ? Boolean.TRUE
							: "LAUNCH".equals(fatal.getDetermination()) ? Boolean.FALSE : null)
					.activity(null).bossName(null).totalDamageTakenInBuffer(totalDamage)
					.ticksCaptured(worldRecorder.size()).otherPlayersPresent(false).build();
				killer = killerName;
				fatalBasis = summary.getFatalHitBasis();
				frozenExport = worldRecorder
					.freezeExport(meta, summary, tickHistory.get(0).getTickNumber(), currentGameTick).toBuilder()
					.journalTicks(new ArrayList<>(tickHistory)).build();
			}
			catch (Exception e)
			{
				log.debug("Failed to build v2 death export: {}", e.getMessage());
			}
		}

		// Create death record (carries the rich export so a single Copy emits everything)
		TickRecording death = TickRecording.builder().timestamp(deathContext.timestamp).killerName(killer)
			.killerConfidence(killerConfidence).killerBasis(killerBasis).fatalHitBasis(fatalBasis)
			.fatalSourceResolution(frozenExport == null || frozenExport.getSummary() == null ? null
				: frozenExport.getSummary().getSourceResolution())
			.fatalHitCandidates(journalCandidates).unresolvedImpactObservations(unresolved)
			.unresolvedImpactObservationCount(unresolvedCount).location(locationStr).ticks(new ArrayList<>(tickHistory))
			.eventsBeforeSnapshot(true).finalHp(0).finalHpKnown(true) // This wrapper records an observed death, independently of resource reads.
			.totalDamageReceived(totalDamage).build();

		// The selected window no longer depends on the rolling buffer. Finish and persist it on
		// the ordered storage worker, which drains already accepted recordings on shutdown.
		DeathRecordingV2 selectedExport = frozenExport;
		TickReplayLoggerPanel currentPanel = panel;
		recordingStorage.addDeath(() ->
		{
			if (selectedExport != null)
			{
				DeathRecordingV2 completed = exportingRecorder.completeExport(selectedExport);
				DeathRecordingV2.Summary resolved = completed.getSummary();
				return death.toBuilder().richExportJson(gson.toJson(completed))
					.ticks(completed.getJournalTicks() == null ? death.getTicks() : completed.getJournalTicks())
					.fatalSourceResolution(resolved == null ? death.getFatalSourceResolution() : resolved.getSourceResolution())
					.build();
			}
			return death;
		}).whenComplete((ignored, error) ->
		{
			if (currentPanel != null)
				currentPanel.storageCompleted(error);
			if (error != null)
				log.error("Could not save the death recording", error);
		});

		log.debug("Death recorded: Killed by {} at {}. {} ticks captured, {} total damage received.", killer,
			locationStr, tickHistory.size(), totalDamage);
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		CallbackContext c = callbackContext();
		if (!c.allowed)
			return;
		Actor source = event.getSource(), target = event.getTarget();
		boolean record = source != null && worldRecorder != null && config.captureWorldSnapshots()
			&& (!(source instanceof Player) || source == c.localPlayer || config.captureOtherPlayers());
		Map<String, Object> fields = record ? actorDataSnapshot(source, c) : java.util.Collections.emptyMap();
		// Targeting is a hint for history, never proof that this NPC caused an individual hit.
		if (c.ready && c.localPlayer != null && source instanceof NPC && target == c.localPlayer)
			try
			{
				NPC npc = (NPC) source;
				if (isCombatNpc(npc))
				{
					String name = fields.containsKey("name") ? (String) fields.get("name") : npc.getName();
					Integer index = fields.containsKey("sceneId") ? (Integer) fields.get("sceneId")
						: Integer.valueOf(npc.getIndex());
					if (name != null && index != null)
						recentAttackers.put(index, new AttackerRecord(stripColorTags(name), index, currentGameTick, npc,
							nativeView(), nativeScene(), causalSceneEpoch, nativePlane()));
				}
			}
			catch (RuntimeException unavailable)
			{
				c.issues.add("interactionHistory: " + unavailable.getClass().getSimpleName());
			}
		if (record)
			observe("INTERACTING_CHANGED", source, target,
				data(
					"interaction", com.tickreplaylogger.capture.ActorInteractions.captureEventTarget(target,
						c.localPlayer, config.captureOtherPlayers(), actor -> callbackActorRef(actor, c)),
					"actorState", fields),
				c);
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		CallbackContext context = callbackContext();
		if (!context.allowed)
			return;
		Actor actor = event.getActor();
		Hitsplat hitsplat = event.getHitsplat();
		if (actor == null || hitsplat == null)
			return;
		Player localPlayer = context.localPlayer;

		int damage = hitsplat.getAmount();
		int hitsplatType = hitsplat.getHitsplatType();
		List<String> callbackIssues = context.issues;
		Integer callbackHp = localPlayer == null ? null
			: readHitContext("localHpAtCallback", () -> client.getBoostedSkillLevel(Skill.HITPOINTS), callbackIssues);
		String surface = damageMap == null ? null : damageMap.catalog().hitsplatSurface(hitsplatType);
		List<String> possibleSurfaces = damageMap == null ? java.util.Arrays.asList("HP", "UNKNOWN")
			: damageMap.catalog().possibleOutcomeSurfaces(hitsplatType);
		boolean possibleHp = possibleSurfaces.contains("HP");
		boolean ambiguousOutcome = possibleHp && !("HP".equals(surface) || "BLOCK".equals(surface) && damage == 0);
		Map<String, Object> actorState = callbackActorData(actor, context);
		Object graphicValue = actorState.get("graphicsState");
		com.tickreplaylogger.model.world.ActorGraphicsState graphicsAtImpact = graphicValue instanceof com.tickreplaylogger.model.world.ActorGraphicsState
			? (com.tickreplaylogger.model.world.ActorGraphicsState) graphicValue
			: null;
		PrayerObservation callbackPrayer = context.ready && actor == localPlayer
			? PrayerObservation.fromActorState(client, actorState)
			: null;
		if (callbackPrayer != null)
			for (Map.Entry<String, String> issue : callbackPrayer.getObservationIssues().entrySet())
				callbackIssues.add("impactPrayer." + issue.getKey() + ": " + issue.getValue());
		Map<String, Object> inventorySourceObservation = context.ready && localPlayer != null && actor == localPlayer
			&& config.trackDamageReceived() && config.captureWorldSnapshots() && damageMap != null
			&& damageMap.catalog().inventorySourceHints().accepts(hitsplatType, damage)
			? damageMap.catalog().inventorySourceHints().capture(client, eventTick(), context.cycle) : null;
		// Static mode qualification belongs to this actual HP callback, not the settled tick.
		// Reuse the inventory capture's identical two scalar reads when it already needed them.
		Object numericContext = null;
		if (context.ready && localPlayer != null && actor == localPlayer && config.trackDamageReceived()
			&& config.captureWorldSnapshots() && ("HP".equals(surface) || "BLOCK".equals(surface)))
			numericContext = inventorySourceObservation != null && inventorySourceObservation.get("numericContext") instanceof Map
				? inventorySourceObservation.get("numericContext")
				: com.tickreplaylogger.capture.ObservedInventorySourceHints.captureNumericContext(client, eventTick(), context.cycle);
		long sequence = observe("HITSPLAT", actor, null,
			data("actorState", actorState, "inventorySourceObservation", inventorySourceObservation,
				"numericContext", numericContext,
				"localPrayerState", callbackPrayer == null ? null : callbackPrayer.toState(),
				"receiverKind", actor instanceof NPC ? "NPC" : actor instanceof Player ? "PLAYER" : "ACTOR",
				"resourceSurface", surface == null ? "UNCLASSIFIED" : surface,
				"possibleOutcomeSurfaces", possibleSurfaces,
				"attributionCaptureStatus", actor != localPlayer ? "RECIPIENT_NOT_LOCAL_AT_CALLBACK"
					: !possibleHp && !("BLOCK".equals(surface) && damage == 0) ? "NON_HP_OUTCOME_REFERENCE"
						: !config.trackDamageReceived() ? "RECEIVED_TRACKING_DISABLED"
						: ambiguousOutcome && resourceImpactQueueCount >= MAX_RESOURCE_IMPACTS_PER_TICK
							? "RESOURCE_IMPACT_QUEUE_LIMIT" : "PENDING_SETTLED_CONTEXT",
				"outcomeSurfaceEvidence", "UNCLASSIFIED".equals(surface) || surface == null ? "UNCLASSIFIED_API_HITSPLAT" : "REFERENCE_OUTCOME_ALTERNATIVES",
				"localHpAtCallback", callbackHp, "captureIssues",
				new ArrayList<>(callbackIssues), "amount", damage, "hitsplatType", hitsplatType, "isMine",
				hitsplat.isMine(), "isOthers", hitsplat.isOthers(), "disappearsOnGameCycle",
				hitsplat.getDisappearsOnGameCycle(), "renderingKnown", false),
			context);

		// Zero HP observations still compete for a projectile; zero does not prove a miss.
		if (damage == 0 && !possibleHp && !"HP".equals(surface) && hitsplatType != HitsplatID.BLOCK_ME
			&& hitsplatType != HitsplatID.BLOCK_OTHER)
		{
			return;
		}

		// Damage received by the player - check for player-targeted hitsplat types
		// isMine() alone doesn't catch environmental damage (acid pools, poison, etc.)
		if (localPlayer == null && actor instanceof Player)
		{
			UnassignedHit deferred = new UnassignedHit();
			deferred.actor = actor;
			deferred.amount = damage;
			deferred.type = hitsplatType;
			deferred.surface = surface;
			deferred.hp = callbackHp;
			deferred.sequence = sequence;
			deferred.context = context;
			deferred.graphicsObservation = graphicsAtImpact;
			unassignedHits.add(deferred);
			return;
		}
		if (actor == localPlayer)
			noteUnresolvedImpact(damage, hitsplatType, surface, callbackHp, sequence, context.cycle, callbackIssues);
		boolean hpDamage = "HP".equals(surface);
		boolean hpBlock = "BLOCK".equals(surface) && damage == 0;
		boolean isPlayerDamageType = hpDamage || hpBlock;

		if (actor == localPlayer && (isPlayerDamageType || possibleHp))
		{
			if (!config.trackDamageReceived())
			{
				return;
			}

			// Preserve impact state now; NPC animation/projectile updates may arrive later in this batch.
			com.tickreplaylogger.capture.ActorCallbackObservation.Sample original = context.actorSamples.get(actor);
			WorldPoint impactPosition = original == null ? null : original.world();
			LocalPoint renderedPosition = original == null ? null : original.local();
			retainActorSampleIssue(original, "worldPosition", "impactWorldPosition", callbackIssues);
			retainActorSampleIssue(original, "localPosition", "impactLocalPosition", callbackIssues);
			NativeHitScope nativeScope = readNativeHitScope(context.localPlayer,
				original == null ? null : original.view(),
				original == null ? null : integerField(original.data(), "worldViewId"), true, callbackIssues, "impact");
			boolean positionScopeKnown = true;
			if (renderedPosition != null && nativeScope.viewId != null
				&& renderedPosition.getWorldView() != nativeScope.viewId)
			{
				callbackIssues.add("impactPositionScope: LOCAL_POINT_WORLD_VIEW_MISMATCH");
				positionScopeKnown = false;
			}
			if (impactPosition != null && nativeScope.plane != null && impactPosition.getPlane() != nativeScope.plane)
			{
				callbackIssues.add("impactPositionScope: WORLD_POINT_PLANE_MISMATCH");
				positionScopeKnown = false;
			}
			List<java.util.Map<String, Object>> contactWitnesses = new ArrayList<>();
			List<String> impactHazards = nativeScope.known && positionScopeKnown
				? readHitContext("impactHazards",
					() -> inNativeHitScope(nativeScope,
						() -> hazardsAtReportedPosition(impactPosition, renderedPosition, contactWitnesses)),
					callbackIssues)
				: java.util.Collections.emptyList();
			PrayerObservation prayerObservation = callbackPrayer;
			List<String> prayers = prayerObservation == null ? java.util.Collections.emptyList()
				: getActivePrayers(prayerObservation);
			Integer baseX = nativeScope.baseX, baseY = nativeScope.baseY;
			net.runelite.api.WorldView view = nativeScope.view;
			net.runelite.api.Scene scene = nativeScope.scene;
			Integer plane = impactPosition == null ? nativeScope.plane : impactPosition.getPlane();
			PendingHit pending = new PendingHit(damage, hitsplatType, determineDamageType(hitsplat),
				prayers == null ? java.util.Collections.emptyList() : prayers, impactPosition, renderedPosition,
				impactHazards == null ? java.util.Collections.emptyList() : impactHazards, baseX == null ? 0 : baseX,
				baseY == null ? 0 : baseY, context.cycle, sequence,
				prayerObservation == null ? PrayerObservation.unavailable() : prayerObservation, view, scene,
				contactWitnesses, causalSceneEpoch, plane == null ? -1 : plane, graphicsAtImpact);
			pending.nativeScope = nativeScope;
			pending.inventorySourceObservation = inventorySourceObservation;
			pending.hpOutcomeKnown = isPlayerDamageType;
			pending.possibleOutcomeSurfaces = new ArrayList<>(possibleSurfaces);
			pending.impactSceneKey = context.primaryKnown && context.localView == view ? context.primarySceneKey : null;
			pending.captureIssues.addAll(callbackIssues);
			if (!pending.hpOutcomeKnown && resourceImpactQueueCount++ >= MAX_RESOURCE_IMPACTS_PER_TICK)
			{
				tickRecorder.addCaptureIssue("resourceImpacts: attribution queue limit; original callback retained");
				return;
			}
			pendingHits.add(pending);
		}
		// Damage dealt by the player
		else if (actor instanceof NPC && hitsplat.isMine() && (hpDamage || hpBlock))
		{
			if (!config.trackDamageDealt())
			{
				return;
			}

			NPC npc = (NPC) actor;
			String targetName = actorState.get("name") instanceof String ? (String) actorState.get("name") : null;
			retainActorSampleIssue(context.actorSamples.get(npc), "name", "defenderName", callbackIssues);
			if (targetName == null)
			{
				targetName = "Unknown";
			}

			DamageEvent.DamageType damageType = determineDamageType(hitsplat);
			// The currently equipped weapon/animation does not identify an arriving
			// projectile, reflected hit or summon hit. Ownership is recorded separately.
			DamageEvent damageEvent = DamageEvent.dealt(damage, damageType, targetName);
			tickRecorder.addDamageEvent(damageEvent);
			recordNpcDamage(npc, hitsplat, damageType, sequence, true, context, graphicsAtImpact);

			log.debug("DAMAGE: Dealt {} to {}", damage, targetName);
		}
		else if (actor instanceof NPC && (hpDamage || hpBlock))
		{
			// A shared boss's other-owned/unknown-source hits are observations, not our personal DPS.
			// The callback identifies the defender, but does not name the player who inflicted the hit.
			recordNpcDamage((NPC) actor, hitsplat, determineDamageType(hitsplat), sequence, false, context,
				graphicsAtImpact);
		}
	}

	private void recordNpcDamage(NPC npc, Hitsplat hitsplat, DamageEvent.DamageType type, long sequence, boolean mine,
		CallbackContext context, com.tickreplaylogger.model.world.ActorGraphicsState graphicsAtImpact)
	{
		if (worldRecorder == null || !config.captureWorldSnapshots())
			return;
		List<String> issues = new ArrayList<>(context.issues);
		com.tickreplaylogger.capture.ActorCallbackObservation.Sample original = context.actorSamples.get(npc);
		WorldPoint position = context.ready && original != null ? original.world() : null;
		Object observedName = original == null ? null : original.data().get("name");
		String defenderName = context.ready && observedName instanceof String ? (String) observedName : null;
		retainActorSampleIssue(original, "worldPosition", "defenderWorldPosition", issues);
		retainActorSampleIssue(original, "name", "defenderName", issues);
		String defenderRef = context.ready ? readHitContext("defenderRef", () -> callbackActorRef(npc, context), issues)
			: null;
		String ownership = mine ? "LOCAL_PLAYER" : hitsplat.isOthers() ? "OTHER" : "UNKNOWN";
		worldRecorder.addDamage(DamageRecord.builder().direction(mine ? "DEALT" : "NPC_RECEIVED").ownership(ownership)
			.impactTick(eventTick()).launchTick(eventTick()).launchTickEstimated(true)
			.launchStateEvidence("UNAVAILABLE").attackerRef(null).attackerName(null).defenderRef(defenderRef)
			.defenderName(defenderName == null ? "Unknown" : stripColorTags(defenderName))
			.defenderGraphicsAtImpact(graphicsAtImpact).captureIssues(issues.isEmpty() ? null : new ArrayList<>(issues))
			.impactPositionKnown(position != null).launchPositionKnown(false).attribution("UNKNOWN")
			.attributionEvidence(
				mine ? "OWNED_HITSPLAT" : hitsplat.isOthers() ? "OTHER_OWNERSHIP_HITSPLAT" : "UNOWNED_STATUS_HITSPLAT")
			.amount(hitsplat.getAmount()).hitsplatType(hitsplat.getHitsplatType()).hitsplatTypeKnown(true)
			.damageType(damageTypeString(type)).determination("UNKNOWN")
			.damageReferenceKey(
				damageMap == null ? null : damageMap.catalog().hitsplatReferenceKey(hitsplat.getHitsplatType()))
			.npcDamageReductionStyle(damageMap == null ? null
				: damageMap.catalog().hitsplatNpcDamageReductionStyle(hitsplat.getHitsplatType()))
			.damagePropertyEvidence(damageMap != null
				&& damageMap.catalog().hitsplatNpcDamageReductionStyle(hitsplat.getHitsplatType()) != null
					? "SOURCE_REFERENCE"
					: null)
			.damageRecurrence(typedStatusHit(hitsplat.getHitsplatType()) ? "UNKNOWN" : null)
			.damageRecurrenceEvidence(typedStatusHit(hitsplat.getHitsplatType()) ? "HITSPLAT_TYPE_ONLY" : null)
			.damageTypeEvidence(type == DamageEvent.DamageType.UNKNOWN ? "UNKNOWN"
				: determineDamageType(hitsplat) != DamageEvent.DamageType.UNKNOWN ? "HITSPLAT_TYPE"
					: "LABELLED_FROM_MAP")
			.observedCycle(context.cycle).observationSequence(sequence).impactX(position == null ? 0 : position.getX())
			.impactY(position == null ? 0 : position.getY()).build());
	}

	private void noteUnresolvedImpact(int amount, int type, String surface, Integer hp, long sequence, Integer cycle,
		List<String> issues)
	{
		if (amount <= 0 || !(surface == null || java.util.Arrays
			.asList("SKILL_OR_HP", "HP_OR_COMBAT_RESOURCE", "UNCLASSIFIED", "SANITY", "STACKS", "CONTEXTUAL_RESOURCE")
			.contains(surface) || "HP".equals(surface) && !config.trackDamageReceived()))
			return;
		int tick = eventTick();
		if (unresolvedImpactTick != tick)
		{
			unresolvedImpactTick = tick;
			unresolvedImpactCount = 0;
			unresolvedImpactObservations.clear();
		}
		if (unresolvedImpactCount < Integer.MAX_VALUE)
			unresolvedImpactCount++;
		if (unresolvedImpactObservations.size() < 256)
			unresolvedImpactObservations
				.add(data("sequence", sequence, "tick", tick, "gameCycle", cycle, "hitsplatType", type, "amount",
					amount, "resourceSurface", surface, "localHpAtCallback", hp, "causalSceneEpoch", causalSceneEpoch,
					"captureIssues", issues.isEmpty() ? null : new ArrayList<>(issues)));
	}

	private static <T> T readHitContext(String field, java.util.function.Supplier<T> read, List<String> issues)
	{
		try
		{
			return read.get();
		}
		catch (RuntimeException unavailable)
		{
			issues.add(field + ": " + unavailable.getClass().getSimpleName());
			return null;
		}
	}

	/**
	 * Determine the damage type from the hitsplat.
	 */
	private DamageEvent.DamageType determineDamageType(Hitsplat hitsplat)
	{
		String category = damageMap == null ? null : damageMap.catalog().hitsplatDamageType(hitsplat.getHitsplatType());
		return DamageEvent.DamageType.fromReference(category);

	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		CallbackContext context = callbackContext();
		if (!context.allowed)
			return;
		Projectile projectile = event.getProjectile();
		LocalPoint target = event.getPosition();
		if (projectile == null)
		{
			observe("PROJECTILE_CALLBACK_UNAVAILABLE", null, null,
				data("targetLocalX", target == null ? null : target.getX(), "targetLocalY",
					target == null ? null : target.getY(), "targetZ", event.getZ()),
				context);
			return;
		}
		boolean first = !projectileIds.containsKey(projectile);
		registerProjectile(projectile);
		LocalPoint previous = projectileTargets.get(projectile);
		if (target == null)
			projectileTargets.remove(projectile);
		else
			projectileTargets.put(projectile, target);
		com.tickreplaylogger.capture.ProjectileObservation sample = sampleProjectile(projectile, eventTick(),
			"MOVED_CALLBACK", context.localPlayer, context.cycle, target);
		ProjectileState state = sample.getState();
		ProjectileState priorState = projectileCallbackStates.put(projectile, state);
		boolean relationshipChanged = priorState != null
			&& (!java.util.Objects.equals(priorState.getSourceRef(), state.getSourceRef())
				|| !java.util.Objects.equals(priorState.getTargetRef(), state.getTargetRef())
				|| !java.util.Objects.equals(priorState.getSourceActorKnown(), state.getSourceActorKnown())
				|| !java.util.Objects.equals(priorState.getTargetActorKnown(), state.getTargetActorKnown())
				|| !java.util.Objects.equals(priorState.getTargetsPlayer(), state.getTargetsPlayer())
				|| !java.util.Objects.equals(priorState.getSourceNpcId(), state.getSourceNpcId())
				|| !java.util.Objects.equals(priorState.getSourceObservedName(), state.getSourceObservedName()));
		boolean detailsChanged = priorState != null
			&& (!java.util.Objects.equals(priorState.getSourceWorldViewId(), state.getSourceWorldViewId())
				|| !java.util.Objects.equals(priorState.getSourcePlane(), state.getSourcePlane())
				|| !java.util.Objects.equals(priorState.getTargetWorldViewId(), state.getTargetWorldViewId())
				|| !java.util.Objects.equals(priorState.getTargetActorWorldViewId(), state.getTargetActorWorldViewId())
				|| !java.util.Objects.equals(priorState.getTargetLocalWorldViewId(), state.getTargetLocalWorldViewId())
				|| !java.util.Objects.equals(priorState.getTargetPlane(), state.getTargetPlane())
				|| !java.util.Objects.equals(priorState.getDestinationPlane(), state.getDestinationPlane())
				|| !java.util.Objects.equals(priorState.getDestinationKnown(), state.getDestinationKnown())
				|| !java.util.Objects.equals(priorState.getDestinationEvidence(), state.getDestinationEvidence())
				|| !java.util.Objects.equals(priorState.getEndWorldX(), state.getEndWorldX())
				|| !java.util.Objects.equals(priorState.getEndWorldY(), state.getEndWorldY())
				|| !java.util.Objects.equals(priorState.getEndCycle(), state.getEndCycle()));
		boolean destinationChanged = !java.util.Objects.equals(previous, target) || priorState != null
			&& (!java.util.Objects.equals(priorState.getEndWorldX(), state.getEndWorldX())
				|| !java.util.Objects.equals(priorState.getEndWorldY(), state.getEndWorldY())
				|| !java.util.Objects.equals(priorState.getDestinationPlane(), state.getDestinationPlane()));
		if (first || destinationChanged || relationshipChanged || detailsChanged)
		{
			projectilesThisTick.add(state);
			observe(
				first ? "PROJECTILE_FIRST_SEEN"
					: relationshipChanged ? "PROJECTILE_RELATIONSHIP_CHANGED"
						: destinationChanged ? "PROJECTILE_TARGET_CHANGED" : "PROJECTILE_RELATIONSHIP_CHANGED",
				sample.getSourceActor(), sample.getTargetActor(),
				data("projectile", state, "targetZ", event.getZ(), "callbackTargetLocalX",
					target == null ? null : target.getX(), "callbackTargetLocalY",
					target == null ? null : target.getY()),
				context);
		}
		// A late target binding may first identify the local player after the initial visual callback.
		if (!context.ready || !Boolean.TRUE.equals(state.getTargetsPlayer()) || !processedProjectiles.add(projectile))
			return;
		try
		{
			WorldPoint playerPoint = context.localPlayer.getWorldLocation();
			if (playerPoint == null)
				throw new IllegalStateException("Player plane unavailable");
			Integer remaining = sample.getRemainingCycles();
			boolean timingKnown = remaining != null;
			int travel = remaining == null ? 1 : Math.max(1, (int) Math.ceil(remaining / 30.0));
			com.tickreplaylogger.model.world.ProjectileSourceHistory.SourceObservation source = state
				.getSourceHistory() == null ? null : state.getSourceHistory().firstActor();
			boolean reference = source == null ? sample.isReferenceContext()
				: Boolean.TRUE.equals(source.getSourceNormalWorldContextObserved()) && source.getSourceNpcId() != null
					&& damageMap != null && damageMap.catalog() != null
					&& damageMap.catalog().observedNameCompatible(source.getSourceNpcId(),
						source.getSourceObservedName(), Boolean.TRUE.equals(source.getSourceNameKnown()));
			PendingProjectile pending = new PendingProjectile(
				source == null ? state.getSourceName() : source.getSourceName(),
				state.getProjectileId() == null ? -1 : state.getProjectileId(), state.getSpawnTick(),
				state.getEstimatedImpactTick() == null ? eventTick() : state.getEstimatedImpactTick(), travel,
				source != null || sample.getSourceActor() != null,
				source == null ? sample.getSourceActor() instanceof Player : source.getActorKind().endsWith("_PLAYER"),
				source == null ? (state.getSourceNpcId() == null ? -1 : state.getSourceNpcId())
					: (source.getSourceNpcId() == null ? -1 : source.getSourceNpcId()),
				source == null ? state.getSourceRef() : source.getSourceRef(),
				source == null ? state.getSourceCombatLevel() : source.getSourceCombatLevel(),
				state.getEndCycle() == null ? 0 : state.getEndCycle(), nativeView(), nativeScene(), reference,
				causalSceneEpoch, playerPoint.getPlane(), timingKnown, projectile, state.getObservationId());
			pending.latestObservation = state;
			pendingProjectiles.add(pending);
			projectileSourceLinks.put(state.getObservationId(), pending);
		}
		catch (RuntimeException unavailable)
		{
			context.issues.add("projectileAttributionContext: " + unavailable.getClass().getSimpleName());
			observe("PROJECTILE_ATTRIBUTION_UNAVAILABLE", null, null, data("observationId", state.getObservationId()),
				context);
		}
	}

	private boolean projectileExpiryObserved(Projectile projectile, String stage)
	{
		try
		{
			return projectile.getRemainingCycles() <= 0;
		}
		catch (RuntimeException unavailable)
		{
			tickRecorder.markStateUnavailable(stage);
			tickRecorder.addCaptureIssue(stage + ": remainingCycles: " + unavailable.getClass().getSimpleName());
			return false; // An unavailable lifetime does not establish that the object expired.
		}
	}

	private void registerProjectile(Projectile projectile)
	{
		if (!projectileIds.containsKey(projectile))
		{
			projectileIds.put(projectile, ++nextProjectileId);
			projectileSpawnTicks.put(projectile, eventTick());
		}
	}

	private com.tickreplaylogger.capture.ProjectileObservation sampleProjectile(Projectile projectile, int tick,
		String evidence, Player local, Integer cycle, LocalPoint callbackTarget)
	{
		boolean includeOthers = config.captureOtherPlayers();
		final com.tickreplaylogger.model.world.ProjectileSourceCandidate[] proximity = new com.tickreplaylogger.model.world.ProjectileSourceCandidate[1];
		com.tickreplaylogger.capture.ProjectileObservation sample = new com.tickreplaylogger.capture.ProjectileObservation(
			projectile, client, local, includeOthers, damageMap == null ? null : damageMap.catalog(),
			actor -> actor instanceof Player && actor != local && !includeOthers ? null
				: captureService == null ? null : captureService.actorRef(actor, local),
			(point, excluded) ->
			{
				NPC nearest = findClosestCombatNpcToPoint(point, excluded);
				proximity[0] = nearest == null ? null : captureProximitySource(nearest, local);
				return proximity[0] == null ? null : proximity[0].getName();
			}, projectileIds.get(projectile), projectileSpawnTicks.get(projectile), tick, cycle, evidence,
			callbackTarget,
			(state, source) ->
			{
				com.tickreplaylogger.model.world.ProjectileState.ProjectileStateBuilder original = state.toBuilder()
					.proximitySourceCandidate(proximity[0]);
				if (projectileSourceTracker.needsOriginalActorMetadata(projectile, state.getObservationId(), source))
				{
					try
					{
						net.runelite.api.WorldView view = source.getWorldView();
						if (view != null && state.getSourceWorldViewId() != null && state.getSourcePlane() != null)
						{
							String scope = com.tickreplaylogger.capture.WorldViews.scope(view, state.getSourcePlane(),
								state.getSourceWorldViewId());
							String key = scope != null && scope.equals(sceneScopeSignature) ? currentSceneKey
								: captureService == null ? null
									: captureService.cachedAdditionalSceneKey(view, state.getSourcePlane(), scope);
							original.sourceSceneKey(key);
						}
					}
					catch (RuntimeException unavailable)
					{
						// No original native scope witness; retain identity only.
					}
				}
				return projectileSourceTracker.observe(projectile, original.build(), source, local, causalSceneEpoch,
					includeOthers);
			});
		PendingProjectile pending = projectileSourceLinks.get(sample.getState().getObservationId());
		if (pending != null && pending.observedObject == projectile)
			pending.latestObservation = sample.getState();
		return sample;
	}

	private com.tickreplaylogger.model.world.ProjectileSourceCandidate captureProximitySource(NPC npc, Player local)
	{
		List<String> issues = new ArrayList<>();
		String name = readHitContext("proximity.name", npc::getName, issues);
		Integer index = readHitContext("proximity.index", npc::getIndex, issues);
		net.runelite.api.WorldView view = readHitContext("proximity.view", npc::getWorldView, issues);
		Integer viewId = view == null ? null : readHitContext("proximity.viewId", view::getId, issues);
		WorldPoint position = readHitContext("proximity.position", npc::getWorldLocation, issues);
		Integer level = readHitContext("proximity.level", npc::getCombatLevel, issues);
		Integer animation = readHitContext("proximity.animation", npc::getAnimation, issues);
		Map<String, String> definitionIssues = new LinkedHashMap<>();
		com.tickreplaylogger.capture.NpcDefinitionObservation definition = com.tickreplaylogger.capture.NpcDefinitionObservation
			.capture(npc, definitionIssues, "proximity.");
		issues.addAll(definitionIssues.keySet());
		net.runelite.api.NpcOverrides overrides = readHitContext("proximity.overrides", npc::getModelOverrides, issues);
		Boolean normal = issues.stream().anyMatch(issue -> issue.startsWith("proximity.overrides")) ? null
			: readHitContext("proximity.context", () -> com.tickreplaylogger.capture.NpcIdentityContext
				.normalWorldObservedWithoutOverrides(client, overrides == null), issues);
		String ref = captureService == null ? null
			: readHitContext("proximity.actorRef", () -> captureService.observedActorRef(npc, local, viewId, index),
				issues);
		String sceneKey = null;
		if (view != null && position != null && captureService != null)
			sceneKey = readHitContext("proximity.scene",
				() -> captureService.additionalSceneKey(client, view, position.getPlane()), issues);
		if (sceneKey == null && view != null && position != null && viewId != null)
		{
			String originalScope = readHitContext("proximity.primaryScope",
				() -> com.tickreplaylogger.capture.WorldViews.scope(view, position.getPlane(), viewId), issues);
			if (originalScope != null && originalScope.equals(sceneScopeSignature))
				sceneKey = currentSceneKey;
		}
		Integer id = definition.getId();
		String family = id == null || damageMap == null ? null
			: damageMap.catalog().attributionFamily(id, name, level, Boolean.TRUE.equals(normal));
		return com.tickreplaylogger.model.world.ProjectileSourceCandidate.builder().name(name).actorRef(ref).npcId(id)
			.baseNpcId(definition.getBaseId()).combatLevel(level).animationId(animation)
			.modelOverridesPresent(
				issues.stream().anyMatch(issue -> issue.startsWith("proximity.overrides")) ? null : overrides != null)
			.familyKey(family).normalWorldContextObserved(normal).worldViewId(viewId)
			.plane(position == null ? null : position.getPlane()).sceneKey(sceneKey)
			.evidence("ORIGINAL_PROJECTILE_ORIGIN_PROXIMITY; not API source actor")
			.unavailableFields(issues.isEmpty() ? null : java.util.Collections.unmodifiableList(issues)).build();
	}

	private List<ProjectileState> captureProjectiles()
	{
		projectileCaptureIssues.clear();
		java.util.Set<Projectile> listed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		boolean complete = false;
		try
		{
			Iterable<? extends Projectile> collection = client.getProjectiles();
			if (collection == null)
				projectileCaptureIssues.add("projectiles: global collection unavailable");
			else
			{
				for (Projectile projectile : collection)
					if (projectile != null)
					{
						listed.add(projectile);
						registerProjectile(projectile);
					}
				complete = true;
			}
		}
		catch (RuntimeException unavailable)
		{
			projectileCaptureIssues.add("projectiles: global collection " + unavailable.getClass().getSimpleName());
		}
		Map<Long, ProjectileState> states = new LinkedHashMap<>();
		for (ProjectileState state : projectilesThisTick)
			states.put(state.getObservationId(), state);
		Player local = readHitContext("projectiles.localPlayer", client::getLocalPlayer, projectileCaptureIssues);
		Integer cycle = readHitContext("projectiles.gameCycle", client::getGameCycle, projectileCaptureIssues);
		java.util.Set<Projectile> removed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (Projectile projectile : new ArrayList<>(projectileIds.keySet()))
		{
			if (complete && !listed.contains(projectile))
			{
				removed.add(projectile);
				continue;
			}
			com.tickreplaylogger.capture.ProjectileObservation sample = sampleProjectile(projectile, currentGameTick,
				"TICK_SAMPLE", local, cycle, null);
			ProjectileState state = sample.getState();
			if (state.getObservationIssues() != null)
				for (Map.Entry<String, String> issue : state.getObservationIssues().entrySet())
					projectileCaptureIssues.add("projectiles: " + issue.getKey() + ": " + issue.getValue());
			Integer remaining = sample.getRemainingCycles();
			if (remaining != null && remaining <= 0)
			{
				removed.add(projectile);
				continue;
			}
			states.put(state.getObservationId(), state.toBuilder()
				.collectionEvidence(listed.contains(projectile) ? "CLIENT_COLLECTION" : "REGISTERED_OBJECT_FALLBACK")
				.build());
		}
		for (Projectile projectile : removed)
		{
			projectileIds.remove(projectile);
			projectileSpawnTicks.remove(projectile);
			projectileTargets.remove(projectile);
			projectileCallbackStates.remove(projectile);
			processedProjectiles.remove(projectile);
			projectileSourceTracker.forget(projectile);
		}
		for (PendingProjectile value : pendingProjectiles)
			if (!value.timingKnown && value.removedObservedTick == null && removed.contains(value.observedObject))
				value.removedObservedTick = currentGameTick;
		return new ArrayList<>(states.values());
	}

	/**
	 * Check if an NPC is a combat-capable entity (not a pet, follower, or scenery NPC).
	 * Uses RuneLite API properties â€” no hardcoded NPC lists needed.
	 */
	private boolean isCombatNpc(NPC npc)
	{
		if (npc == null || npc.isDead())
		{
			return false;
		}

		// Check composition for follower status (pets, familiars)
		NPCComposition composition = npc.getComposition();
		if (composition != null)
		{
			// isFollower() returns true for pets and followers â€” instant reject
			if (composition.isFollower())
			{
				return false;
			}

			// Check for "Attack" in right-click menu options
			String[] actions = composition.getActions();
			boolean hasAttackOption = false;
			if (actions != null)
			{
				for (String action : actions)
				{
					if ("Attack".equals(action))
					{
						hasAttackOption = true;
						break;
					}
				}
			}

			// Combat level 0 + no Attack option = non-combat NPC (banks, shops, scenery)
			if (npc.getCombatLevel() == 0 && !hasAttackOption)
			{
				return false;
			}

			// No Attack option + idle + no health bar = non-combat NPC
			if (!hasAttackOption && npc.getAnimation() == -1 && npc.getHealthRatio() == -1)
			{
				return false;
			}
		}

		return true;
	}

	/**
	 * Resolve the source name for a projectile.
	 * Priority: direct source actor â†’ closest combat NPC to spawn point â†’ Unknown.
	 */
	private String resolveProjectileSource(Projectile projectile)
	{
		// Priority 1: Direct source actor from API
		Actor sourceActor = projectile.getSourceActor();
		if (sourceActor != null)
		{
			String name = getActorName(sourceActor);
			if (name != null)
			{
				return name;
			}
		}

		// Priority 2: Find closest combat NPC to projectile spawn point
		Actor targetActor = projectile.getTargetActor();
		WorldPoint sourcePoint = projectile.getSourcePoint();

		if (sourcePoint != null)
		{
			NPC closest = findClosestCombatNpcToPoint(sourcePoint, targetActor);
			if (closest != null)
			{
				String name = closest.getName();
				if (name != null)
				{
					return stripColorTags(name);
				}
			}
		}

		return "Unknown";
	}

	/**
	 * Find the closest combat NPC to a world point, excluding the target actor.
	 */
	private NPC findClosestCombatNpcToPoint(WorldPoint sourcePoint, Actor excludeActor)
	{
		NPC closest = null;
		int closestDistance = 2;
		boolean tied = false;

		for (NPC npc : nativeNpcs())
		{
			if (npc == excludeActor || !isCombatNpc(npc))
			{
				continue;
			}

			WorldPoint npcPoint = npc.getWorldLocation();
			if (npcPoint == null || npcPoint.getPlane() != sourcePoint.getPlane())
			{
				continue;
			}

			// A large actor's projectile can start inside its footprint, several tiles
			// from the southwest anchor. This remains a proximity hypothesis only.
			net.runelite.api.coords.WorldArea area = null;
			try
			{
				area = npc.getWorldArea();
			}
			catch (RuntimeException unavailable)
			{
				/* retain the known anchor fallback */ }
			if (area != null && area.getPlane() != sourcePoint.getPlane())
				continue;
			int distance = area == null ? npcPoint.distanceTo2D(sourcePoint) : area.distanceTo2D(sourcePoint);
			if (distance < closestDistance)
			{
				closestDistance = distance;
				closest = npc;
				tied = false;
			}
			else if (distance == closestDistance)
				tied = true;
		}

		return tied ? null : closest;
	}

	/**
	 * Find the closest combat NPC to a local point, excluding the target actor.
	 */
	private NPC findClosestCombatNpcToLocalPoint(LocalPoint sourceLocal, Actor excludeActor)
	{
		NPC closest = null;
		int closestDistance = Integer.MAX_VALUE;

		for (NPC npc : nativeNpcs())
		{
			if (npc == excludeActor || !isCombatNpc(npc))
			{
				continue;
			}

			LocalPoint npcPoint = npc.getLocalLocation();
			if (npcPoint == null)
			{
				continue;
			}

			int distance = npcPoint.distanceTo(sourceLocal);
			if (distance < closestDistance)
			{
				closestDistance = distance;
				closest = npc;
				if (distance == 0)
				{
					return closest;
				}
			}
		}

		return closest;
	}

	/**
	 * NPC scene index of an actor if it is an NPC, else null (used as a per-tick actor ref).
	 */
	private Integer sourceActorNpcIndex(Actor actor)
	{
		return actor instanceof NPC ? ((NPC) actor).getIndex() : null;
	}

	/**
	 * Get a sanitized display name for an actor.
	 */
	private String getActorName(Actor actor)
	{
		if (actor == null)
		{
			return null;
		}

		String name = null;
		if (actor instanceof NPC)
		{
			name = ((NPC) actor).getName();
		}
		else if (actor instanceof Player)
		{
			name = actor != nativeLocalPlayer() && !config.captureOtherPlayers() ? "Other player"
				: ((Player) actor).getName();
		}

		if (name == null)
		{
			return null;
		}

		String cleaned = name.replaceAll("<[^>]*>", "").trim();
		return cleaned.isEmpty() ? null : cleaned;
	}

	/**
	 * Correlate an observed HP hit with source hypotheses. Self actions, pickpocket failures,
	 * projectiles, damaging surfaces and actor animations compete. Multiple viable causes are
	 * retained as alternatives; priority, list order and attacker history do not establish a cause.
	 */
	private void resetAttribution()
	{
		// Reset attribution metadata for this resolution (overwritten by the branch that resolves).
		lastAttributionAttackRule = null;
		lastAttributionAttackName = null;
		lastAttributionProjectileId = -1;
		lastAttributionMapRef = null;
		lastAttributionConfidence = "UNKNOWN";
		lastAttributionLaunchTick = currentGameTick;
		lastAttributionSourceIsPlayer = false;
		lastAttributionNpcId = -1;
		lastAttributionNpcLevel = null;

		lastAttributionActorRef = null;
		lastAttributionEvidence = "UNMATCHED";
		lastAttributionCandidates = new ArrayList<>();
		lastAttributionNormalContext = false;
	}

	private String determineDamageSource(PendingHit hit)
	{
		resetAttribution();
		Player localPlayer = nativeLocalPlayer();
		if (localPlayer == null)
			return "Unknown";
		if (hit.nativeScope == null || !hit.nativeScope.known)
		{
			lastAttributionEvidence = "IMPACT_NATIVE_SCOPE_UNAVAILABLE";
			return "Unknown";
		}
		if (activeHitScope == null || !activeHitScope.known)
		{
			lastAttributionEvidence = "SETTLED_NATIVE_SCOPE_UNAVAILABLE";
			return "Unknown";
		}
		// Packet processing may cross a scene/view boundary before deferred resolution.
		// Keep the observed damage, but do not search a different coordinate frame for its cause.
		if (hit.epoch != causalSceneEpoch || hit.plane != nativePlane() || hit.worldView != nativeView()
			|| hit.scene != nativeScene() || hit.baseX != nativeBaseX() || hit.baseY != nativeBaseY())
		{
			lastAttributionEvidence = "SCENE_CHANGED_BEFORE_ATTRIBUTION";
			return "Unknown";
		}
		ensureSelfDamageScope();
		boolean ambiguousBatch = !hit.hpOutcomeKnown || unlinkedHpObservationCount(hit) > 1;
		if (ambiguousBatch)
		{
			lastAttributionCandidates.addAll(selfDamageInputs.candidates(currentGameTick));
			for (String failure : failedPickpockets)
				lastAttributionCandidates.add(data("kind", "PICKPOCKET_FAILURE_CORRELATION", "name", failure));
		}
		if (!ambiguousBatch && selfConsumptionObserved(hit))
		{
			String candidate = selfDamageInputSource(hit, pendingSelfDamageSource);
			if ("Unknown".equals(candidate))
				return candidate;
			lastAttributionConfidence = "TIMED";
			lastAttributionEvidence = "ITEM_CONSUMPTION_CORRELATION";
			String source = pendingSelfDamageSource;
			pendingSelfDamageSource = null;
			return source;
		}
		String selfInputSource = ambiguousBatch ? null : selfDamageInputSource(hit);
		if (selfInputSource != null)
			return selfInputSource;

		// A failure message and an actual ordinary hit supply a pickpocket hypothesis.
		// Competing damage evidence must survive rather than losing to this branch's priority.
		if (!ambiguousBatch && damageMap != null && damageMap.pickpocketRule() != null && failedPickpockets.size() == 1
			&& hit.amount > 0 && hit.damageType == DamageEvent.DamageType.UNKNOWN && pendingHits.stream()
				.filter(h -> h.amount > 0 && h.damageType == DamageEvent.DamageType.UNKNOWN).count() == 1)
		{
			lastAttributionCandidates
				.add(data("kind", "PICKPOCKET_FAILURE_CORRELATION", "name", failedPickpockets.get(0)));
			for (NPC npc : nativeNpcs())
				if (npc != null && npc != pickpocketTarget && npc.getInteracting() == localPlayer && isCombatNpc(npc))
					lastAttributionCandidates.add(npcCandidate(npc));
			for (Player other : playerDamageCandidates(localPlayer))
				lastAttributionCandidates.add(playerCandidate(other));
			for (PendingProjectile projectile : pendingProjectiles)
				if (projectile.epoch == hit.epoch && projectile.plane == hit.plane
					&& projectile.worldView == hit.worldView && projectile.scene == hit.scene
					&& (!projectile.timingKnown || Math.abs(projectile.estimatedArrivalTick - currentGameTick) <= 2))
					lastAttributionCandidates.add(data("kind", "PROJECTILE_CORRELATION", "name", projectile.sourceName,
						"projectileId", projectile.projectileId, "projectileObservationId",
						projectile.projectileObservationId, "sourceHistory",
						projectile.latestObservation == null ? null : projectile.latestObservation.getSourceHistory(),
						"latestSourceObservation", projectileLatestSource(projectile), "sourceSamplingUnavailable",
						projectile.sourceSamplingUnavailable, "matchBasis",
						projectile.timingKnown ? "ARRIVAL_WINDOW" : "TIMING_UNAVAILABLE"));
			for (String hazard : hazardsForHit(hit))
				lastAttributionCandidates.add(hazardCandidate(hit, hazard));
			if (lastAttributionCandidates.size() > 1)
			{
				lastAttributionEvidence = "MULTIPLE_POSSIBLE_DAMAGE_SOURCES";
				return "Unknown";
			}
			lastAttributionConfidence = "TIMED";
			lastAttributionAttackName = damageMap.pickpocketRule().getName();
			lastAttributionMapRef = damageMap.pickpocketRule().getMapRef();
			lastAttributionEvidence = "PICKPOCKET_FAILURE_CORRELATION";
			return failedPickpockets.remove(0);
		}

		// All current hypotheses compete; history and list ordering cannot choose a cause.
		List<NPC> npcCandidates = npcDamageCandidates(localPlayer);
		List<Player> playerCandidates = playerDamageCandidates(localPlayer);
		List<String> hazards = hazardsForHit(hit);
		String projectileSource = matchPendingProjectile(hit, hazards, npcCandidates, playerCandidates);
		if (projectileSource != null)
			return projectileSource;
		if (projectileMatchAmbiguous)
			return "Unknown";
		for (NPC npc : npcCandidates)
			lastAttributionCandidates.add(npcCandidate(npc));
		for (Player player : playerCandidates)
			lastAttributionCandidates.add(playerCandidate(player));
		int count = npcCandidates.size() + playerCandidates.size() + hazards.size();
		if (count > 1)
		{
			lastAttributionEvidence = "MULTIPLE_POSSIBLE_DAMAGE_SOURCES";
			return "Unknown";
		}
		if (hazards.size() == 1)
		{
			if (!npcContactStagesAgree(hit, hazards.get(0)))
			{
				lastAttributionEvidence = "CONTACT_OBSERVATION_PHASE_CONFLICT";
				return "Unknown";
			}
			if (!objectSurfaceEligibilityKnown(hit, hazards.get(0)))
			{
				lastAttributionEvidence = "SURFACE_ELIGIBILITY_UNESTABLISHED";
				return "Unknown";
			}
			if (!objectSurfaceStagesAgree(hit, hazards.get(0)))
			{
				lastAttributionEvidence = "HAZARD_OBSERVATION_PHASE_CONFLICT";
				return "Unknown";
			}
			lastAttributionEvidence = "HAZARD_ON_IMPACT_TILE";
			lastAttributionConfidence = "ENVIRONMENTAL";
			return hazards.get(0);
		}
		if (npcCandidates.size() == 1)
		{
			NPC npc = npcCandidates.get(0);
			String name = getActorName(npc);
			if (requiresProjectile(npc))
			{
				lastAttributionEvidence = "NPC_PROJECTILE_ATTACK_WITHOUT_CAPTURED_PROJECTILE";
				return "Unknown";
			}
			recentAttackers.put(npc.getIndex(), new AttackerRecord(name, npc.getIndex(), currentGameTick, npc,
				nativeView(), nativeScene(), causalSceneEpoch, nativePlane()));
			lastAttributionConfidence = "TIMED";
			lastAttributionNpcId = observedNpcId(npc);
			lastAttributionNpcLevel = npc.getCombatLevel();
			lastAttributionNormalContext = referenceContextObserved(npc);
			lastAttributionActorRef = actorRef(npc);
			lastAttributionEvidence = "NPC_ANIMATION_CORRELATION";
			lastAttributionAttackRule = damageMap == null ? null
				: damageMap.attackRuleForNpc(observedNpcId(npc), npc.getAnimation(), npc.getCombatLevel(),
					lastAttributionNormalContext);
			lastAttributionMapRef = lastAttributionAttackRule == null ? null : lastAttributionAttackRule.getMapRef();
			return name;
		}
		if (playerCandidates.size() == 1)
		{
			Player other = playerCandidates.get(0);
			if (damageMap == null || damageMap.styleForPlayerAnimation(other.getAnimation()) == null)
			{
				lastAttributionEvidence = "PLAYER_UNCLASSIFIED_ACTIVITY_WITHOUT_ATTACK_LINK";
				return "Unknown";
			}
			lastAttributionConfidence = "PROXIMITY";
			lastAttributionSourceIsPlayer = true;
			lastAttributionActorRef = actorRef(other);
			lastAttributionEvidence = "PLAYER_ANIMATION_CORRELATION";
			return getActorName(other);
		}
		for (AttackerRecord record : recentAttackers.values())
			if (record.epoch == causalSceneEpoch && record.plane == nativePlane() && record.view == nativeView()
				&& record.scene == nativeScene())
				lastAttributionCandidates.add(data("kind", "RECENT_ATTACKER_NOT_CURRENT_HIT_EVIDENCE", "name",
					record.name, "lastCorrelatedTick", record.tickRecorded));
		lastAttributionEvidence = lastAttributionCandidates.isEmpty() ? "UNRESOLVED_HP_DAMAGE"
			: "HISTORY_WITHOUT_CURRENT_HIT_EVIDENCE";
		return "Unknown";
	}

	private int unlinkedHpObservationCount(PendingHit hit)
	{
		// A coloured hit can be one-off damage. Type alone does not exclude it from
		// the observations competing for an attack/projectile association.
		return (int) pendingHits.stream()
			.filter(other -> other.epoch == hit.epoch && other.plane == hit.plane && other.worldView == hit.worldView
				&& other.scene == hit.scene && other.baseX == hit.baseX && other.baseY == hit.baseY)
			.count();
	}

	private boolean requiresProjectile(NPC npc)
	{
		boolean normal = referenceContextObserved(npc);
		DamageMap.AttackRule rule = damageMap == null ? null
			: damageMap.attackRuleForNpc(observedNpcId(npc), npc.getAnimation(), npc.getCombatLevel(), normal);
		return rule != null && rule.isRequiresProjectile();
	}

	private List<NPC> npcDamageCandidates(Player local)
	{
		List<NPC> out = new ArrayList<>();
		for (NPC npc : nativeNpcs())
		{
			if (npc == null || npc.getInteracting() != local || !isCombatNpc(npc) || npc.getAnimation() == -1)
				continue;
			// Display labels such as spawn/transition/death are not proof that this activity cannot deal damage.
			out.add(npc);
		}
		return out;
	}

	private List<Player> playerDamageCandidates(Player local)
	{
		List<Player> out = new ArrayList<>();
		if (!isPvpContext())
			return out;
		for (Player other : nativePlayers())
			if (other != null && other != local && other.getInteracting() == local && other.getAnimation() != -1)
				out.add(other);
		return out;
	}

	private Map<String, Object> playerCandidate(Player player)
	{
		boolean recognized = damageMap != null && damageMap.styleForPlayerAnimation(player.getAnimation()) != null;
		return data("kind", recognized ? "PLAYER_ANIMATION_CORRELATION" : "PLAYER_UNCLASSIFIED_ANIMATION", "name",
			getActorName(player), "actorRef", actorRef(player), "animationId",
			config.captureOtherPlayers() ? player.getAnimation() : null, "evidence",
			"Engagement and active animation in observed PvP context; neither a recognized style nor missing style establishes this hit's source");
	}

	private Map<String, Object> npcCandidate(NPC npc)
	{
		return data("kind", requiresProjectile(npc) ? "NPC_PROJECTILE_ATTACK_ANIMATION" : "NPC_ANIMATION_CORRELATION",
			"name", getActorName(npc), "actorRef", actorRef(npc), "npcId", observedNpcId(npc), "combatLevel",
			npc.getCombatLevel(), "animationId", npc.getAnimation(), "requiresProjectile", requiresProjectile(npc),
			"evidence",
			"Animation and engagement are not proof that this hit came from this actor; a missing projectile does not exclude the actor");
	}

	/**
	 * The name of a known damaging hazard the player is standing ON this tick â€” a spot-anim
	 * (graphicsObject), ground object, or game object on the player's exact tile whose id maps to a
	 * hazard. Co-location is evidence for a possible source, not a unique causal link to a hit.
	 * Returns null if the player is not on a mapped damaging surface.
	 */
	private List<String> hazardsForHit(PendingHit hit)
	{
		java.util.Set<String> names = new java.util.LinkedHashSet<>(hit.observedHazards);
		names.addAll(hazardsAtReportedPosition(hit.position, hit.localPosition, hit.hazardWitnesses));
		return new ArrayList<>(names);
	}

	private java.util.Map<String, Object> hazardCandidate(PendingHit hit, String name)
	{
		java.util.Map<String, Object> candidate = new java.util.LinkedHashMap<>(
			data("kind", "HAZARD_ON_IMPACT_TILE", "name", name));
		List<java.util.Map<String, Object>> observations = new ArrayList<>();
		for (java.util.Map<String, Object> witness : hit.hazardWitnesses)
			if (name.equals(witness.get("name")))
				observations.add(new java.util.LinkedHashMap<>(witness));
		if (!observations.isEmpty())
			candidate.put("observations", observations);
		if (observations.stream().anyMatch(witness -> "NPC_CONTACT".equals(witness.get("kind"))))
			candidate.put("observationPhaseAgreement",
				npcContactStagesAgree(hit, name) ? "CONSISTENT_AT_BOTH_STAGES" : "UNESTABLISHED");
		if (observations.stream().anyMatch(witness -> "OBJECT_SURFACE".equals(witness.get("kind"))))
		{
			candidate.put("observationPhaseAgreement",
				objectSurfaceStagesAgree(hit, name) ? "CONSISTENT_AT_BOTH_STAGES" : "UNESTABLISHED");
			candidate.put("surfaceEligibility",
				objectSurfaceEligibilityKnown(hit, name) ? "REFERENCE_CANDIDATE" : "UNESTABLISHED");
		}
		return java.util.Collections.unmodifiableMap(candidate);
	}

	private boolean npcContactStagesAgree(PendingHit hit, String name)
	{
		java.util.Set<List<Object>> callback = new java.util.HashSet<>(), settled = new java.util.HashSet<>();
		boolean contact = false;
		for (Map<String, Object> witness : hit.hazardWitnesses)
		{
			if (!name.equals(witness.get("name")) || !"NPC_CONTACT".equals(witness.get("kind")))
				continue;
			contact = true;
			if (witness.get("actorRef") == null)
				continue;
			List<Object> identity = java.util.Arrays.asList(witness.get("actorRef"), witness.get("npcId"),
				witness.get("worldViewId"), witness.get("plane"), witness.get("worldX"), witness.get("worldY"),
				witness.get("footprintWidth"), witness.get("footprintHeight"), witness.get("contactPredicate"),
				witness.get("defenderPositionEvidence"));
			if ("HITSPLAT_CALLBACK".equals(witness.get("observationStage")))
				callback.add(identity);
			else if ("DEFERRED_RESOLUTION".equals(witness.get("observationStage")))
				settled.add(identity);
		}
		// Agreement is a stronger spatial correlation, not proof of packet ordering or causality.
		return !contact || callback.stream().anyMatch(settled::contains);
	}

	private boolean objectSurfaceEligibilityKnown(PendingHit hit, String name)
	{
		for (Map<String, Object> witness : hit.hazardWitnesses)
			if (name.equals(witness.get("name")) && "OBJECT_SURFACE".equals(witness.get("kind")))
			{
				String eligibility = (String) witness.get("sourceEligibility");
				if (!"ELIGIBLE_REFERENCE_SURFACE".equals(eligibility) && !"NOT_DAMAGE_SURFACE".equals(eligibility))
					return false;
			}
		return true;
	}

	private boolean objectSurfaceStagesAgree(PendingHit hit, String name)
	{
		java.util.Set<List<Object>> callback = new java.util.HashSet<>(), settled = new java.util.HashSet<>();
		boolean observed = false;
		for (Map<String, Object> witness : hit.hazardWitnesses)
		{
			if (!name.equals(witness.get("name")) || !"OBJECT_SURFACE".equals(witness.get("kind")))
				continue;
			observed = true;
			if (!"ELIGIBLE_REFERENCE_SURFACE".equals(witness.get("sourceEligibility")))
				continue;
			List<Object> identity = java.util.Arrays.asList(witness.get("objectObservationId"), witness.get("objectId"),
				witness.get("baseObjectId"), witness.get("objectKind"), witness.get("worldViewId"),
				witness.get("plane"), witness.get("contactWorldX"), witness.get("contactWorldY"), witness.get("state"),
				witness.get("defenderPositionEvidence"));
			if ("HITSPLAT_CALLBACK".equals(witness.get("observationStage")))
				callback.add(identity);
			else if ("DEFERRED_RESOLUTION".equals(witness.get("observationStage")))
				settled.add(identity);
		}
		return !observed || callback.stream().anyMatch(settled::contains);
	}

	private void observeObjectSurface(java.util.Set<String> names, List<Map<String, Object>> witnesses,
		net.runelite.api.TileObject object, String kind, ObjectHazards.Resolved hazard, LocalPoint contact, int plane,
		boolean serverTargetKnown)
	{
		if (hazard == null)
			return;
		if (hazard.isPotentialSource())
			names.add(hazard.getName());
		if (witnesses != null && damageMap.objectHazardRule(hazard.getBaseId()) != null)
		{
			Map<String, Object> witness = new LinkedHashMap<>(data("kind", "OBJECT_SURFACE", "name", hazard.getName(), "objectId",
				hazard.getId(), "baseObjectId", hazard.getBaseId(), "objectKind", kind, "objectObservationId",
				surfaceObservationIds.id(object), "worldViewId", nativeViewId(), "plane", plane,
				"sceneKey", nativeView() != null && nativeScene() != null ? currentSceneKey : null,
				"scopeEvidence", "NATIVE_SCENE_TILE_MEMBERSHIP", "contactWorldX",
				nativeBaseX() + contact.getSceneX(), "contactWorldY", nativeBaseY() + contact.getSceneY(), "state",
				hazard.getState(), "damaging", hazard.getDamaging(), "sourceEligibility", hazard.getSourceEligibility(),
				"surfaceRuleKey", hazard.getRuleKey(), "stateEvidence", hazard.getEvidence(), "mapRef",
				hazard.getMapRef(), "observedCycle", client.getGameCycle(), "observationStage",
				gameTickInProgress ? "DEFERRED_RESOLUTION" : "HITSPLAT_CALLBACK", "defenderPositionEvidence",
				serverTargetKnown ? "SERVER_REPORTED_TILE" : "CLIENT_RENDER_FALLBACK"));
			DamageMap.ObjectHazardRule surfaceRule = damageMap.objectHazardRule(hazard.getBaseId());
			if (surfaceRule != null && ("CONDITIONAL_HP_OBJECT_FOOTPRINT".equals(surfaceRule.getSurfaceOperator())
				|| "CONDITIONAL_HP_TILE".equals(surfaceRule.getSurfaceOperator())))
			{
				com.tickreplaylogger.capture.CallbackFieldObservation scope = new com.tickreplaylogger.capture.CallbackFieldObservation();
				net.runelite.api.WorldView own = scope.sample("worldView", object::getWorldView);
				Integer ownId = own == null ? null : scope.value("worldViewId", own::getId);
				Integer ownPlane = scope.value("plane", object::getPlane);
				witness.put("objectNativeScopeKnown", ownId != null && ownId >= 0 && ownPlane != null && ownPlane >= 0 && ownPlane <= 3);
				witness.put("objectWorldViewId", ownId);
				witness.put("objectPlane", ownPlane);
				witness.put("objectNativeScopeObservation", scope.data());
			}
			if (!witnesses.contains(witness))
					witnesses.add(java.util.Collections.unmodifiableMap(witness));
		}
	}

	/** Server tile may lead the rendered actor during movement; surfaces use tile occupancy. */
	private List<String> hazardsAtReportedPosition(WorldPoint reported, LocalPoint rendered)
	{
		return hazardsAtReportedPosition(reported, rendered, null);
	}

	private List<String> hazardsAtReportedPosition(WorldPoint reported, LocalPoint rendered,
		List<java.util.Map<String, Object>> witnesses)
	{
		if (reported == null)
			return hazardsOnTile(rendered, nativePlane(), witnesses, false);
		int x = reported.getX() - nativeBaseX(), y = reported.getY() - nativeBaseY();
		net.runelite.api.WorldView view = nativeView();
		int width = activeHitScope != null ? activeHitScope.width : view == null ? 104 : view.getSizeX(),
			height = activeHitScope != null ? activeHitScope.height : view == null ? 104 : view.getSizeY();
		// A known tile outside this view cannot be replaced by a different rendered tile.
		if (x < 0 || y < 0 || x >= width || y >= height)
			return java.util.Collections.emptyList();
		return hazardsOnTile(new LocalPoint(x * 128 + 64, y * 128 + 64), reported.getPlane(), witnesses, true);
	}

	private List<String> hazardsOnTile(LocalPoint pl, int plane)
	{
		return hazardsOnTile(pl, plane, null, false);
	}

	private List<String> hazardsOnTile(LocalPoint pl, int plane, List<java.util.Map<String, Object>> witnesses,
		boolean serverTargetKnown)
	{
		java.util.Set<String> names = new java.util.LinkedHashSet<>();
		if (damageMap == null)
		{
			return new ArrayList<>(names);
		}
		if (pl == null)
		{
			return new ArrayList<>(names);
		}

		// 1) Spot-anim graphics objects on the player's exact scene tile.
		for (GraphicsObject go : nativeGraphics())
		{
			if (go == null)
			{
				continue;
			}
			LocalPoint loc = go.getLocation();
			if (loc == null || loc.getSceneX() != pl.getSceneX() || loc.getSceneY() != pl.getSceneY())
			{
				continue;
			}
			if (go.getLevel() != plane || go.finished() || go.getStartCycle() > client.getGameCycle())
				continue;
			net.runelite.api.WorldView graphicView = go.getWorldView();
			if (graphicView != null && nativeView() != null && graphicView != nativeView())
				continue;
			Integer graphicViewId = graphicView == null ? null : graphicView.getId();
			int graphicId = go.getId();
			com.tickreplaylogger.capture.CombatCatalog.GraphicSurface surface = damageMap.catalog().graphicSurface(graphicId);
			String name = damageMap.catalog().damagingGraphicName(graphicId);
			if (name != null)
			{
				names.add(name);
			}
			if (witnesses != null)
			{
				Map<String, Object> witness = data("kind", "GRAPHIC_SURFACE", "name", name, "graphicId", graphicId,
					"graphicObservationId", surfaceObservationIds.id(go), "worldViewId", graphicViewId, "plane", plane,
					"sceneKey", graphicView != null && graphicView == nativeView() ? currentSceneKey : null,
					"contactWorldX", nativeBaseX() + pl.getSceneX(), "contactWorldY", nativeBaseY() + pl.getSceneY(),
					"worldX", nativeBaseX() + loc.getSceneX(), "worldY", nativeBaseY() + loc.getSceneY(),
					"startCycle", go.getStartCycle(), "finished", false, "observedCycle", client.getGameCycle(),
					"state", surface == null ? null : surface.getState(), "damaging", surface == null ? null : surface.getDamaging(),
					"sourceEligibility", surface == null || surface.getDamaging() == null ? "UNCLASSIFIED_GRAPHIC_SURFACE"
						: surface.getDamaging() ? "ELIGIBLE_REFERENCE_SURFACE" : "NOT_DAMAGE_SURFACE",
					"surfaceRuleKey", surface == null ? null : surface.getRuleKey(), "mapRef", surface == null ? null : surface.getMapRef(),
					"observationStage", gameTickInProgress ? "DEFERRED_RESOLUTION" : "HITSPLAT_CALLBACK",
					"defenderPositionEvidence", serverTargetKnown ? "SERVER_REPORTED_TILE" : "CLIENT_RENDER_FALLBACK");
				if (!witnesses.contains(witness))
					witnesses.add(witness);
			}
		}

		// Non-interactive NPCs can be contact hazards without an attack animation or target.
		for (NPC npc : nativeNpcs())
		{
			if (npc == null)
				continue;
			net.runelite.api.WorldView contactView = npc.getWorldView();
			if (contactView != null && contactView != nativeView())
				continue;
			WorldPoint position = npc.getWorldLocation();
			if (position == null || position.getPlane() != plane)
				continue;
			net.runelite.api.NPCComposition definition = npc.getTransformedComposition();
			if (definition == null)
				definition = npc.getComposition();
			if (definition == null || definition.getSize() < 1 || definition.getSize() > 64)
				continue;
			int size = definition.getSize(), targetX = nativeBaseX() + pl.getSceneX(),
				targetY = nativeBaseY() + pl.getSceneY();
			// Presence is a recorded source alternative, not contact, activation or an observed attack.
			if (witnesses != null && !gameTickInProgress && sourceAssociationNpcs.contains(definition.getId())
				&& contactView != null && nativeScene() != null)
			{
				Map<String, Object> witness = data("kind", "NPC_EMITTER", "npcId", definition.getId(),
					"baseNpcId", npc.getId(), "actorRef", actorRef(npc), "observedName", npc.getName(),
					"combatLevel", npc.getCombatLevel(), "worldX", position.getX(), "worldY", position.getY(),
					"plane", position.getPlane(), "observedSize", size, "worldViewId", contactView.getId(),
					"sceneKey", currentSceneKey, "scopeEvidence", "NPC_WORLD_VIEW_READ", "observedCycle",
					client.getGameCycle(), "observationStage", "HITSPLAT_CALLBACK", "sourceEligibility",
					"DECLARED_SOURCE_ASSOCIATION");
				if (!witnesses.contains(witness))
				{
					witnesses.add(witness);
				}
			}
			boolean coherentFootprint = false;
			if (size == 1)
			{
				if (position.getX() != targetX || position.getY() != targetY)
					continue;
			}
			else
			{
				// Require the API area, server anchor and transformed definition to agree; never
				// substitute a rendered footprint lagging behind a known server position.
				net.runelite.api.coords.WorldArea area = npc.getWorldArea();
				if (area == null || area.getPlane() != plane || area.getX() != position.getX()
					|| area.getY() != position.getY() || area.getWidth() != size || area.getHeight() != size)
					continue;
				if (targetX < area.getX() || targetY < area.getY() || (long) targetX >= ((long) area.getX() + size)
					|| (long) targetY >= ((long) area.getY() + size))
					continue;
				coherentFootprint = true;
			}
			int contactNpcId = observedNpcId(npc), contactCombatLevel = npc.getCombatLevel();
			boolean contactNormalContext = referenceContextObserved(npc);
			String name = damageMap.catalog().npcContactHazardName(contactNpcId, contactCombatLevel,
				contactNormalContext, size, coherentFootprint);
			if (name != null)
			{
				names.add(name);
				if (witnesses != null)
				{
					java.util.Map<String, Object> witness = data("name", name, "kind", "NPC_CONTACT", "npcId",
						contactNpcId, "combatLevel", contactCombatLevel, "normalWorldContextObserved", contactNormalContext,
						"baseNpcId", npc.getId(), "actorRef", actorRef(npc), "observedName",
						npc.getName(), "worldX", position.getX(), "worldY", position.getY(), "plane",
						position.getPlane(), "observedSize", size, "contactPredicate",
						coherentFootprint ? "COHERENT_SERVER_FOOTPRINT" : "SAME_SERVER_TILE", "footprintWidth", size,
						"footprintHeight", size, "worldViewId",
						contactView == null ? null : contactView.getId(),
						"sceneKey", contactView != null && contactView == nativeView() && nativeScene() != null ? currentSceneKey : null,
						"scopeEvidence", "NPC_WORLD_VIEW_READ", "observedCycle",
						client.getGameCycle(), "observationStage",
						gameTickInProgress ? "DEFERRED_RESOLUTION" : "HITSPLAT_CALLBACK", "defenderPositionEvidence",
						serverTargetKnown ? "SERVER_REPORTED_TILE" : "CLIENT_RENDER_FALLBACK",
						"sourceEligibility", "ELIGIBLE_REFERENCE_SURFACE", "damaging", true);
					if (!witnesses.contains(witness))
						witnesses.add(witness);
				}
			}
		}

		// 2) Ground / game objects on the player's exact scene tile.
		{
			net.runelite.api.Scene scene = nativeScene();
			if (scene != null && scene.getTiles() != null && plane >= 0 && plane < scene.getTiles().length)
			{
				int sx = pl.getSceneX();
				int sy = pl.getSceneY();
				net.runelite.api.Tile[][] planeTiles = scene.getTiles()[plane];
				if (sx >= 0 && sx < planeTiles.length && sy >= 0 && sy < planeTiles[sx].length)
				{
					net.runelite.api.Tile tile = planeTiles[sx][sy];
					if (tile != null)
					{
						net.runelite.api.GroundObject gnd = tile.getGroundObject();
						if (gnd != null)
						{
							ObjectHazards.Resolved hazard = ObjectHazards.resolve(client, gnd, damageMap);
							observeObjectSurface(names, witnesses, gnd, "groundObject", hazard, pl, plane,
								serverTargetKnown);
						}
						net.runelite.api.GameObject[] gos = tile.getGameObjects();
						if (gos != null)
						{
							for (net.runelite.api.GameObject g : gos)
							{
								if (g != null)
								{
									ObjectHazards.Resolved hazard = ObjectHazards.resolve(client, g, damageMap);
									observeObjectSurface(names, witnesses, g, "gameObject", hazard, pl, plane,
										serverTargetKnown);
								}
							}
						}
					}
				}
			}
		}
		// Read failures propagate to the per-hit guard. Missing evidence must not select a rival source.
		return new ArrayList<>(names);
	}

	/**
	 * Try to match a pending projectile to the current hitsplat.
	 * Consumes the matched projectile so it isn't matched again.
	 * Uses adaptive matching window: Â±1 tick for fast, Â±2 for slow projectiles.
	 */
	private Player nativeLocalPlayer()
	{
		return activeHitScope == null ? client.getLocalPlayer() : activeHitScope.localPlayer;
	}

	private net.runelite.api.WorldView nativeView()
	{
		return activeHitScope == null ? com.tickreplaylogger.capture.WorldViews.active(client) : activeHitScope.view;
	}

	private Integer nativeViewId()
	{
		if (activeHitScope != null)
			return activeHitScope.viewId;
		net.runelite.api.WorldView view = nativeView();
		return view == null ? null : view.getId();
	}

	private int nativePlane()
	{
		return activeHitScope != null ? activeHitScope.plane == null ? -1 : activeHitScope.plane
			: nativeView() == null ? client.getPlane() : nativeView().getPlane();
	}

	private int nativeBaseX()
	{
		return activeHitScope != null ? activeHitScope.baseX == null ? 0 : activeHitScope.baseX
			: nativeView() == null ? client.getBaseX() : nativeView().getBaseX();
	}

	private int nativeBaseY()
	{
		return activeHitScope != null ? activeHitScope.baseY == null ? 0 : activeHitScope.baseY
			: nativeView() == null ? client.getBaseY() : nativeView().getBaseY();
	}

	private net.runelite.api.Scene nativeScene()
	{
		return activeHitScope != null ? activeHitScope.scene
			: nativeView() == null ? client.getScene() : nativeView().getScene();
	}

	private List<NPC> nativeNpcs()
	{
		List<NPC> out = new ArrayList<>();
		if (activeHitScope != null && activeHitScope.view == null)
			return out;
		Iterable<? extends NPC> actors = nativeView() == null ? client.getNpcs() : nativeView().npcs();
		if (actors != null)
			for (NPC actor : actors)
				out.add(actor);
		return out;
	}

	private List<Player> nativePlayers()
	{
		List<Player> out = new ArrayList<>();
		if (activeHitScope != null && activeHitScope.view == null)
			return out;
		Iterable<? extends Player> actors = nativeView() == null ? client.getPlayers() : nativeView().players();
		if (actors != null)
			for (Player actor : actors)
				out.add(actor);
		return out;
	}

	private Iterable<? extends GraphicsObject> nativeGraphics()
	{
		if (activeHitScope != null && activeHitScope.view == null)
			return java.util.Collections.emptyList();
		Iterable<? extends GraphicsObject> graphics = nativeView() == null ? client.getGraphicsObjects()
			: nativeView().getGraphicsObjects();
		return graphics == null ? java.util.Collections.emptyList() : graphics;
	}

	private boolean isPvpContext()
	{
		try
		{
			return client.getVarbitValue(net.runelite.api.Varbits.PVP_SPEC_ORB) == 1;
		}
		catch (RuntimeException unavailable)
		{
			return false;
		}
	}

	private boolean projectileCouldMatch(PendingProjectile candidate, PendingHit hit)
	{
		if (candidate.epoch != hit.epoch || candidate.plane != hit.plane || candidate.worldView != hit.worldView
			|| candidate.scene != hit.scene)
			return false;
		if (candidate.sourceIsPlayer && !isPvpContext() && !projectileSourceUnestablished(candidate))
			return false;
		if (!candidate.timingKnown)
			return candidate.removedObservedTick == null || currentGameTick <= candidate.removedObservedTick + 2;
		int tolerance = candidate.travelTicks >= 3 ? 2 : 1;
		// Callback ticks and rounded arrival estimates have different boundary
		// conventions. When both client cycles were read, compare those directly.
		if (candidate.endCycle > 0 && hit.observedCycle != null)
			return Math.abs((long) hit.observedCycle - candidate.endCycle) <= 30L * tolerance;
		return Math.abs(currentGameTick - candidate.estimatedArrivalTick) <= tolerance;
	}

	private boolean projectileSourceUnestablished(PendingProjectile candidate)
	{
		return candidate.sourceSamplingUnavailable
			|| candidate.latestObservation != null && candidate.latestObservation.getSourceHistory() != null
				&& candidate.latestObservation.getSourceHistory().isAttributionUnestablished();
	}

	private Map<String, Object> projectileLatestSource(PendingProjectile candidate)
	{
		ProjectileState state = candidate.latestObservation;
		return state == null ? null
			: data("observationId", state.getObservationId(), "observedTick", state.getObservedTick(), "observedCycle",
				state.getObservedCycle(), "sourceRef", state.getSourceRef(), "sourceNpcId", state.getSourceNpcId(),
				"sourceName", state.getSourceName(), "sourceActorKnown", state.getSourceActorKnown(), "sourceNameKnown",
				state.getSourceNameKnown());
	}

	private void retainTypedHitProjectileAlternatives(PendingHit hit)
	{
		// Missing optional context cannot establish that a compatible projectile was unused.
		for (PendingProjectile candidate : pendingProjectiles)
			if (projectileCouldMatch(candidate, hit))
			{
				candidate.ambiguousForSequences.add(hit.sequence);
				lastAttributionCandidates
					.add(data("kind", "PROJECTILE_MAY_HAVE_CAUSED_TYPED_DAMAGE", "name", candidate.sourceName,
						"actorRef", candidate.sourceRef, "projectileId", candidate.projectileId, "observationSequence",
						hit.sequence, "projectileObservationId", candidate.projectileObservationId, "sourceHistory",
						candidate.latestObservation == null ? null : candidate.latestObservation.getSourceHistory(),
						"latestSourceObservation", projectileLatestSource(candidate), "sourceSamplingUnavailable",
						candidate.sourceSamplingUnavailable, "matchBasis",
						candidate.timingKnown ? "ARRIVAL_WINDOW" : "TIMING_UNAVAILABLE", "evidence",
						"Damage type does not establish a recurring tick or identify its inflictor"));
			}
	}

	private String matchPendingProjectile(PendingHit hit, List<String> hazards, List<NPC> npcCandidates,
		List<Player> playerCandidates)
	{
		projectileMatchAmbiguous = false;
		List<PendingProjectile> matches = new ArrayList<>();
		for (PendingProjectile candidate : pendingProjectiles)
		{
			if (projectileCouldMatch(candidate, hit))
				matches.add(candidate);
		}
		for (PendingProjectile candidate : matches)
			lastAttributionCandidates.add(data("kind", "PROJECTILE_CORRELATION", "name", candidate.sourceName,
				"actorRef", candidate.sourceRef, "projectileId", candidate.projectileId, "npcId",
				candidate.sourceNpcId < 0 ? null : candidate.sourceNpcId, "combatLevel", candidate.sourceNpcLevel,
				"firstObservedTick", candidate.spawnTick, "estimatedArrivalTick",
				candidate.timingKnown ? candidate.estimatedArrivalTick : null, "arrivalGapTicks",
				candidate.timingKnown ? currentGameTick - candidate.estimatedArrivalTick : null, "matchBasis",
				!candidate.timingKnown ? "TIMING_UNAVAILABLE"
					: candidate.endCycle > 0 && hit.observedCycle != null ? "OBSERVED_CLIENT_CYCLE_WINDOW"
						: "ARRIVAL_WINDOW",
				"hitObservedCycle", hit.observedCycle, "projectileEndCycle",
				candidate.endCycle > 0 ? candidate.endCycle : null, "arrivalGapCycles",
				candidate.endCycle > 0 && hit.observedCycle != null ? (long) hit.observedCycle - candidate.endCycle
					: null,
				"removedObservedTick", candidate.removedObservedTick, "previouslyCandidateForObservationSequences",
				new ArrayList<>(candidate.ambiguousForSequences), "sourceHistory",
				candidate.latestObservation == null ? null : candidate.latestObservation.getSourceHistory(),
				"latestSourceObservation", projectileLatestSource(candidate), "sourceSamplingUnavailable",
				candidate.sourceSamplingUnavailable, "projectileObservationId", candidate.projectileObservationId));
		for (String hazard : hazards)
			lastAttributionCandidates.add(hazardCandidate(hit, hazard));
		int competing = 0;
		if (!matches.isEmpty())
		{
			for (NPC npc : npcCandidates)
			{
				lastAttributionCandidates.add(npcCandidate(npc));
				// A reviewed projectile wind-up supports this same actor's projectile
				// hypothesis. Other actions remain independent competing possibilities.
				String ref = actorRef(npc);
				if (!requiresProjectile(npc) || ref == null
					|| matches.stream().noneMatch(candidate -> ref.equals(candidate.sourceRef)))
					competing++;
			}
			// A current action can differ from this actor's older projectile. A shared
			// identity supports a qualified actor name, not a unique attack or impact.
			for (Player other : playerCandidates)
			{
				lastAttributionCandidates.add(playerCandidate(other));
				competing++;
			}
		}
		boolean reusedAmbiguous = matches.stream().anyMatch(candidate -> !candidate.ambiguousForSequences.isEmpty());
		boolean timingUnavailable = matches.stream().anyMatch(candidate -> !candidate.timingKnown);
		if (matches.stream().anyMatch(this::projectileSourceUnestablished))
		{
			for (PendingProjectile candidate : matches)
				candidate.ambiguousForSequences.add(hit.sequence);
			boolean changed = matches.stream()
				.anyMatch(candidate -> candidate.latestObservation != null
					&& candidate.latestObservation.getSourceHistory() != null
					&& candidate.latestObservation.getSourceHistory().isActorChanged());
			projectileMatchAmbiguous = true;
			lastAttributionEvidence = changed ? "PROJECTILE_SOURCE_OBSERVATIONS_CONFLICT"
				: "PROJECTILE_SOURCE_HISTORY_UNESTABLISHED";
			return null;
		}
		if (!hit.hpOutcomeKnown || unlinkedHpObservationCount(hit) > 1 || reusedAmbiguous || timingUnavailable)
		{
			if (matches.isEmpty())
			{
				for (NPC npc : npcCandidates)
					lastAttributionCandidates.add(npcCandidate(npc));
				for (Player other : playerCandidates)
					lastAttributionCandidates.add(playerCandidate(other));
			}
			lastAttributionCandidates.add(data("kind",
				timingUnavailable ? "PROJECTILE_TIMING_UNAVAILABLE"
					: reusedAmbiguous ? "PROJECTILE_MAY_HAVE_ALREADY_IMPACTED"
					: !hit.hpOutcomeKnown ? "OUTCOME_SURFACE_UNRESOLVED" : "OBSERVATION_BATCH_WITHOUT_UNIQUE_ATTACK_LINK",
				"observationCount", unlinkedHpObservationCount(hit), "evidence",
				timingUnavailable ? "Unavailable projectile timing cannot select or exclude a possible damage source"
					: "No unique observation-to-attack association is exposed"));
			for (PendingProjectile candidate : matches)
				candidate.ambiguousForSequences.add(hit.sequence);
			projectileMatchAmbiguous = true;
			lastAttributionEvidence = timingUnavailable ? "PROJECTILE_TIMING_UNAVAILABLE"
				: "MULTIPLE_POSSIBLE_DAMAGE_SOURCES";
			return null;
		}
		if (matches.size() > 1 || (!matches.isEmpty() && (!hazards.isEmpty() || competing > 0)))
		{
			for (PendingProjectile candidate : matches)
				candidate.ambiguousForSequences.add(hit.sequence);
			projectileMatchAmbiguous = true;
			lastAttributionEvidence = "MULTIPLE_POSSIBLE_DAMAGE_SOURCES";
			// Several attacks can remain ambiguous while all observed hypotheses identify
			// the same actor. Preserve that qualified identity without choosing an attack,
			// consuming a projectile or borrowing its style/launch/prayer timing.
			String commonRef = null, commonName = null;
			boolean common = !lastAttributionCandidates.isEmpty();
			for (Map<String, Object> candidate : lastAttributionCandidates)
			{
				Object ref = candidate.get("actorRef"), name = candidate.get("name");
				if (!(ref instanceof String) || !(name instanceof String) || ((String) ref).isEmpty())
				{
					common = false;
					break;
				}
				if (commonRef == null)
				{
					commonRef = (String) ref;
					commonName = (String) name;
				}
				else if (!commonRef.equals(ref) || !commonName.equals(name))
				{
					common = false;
					break;
				}
			}
			if (common)
			{
				lastAttributionActorRef = commonRef;
				lastAttributionConfidence = "TIMED";
				lastAttributionSourceIsPlayer = matches.stream().allMatch(candidate -> candidate.sourceIsPlayer);
				lastAttributionEvidence = "SAME_ACTOR_MULTIPLE_ATTACK_CANDIDATES";
				return commonName;
			}
			return null;
		}
		if (matches.isEmpty())
			return null;
		PendingProjectile match = matches.get(0);
		pendingProjectiles.remove(match);
		projectileSourceLinks.remove(match.projectileObservationId);
		// Observing a projectile source does not prove that this hitsplat came from it.
		lastAttributionConfidence = match.hadSourceActor ? "TIMED" : "PROXIMITY";
		lastAttributionLaunchTick = match.spawnTick;
		lastAttributionSourceIsPlayer = match.sourceIsPlayer;
		lastAttributionProjectileId = match.projectileId;
		lastAttributionNpcId = match.sourceNpcId;
		lastAttributionNpcLevel = match.sourceNpcLevel;
		lastAttributionActorRef = match.sourceRef;
		lastAttributionNormalContext = match.normalContext;
		lastAttributionEvidence = "PROJECTILE_TIMING_CORRELATION";
		return match.sourceName;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		gameTickInProgress = true;
		try
		{
			captureGameTick();
		}
		finally
		{
			gameTickInProgress = false;
			unassignedHits.clear();
			unassignedDeaths.clear();
			settledProjectileSamples = null;
		}
	}

	private void captureGameTick()
	{
		List<String> settledIssues = new ArrayList<>();
		GameState state = readHitContext("settledGameState", client::getGameState, settledIssues);
		if (state == GameState.LOGGED_IN)
			captureSessionObserved = true;
		else if (state != null && !java.util.Arrays
			.asList(GameState.LOADING, GameState.CONNECTION_LOST, GameState.HOPPING, GameState.UNKNOWN).contains(state))
		{
			captureSessionObserved = false;
			return;
		}
		if (!captureSessionObserved)
			return;
		if (state == null && settledIssues.isEmpty())
			settledIssues.add("settledGameState: unavailable");
		Player localPlayer = state == GameState.LOGGED_IN
			? readHitContext("settledLocalPlayer", client::getLocalPlayer, settledIssues)
			: null;
		if (state == GameState.LOGGED_IN && localPlayer == null && settledIssues.isEmpty())
			settledIssues.add("settledLocalPlayer: unavailable");
		currentGameTick++;
		tickRecorder.startNewTick();
		if (worldRecorder != null)
			worldRecorder.advanceTick(currentGameTick);
		Integer apiTick = readHitContext("boundaryApiTickCount", client::getTickCount, settledIssues);
		Integer cycle = readHitContext("boundaryGameCycle", client::getGameCycle, settledIssues);
		tickRecorder.setBoundaryContext(state == null ? null : state.name(), apiTick, cycle);
		settleUnassignedIntake(localPlayer);
		if (localPlayer == null)
		{
			tickRecorder.markStateUnavailable("localPlayer");
			for (PendingHit hit : pendingHits)
				hit.captureIssues.addAll(settledIssues);
		}
		try
		{
			runSettledStage("boundaryTelemetry", this::recordBoundaryTelemetry);
			if (state == GameState.LOGGED_IN)
				captureReadyGameTick(localPlayer, settledIssues);
			else
				captureUnreadyGameTick(
					state == null ? "game state unavailable" : "world state not ready: " + state.name());
		}
		finally
		{
			runSettledStage("resourceImpactContext", () -> flushResourceImpactContexts(java.util.Collections.emptyMap()));
			projectilesThisTick.clear();
			for (String issue : settledIssues)
				tickRecorder.addCaptureIssue(issue);
			tickRecorder.finalizeTick();
		}

		if (pendingDeath != null)
		{
			PendingDeath death = pendingDeath;
			pendingDeath = null;
			recordDeath(death);
		}

		// Update live view panel
		if (panel != null)
		{
			panel.onGameTick();
		}

	}

	private void recordBoundaryTelemetry()
	{
		// Measure tick duration (time since last onGameTick)
		long now = System.currentTimeMillis();
		long tickDuration = 0;
		if (lastTickTimeMs > 0)
		{
			tickDuration = now - lastTickTimeMs;
			// Clamp unreasonable values (e.g., first tick after login)
			if (tickDuration > 10000)
			{
				tickDuration = 0;
			}
		}
		lastTickTimeMs = now;
		// Set telemetry data
		if (config.trackTickTime() && tickDuration > 0)
		{
			tickRecorder.setTickDuration(tickDuration);
		}
		if (config.trackPing())
		{
			tickRecorder.setPing(currentPingMs);
			tickRecorder.setPacketLoss(getPacketLossPercent());
		}
	}

	private void captureUnreadyGameTick(String reason)
	{
		causalSceneEpoch++;
		String issue = "settledContext: " + reason;
		tickRecorder.addCaptureIssue(issue);
		tickRecorder.markStateUnavailable("movement");
		tickRecorder.markStateUnavailable("callbackCoverage");
		tickRecorder.updateObservedState(null, null, null, null, java.util.Collections.emptyList(), false);
		for (PendingHit hit : pendingHits)
			hit.captureIssues.add(issue);
		resolvePendingHits(null);
		failedPickpockets.clear();
		lastPlayerPosition = null;
		lastMovementScope = null;
		lastCaptureWorld = null;
		lastTickPrayerStateKnown = false;
		lastTickPrayerPointsKnown = false;
		manuallyDeactivatedPrayers.clear();
		lastBoostedCombat = null;
		poisonBaselineObserved = false;
		lastPoisonVarp = -1;
		activeEffectSpotanims.clear();
		capturedWorld = 0;
		unavailableWorldSnapshot(reason);
		runSettledStage("sourceResolution", this::resolveRecordedSources);
	}

	private void captureReadyGameTick(Player localPlayer, List<String> settledIssues)
	{
		// Expire old attacker records
		runSettledStage("attackerExpiry",
			() -> recentAttackers.values()
				.removeIf(record -> currentGameTick - record.tickRecorded > ATTACKER_EXPIRY_TICKS
					|| record.epoch != causalSceneEpoch || record.plane != nativePlane() || record.view != nativeView()
					|| record.scene != nativeScene()));

		// Expire old pending projectiles (arrival tick + grace window passed)
		pendingProjectiles.removeIf(pp -> pp.timingKnown ? currentGameTick > pp.estimatedArrivalTick + 2
			: pp.removedObservedTick != null && currentGameTick > pp.removedObservedTick + 2);
		java.util.Set<Long> activePendingIds = new java.util.HashSet<>();
		for (PendingProjectile candidate : pendingProjectiles)
			activePendingIds.add(candidate.projectileObservationId);
		projectileSourceLinks.keySet().retainAll(activePendingIds);

		// Clean up processed projectile references for expired projectiles
		runSettledStage("projectileExpiry",
			() -> processedProjectiles.removeIf(p -> projectileExpiryObserved(p, "projectileExpiry")));

		// Movement failure must not prevent already observed hits from settling.
		try
		{
			WorldPoint currentPosition = localPlayer.getWorldLocation();
			if (currentPosition == null)
				throw new IllegalStateException("position unavailable");
			String movementScope = com.tickreplaylogger.capture.WorldViews
				.scope(com.tickreplaylogger.capture.WorldViews.active(client));
			if (movementScope == null)
				movementScope = "legacy:" + nativeBaseX() + ":" + nativeBaseY() + ":" + nativePlane();
			Integer distance = com.tickreplaylogger.capture.WorldViews.settledDistance(lastPlayerPosition,
				currentPosition, lastMovementScope, movementScope);
			if (!java.util.Objects.equals(lastMovementScope, movementScope))
				lastCaptureWorld = null;
			if (distance != null)
			{
				if (config.trackMovement() && distance > 0)
				{
					MovementData movement = MovementData.completed(distance);
					tickRecorder.setMovement(movement);
				}
			}
			lastPlayerPosition = currentPosition;
			lastMovementScope = movementScope;
		}
		catch (RuntimeException unavailable)
		{
			lastPlayerPosition = null;
			lastMovementScope = null;
			lastCaptureWorld = null;
			tickRecorder.markStateUnavailable("movement");
			settledIssues.add("settledMovement: " + unavailable.getClass().getSimpleName());
		}

		// Independent nullable observations: zero placeholders are explicitly unavailable.
		Integer currentHp = readHitContext("settledCurrentHp", () -> client.getBoostedSkillLevel(Skill.HITPOINTS),
			settledIssues);
		Integer maxHp = readHitContext("settledMaxHp", () -> client.getRealSkillLevel(Skill.HITPOINTS), settledIssues);
		Integer currentPrayer = readHitContext("settledCurrentPrayer", () -> client.getBoostedSkillLevel(Skill.PRAYER),
			settledIssues);
		Integer maxPrayer = readHitContext("settledMaxPrayer", () -> client.getRealSkillLevel(Skill.PRAYER),
			settledIssues);

		// Get active prayers
		List<String> activePrayers = readHitContext("settledPrayers", this::getActivePrayers, settledIssues);
		boolean prayerStateKnown = activePrayers != null;
		if (activePrayers == null)
			activePrayers = java.util.Collections.emptyList();

		// Detect forced prayer deactivation (drained by NPC or ran out of prayer points)
		if (config.trackPrayers() && prayerStateKnown && lastTickPrayerStateKnown)
		{
			for (String prayer : lastTickActivePrayers)
			{
				if (!activePrayers.contains(prayer) && !manuallyDeactivatedPrayers.contains(prayer))
				{
					// Check if this prayer was switched to another in the same mutually exclusive group
					// (e.g., switching from Protect from Melee to Protect from Missiles)
					boolean wasSwitched = isPrayerSwitch(prayer, activePrayers);

					// Skip logging if it was just a prayer switch (not a drain/disable)
					if (wasSwitched)
					{
						log.debug("Prayer switch detected: {} -> conflicting prayer", prayer);
						continue;
					}

					// Prayer was active last tick but not now, and player didn't manually deactivate
					String reason;
					if (currentPrayer != null && lastTickPrayerPointsKnown && currentPrayer == 0
						&& lastTickPrayerPoints > 0)
					{
						reason = "out of prayer";
					}
					else if (currentPrayer != null && lastTickPrayerPointsKnown && currentPrayer < lastTickPrayerPoints)
					{
						reason = "deactivated; prayer points also decreased";
					}
					else
					{
						reason = "deactivated; cause not established";
					}

					PlayerAction drainedAction = reason.equals("out of prayer")
						? PlayerAction.prayerDrained(prayer, reason)
						: PlayerAction.builder().type(PlayerAction.ActionType.PRAYER_DEACTIVATED).target(prayer)
							.description(prayer + " (" + reason + ")").build();
					tickRecorder.addPlayerAction(drainedAction);
					recordAction(drainedAction, null, null, 0, "DERIVED");

					// Rich capture: a prayer-disable / prayer-drain combat effect.
					if (worldRecorder != null)
					{
						worldRecorder.addEffect(EffectEvent.builder()
							.type(reason.equals("out of prayer") ? "prayer_depleted" : "prayer_state_change")
							.sourceName(null).attribution("OBSERVED").startTick(currentGameTick).durationTicks(null)
							.magnitude(prayer + " (" + reason + ")").linkedActionDetail(drainedAction.getDescription())
							.linkedActionType(drainedAction.getType().name())
							.observedVia("prayerStateDelta; cause not inferred").mapRef(null).build());
					}

					log.debug("PRAYER DRAINED: {} ({})", prayer, reason);
				}
			}
		}

		// Clear manual deactivations for next tick
		manuallyDeactivatedPrayers.clear();

		// Update tracking for next tick comparison
		lastTickActivePrayers = new ArrayList<>(activePrayers);
		lastTickPrayerStateKnown = prayerStateKnown;
		lastTickPrayerPointsKnown = currentPrayer != null;
		lastTickPrayerPoints = currentPrayer == null ? 0 : currentPrayer;

		// Update state
		tickRecorder.updateObservedState(currentHp, maxHp, currentPrayer, maxPrayer, activePrayers, prayerStateKnown);

		if (worldRecorder != null && config.captureWorldSnapshots())
		{
			for (PendingProjectile candidate : pendingProjectiles)
				candidate.sourceSamplingUnavailable = false;
			settledProjectileSamples = new ArrayList<>(projectilesThisTick);
			if (!runSettledStage("projectileSampling", () -> settledProjectileSamples = captureProjectiles()))
			{
				projectileCaptureIssues
					.add("projectiles: settled sampling unavailable; callback observations retained");
				for (PendingProjectile candidate : pendingProjectiles)
					candidate.sourceSamplingUnavailable = true;
			}
		}
		resolvePendingHits(localPlayer);
		failedPickpockets.clear();

		// Detect combat effects from UNAMBIGUOUS observed signals only (freeze/bind/teleblock graphics,
		// poison/venom varp, stat-level drops). Knockback and disarm were removed: neither can be told
		// apart from ordinary movement / voluntary-or-death weapon changes, so they produced wrong
		// labels (observed-over-predicted).
		if (config.captureEffects())
		{
			if (!runSettledStage("statEffects", this::detectStatDrains))
				lastBoostedCombat = null;
			if (!runSettledStage("spotEffects", () -> detectEffectSpotanims(localPlayer)))
				activeEffectSpotanims.clear();
			if (!runSettledStage("statusEffects", this::detectDotOnset))
			{
				poisonBaselineObserved = false;
				lastPoisonVarp = -1;
			}
		}
		else
		{
			poisonBaselineObserved = false;
			lastPoisonVarp = -1;
			lastBoostedCombat = null;
			activeEffectSpotanims.clear();
		}
		runSettledStage("worldSnapshot", () -> captureWorldSnapshot(localPlayer));
		runSettledStage("sourceResolution", this::resolveRecordedSources);
		capturedRsn = readHitContext("settledPlayerName", () -> localPlayer == null ? null : localPlayer.getName(),
			settledIssues);
		Integer world = readHitContext("settledWorld", client::getWorld, settledIssues);
		capturedWorld = world == null ? 0 : world;
	}

	private void resolveRecordedSources()
	{
		if (worldRecorder == null || !config.captureWorldSnapshots())
			return;
		List<DamageRecord> hits = new ArrayList<>(worldRecorder.receivedAtTick(currentGameTick));
		hits.addAll(resourceImpactContexts.values());
		if (hits.isEmpty())
			return;
		boolean needsActorCues = damageMap != null && damageMap.catalog().mechanismRules().hasOrderedActorRules();
		List<com.tickreplaylogger.model.world.ObservedEvent> sourceObservations = needsActorCues
			? worldRecorder.observationsForSourceResolution() : worldRecorder.observationsAtTickForSourceResolution(currentGameTick);
		int retainedDelay = damageMap == null ? 0 : damageMap.catalog().mechanismRules().maximumRetainedLifecycleDelay();
		com.tickreplaylogger.capture.WorldSnapshotRecorder.LifecycleObservationBatch lifecycleBatch = !needsActorCues && retainedDelay > 0
			? worldRecorder.retainedLifecycleObservationsForSourceResolution(currentGameTick, retainedDelay, sourceObservations) : null;
		if (lifecycleBatch != null) sourceObservations = lifecycleBatch.getEvents();
		com.tickreplaylogger.capture.WorldSnapshotRecorder.ProjectileObservationBatch projectileBatch = needsActorCues || damageMap == null ? null
			: worldRecorder.projectileObservationsForSourceResolution(damageMap.catalog().projectileEndpointHints().projectileIds(), currentGameTick, sourceObservations);
		com.tickreplaylogger.capture.RecordedSourceResolver resolver = new com.tickreplaylogger.capture.RecordedSourceResolver(
			worldRecorder.snapshotsForSourceResolution(), worldRecorder.scenesForSourceResolution(), damageMap,
			projectileBatch == null ? sourceObservations : projectileBatch.getEvents(),
			needsActorCues ? worldRecorder.captureGapsForSourceResolution() : retainedDelay > 0
				? worldRecorder.retainedLifecycleGapsForSourceResolution(currentGameTick, retainedDelay)
				: worldRecorder.captureGapsAtTickForSourceResolution(currentGameTick),
			projectileBatch == null || projectileBatch.isComplete(), lifecycleBatch == null || lifecycleBatch.isComplete());
		Map<Long, com.tickreplaylogger.model.world.SourceResolution> resolutions = new LinkedHashMap<>();
		for (DamageRecord hit : hits)
			if (hit.getObservationSequence() != null)
				resolutions.put(hit.getObservationSequence(), resolver.resolve(hit));
		worldRecorder.applySourceResolutions(currentGameTick, resolutions);
		tickRecorder.applyReceivedSourceResolutions(currentGameTick, resolutions);
		flushResourceImpactContexts(resolutions);
	}

	private void flushResourceImpactContexts(Map<Long, com.tickreplaylogger.model.world.SourceResolution> resolutions)
	{
		if (!resourceImpactContexts.isEmpty())
		{
			Map<Long, Map<String, Object>> enrichments = new LinkedHashMap<>();
			for (Map.Entry<Long, DamageRecord> entry : resourceImpactContexts.entrySet())
			{
				com.tickreplaylogger.model.world.SourceResolution resolution = resolutions.get(entry.getKey());
				if (resolution == null) resolution = com.tickreplaylogger.capture.SourceEvidenceResolver.resolve(
					java.util.Collections.emptyList(), entry.getValue().getAmount(), true, damageMap == null ? null : damageMap.catalog().id());
				DamageRecord context = entry.getValue().toBuilder().sourceResolution(resolution).build();
				List<String> possible = damageMap == null ? java.util.Arrays.asList("HP", "UNKNOWN")
					: damageMap.catalog().possibleOutcomeSurfaces(context.getHitsplatType());
				enrichments.put(entry.getKey(), data("attributionContext", context.toBuilder().sourceResolution(null)
					.attributionCandidates(null).observedHazardContacts(null).build(), "attributionContextEvidence", "DERIVED_SOURCE_CONTEXT_ONLY",
					"sourceResolution", context.getSourceResolution(), "attributionCandidates", context.getAttributionCandidates(),
					"observedHazardContacts", context.getObservedHazardContacts(), "possibleOutcomeSurfaces", possible,
					"attributionCaptureStatus", "SETTLED_DERIVED_SOURCE_CONTEXT", "resourceOutcomeStatus", "UNRESOLVED"));
				String label = com.tickreplaylogger.util.SourceResolutionLabels.label(context.getSourceResolution());
				tickRecorder.addEffectNote(resourceImpactNote(context.getAmount(), possible, label), context.getObservationSequence());
			}
			if (worldRecorder != null && config.captureWorldSnapshots()) worldRecorder.enrichHitsplatObservations(currentGameTick, enrichments);
			resourceImpactContexts.clear();
		}
	}

	/** Isolate optional stages; a failed channel is not an empty observation. */
	private boolean runSettledStage(String name, Runnable stage)
	{
		try
		{
			stage.run();
			return true;
		}
		catch (RuntimeException unavailable)
		{
			tickRecorder.markStateUnavailable(name);
			tickRecorder.addCaptureIssue(name + ": " + unavailable.getClass().getSimpleName());
			return false;
		}
	}

	private void unavailableWorldSnapshot(String issue)
	{
		causalSceneEpoch++;
		lastCaptureWorld = null;
		if (captureService != null)
			runSettledStage("actorTimingReset", captureService::invalidateActorTiming);
		tickRecorder.markStateUnavailable("worldSnapshot");
		tickRecorder.addCaptureIssue("worldSnapshot: " + issue);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		CallbackContext context = callbackContext();
		if (!context.ready)
		{
			return;
		}

		CallbackFieldObservation fields = new CallbackFieldObservation();
		String option = fields.value("option", event::getMenuOption);
		String cleanTarget = fields.value("target", () ->
		{
			String target = event.getMenuTarget();
			return target == null ? null : stripColorTags(target);
		});
		fields.value("identifier", event::getId);
		Integer param0 = fields.value("param0", event::getParam0);
		fields.value("param1", event::getParam1);
		Integer itemId = fields.value("itemId", event::getItemId);
		fields.value("menuAction", () ->
		{
			net.runelite.api.MenuAction value = event.getMenuAction();
			return value == null ? null : value.name();
		});
		net.runelite.api.MenuEntry entry = fields.sample("menuEntry", event::getMenuEntry);
		Actor related = null;
		if (entry != null)
			related = fields.sample("relatedActor", entry::getActor);
		else
			fields.unavailable("relatedActor", "MENU_ENTRY_UNAVAILABLE");
		if (related instanceof NPC && config.captureWorldSnapshots())
		{
			// Preserve the input's first identity result, including null, before optional metadata reads.
			callbackActorRef(related, context);
			fields.put("relatedActorState", callbackActorData(related, context));
		}
		long sequence = observe("MENU_OPTION", context.localPlayer, related, fields.data(), context);
		try
		{
			ensureSelfDamageScope();
			if (itemId != null)
				selfDamageInputs.observe(damageMap == null ? null : damageMap.catalog(), itemId, option, eventTick(),
					sequence);
		}
		catch (RuntimeException unavailable)
		{
			tickRecorder.addCaptureIssue("menuSelfDamageContext: " + unavailable.getClass().getSimpleName());
		}
		if (option == null)
			return;

		if (option.toLowerCase(java.util.Locale.ROOT).contains("deactivate") && (option + " " + cleanTarget)
			.toLowerCase(java.util.Locale.ROOT).replace("-", " ").contains("quick prayer"))
			manuallyDeactivatedPrayers.addAll(lastTickActivePrayers);
		PlayerAction action = null;

		// Eating food
		if (option.equals("Pickpocket"))
		{
			pickpocketTarget = related instanceof NPC ? (NPC) related : null;
			pickpocketTargetTick = currentGameTick;
			action = PlayerAction.builder().type(PlayerAction.ActionType.PICKPOCKET).target(cleanTarget)
				.description("Attempted pickpocket: " + cleanTarget).build();
		}
		else if (option.equals("Eat") && config.trackFood())
		{
			action = PlayerAction.ate(cleanTarget);
			log.debug("ACTION: Ate {}", cleanTarget);
		}
		// Drinking potions
		else if (option.equals("Drink") && config.trackFood())
		{
			action = PlayerAction.drank(cleanTarget);
			log.debug("ACTION: Drank {}", cleanTarget);

			// A click is only an input. Label an actual later hit only with observed inventory evidence.
			if (config.trackDamageReceived() && cleanTarget != null)
			{
				com.google.gson.JsonObject rule = itemId == null || damageMap == null ? null
					: damageMap.catalog().selfDamageInput(itemId, option);
				if (rule != null)
				{
					pendingSelfDamageSource = cleanTarget;
					pendingSelfDamageTick = eventTick();
					pendingSelfDamageItemId = itemId == null ? -1 : itemId;
					pendingSelfDamageSlot = param0 == null ? -1 : param0;
					pendingSelfDamageQuantity = null;
					try
					{
						net.runelite.api.ItemContainer inventory = client
							.getItemContainer(net.runelite.api.InventoryID.INVENTORY);
						net.runelite.api.Item item = inventory == null || pendingSelfDamageSlot < 0 ? null
							: inventory.getItem(pendingSelfDamageSlot);
						if (item != null && item.getId() == pendingSelfDamageItemId)
							pendingSelfDamageQuantity = item.getQuantity();
					}
					catch (RuntimeException unavailable)
					{
						/* Input remains intent only. */ }
				}
			}
		}
		// Activating prayers
		else if (option.equals("Activate") && config.trackPrayers())
		{
			action = PlayerAction.prayerOn(cleanTarget);
			log.debug("ACTION: Activated {}", cleanTarget);
		}
		// Deactivating prayers
		else if (option.equals("Deactivate"))
		{
			manuallyDeactivatedPrayers.add(cleanTarget); // Always track for drain detection
			if (config.trackPrayers())
			{
				action = PlayerAction.prayerOff(cleanTarget);
				log.debug("ACTION: Deactivated {}", cleanTarget);
			}
		}
		// Equipping gear
		else if (option.equals("Wear") || option.equals("Wield") || option.equals("Equip"))
		{
			if (config.trackGear())
			{
				action = PlayerAction.equipped(cleanTarget);
				log.debug("ACTION: Equipped {}", cleanTarget);
			}
		}
		// Unequipping gear
		else if (option.equals("Remove"))
		{
			if (config.trackGear())
			{
				action = PlayerAction.unequipped(cleanTarget);
				log.debug("ACTION: Unequipped {}", cleanTarget);
			}
		}
		// Attacking
		else if (option.equals("Attack") && config.trackAttacks())
		{
			// Get current weapon name
			String weapon = readHitContext("menuWeapon", this::getCurrentWeaponName, context.issues);
			action = PlayerAction.attacked(cleanTarget, weapon);
			log.debug("ACTION: Attacked {} with {}", cleanTarget, weapon);
		}

		// Add action to current tick if we captured something
		if (action != null)
		{
			tickRecorder.addPlayerAction(action);
			recordAction(action, related, itemId, sequence, "INPUT", context);
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		ChatMessageType channel = event.getType();
		// Reject player/private chat before reading client context or any message content.
		if (channel != ChatMessageType.SPAM && channel != ChatMessageType.GAMEMESSAGE)
			return;
		String message = event.getMessage();
		int timestamp = event.getTimestamp();
		CallbackContext context = callbackContext();
		long sequence = observe("SYSTEM_MESSAGE", null, null,
			data("channel", channel.name(), "message", message, "messagePresent", message != null, "messageTimestamp",
				timestamp, "callbackGameState", context.state == null ? null : context.state.name()),
			context);
		if (captureService != null && config.captureWorldSnapshots())
		{
			if (!context.ready)
				captureService.resetEncounterWaveCues();
			else if (channel == ChatMessageType.GAMEMESSAGE)
			{
				if (preparePrimaryCaptureScope())
					captureService.observeEncounterWaveCue(client, currentSceneKey, context.state, channel, message,
						eventTick(), context.cycle, sequence);
				else
					captureService.resetEncounterWaveCues();
			}
		}
		if (!context.ready || !config.trackDamageReceived() || message == null
			|| !PICKPOCKET_FAILURE.matcher(stripColorTags(message)).matches())
			return;
		String name = null;
		try
		{
			Player player = context.localPlayer;
			boolean targetKnown = pickpocketTarget != null && !pickpocketTarget.isDead()
				&& currentGameTick - pickpocketTargetTick <= 2 && player != null && player.getWorldLocation() != null
				&& pickpocketTarget.getWorldLocation() != null
				&& player.getWorldLocation().distanceTo(pickpocketTarget.getWorldLocation()) <= 2;
			name = targetKnown ? pickpocketTarget.getName() : null;
		}
		catch (RuntimeException unavailable)
		{
			tickRecorder.addCaptureIssue("pickpocketContext: " + unavailable.getClass().getSimpleName());
		}
		failedPickpockets.add(name == null ? "Pickpocket failure" : stripColorTags(name) + " (pickpocket)");
	}

	/** Keep the NPC identity through projectile matching, including a unique proximity-resolved source. */
	private int projectileNpcId(Projectile projectile, String source)
	{
		if (projectile.getSourceActor() instanceof NPC)
			return observedNpcId((NPC) projectile.getSourceActor());
		return -1; // A nearby/name-matched NPC does not establish the source actor's identity.
	}

	/** Reference applicability includes observed name compatibility; raw world context stays separate. */
	private boolean referenceContextObserved(NPC npc)
	{
		try
		{
			int id = observedNpcId(npc);
			return id >= 0 && damageMap != null && damageMap.catalog() != null
				&& com.tickreplaylogger.capture.NpcIdentityContext.normalWorldObserved(client, npc)
				&& damageMap.catalog().observedNameCompatible(id, npc.getName(), true);
		}
		catch (RuntimeException unavailable)
		{
			return false;
		}
	}

	private int observedNpcId(NPC npc)
	{
		Integer id = com.tickreplaylogger.capture.NpcDefinitionObservation.capture(npc, new LinkedHashMap<>(), "")
			.getId();
		return id == null ? -1 : id;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		CallbackContext context = callbackContext();
		com.tickreplaylogger.model.world.ObservedVarbitLayout layout = worldRecorder != null
			&& config.captureWorldSnapshots() && context.allowed
				? com.tickreplaylogger.capture.VarbitLayoutCapture.capture(client, event.getVarbitId())
				: null;
		// Client-global primitive facts do not require a local actor identity or a map assignment.
		observe("GAME_VARIABLE_CHANGED", null, null,
			data("varpId", event.getVarpId(), "varbitId", event.getVarbitId(), "value", event.getValue(), "longValue",
				Long.toString(event.getLongValue()), "varbitLayout", layout, "scopeKnown", false),
			context);
		if (context.state != GameState.LOGGED_IN)
		{
			lastSpecialAttackPercent = -1;
			return;
		}

		// Check for special attack energy change
		Integer currentSpecPercent = readHitContext("variableSpecialEnergy",
			() -> client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT), context.issues);
		if (currentSpecPercent == null)
		{
			lastSpecialAttackPercent = -1;
			return;
		}
		long sequence = currentSpecPercent != lastSpecialAttackPercent
			? observe(lastSpecialAttackPercent < 0 ? "SPECIAL_ENERGY_OBSERVED" : "SPECIAL_ENERGY_CHANGED",
				context.ready ? context.localPlayer : null, null,
				data("previous", lastSpecialAttackPercent < 0 ? null : lastSpecialAttackPercent, "current",
					currentSpecPercent),
				context)
			: 0;
		int previousSpecPercent = lastSpecialAttackPercent;
		lastSpecialAttackPercent = currentSpecPercent;
		if (context.localPlayer == null)
			return;
		// Global values/baselines survive failures of optional actor and scene enrichment.
		try
		{

			// Special attack energy is stored as 0-1000 (divide by 10 for percentage)
			if (previousSpecPercent >= 0 && currentSpecPercent < previousSpecPercent)
			{
				ensureSelfDamageScope();
				net.runelite.api.ItemContainer equipment = client
					.getItemContainer(net.runelite.api.InventoryID.EQUIPMENT);
				net.runelite.api.Item weapon = equipment == null ? null
					: equipment.getItem(net.runelite.api.EquipmentInventorySlot.WEAPON.getSlotIdx());
				selfDamageInputs.observeResourceDecrease(damageMap == null ? null : damageMap.catalog(),
					weapon == null ? -1 : weapon.getId(), eventTick(), sequence);
			}
			if (previousSpecPercent >= 0 && currentSpecPercent < previousSpecPercent && config.trackSpecialAttacks())
			{
				// Special attack was used
				String weaponName = getCurrentWeaponName();
				PlayerAction specAction = PlayerAction.builder().type(PlayerAction.ActionType.SPECIAL_ATTACK)
					.itemName(weaponName).description("Special energy decreased (" + previousSpecPercent / 10 + "% to "
						+ currentSpecPercent / 10 + "%)")
					.build();
				tickRecorder.addPlayerAction(specAction);
				recordAction(specAction, null, null, sequence, "RESOURCE_CHANGE", context);
				log.debug("ACTION: Special attack with {}", weaponName);
			}

		}
		catch (RuntimeException unavailable)
		{
			log.debug("Special-energy actor context unavailable: {}", unavailable.getClass().getSimpleName());
		}
	}

	/**
	 * Strip RuneLite color tags from strings.
	 * E.g., "<col=ff9040>Shark</col>" -> "Shark"
	 */
	private String stripColorTags(String input)
	{
		if (input == null)
		{
			return "";
		}
		// Remove <col=xxxxxx> and </col> tags
		return input.replaceAll("<col=[^>]*>", "").replaceAll("</col>", "").trim();
	}

	/**
	 * Get the name of the currently equipped weapon.
	 */
	private String getCurrentWeaponName()
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return "Unknown";
		}

		// Get weapon from equipment
		net.runelite.api.ItemContainer equipment = client.getItemContainer(net.runelite.api.InventoryID.EQUIPMENT);
		if (equipment == null)
		{
			return "Unarmed";
		}

		net.runelite.api.Item weapon = equipment.getItem(net.runelite.api.EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (weapon == null)
		{
			return "Unarmed";
		}

		// Get item name from item ID
		return client.getItemDefinition(weapon.getId()).getName();
	}

	/**
	 * Get a list of currently active prayers.
	 * Filters out base prayers when upgraded versions are active.
	 */
	private List<String> getActivePrayers()
	{
		PrayerObservation sample = new PrayerObservation(client);
		if (!sample.isFlagsComplete() || !sample.isOverheadResolved())
			throw new IllegalStateException("Prayer sample unavailable or unresolved");
		return getActivePrayers(sample);
	}

	private List<String> getActivePrayers(PrayerObservation observation)
	{
		List<String> active = new ArrayList<>();
		java.util.Set<Prayer> activePrayerSet = java.util.EnumSet.noneOf(Prayer.class);

		// First pass: collect all active prayers
		for (String prayer : observation.getActive())
			activePrayerSet.add(Prayer.valueOf(prayer));

		// Filter out base prayers when upgraded versions are active
		// Check for Ruinous Powers / newer prayer variants by name
		boolean hasDeadeye = false;
		boolean hasMysticVigour = false;
		for (Prayer prayer : activePrayerSet)
		{
			String name = prayer.name().toLowerCase();
			if (name.contains("deadeye"))
			{
				hasDeadeye = true;
			}
			if (name.contains("mystic") && name.contains("vigour"))
			{
				hasMysticVigour = true;
			}
		}

		// Ranged: Eagle Eye < Rigour or Deadeye variant
		if (activePrayerSet.contains(Prayer.RIGOUR) || hasDeadeye)
		{
			activePrayerSet.remove(Prayer.EAGLE_EYE);
			activePrayerSet.remove(Prayer.HAWK_EYE);
			activePrayerSet.remove(Prayer.SHARP_EYE);
		}

		// Magic: Mystic Might < Augury or Mystic Vigour variant
		if (activePrayerSet.contains(Prayer.AUGURY) || hasMysticVigour)
		{
			activePrayerSet.remove(Prayer.MYSTIC_MIGHT);
			activePrayerSet.remove(Prayer.MYSTIC_LORE);
			activePrayerSet.remove(Prayer.MYSTIC_WILL);
		}

		// Melee: Steel Skin/Ultimate Strength/Incredible Reflexes < Piety < Chivalry
		if (activePrayerSet.contains(Prayer.PIETY))
		{
			activePrayerSet.remove(Prayer.CHIVALRY);
			activePrayerSet.remove(Prayer.ULTIMATE_STRENGTH);
			activePrayerSet.remove(Prayer.INCREDIBLE_REFLEXES);
			activePrayerSet.remove(Prayer.STEEL_SKIN);
		}
		else if (activePrayerSet.contains(Prayer.CHIVALRY))
		{
			activePrayerSet.remove(Prayer.ULTIMATE_STRENGTH);
			activePrayerSet.remove(Prayer.INCREDIBLE_REFLEXES);
			activePrayerSet.remove(Prayer.STEEL_SKIN);
		}

		// Convert to formatted names
		for (Prayer prayer : activePrayerSet)
		{
			active.add(formatPrayerName(prayer));
		}

		return active;
	}

	/**
	 * Check if a prayer was switched to another in a conflicting group.
	 * E.g., switching from Protect from Melee to Protect from Missiles.
	 *
	 * @param oldPrayer The prayer that was deactivated
	 * @param activePrayers The currently active prayers
	 * @return true if the prayer was switched (not drained/disabled)
	 */
	private boolean isPrayerSwitch(String oldPrayer, List<String> activePrayers)
	{
		// Check each conflict group
		for (java.util.Set<String> group : PRAYER_CONFLICT_GROUPS)
		{
			if (group.contains(oldPrayer))
			{
				// Old prayer is in this group - check if another prayer from the same group is now active
				for (String activePrayer : activePrayers)
				{
					if (group.contains(activePrayer) && !activePrayer.equals(oldPrayer))
					{
						return true; // Found a conflicting prayer that's now active
					}
				}
			}
		}
		return false;
	}

	/**
	 * Format prayer enum name to readable string.
	 * E.g., PROTECT_FROM_MELEE -> "Protect from Melee"
	 */
	private String formatPrayerName(Prayer prayer)
	{
		String name = prayer.name().replace("_", " ").toLowerCase();
		// Capitalize first letter of each word
		StringBuilder result = new StringBuilder();
		boolean capitalizeNext = true;
		for (char c : name.toCharArray())
		{
			if (c == ' ')
			{
				capitalizeNext = true;
				result.append(c);
			}
			else if (capitalizeNext)
			{
				result.append(Character.toUpperCase(c));
				capitalizeNext = false;
			}
			else
			{
				result.append(c);
			}
		}
		return result.toString();
	}

	/**
	 * Capture the rich per-tick {@link com.tickreplaylogger.model.world.WorldSnapshot} into the
	 * death-reconstruction buffer. Captures the scene collision grid on load (Â§4b) and the full
	 * world (all NPCs/players/projectiles/hazards) each tick. Local only â€” no network.
	 */
	/** Reserve an epoch before callbacks, then populate scene metadata at the settled tick. */
	private boolean prepareCallbackCaptureScope(CallbackContext context)
	{
		if (context.primaryAttempted)
			return context.primaryKnown;
		context.primaryAttempted = true;
		if (worldRecorder == null || captureService == null || !config.captureWorldSnapshots())
			return false;
		try
		{
			com.tickreplaylogger.capture.ActorCallbackObservation.Sample local = context.actorSamples
				.get(context.localPlayer);
			Object issues = local == null ? null : local.data().get("observationIssues");
			if (issues instanceof Map && ((Map<?, ?>) issues).containsKey("worldView"))
				return false;
			context.localViewSampled = true;
			context.localView = local != null ? local.view()
				: context.localPlayer == null ? null : context.localPlayer.getWorldView();
			net.runelite.api.WorldView active = context.localView;
			if (active == null)
				active = client.getTopLevelWorldView();
			context.primaryView = active;
			context.primaryPlane = active == null ? client.getPlane() : active.getPlane();
			Integer viewId = null;
			if (active != null)
			{
				boolean sampled = false;
				for (com.tickreplaylogger.capture.ActorCallbackObservation.Sample sample : context.actorSamples
					.values())
					if (sample.view() == active)
					{
						viewId = integerField(sample.data(), "worldViewId");
						sampled = true;
						break;
					}
				if (!sampled)
					viewId = active.getId();
				if (viewId == null)
					return false;
			}
			context.primaryViewId = viewId;
			String scope = active == null ? client.getBaseX() + ":" + client.getBaseY() + ":" + context.primaryPlane
				: com.tickreplaylogger.capture.WorldViews.scope(active, context.primaryPlane, viewId);
			if (currentSceneKey == null || !java.util.Objects.equals(scope, sceneScopeSignature)
				|| (needSceneCapture && !primarySceneReserved))
			{
				int[] regions = active == null ? client.getMapRegions() : active.getMapRegions();
				boolean instance = active == null ? client.isInInstancedRegion() : active.isInstance();
				currentSceneKey = Integer.toHexString(java.util.Arrays.hashCode(regions) ^ (instance ? 1 : 0)) + ":"
					+ (sceneLoadSeq++);
				captureService.resetTiming();
				lastCaptureWorld = null;
				sceneScopeSignature = scope;
				primarySceneReserved = true;
				needSceneCapture = true;
			}
			context.primarySceneKey = currentSceneKey;
			context.primaryKnown = true;
			return true;
		}
		catch (RuntimeException unavailable)
		{
			context.issues.add("callbackPrimaryScope: " + unavailable.getClass().getSimpleName());
			return false;
		}
	}

	private boolean preparePrimaryCaptureScope()
	{
		if (worldRecorder == null || captureService == null || !config.captureWorldSnapshots())
			return false;
		try
		{
			net.runelite.api.WorldView active = com.tickreplaylogger.capture.WorldViews.active(client);
			String scope = active == null ? nativeBaseX() + ":" + nativeBaseY() + ":" + nativePlane()
				: com.tickreplaylogger.capture.WorldViews.scope(active);
			if (currentSceneKey == null || !java.util.Objects.equals(scope, sceneScopeSignature)
				|| (needSceneCapture && !primarySceneReserved))
			{
				int[] regions = active == null ? client.getMapRegions() : active.getMapRegions();
				boolean instance = active == null ? client.isInInstancedRegion() : active.isInstance();
				int regionHash = java.util.Arrays.hashCode(regions) ^ (instance ? 1 : 0);
				currentSceneKey = Integer.toHexString(regionHash) + ":" + (sceneLoadSeq++);
				captureService.resetTiming();
				lastCaptureWorld = null;
				sceneScopeSignature = scope;
				primarySceneReserved = true;
				needSceneCapture = true;
			}
			return true;
		}
		catch (RuntimeException unavailable)
		{
			return false;
		}
	}

	private void captureWorldSnapshot(Player localPlayer)
	{
		if (worldRecorder == null || captureService == null || !config.captureWorldSnapshots())
		{
			return;
		}
		long captureStartNs = System.nanoTime();
		try
		{
			// Capture the scene collision grid on (re)load, keyed by region + load sequence.
			if (localPlayer == null)
			{
				unavailableWorldSnapshot("local player unavailable");
				return;
			}
			boolean scopeKnown = preparePrimaryCaptureScope();
			List<String> snapshotIssues = new ArrayList<>();
			if (!scopeKnown)
			{
				causalSceneEpoch++;
				lastCaptureWorld = null;
				currentSceneKey = null;
				sceneScopeSignature = null;
				primarySceneReserved = false;
				needSceneCapture = true;
				tickRecorder.markStateUnavailable("nativeContext");
				tickRecorder.addCaptureIssue(
					"nativeContext: primary scene scope unavailable; independent observations retained");
			}
			if (scopeKnown && (needSceneCapture || !worldRecorder.hasScene(currentSceneKey)))
			{
				try
				{
					com.tickreplaylogger.model.world.SceneData metadata = captureService.captureScene(client,
						currentSceneKey, sceneLoadSeq, config.captureSceneCollision());
					worldRecorder.putScene(metadata);
					if (metadata != null)
						needSceneCapture = false;
					else
						snapshotIssues.add("sceneMetadata: unavailable");
				}
				catch (RuntimeException unavailable)
				{
					snapshotIssues.add("sceneMetadata: " + unavailable.getClass().getSimpleName());
				}
				if (!snapshotIssues.isEmpty())
				{
					tickRecorder.markStateUnavailable("sceneMetadata");
					for (String issue : snapshotIssues)
						tickRecorder.addCaptureIssue(issue);
				}
			}

			List<ProjectileState> observedProjectiles = settledProjectileSamples == null ? captureProjectiles()
				: settledProjectileSamples;
			com.tickreplaylogger.model.world.WorldSnapshot snapshot = scopeKnown
				? captureService.captureTick(client, currentGameTick, currentSceneKey, observedProjectiles,
					lastCaptureWorld, config.captureOtherPlayers(), config.captureSceneCollision(),
					config.captureSceneTerrain())
				: captureService.captureUnscopedTick(client, localPlayer, currentGameTick, observedProjectiles,
					config.captureOtherPlayers(), "primary scene scope unavailable");

			if (snapshot != null)
			{
				if (!projectileCaptureIssues.isEmpty() || !snapshotIssues.isEmpty())
				{
					java.util.Map<String, String> coverage = new java.util.LinkedHashMap<>(
						snapshot.getCaptureCoverage());
					if (!projectileCaptureIssues.isEmpty())
						coverage.put("projectiles", "partial");
					if (!snapshotIssues.isEmpty())
						coverage.put("sceneMetadata", "unavailable");
					List<String> issues = new ArrayList<>(snapshot.getCaptureIssues());
					issues.addAll(projectileCaptureIssues);
					issues.addAll(snapshotIssues);
					snapshot = snapshot.toBuilder().captureCoverage(coverage).captureIssues(issues).build();
				}
				worldRecorder.addSnapshot(snapshot);
				com.tickreplaylogger.model.world.PlayerState player = snapshot.getPlayer();
				lastCaptureWorld = !Boolean.FALSE.equals(snapshot.getNativeContextKnown()) && player != null
					&& Boolean.TRUE.equals(player.getWorldPositionKnown())
						? new WorldPoint(player.getWorldX(), player.getWorldY(), player.getPlane())
						: null;
			}
			else
				unavailableWorldSnapshot("no snapshot returned");
		}
		catch (Exception e)
		{
			unavailableWorldSnapshot(e.getClass().getSimpleName());
			log.debug("WorldSnapshot capture failed this tick: {}", e.getMessage());
		}
		finally
		{
			projectilesThisTick.clear();
			runSettledStage("projectileCleanup", () ->
			{
				projectileIds.keySet().removeIf(p -> projectileExpiryObserved(p, "projectileCleanup"));
				projectileSpawnTicks.keySet().retainAll(projectileIds.keySet());
				projectileTargets.keySet().retainAll(projectileIds.keySet());
				projectileCallbackStates.keySet().retainAll(projectileIds.keySet());
				projectileSourceTracker.retain(projectileIds.keySet());
			});
			// Perf guard: capture must stay well within the 600ms tick budget. Warn (throttled) if a
			// tick's capture is heavy so we can see it in logs and regress capture if needed.
			long captureMs = (System.nanoTime() - captureStartNs) / 1_000_000L;
			if (captureMs >= CAPTURE_BUDGET_WARN_MS && currentGameTick - lastCaptureWarnTick > 50)
			{
				lastCaptureWarnTick = currentGameTick;
				log.warn("World capture took {}ms this tick (budget {}ms) â€” consider disabling heavier "
					+ "capture toggles if this persists.", captureMs, CAPTURE_BUDGET_WARN_MS);
			}
		}
	}

	/**
	 * GameTick runs after packet processing. Resolve incoming hits once, in observed order,
	 * using settled attacker state and the defender state preserved at HitsplatApplied.
	 */
	private static boolean typedStatusHit(int type)
	{
		return type == HitsplatID.POISON || type == HitsplatID.VENOM || type == HitsplatID.BLEED
			|| type == HitsplatID.BURN;
	}

	private void resolvePendingHits(Player localPlayer)
	{
		if (pendingHits.isEmpty())
		{
			if (currentGameTick - pendingSelfDamageTick > 2)
				pendingSelfDamageSource = null;
			return;
		}
		List<String> issues = new ArrayList<>();
		net.runelite.api.WorldView view = localPlayer == null ? null
			: readHitContext("settledImpactWorldView", localPlayer::getWorldView, issues);
		NativeHitScope scope = readNativeHitScope(localPlayer, view, null, false, issues, "settledImpact");
		for (PendingHit hit : pendingHits)
			hit.captureIssues.addAll(issues);
		inNativeHitScope(scope, () ->
		{
			resolvePendingHitsInScope();
			return null;
		});
	}

	private void resolvePendingHitsInScope()
	{
		if (resourceImpactQueueCount > MAX_RESOURCE_IMPACTS_PER_TICK)
			tickRecorder.addCaptureIssue("resourceImpacts: attribution queue limit; original callbacks retained");
		pendingHits.sort(java.util.Comparator.comparing((PendingHit hit) -> hit.hpOutcomeKnown).thenComparingLong(hit -> hit.sequence));
		for (PendingHit hit : pendingHits)
		{
			resetAttribution();
			String source;
			boolean status = typedStatusHit(hit.hitsplatType);
			if (status)
			{
				// The observed type is known; recurrence and the inflicting attack remain unresolved.
				source = hit.hitsplatType == HitsplatID.POISON ? "Poison"
					: hit.hitsplatType == HitsplatID.VENOM ? "Venom"
						: hit.hitsplatType == HitsplatID.BURN ? "Burn" : "Bleed";
				lastAttributionConfidence = "ENVIRONMENTAL";
				lastAttributionLaunchTick = currentGameTick;
				lastAttributionSourceIsPlayer = false;
				lastAttributionNpcId = -1;
				lastAttributionNpcLevel = null;
				lastAttributionProjectileId = -1;
				lastAttributionAttackRule = null;
				lastAttributionAttackName = null;
				lastAttributionMapRef = null;
				lastAttributionActorRef = null;
				lastAttributionEvidence = "STATUS_HITSPLAT";
				lastAttributionCandidates = new ArrayList<>();
				lastAttributionNormalContext = false;
				try
				{
					if (hit.captureIssues.isEmpty())
						selfDamageInputSource(hit);
				}
				catch (RuntimeException unavailable)
				{
					hit.captureIssues.add("statusInflictor: " + unavailable.getClass().getSimpleName());
				}
				retainTypedHitProjectileAlternatives(hit);
				lastAttributionConfidence = "ENVIRONMENTAL";
				lastAttributionEvidence = "STATUS_HITSPLAT";
			}
			else
			{
				if (!hit.captureIssues.isEmpty())
				{
					source = "Unknown";
					lastAttributionEvidence = "INCOMPLETE_HIT_CONTEXT";
				}
				else
					try
					{
						source = determineDamageSource(hit);
					}
					catch (RuntimeException unavailable)
					{
						List<Map<String, Object>> partialCandidates = new ArrayList<>(lastAttributionCandidates);
						resetAttribution();
						lastAttributionCandidates = partialCandidates;
						hit.captureIssues.add("sourceAttribution: " + unavailable.getClass().getSimpleName());
						lastAttributionEvidence = "ATTRIBUTION_CONTEXT_UNAVAILABLE";
						source = "Unknown";
					}
			}
			DamageEvent.DamageType type = hit.damageType;
			String typeEvidence = type == DamageEvent.DamageType.UNKNOWN ? "UNKNOWN" : "HITSPLAT_TYPE";
			List<String> typeCandidates = new ArrayList<>();
			String mappedType = null;
			if (!status && lastAttributionAttackName != null)
			{
				mappedType = damageMap.pickpocketRule().getDamageType();
			}
			else if (!status && lastAttributionAttackRule != null)
			{
				mappedType = lastAttributionAttackRule.getDamageType();
			}
			else if (!status && "ENVIRONMENTAL".equals(lastAttributionConfidence) && damageMap != null)
			{
				mappedType = damageMap.catalog().hazardDamageType(source);
				if (mappedType != null)
					lastAttributionMapRef = "replay_bindings.json:hazard_damage_type";
			}
			if (type == DamageEvent.DamageType.UNKNOWN && mappedType != null && !mappedType.contains("|"))
			{
				type = DamageEvent.DamageType.fromReference(mappedType);
				if (type != DamageEvent.DamageType.UNKNOWN)
					typeEvidence = "ENVIRONMENTAL".equals(lastAttributionConfidence) ? "SCOPED_HAZARD_RULE"
						: "REVIEWED_ATTACK_BINDING";
			}
			if (type == DamageEvent.DamageType.UNKNOWN && damageMap != null && lastAttributionProjectileId >= 0)
			{
				String projectileStyle = damageMap.styleForProjectile(lastAttributionProjectileId);
				if (projectileStyle != null)
				{
					typeCandidates.add(projectileStyle);
				}
				String reviewed = damageMap.catalog().projectileDamageType(lastAttributionNpcId,
					lastAttributionProjectileId, lastAttributionNpcLevel, lastAttributionNormalContext);
				if (reviewed != null)
				{
					type = DamageEvent.DamageType.fromReference(reviewed);
					typeEvidence = "REVIEWED_PROJECTILE_BINDING";
				}
			}
			String damageTypeLabel = damageTypeString(type);
			if (type == DamageEvent.DamageType.UNKNOWN && damageMap != null && lastAttributionNpcId >= 0)
			{
				String sole = damageMap.soleStyleForNpc(lastAttributionNpcId, lastAttributionNpcLevel,
					lastAttributionNormalContext);
				if (sole != null)
				{
					if (!typeCandidates.contains(sole))
						typeCandidates.add(sole);
				}
			}
			String reviewedType = !status && damageMap != null
				? damageMap.catalog().entityDamageType(lastAttributionNpcId, lastAttributionNpcLevel,
					lastAttributionNormalContext)
				: null;
			if (reviewedType != null && type == DamageEvent.DamageType.UNKNOWN)
			{
				damageTypeLabel = reviewedType;
				type = DamageEvent.DamageType.fromReference(reviewedType);
				typeEvidence = "SCOPED_DEFINITION_RULE";
				lastAttributionMapRef = "replay_bindings.json:"
					+ damageMap.catalog().entityRuleKey(lastAttributionNpcId);
			}
			String determination = type == DamageEvent.DamageType.TYPELESS || damageMap == null
				|| "ENVIRONMENTAL".equals(lastAttributionConfidence) || "UNKNOWN".equals(lastAttributionConfidence)
					? "UNKNOWN"
					: damageMap.determinationForAttack(lastAttributionNpcId, lastAttributionProjectileId,
						lastAttributionNpcLevel, lastAttributionNormalContext);
			if (hit.hpOutcomeKnown)
				tickRecorder.addDamageEvent(DamageEvent.received(hit.amount, type, source, lastAttributionConfidence, determination)
					.toBuilder().observationSequence(hit.sequence).build());
			else if (worldRecorder == null || !config.captureWorldSnapshots())
				tickRecorder.addEffectNote(resourceImpactNote(hit.amount, hit.possibleOutcomeSurfaces, source), hit.sequence);
			buildReceivedDamageRecord(hit, damageTypeLabel, source, determination, typeEvidence, typeCandidates);
		}
		pendingHits.clear();
		resourceImpactQueueCount = 0;
		if (currentGameTick - pendingSelfDamageTick > 2)
			pendingSelfDamageSource = null;
	}

	private boolean selfConsumptionObserved(PendingHit hit)
	{
		if (pendingSelfDamageSource == null || pendingSelfDamageItemId <= 0 || pendingSelfDamageSlot < 0
			|| pendingSelfDamageSlot >= 28 || currentGameTick - pendingSelfDamageTick > 2
			|| pendingSelfDamageQuantity == null
			|| pendingHits.stream().filter(h -> h.amount == hit.amount).count() != 1)
			return false;
		net.runelite.api.ItemContainer inventory = client.getItemContainer(net.runelite.api.InventoryID.INVENTORY);
		if (inventory == null)
			return false;
		net.runelite.api.Item item = inventory.getItem(pendingSelfDamageSlot);
		return item == null || item.getId() != pendingSelfDamageItemId
			|| item.getQuantity() < pendingSelfDamageQuantity;
	}

	private void ensureSelfDamageScope()
	{
		net.runelite.api.Scene currentScene = nativeScene();
		net.runelite.api.WorldView currentView = nativeView();
		int baseX = nativeBaseX(), baseY = nativeBaseY(), plane = nativePlane();
		if (!selfDamageScopeKnown || selfDamageScopeEpoch != causalSceneEpoch || selfDamageScopeScene != currentScene
			|| selfDamageScopeView != currentView || selfDamageScopeBaseX != baseX || selfDamageScopeBaseY != baseY
			|| selfDamageScopePlane != plane)
		{
			selfDamageInputs.clear();
			pendingSelfDamageSource = null;
			selfDamageScopeEpoch = causalSceneEpoch;
			selfDamageScopeScene = currentScene;
			selfDamageScopeView = currentView;
			selfDamageScopeBaseX = baseX;
			selfDamageScopeBaseY = baseY;
			selfDamageScopePlane = plane;
			selfDamageScopeKnown = true;
		}
	}

	private String selfDamageInputSource(PendingHit hit)
	{
		return selfDamageInputSource(hit, null);
	}

	private String selfDamageInputSource(PendingHit hit, String consumedSource)
	{
		if (hit.nativeScope == null || !hit.nativeScope.known || activeHitScope == null || !activeHitScope.known)
			return null;
		if (hit.epoch != causalSceneEpoch || hit.plane != nativePlane() || hit.worldView != nativeView()
			|| hit.scene != nativeScene() || hit.baseX != nativeBaseX() || hit.baseY != nativeBaseY())
			return null;
		ensureSelfDamageScope();
		if (hit.amount <= 0 || (hit.damageType != DamageEvent.DamageType.UNKNOWN
			&& hit.damageType != DamageEvent.DamageType.POISON && hit.damageType != DamageEvent.DamageType.VENOM))
			return null;
		List<Map<String, Object>> candidates = selfDamageInputs.candidates(currentGameTick);
		if (candidates.isEmpty() && consumedSource != null)
			candidates.add(data("kind", "ITEM_CONSUMPTION_CANDIDATE", "name", consumedSource, "evidence",
				"Observed inventory consumption and HP impact; causal relation unproven"));
		if (candidates.isEmpty())
			return null;
		lastAttributionCandidates.addAll(candidates);
		boolean competing = candidates.size() > 1;
		for (String hazard : hazardsForHit(hit))
		{
			competing = true;
			lastAttributionCandidates.add(hazardCandidate(hit, hazard));
		}
		for (PendingProjectile projectile : pendingProjectiles)
			if (projectile.epoch == hit.epoch && projectile.plane == hit.plane && projectile.worldView == hit.worldView
				&& projectile.scene == hit.scene
				&& (!projectile.timingKnown || Math.abs(projectile.estimatedArrivalTick - currentGameTick) <= 2))
			{
				competing = true;
				lastAttributionCandidates.add(data("kind", "PROJECTILE_CORRELATION", "name", projectile.sourceName,
					"projectileId", projectile.projectileId, "projectileObservationId",
					projectile.projectileObservationId, "sourceHistory",
					projectile.latestObservation == null ? null : projectile.latestObservation.getSourceHistory(),
					"latestSourceObservation", projectileLatestSource(projectile), "sourceSamplingUnavailable",
					projectile.sourceSamplingUnavailable, "matchBasis",
					projectile.timingKnown ? "ARRIVAL_WINDOW" : "TIMING_UNAVAILABLE"));
			}
		for (NPC npc : nativeNpcs())
			if (npc != null && npc.getInteracting() == nativeLocalPlayer() && isCombatNpc(npc))
			{
				competing = true;
				lastAttributionCandidates
					.add(data("kind", "ENGAGED_NPC_CANDIDATE", "name", getActorName(npc), "actorRef", actorRef(npc)));
			}
		for (Player other : playerDamageCandidates(nativeLocalPlayer()))
		{
			competing = true;
			lastAttributionCandidates.add(playerCandidate(other));
		}
		if (hit.damageType == DamageEvent.DamageType.UNKNOWN)
			for (String failure : failedPickpockets)
			{
				competing = true;
				lastAttributionCandidates.add(data("kind", "PICKPOCKET_FAILURE_CORRELATION", "name", failure));
			}
		if (hit.damageType == DamageEvent.DamageType.POISON || hit.damageType == DamageEvent.DamageType.VENOM)
		{
			String state = null;
			try
			{
				Integer id = damageMap == null ? null
					: damageMap.catalog().localStatusVarpId("poison_venom_client_state");
				if (id != null)
					state = damageMap.catalog().localStatusState("poison_venom_client_state", client.getVarpValue(id));
			}
			catch (RuntimeException unavailable)
			{
				/* Missing state must remain a competing possibility. */}
			if (state == null)
			{
				competing = true;
				lastAttributionCandidates.add(data("kind", "LOCAL_STATUS_STATE_UNAVAILABLE", "name",
					"Local poison/venom state", "evidence", "Status reference or variable unavailable"));
			}
			else if (hit.damageType == DamageEvent.DamageType.POISON && "poison".equals(state))
			{
				competing = true;
				lastAttributionCandidates.add(data("kind", "ACTIVE_POISON_STATE", "name", "Poison"));
			}
			else if (hit.damageType == DamageEvent.DamageType.VENOM && "venom".equals(state))
			{
				competing = true;
				lastAttributionCandidates.add(data("kind", "ACTIVE_VENOM_STATE", "name", "Venom"));
			}
		}
		lastAttributionConfidence = competing ? "UNKNOWN" : "TIMED";
		lastAttributionEvidence = competing ? "MULTIPLE_POSSIBLE_DAMAGE_SOURCES"
			: "SELF_DAMAGE_RESOURCE_CANDIDATE".equals(candidates.get(0).get("kind"))
				? "SELF_DAMAGE_RESOURCE_CORRELATION"
				: "SELF_DAMAGE_INPUT_CORRELATION";
		return competing ? "Unknown" : String.valueOf(candidates.get(0).get("name"));
	}

	/** Called only on user save/pause; buffered observations need no off-thread client API calls. */
	String captureLiveExport(List<TickData> journalTicks)
	{
		return prepareLiveExport(journalTicks).get();
	}

	/** Freeze finished DTOs now; the returned task performs no RuneLite API reads. */
	private java.util.function.Supplier<String> prepareLiveExport(List<TickData> journalTicks)
	{
		if (journalTicks.isEmpty() || worldRecorder == null || !config.captureWorldSnapshots())
			return () -> null;
		WorldSnapshotRecorder recorder = worldRecorder;
		int first = journalTicks.get(0).getTickNumber();
		int last = journalTicks.get(journalTicks.size() - 1).getTickNumber();
		DeathRecordingV2.Meta meta = DeathRecordingV2.Meta.builder().recordingId(java.util.UUID.randomUUID().toString())
			.pluginVersion("1.0.0").captureBuildId(com.tickreplaylogger.capture.CaptureBuild.sourceTreeHash())
			.runeLiteApiVersion(com.tickreplaylogger.capture.CaptureBuild.apiVersion())
			.runeLiteArtifactIdentityScope(com.tickreplaylogger.capture.CaptureBuild.artifactIdentityScope())
			.runeLiteRuntimeVersion(com.tickreplaylogger.capture.CaptureBuild.runtimeVersion())
			.runeLiteRuntimeReferenceVerification(
				com.tickreplaylogger.capture.CaptureBuild.runtimeReferenceVerification())
			.runeLiteCompiledApiVersion(com.tickreplaylogger.capture.CaptureBuild.compiledApiVersion())
			.runeLiteCompiledApiArtifactSha256(com.tickreplaylogger.capture.CaptureBuild.compiledApiArtifactSha256())
			.runeLiteCompiledClientArtifactSha256(
				com.tickreplaylogger.capture.CaptureBuild.compiledClientArtifactSha256())
			.runeLiteApiArtifactSha256(com.tickreplaylogger.capture.CaptureBuild.apiArtifactSha256())
			.runeLiteClientArtifactSha256(com.tickreplaylogger.capture.CaptureBuild.clientArtifactSha256())
			.dataMapVersion(damageMap == null ? "unknown" : damageMap.getVersion())
			.referenceCatalogId(damageMap == null ? null : damageMap.catalog().id())
			.exportedAtMs(System.currentTimeMillis()).rsn(capturedRsn).world(capturedWorld).snapshotTick(last)
			.bufferTicks(journalTicks.size()).captureSettings(captureSettings()).build();
		DeathRecordingV2.Summary summary = DeathRecordingV2.Summary.builder()
			.totalDamageTakenInBuffer(TickRecording.calculateTotalDamage(journalTicks))
			.ticksCaptured(journalTicks.size()).build();
		DeathRecordingV2 frozen = recorder.freezeExport(meta, summary, first, last).toBuilder()
			.journalTicks(new ArrayList<>(journalTicks)).build();
		return () -> gson.toJson(recorder.completeExport(frozen));
	}

	/**
	 * Build a rich {@link DamageRecord} (full Â§3 lifecycle + attribution confidence) for a hit the
	 * player received, and add it to the death-reconstruction buffer.
	 */
	private void buildReceivedDamageRecord(PendingHit hit, String damageTypeLabel, String source, String determination,
		String typeEvidence, List<String> typeCandidates)
	{
		if (worldRecorder == null || !config.captureWorldSnapshots())
		{
			return;
		}
		String attribution = lastAttributionConfidence;
		int launchTick = (lastAttributionLaunchTick > 0 && lastAttributionLaunchTick <= currentGameTick)
			? lastAttributionLaunchTick
			: currentGameTick;
		boolean impactPrayerStateKnown = hit.prayerObservation.isFlagsComplete()
			&& hit.prayerObservation.isOverheadResolved();
		List<String> prayers = hit.prayerObservation.isAvailable() ? hit.prayers : null;
		String overhead = hit.prayerObservation.getOverhead();
		WorldPoint w = hit.position;
		int impactX = w != null ? w.getX() : 0;
		int impactY = w != null ? w.getY() : 0;

		// Defender state at the LAUNCH tick (reactability: "wrong prayer at launch, flicked too late").
		// Read from the buffered snapshot for that tick; fall back to impact state if not present.
		String overheadAtLaunch = overhead;
		List<String> prayersAtLaunch = prayers;
		int launchX = impactX;
		int launchY = impactY;
		Boolean launchPositionKnown = false; // Impact fallback is not an observed launch location.
		boolean launchPrayerStateKnown = false;
		String launchStateEvidence = "IMPACT_FALLBACK";
		if (launchTick != currentGameTick && worldRecorder != null)
		{
			com.tickreplaylogger.model.world.WorldSnapshot ls = worldRecorder.snapshotAtTick(launchTick);
			if (ls != null && ls.getPlayer() != null)
			{
				overheadAtLaunch = ls.getPlayer().getOverheadPrayer();
				prayersAtLaunch = ls.getPlayer().getActivePrayers();
				launchPrayerStateKnown = ls.getPlayer().getPrayerStateKnown() != null
					? Boolean.TRUE.equals(ls.getPlayer().getPrayerStateKnown())
					: prayersAtLaunch != null;
				launchX = ls.getPlayer().getWorldX();
				launchY = ls.getPlayer().getWorldY();
				launchPositionKnown = ls.getPlayer().getWorldPositionKnown();
				launchStateEvidence = "SNAPSHOT";
			}
		}
		String timingRuleKey = "UNKNOWN".equals(determination) || damageMap == null ? null
			: damageMap.catalog().prayerTimingRuleKey(lastAttributionNpcId, lastAttributionProjectileId,
				lastAttributionNpcLevel, lastAttributionNormalContext);
		String defenderName = readHitContext("defenderName",
			() -> nativeLocalPlayer() == null ? null : nativeLocalPlayer().getName(), hit.captureIssues);
		List<Map<String, Object>> receivedCandidates = new ArrayList<>(lastAttributionCandidates);
		if (damageMap != null && hit.inventorySourceObservation != null)
			receivedCandidates.addAll(damageMap.catalog().inventorySourceHints().witnesses(hit.inventorySourceObservation, hit.sequence));
		DamageRecord record = DamageRecord.builder().direction(hit.hpOutcomeKnown ? "RECEIVED" : "RESOURCE_IMPACT").defenderRef("player")
			.defenderName(defenderName).defenderIdentityEvidence(hit.defenderIdentityEvidence)
			.captureIssues(hit.captureIssues.isEmpty() ? null : new ArrayList<>(hit.captureIssues))
			.impactPositionKnown(w != null).launchPositionKnown(launchPositionKnown).impactSceneKey(hit.impactSceneKey)
			.impactWorldViewId(hit.nativeScope == null ? null : hit.nativeScope.viewId)
			.impactPlane(hit.nativeScope == null ? null : hit.nativeScope.plane)
			.impactPrayerStateKnown(impactPrayerStateKnown).defenderGraphicsAtImpact(hit.graphicsObservation)
			.defenderPrayerStateAtImpact(hit.prayerObservation.toState()).launchPrayerStateKnown(launchPrayerStateKnown)
			.attackerRef(lastAttributionActorRef).observedCycle(hit.observedCycle).observationSequence(hit.sequence)
			.attributionEvidence(lastAttributionEvidence).launchTickEstimated(true)
			.launchStateEvidence(launchStateEvidence)
			.observedHazardContacts(java.util.Collections.unmodifiableList(new ArrayList<>(hit.hazardWitnesses)))
			.launchTick(launchTick).impactTick(currentGameTick).attackerName(source)
			.attackerNpcId(lastAttributionNpcId < 0 ? null : lastAttributionNpcId)
			.attackerCombatLevel(lastAttributionNpcLevel).attribution(attribution)
			.attributionCandidates(receivedCandidates).amount(hit.amount)
			.hitsplatType(hit.hitsplatType).hitsplatTypeKnown(true).damageType(damageTypeLabel)
			.damageRecurrence(typedStatusHit(hit.hitsplatType) ? "UNKNOWN" : null)
			.damageRecurrenceEvidence(typedStatusHit(hit.hitsplatType) ? "HITSPLAT_TYPE_ONLY" : null)
			.damageTypeEvidence(typeEvidence).damageTypeCandidates(typeCandidates)
			.attackStyleName(lastAttributionAttackName != null ? lastAttributionAttackName
				: lastAttributionAttackRule == null ? null : lastAttributionAttackRule.getName())
			.determination(determination) // LAUNCH / IMPACT / UNKNOWN from the bundled map
			.determinationEvidence(timingRuleKey == null ? null : "REVIEWED_ATTACK_CONTEXT")
			.determinationRuleKey(timingRuleKey)
			.damageTypeRuleKey(
				"REVIEWED_PROJECTILE_BINDING".equals(typeEvidence) && damageMap != null
					? damageMap.catalog().projectileDamageTypeRuleKey(lastAttributionNpcId, lastAttributionProjectileId,
						lastAttributionNpcLevel, lastAttributionNormalContext)
					: null)
			.damageTypeCatalogId(
				"REVIEWED_PROJECTILE_BINDING".equals(typeEvidence) && damageMap != null ? damageMap.catalog().id()
					: null)
			.prayerEffect(damageMap == null ? null
				: damageMap.catalog().hitsplatPlayerPrayerEffect(hit.hitsplatType) != null
					? damageMap.catalog().hitsplatPlayerPrayerEffect(hit.hitsplatType)
					: damageMap.catalog().prayerIneffective(lastAttributionNpcId, lastAttributionNpcLevel,
						lastAttributionNormalContext) ? "NONE_REVIEWED" : null)
			.damageReferenceKey(damageMap == null ? null : damageMap.catalog().hitsplatReferenceKey(hit.hitsplatType))
			.damagePropertyEvidence(
				damageMap != null && damageMap.catalog().hitsplatPlayerPrayerEffect(hit.hitsplatType) != null
					? "SOURCE_REFERENCE"
					: null)
			.pvp(lastAttributionSourceIsPlayer).overheadAtLaunch(overheadAtLaunch).prayersAtLaunch(prayersAtLaunch)
			.launchX(launchX).launchY(launchY).overheadAtImpact(overhead)
			.overheadIconAtImpact(hit.prayerObservation.getIcon())
			.reportedPrayersAtImpact(
				hit.prayerObservation.getFlags().isEmpty() ? null : hit.prayerObservation.getReported())
			.prayerStateConflictAtImpact(
				hit.prayerObservation.isAvailable() ? hit.prayerObservation.isConflict() : null)
			.overheadAtImpactEvidence(hit.prayerObservation.getEvidence()).prayersAtImpact(prayers).impactX(impactX)
			.impactY(impactY).mapRef(lastAttributionMapRef).build();
		if (hit.hpOutcomeKnown) worldRecorder.addDamage(record);
		else resourceImpactContexts.put(hit.sequence, record);
	}

	private static String resourceImpactNote(int amount, List<String> surfaces, String source)
	{
		String outcome = surfaces.contains("SKILL") ? "HP/stat" : surfaces.contains("COMBAT_RESOURCE") ? "HP/resource" : "HP/unknown";
		String label = com.tickreplaylogger.util.SourceResolutionLabels.usableName(source);
		return "Impact " + amount + " (" + outcome + ") from " + (label == null ? "Unknown" : label);
	}

	/**
	 * Detect stat-drain combat effects from a sudden drop (>=2 levels in one tick) in any boosted
	 * combat level. A &gt;=2 drop distinguishes a drain/special from the slow 1-per-minute boost decay.
	 * First-cut heuristic; the bundled map will later attribute the specific source.
	 */
	private void detectStatDrains()
	{
		int[] now =
		{ client.getBoostedSkillLevel(Skill.ATTACK), client.getBoostedSkillLevel(Skill.STRENGTH),
			client.getBoostedSkillLevel(Skill.DEFENCE), client.getBoostedSkillLevel(Skill.RANGED),
			client.getBoostedSkillLevel(Skill.MAGIC), };
		String[] names =
		{ "Attack", "Strength", "Defence", "Ranged", "Magic" };
		if (lastBoostedCombat != null && worldRecorder != null)
		{
			for (int i = 0; i < now.length; i++)
			{
				int drop = lastBoostedCombat[i] - now[i];
				if (drop >= 2)
				{
					worldRecorder
						.addEffect(EffectEvent.builder().type("stat_change").sourceName(null).attribution("OBSERVED")
							.startTick(currentGameTick).durationTicks(null).magnitude("-" + drop + " " + names[i])
							.observedVia("skillDelta").evidence("OBSERVED_SKILL_DELTA; cause not established")
							.journalNote("-" + drop + " " + names[i]).mapRef(null).build());
					tickRecorder.addEffectNote("-" + drop + " " + names[i]);
				}
			}
		}
		lastBoostedCombat = now;
	}

	/** Observe exact local graphic references. A sighting never establishes an applied effect. */
	private void detectEffectSpotanims(Player localPlayer)
	{
		if (worldRecorder == null || damageMap == null)
			return;
		if (localPlayer == null)
			throw new IllegalStateException("local graphic actor unavailable");
		if (lastEffectGraphicSampleTick == null || lastEffectGraphicSampleTick + 1 != currentGameTick
			|| lastEffectGraphicSampleEpoch != causalSceneEpoch)
			activeEffectSpotanims.clear();
		java.util.Set<Integer> present = new java.util.HashSet<>();
		List<String> issues = new ArrayList<>();
		boolean collectionKnown = false, primaryKnown = false;
		try
		{
			net.runelite.api.IterableHashTable<net.runelite.api.ActorSpotAnim> graphics = localPlayer.getSpotAnims();
			if (graphics != null)
			{
				for (net.runelite.api.ActorSpotAnim graphic : graphics)
					if (graphic != null)
						present.add(graphic.getId());
				collectionKnown = true;
			}
			else
				issues.add("effectGraphics: active graphic collection unavailable");
		}
		catch (RuntimeException unavailable)
		{
			issues.add("effectGraphics: " + unavailable.getClass().getSimpleName());
		}
		try
		{
			int graphic = localPlayer.getGraphic();
			if (graphic >= 0)
				present.add(graphic);
			primaryKnown = true;
		}
		catch (RuntimeException unavailable)
		{
			issues.add("effectGraphics primary: " + unavailable.getClass().getSimpleName());
		}
		for (String issue : issues)
			tickRecorder.addCaptureIssue(issue);
		if (!collectionKnown || !primaryKnown)
			tickRecorder.markStateUnavailable("effectGraphics");
		if (!collectionKnown && !primaryKnown && present.isEmpty())
			throw new IllegalStateException("local graphics unavailable");
		for (int id : present)
		{
			if (activeEffectSpotanims.contains(id))
				continue;
			com.google.gson.JsonObject rule = damageMap.catalog().localGraphicReference(id);
			if (rule.size() == 0)
				continue;
			String note = rule.get("label").getAsString();
			worldRecorder.addEffect(EffectEvent.builder().type("graphic_reference").targetRef("player")
				.observedGraphicId(id).attribution("UNKNOWN").startTick(currentGameTick).durationTicks(null)
				.magnitude(null).observedVia("spotanim:" + id).evidence("PINNED_LOCAL_GRAPHIC_REFERENCE")
				.referenceRuleKey(rule.get("key").getAsString()).referenceCatalogId(damageMap.catalog().id())
				.journalNote(note).build());
			tickRecorder.addEffectNote(note);
		}
		activeEffectSpotanims.clear();
		activeEffectSpotanims.addAll(present);
		lastEffectGraphicSampleTick = currentGameTick;
		lastEffectGraphicSampleEpoch = causalSceneEpoch;
	}

	/**
	 * Observe poison/venom category changes and cures after a known baseline. Negative immunity
	 * timers are inactive, not poison. Hitsplats remain independent damage observations; these
	 * state changes neither identify an inflictor nor create a damage/healing amount.
	 */
	private void detectDotOnset()
	{
		if (worldRecorder == null)
		{
			return;
		}
		Integer variable = damageMap == null ? null
			: damageMap.catalog().localStatusVarpId("poison_venom_client_state");
		if (variable == null)
		{
			poisonBaselineObserved = false;
			return;
		}
		int poison = client.getVarpValue(variable);
		if (poisonBaselineObserved && (lastPoisonSampleTick == null || currentGameTick - lastPoisonSampleTick != 1))
			poisonBaselineObserved = false;
		String previousState = damageMap.catalog().localStatusState("poison_venom_client_state", lastPoisonVarp);
		String currentState = damageMap.catalog().localStatusState("poison_venom_client_state", poison);
		if (currentState == null)
		{
			poisonBaselineObserved = false;
			return;
		}
		if (previousState == null)
			poisonBaselineObserved = false;
		if ("inactive".equals(previousState))
			previousState = "clear";
		if ("inactive".equals(currentState))
			currentState = "clear";
		if (poisonBaselineObserved && !previousState.equals(currentState))
		{
			boolean venom = "venom".equals(currentState);
			String note = currentState.equals("clear") ? "Poison/venom state cleared"
				: previousState.equals("clear") ? (venom ? "Venomed" : "Poisoned")
					: "Status changed: " + previousState + " to " + currentState;
			worldRecorder.addEffect(EffectEvent.builder()
				.type(currentState.equals("clear") ? "poison_state_change" : "dot")
				.sourceName(currentState.equals("clear") ? null : venom ? "Venom" : "Poison").attribution("CONFIRMED")
				.startTick(currentGameTick).durationTicks(null).magnitude(previousState + " -> " + currentState)
				.observedVia("varp:" + variable).observedRawValue(poison).previousRawValue(lastPoisonVarp)
				.evidence("OBSERVED_VARIABLE_CATEGORY_TRANSITION").referenceRuleKey("poison_venom_client_state")
				.referenceCatalogId(damageMap.catalog().id()).journalNote(note).mapRef(null).build());
			tickRecorder.addEffectNote(note);
		}
		lastPoisonVarp = poison;
		poisonBaselineObserved = true;
		lastPoisonSampleTick = currentGameTick;
	}

	private String damageTypeString(DamageEvent.DamageType dt)
	{
		switch (dt)
		{
		case MELEE:
			return "melee";
		case RANGED:
			return "ranged";
		case MAGIC:
			return "magic";
		case POISON:
			return "poison";
		case VENOM:
			return "venom";
		case BURN:
			return "burn";
		case BLEED:
			return "bleed";
		case TYPELESS:
			return "typeless";
		default:
			return "unknown";
		}
	}

	private String overheadFromPrayers(List<String> prayers)
	{
		for (String p : prayers)
		{
			if (OVERHEAD_PRAYERS.contains(p))
			{
				return p;
			}
		}
		return null;
	}

	/**
	 * Get the recording storage for UI access
	 */
	public RecordingStorage getRecordingStorage()
	{
		return recordingStorage;
	}

	private int eventTick()
	{
		return currentGameTick + (gameTickInProgress ? 0 : 1);
	}

	private boolean captureActor(Actor actor)
	{
		return actor != null && worldRecorder != null && config.captureWorldSnapshots()
			&& (!(actor instanceof Player) || actor == nativeLocalPlayer() || config.captureOtherPlayers());
	}

	private String actorRef(Actor actor)
	{
		if (actor == null)
			return null;
		if (actor == nativeLocalPlayer())
			return "player";
		if (actor instanceof Player && !config.captureOtherPlayers())
			return null;
		return captureService == null ? null : captureService.actorRef(actor, nativeLocalPlayer());
	}

	private static Map<String, Object> data(Object... pairs)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
			result.put((String) pairs[i], pairs[i + 1]);
		return java.util.Collections.unmodifiableMap(result);
	}

	private Map<String, Object> actorDataSnapshot(Actor actor, CallbackContext c)
	{
		if (c.actorSamples.containsKey(actor))
			return c.actorSamples.get(actor).data();
		com.tickreplaylogger.capture.ActorCallbackObservation.Sample sample = com.tickreplaylogger.capture.ActorCallbackObservation
			.captureSample(actor, c.ready ? client : null, c.localPlayer, config.captureOtherPlayers(),
				target -> callbackActorRef(target, c), (target, metadata) -> callbackActorRef(target, c, metadata));
		c.actorSamples.put(actor, sample);
		return sample.data();
	}

	private void observeActorCallback(String type, Actor actor)
	{
		observeActorCallback(type, actor, java.util.Collections::emptyMap);
	}

	private void observeActorCallback(String type, Actor actor,
		java.util.function.Supplier<Map<String, Object>> payload)
	{
		if (worldRecorder == null || !config.captureWorldSnapshots())
			return;
		CallbackContext c = callbackContext();
		if (!c.allowed || actor == null
			|| actor instanceof Player && actor != c.localPlayer && !config.captureOtherPlayers())
			return;
		Map<String, Object> fields = new LinkedHashMap<>(actorDataSnapshot(actor, c));
		fields.putAll(payload.get());
		fields.put("callbackMetadataEvidence",
			c.ready ? "LOGGED_IN_ACTOR_FIELDS" : "API_REPORTED_WITHOUT_READY_CONTEXT");
		observe(type, actor, null, fields, c);
	}

	private String callbackActorRef(Actor actor, CallbackContext c)
	{
		return callbackActorRef(actor, c, null);
	}

	private String callbackActorRef(Actor actor, CallbackContext c, Map<String, Object> metadata)
	{
		if (actor == null || !c.ready)
			return null;
		if (actor == c.localPlayer)
			return "player";
		if (actor instanceof Player && (c.localPlayer == null || !config.captureOtherPlayers()))
			return null;
		if (c.actorReferences.containsKey(actor))
			return c.actorReferences.get(actor);
		String result = null;
		try
		{
			if (metadata == null && c.actorSamples.containsKey(actor))
				metadata = c.actorSamples.get(actor).data();
			if (captureService != null)
				result = metadata == null ? captureService.actorRef(actor, c.localPlayer)
					: captureService.observedActorRef(actor, c.localPlayer, integerField(metadata, "worldViewId"),
						integerField(metadata, actor instanceof NPC ? "sceneId" : "playerId"));
		}
		catch (RuntimeException unavailable)
		{
			c.issues.add("callbackActorRef: " + unavailable.getClass().getSimpleName());
		}
		c.actorReferences.put(actor, result);
		return result;
	}

	private static Integer integerField(Map<?, ?> fields, String name)
	{
		Object value = fields.get(name);
		return value instanceof Number ? ((Number) value).intValue() : null;
	}

	private static void retainActorSampleIssue(com.tickreplaylogger.capture.ActorCallbackObservation.Sample sample,
		String field, String label, List<String> issues)
	{
		Object original = sample == null ? null : sample.data().get("observationIssues");
		Object reason = original instanceof Map ? ((Map<?, ?>) original).get(field) : null;
		if (reason instanceof String)
			issues.add(label + ": " + reason);
	}

	@Subscribe
	public void onSoundEffectPlayed(net.runelite.api.events.SoundEffectPlayed event)
	{
		if (event == null || worldRecorder == null || !config.captureWorldSnapshots())
			return;
		CallbackContext c = callbackContext();
		if (!c.allowed)
			return;
		observe("SOUND_EFFECT", null, null,
			com.tickreplaylogger.capture.SoundCallbackObservation.capture(event, c.localPlayer, c.ready,
				config.captureOtherPlayers(),
				actor -> captureService == null ? null : captureService.existingActorRef(actor, c.localPlayer)),
			c);
	}

	@Subscribe
	public void onAreaSoundEffectPlayed(net.runelite.api.events.AreaSoundEffectPlayed event)
	{
		if (event == null || worldRecorder == null || !config.captureWorldSnapshots())
			return;
		CallbackContext c = callbackContext();
		if (!c.allowed)
			return;
		observe("AREA_SOUND_EFFECT", null, null,
			com.tickreplaylogger.capture.SoundCallbackObservation.capture(event, c.localPlayer, c.ready,
				config.captureOtherPlayers(),
				actor -> captureService == null ? null : captureService.existingActorRef(actor, c.localPlayer)),
			c);
	}

	private Map<String, Object> callbackActorData(Actor actor, CallbackContext c)
	{
		if (!c.ready || actor instanceof Player && actor != c.localPlayer && !config.captureOtherPlayers())
			return java.util.Collections.emptyMap();
		try
		{
			return actorDataSnapshot(actor, c);
		}
		catch (RuntimeException unavailable)
		{
			c.issues.add("callbackActorMetadata: " + unavailable.getClass().getSimpleName());
			return data("captureStatus", "PARTIAL_ACTOR_METADATA");
		}
	}

	private long observe(String type, Actor actor, Actor related, Map<String, Object> values)
	{
		return observe(type, actor, related, values, callbackContext());
	}

	private long observe(String type, Actor actor, Actor related, Map<String, Object> values, CallbackContext c)
	{
		long sequence = ++observationSequence;
		if (worldRecorder == null || !config.captureWorldSnapshots() || !c.allowed)
			return sequence;
		boolean privateActor = actor instanceof Player && actor != c.localPlayer && !config.captureOtherPlayers();
		if (privateActor)
		{
			if (c.localPlayer != null || !("HITSPLAT".equals(type) || "ACTOR_DEATH".equals(type)))
				return sequence;
			// Preserve anonymous primitive combat facts when local identity is unavailable, never another player's metadata.
			Map<String, Object> safe = new LinkedHashMap<>();
			for (String key : java.util.Arrays.asList("amount", "hitsplatType", "resourceSurface", "isMine", "isOthers",
				"disappearsOnGameCycle", "renderingKnown", "receiverKind"))
				if (values != null && values.containsKey(key))
					safe.put(key, values.get(key));
			values = safe;
			actor = null;
			related = null;
		}
		Map<String, Object> recorded = values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
		String ref = actor == c.localPlayer && actor != null ? "player" : null, name = null, relatedRef = null,
			sceneKey = null;
		List<String> observationOnlyIssues = new ArrayList<>();
		final Actor observedActor = actor, observedRelated = related;
		if (c.ready)
		{
			if (ref == null)
				ref = readHitContext("observationActorRef", () -> callbackActorRef(observedActor, c), c.issues);
			com.tickreplaylogger.capture.ActorCallbackObservation.Sample original = c.actorSamples.get(actor);
			retainActorSampleIssue(original, "name", "observationActorName", c.issues);
			name = actor == null ? null
				: original == null ? readHitContext("observationActorName", actor::getName, c.issues)
					: (String) original.data().get("name");
			relatedRef = readHitContext("observationRelatedRef", () -> callbackActorRef(observedRelated, c), c.issues);
		}
		values = recorded;
		if (c.ready)
			try
			{
				boolean primaryScopeKnown = prepareCallbackCaptureScope(c);
				if (values != null && damageMap != null)
				{
					values = new java.util.LinkedHashMap<>(values);
					com.tickreplaylogger.capture.CombatCatalog catalog = damageMap.catalog();
					java.util.Set<String> candidates = new java.util.LinkedHashSet<>();
					Object animation = values.get("animationId");
					if (animation instanceof Number)
						candidates.addAll(catalog.observationReferences("animation", ((Number) animation).intValue()));
					if (type.equals("GRAPHICS_OBJECT_CREATED") && values.get("id") instanceof Number)
						candidates
							.addAll(catalog.observationReferences("graphic", ((Number) values.get("id")).intValue()));
					Object object = values.get("object");
					if (object instanceof com.tickreplaylogger.model.world.ObservedObjectState)
					{
						Integer id = ((com.tickreplaylogger.model.world.ObservedObjectState) object).getId();
						if (id != null)
							candidates.addAll(catalog.observationReferences("object", id));
					}
					if (!candidates.isEmpty())
						values.put("referenceCandidates", new ArrayList<>(candidates));
					if (actor instanceof NPC)
					{
						Object nested = values.get("actorState");
						Map<?, ?> metadata = nested instanceof Map ? (Map<?, ?>) nested : values;
						Integer id = null;
						if (metadata.containsKey("definitionEvidence"))
						{
							Object observedId = metadata.get("npcId");
							if (observedId instanceof Number)
								id = ((Number) observedId).intValue();
						}
						else
						{
							Map<String, String> definitionIssues = new LinkedHashMap<>();
							id = com.tickreplaylogger.capture.NpcDefinitionObservation
								.capture((NPC) actor, definitionIssues, "").getId();
							if (!definitionIssues.isEmpty())
								values.put("actorResearchIssues", definitionIssues);
						}
						Object metadataIssues = metadata.get("observationIssues");
						boolean nameKnown = (!(metadataIssues instanceof Map)
							|| !((Map<?, ?>) metadataIssues).containsKey("name"))
							&& c.issues.stream().noneMatch(issue -> issue.startsWith("observationActorName:"));
						if (id == null || id < 0)
						{
							values.put("actorResearchStatus", "unavailable");
							values.put("actorResearchUnavailableReason", "EFFECTIVE_DEFINITION_UNAVAILABLE");
						}
						else if (!catalog.observedNameCompatible(id, name, nameKnown))
						{
							values.put("actorResearchStatus", "unavailable");
							values.put("actorResearchUnavailableReason", "OBSERVED_NAME_NOT_QUALIFIED");
						}
						else
						{
							List<String> referenceKeys = catalog.referenceKeys(id);
							values.put("actorResearchKeys", referenceKeys);
							values.put("actorResearchEvidence", "EFFECTIVE_ID_NAME_COMPATIBLE_REFERENCE_CANDIDATES");
						}
					}
				}
				Object nativeScene = values == null ? null : values.get("nativeSceneKey");
				com.tickreplaylogger.capture.ActorCallbackObservation.Sample original = c.actorSamples.get(actor);
				retainActorSampleIssue(original, "worldPosition", "observationActorWorldPosition", c.issues);
				retainActorSampleIssue(original, "worldView", "observationActorWorldView", c.issues);
				// The ID diagnostic describes raw placement; it adds no damage-attribution guard.
				retainActorSampleIssue(original, "worldViewId", "observationActorWorldViewId", observationOnlyIssues);
				WorldPoint actorPosition = actor == null ? null
					: original == null ? actor.getWorldLocation() : original.world();
				net.runelite.api.WorldView actorView = actor == null ? null
					: original != null ? original.view()
						: actor == c.localPlayer && c.localViewSampled ? c.localView : actor.getWorldView();
				Integer actorViewId = original != null ? integerField(original.data(), "worldViewId")
					: actorView == null ? null : actorView == c.primaryView ? c.primaryViewId : actorView.getId();
				boolean unknownActorScope = actor != null
					&& (actorPosition == null || actorView == null || actorViewId == null);
				boolean foreignActor = actor != null && (unknownActorScope || actorView != c.primaryView
					|| !java.util.Objects.equals(actorPosition.getPlane(), c.primaryPlane));
				if (!(nativeScene instanceof String) && foreignActor && !unknownActorScope && primaryScopeKnown
					&& captureService != null)
					try
					{
						nativeScene = captureService.observedSceneKey(actorView, actorPosition.getPlane(), actorViewId,
							c.primaryView, c.primaryPlane);
					}
					catch (RuntimeException unavailable)
					{
						c.issues.add("observationActorScope: " + unavailable.getClass().getSimpleName());
					}
				sceneKey = nativeScene instanceof String ? (String) nativeScene
					: foreignActor || !primaryScopeKnown || Boolean.FALSE.equals(values.get("scopeKnown")) ? null
						: c.primarySceneKey;
			}
			catch (RuntimeException unavailable)
			{
				c.issues.add("observationContext: " + unavailable.getClass().getSimpleName());
			}
		recorded = new LinkedHashMap<>(values);
		List<String> recordedIssues = new ArrayList<>(c.issues);
		recordedIssues.addAll(observationOnlyIssues);
		if (!recordedIssues.isEmpty())
		{
			recorded.put("captureStatus", "PARTIAL_EVENT_CONTEXT");
			recorded.put("captureIssues", recordedIssues);
		}
		worldRecorder
			.addObservation(ObservedEvent.builder().tick(eventTick()).gameCycle(c.cycle).sequence(sequence).type(type)
				.actorRef(ref).actorName(name).relatedActorRef(relatedRef).sceneKey(sceneKey).data(recorded).build());
		return sequence;
	}

	private void recordAction(PlayerAction action, Actor target, Integer itemId, long sequence, String kind)
	{
		recordAction(action, target, itemId, sequence, kind, callbackContext());
	}

	private void recordAction(PlayerAction action, Actor target, Integer itemId, long sequence, String kind,
		CallbackContext context)
	{
		if (worldRecorder == null || !config.captureWorldSnapshots())
			return;
		String targetRef = readHitContext("actionTargetRef", () -> callbackActorRef(target, context), context.issues);
		worldRecorder.addPlayerAction(DeathRecordingV2.Events.PlayerActionRecord.builder().tick(eventTick())
			.type(action.getType().name()).detail(action.getDescription()).target(action.getTarget())
			.targetRef(targetRef).itemName(action.getItemName()).itemId(itemId).gameCycle(context.cycle)
			.sequence(sequence == 0 ? null : sequence).observationKind(kind).build());
	}

	private DeathRecordingV2.CaptureSettings captureSettings()
	{
		return DeathRecordingV2.CaptureSettings.builder().worldSnapshots(config.captureWorldSnapshots())
			.sceneCollision(config.captureSceneCollision()).sceneTerrain(config.captureSceneTerrain())
			.otherPlayers(config.captureOtherPlayers()).effects(config.captureEffects())
			.damageReceived(config.trackDamageReceived()).damageDealt(config.trackDamageDealt()).hazardRadiusTiles(12)
			.sceneObjectScope("DISCOVERED_NATIVE_PLANE_CHANNELS_ALL_FOUR_OBJECT_TYPES")
			.graphicsScope("DISCOVERED_NATIVE_PLANE_CHANNELS_PLUS_CREATION_CALLBACKS")
			.variableMode("VARP_SERVER_LONG_CLIENT_INTEGER_CHECKPOINT_DELTA_AND_NUMERIC_CALLBACKS")
			.varbitLayoutScope("ACTUAL_CLIENT_COMPOSITIONS_FOR_CONFIGURED_CONTEXT_STATUS_MODE_IDS_AND_VARBIT_CALLBACKS")
			.projectileMode("PER_TICK_OBSERVED")
			.damageAmountMode("HITSPLAT_CALLBACK_VALUES; net HP loss and hidden rolls are not reconstructed")
			.tickConvention("CALLBACKS_BEFORE_SETTLED_SNAPSHOT").build();
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		observeActorCallback("ANIMATION_CHANGED", event.getActor());
	}

	@Subscribe
	public void onPlayerChanged(net.runelite.api.events.PlayerChanged event)
	{
		observeActorCallback("PLAYER_CHANGED", event.getPlayer());
	}

	@Subscribe
	public void onVarClientIntChanged(net.runelite.api.events.VarClientIntChanged event)
	{
		CallbackContext context = callbackContext();
		if (!context.allowed)
			return;
		CallbackFieldObservation fields = new CallbackFieldObservation();
		Integer id = fields.value("id", event::getIndex);
		if (id != null)
			fields.value("value", () -> client.getVarcIntValue(id));
		else
			fields.unavailable("value", "ID_UNAVAILABLE");
		if (!context.ready)
		{
			fields.put("scopeKnown", false);
			fields.put("positionScopeKnown", false);
			fields.unavailable("nativeSceneKey", "WORLD_CONTEXT_UNAVAILABLE");
		}
		observe("CLIENT_INTEGER_CHANGED", context.localPlayer, null, fields.data(), context);
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		observeActorCallback("NPC_SPAWNED", event.getNpc());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		recentAttackers.entrySet().removeIf(entry -> entry.getValue().actor == event.getNpc());
		observeActorCallback("NPC_DESPAWNED", event.getNpc());
		if (captureService != null)
			captureService.clearMechanics(event.getNpc());
		if (captureService != null)
			captureService.forgetActor(event.getNpc());
	}

	@Subscribe
	public void onPlayerDespawned(net.runelite.api.events.PlayerDespawned event)
	{
		observeActorCallback("PLAYER_DESPAWNED", event.getPlayer());
		if (captureService != null)
			captureService.forgetActor(event.getPlayer());
	}

	@Subscribe
	public void onPlayerSpawned(net.runelite.api.events.PlayerSpawned event)
	{
		observeActorCallback("PLAYER_SPAWNED", event.getPlayer());
	}

	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		observeActorCallback("NPC_CHANGED", event.getNpc(),
			() -> data("oldComposition",
				com.tickreplaylogger.capture.ActorCallbackObservation.oldComposition(event.getOld()),
				"oldCompositionProvided", event.getOld() != null));
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		CallbackFieldObservation fields = new CallbackFieldObservation();
		Integer id = fields.value("containerId", event::getContainerId);
		if (id == null || id != net.runelite.api.InventoryID.INVENTORY.getId()
			&& id != net.runelite.api.InventoryID.EQUIPMENT.getId())
			return;
		net.runelite.api.ItemContainer container = fields.sample("itemContainer", event::getItemContainer);
		net.runelite.api.Item[] items = null;
		if (container != null)
			items = fields.sample("items", () ->
			{
				net.runelite.api.Item[] value = container.getItems();
				return value == null ? null : value.clone();
			});
		else
			fields.unavailable("items", "ITEM_CONTAINER_UNAVAILABLE");
		fields.put("itemsKnown", items != null);
		if (items == null)
		{
			fields.unavailable("itemIds", "ITEMS_UNAVAILABLE");
			fields.unavailable("quantities", "ITEMS_UNAVAILABLE");
		}
		else
		{
			final net.runelite.api.Item[] observed = items;
			fields.value("itemIds", () ->
			{
				int[] values = new int[observed.length];
				for (int i = 0; i < values.length; i++)
					values[i] = observed[i] == null ? -1 : observed[i].getId();
				return values;
			});
			fields.value("quantities", () ->
			{
				int[] values = new int[observed.length];
				for (int i = 0; i < values.length; i++)
					values[i] = observed[i] == null ? 0 : observed[i].getQuantity();
				return values;
			});
		}
		CallbackContext context = callbackContext();
		observe("ITEM_CONTAINER_CHANGED", context.localPlayer, null, fields.data(), context);
	}

	@Provides
	TickReplayLoggerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TickReplayLoggerConfig.class);
	}

	private void observeObject(String kind, net.runelite.api.TileObject object, boolean spawn)
	{
		if (object == null || captureService == null || !config.captureWorldSnapshots())
			return;
		CallbackContext context = callbackContext();
		if (!context.allowed)
			return;
		if (!context.ready)
		{
			CallbackFieldObservation fields = new CallbackFieldObservation();
			fields.put("kind", kind);
			fields.value("id", object::getId);
			fields.value("plane", object::getPlane);
			LocalPoint location = fields.sample("location", object::getLocalLocation);
			if (location != null)
			{
				fields.value("localX", location::getX);
				fields.value("localY", location::getY);
				fields.value("localWorldViewId", location::getWorldView);
			}
			else
			{
				fields.unavailable("localX", "LOCATION_UNAVAILABLE");
				fields.unavailable("localY", "LOCATION_UNAVAILABLE");
				fields.unavailable("localWorldViewId", "LOCATION_UNAVAILABLE");
			}
			WorldPoint world = fields.sample("worldLocation", object::getWorldLocation);
			if (world != null)
			{
				fields.value("worldX", world::getX);
				fields.value("worldY", world::getY);
			}
			else
			{
				fields.unavailable("worldX", "WORLD_LOCATION_UNAVAILABLE");
				fields.unavailable("worldY", "WORLD_LOCATION_UNAVAILABLE");
			}
			net.runelite.api.WorldView view = fields.sample("worldView", object::getWorldView);
			if (view != null)
				fields.value("worldViewId", view::getId);
			else
				fields.unavailable("worldViewId", "WORLD_VIEW_UNAVAILABLE");
			fields.put("scopeKnown", false);
			fields.put("positionScopeKnown", false);
			fields.unavailable("nativeSceneKey", "WORLD_CONTEXT_UNAVAILABLE");
			fields.put("callbackMetadataEvidence", "API_REPORTED_WITHOUT_READY_CONTEXT");
			observe(spawn ? "OBJECT_SPAWNED" : "OBJECT_DESPAWNED", null, null, fields.data(), context);
			return;
		}
		preparePrimaryCaptureScope();
		com.tickreplaylogger.capture.ObjectStateTracker tracker = null;
		Integer plane = null;
		try
		{
			plane = object.getPlane();
			tracker = captureService.objects(client, object.getWorldView(), plane);
			com.tickreplaylogger.model.world.ObservedObjectState state = tracker.observation(client, kind, object);
			Map<String, Object> values = new LinkedHashMap<>(data("object", state, "nativeSceneKey",
				captureService.additionalSceneKey(client, object.getWorldView(), plane)));
			if (state != null && state.getObservationIssues() != null)
			{
				values.put("captureStatus", "PARTIAL_OBJECT_OBSERVATION");
				values.put("observationIssues", state.getObservationIssues());
			}
			observe(spawn ? "OBJECT_SPAWNED" : "OBJECT_DESPAWNED", null, null, values, context);
		}
		catch (RuntimeException error)
		{
			// Preserve a lifecycle fact even when its definition/position cannot be read.
			Integer id = null, viewId = null;
			try
			{
				id = object.getId();
			}
			catch (RuntimeException unavailable)
			{
				/* Unknown definition remains unknown. */}
			try
			{
				viewId = object.getWorldView() == null ? null : object.getWorldView().getId();
			}
			catch (RuntimeException unavailable)
			{
				/* No frame is invented. */}
			observe(spawn ? "OBJECT_SPAWNED" : "OBJECT_DESPAWNED", null, null, data("kind", kind, "id", id,
				"worldViewId", viewId, "plane", plane, "captureStatus", "PARTIAL_OBJECT_OBSERVATION"), context);
		}
		finally
		{
			if (!spawn && tracker != null)
				tracker.despawned(object);
		}
	}

	private void observeGroundItem(String type, net.runelite.api.Tile tile, net.runelite.api.TileItem item,
		Integer oldQuantity, Integer newQuantity)
	{
		if (item == null || captureService == null || !config.captureWorldSnapshots())
			return;
		CallbackContext context = callbackContext();
		if (!context.allowed)
			return;
		if (!context.ready)
		{
			CallbackFieldObservation fields = new CallbackFieldObservation();
			fields.put("oldQuantity", oldQuantity);
			fields.put("newQuantity", newQuantity);
			fields.value("id", item::getId);
			fields.value("quantity", item::getQuantity);
			LocalPoint location = null;
			if (tile != null)
			{
				fields.value("plane", tile::getPlane);
				location = fields.sample("location", tile::getLocalLocation);
			}
			else
			{
				fields.unavailable("plane", "TILE_UNAVAILABLE");
				fields.unavailable("location", "TILE_UNAVAILABLE");
			}
			if (location != null)
			{
				fields.value("localX", location::getX);
				fields.value("localY", location::getY);
				fields.value("localWorldViewId", location::getWorldView);
			}
			else
			{
				fields.unavailable("localX", "LOCATION_UNAVAILABLE");
				fields.unavailable("localY", "LOCATION_UNAVAILABLE");
				fields.unavailable("localWorldViewId", "LOCATION_UNAVAILABLE");
			}
			fields.put("scopeKnown", false);
			fields.put("positionScopeKnown", false);
			fields.unavailable("nativeSceneKey", "WORLD_CONTEXT_UNAVAILABLE");
			fields.put("callbackMetadataEvidence", "API_REPORTED_WITHOUT_READY_CONTEXT");
			observe(type, null, null, fields.data(), context);
			return;
		}
		if (tile == null)
			return;
		com.tickreplaylogger.capture.GroundItemTracker tracker = null;
		Map<String, Object> values = new LinkedHashMap<>(
			data("oldQuantity", oldQuantity, "newQuantity", newQuantity, "scopeKnown", false));
		try
		{
			preparePrimaryCaptureScope();
			LocalPoint point = tile.getLocalLocation();
			net.runelite.api.WorldView nativeView = point == null ? null : client.getWorldView(point.getWorldView());
			if (nativeView == null && point != null && point.getWorldView() == net.runelite.api.WorldView.TOPLEVEL)
				nativeView = client.getTopLevelWorldView();
			boolean known = com.tickreplaylogger.capture.WorldViews.containsTile(nativeView, tile);
			String key = known ? captureService.additionalSceneKey(client, nativeView, tile.getPlane()) : null;
			if (known)
			{
				tracker = captureService.groundItems(client, nativeView, tile.getPlane());
				tracker.beginScope(key == null ? currentSceneKey : key);
			}
			com.tickreplaylogger.capture.GroundItemTracker reader = tracker == null
				? new com.tickreplaylogger.capture.GroundItemTracker()
				: tracker;
			com.tickreplaylogger.model.world.GroundItemState state = reader.observation(tile, item,
				known ? nativeView.getId() : null);
			values.put("item", known || state == null ? state : state.toBuilder().itemKey(null).build());
			values.put("scopeKnown", known);
			values.put("nativeSceneKey", key);
		}
		catch (RuntimeException error)
		{
			values.put("captureStatus", "PARTIAL_GROUND_ITEM_OBSERVATION");
			values.put("contextErrorClass", error.getClass().getSimpleName());
			try
			{
				values.put("id", item.getId());
			}
			catch (RuntimeException unavailable)
			{
			}
			try
			{
				values.put("quantity", item.getQuantity());
			}
			catch (RuntimeException unavailable)
			{
			}
		}
		finally
		{
			observe(type, null, null, values, context);
			if ("GROUND_ITEM_DESPAWNED".equals(type) && tracker != null)
				tracker.despawned(item);
		}
	}

	@Subscribe
	public void onItemSpawned(net.runelite.api.events.ItemSpawned e)
	{
		observeGroundItem("GROUND_ITEM_SPAWNED", e.getTile(), e.getItem(), null, null);
	}

	@Subscribe
	public void onItemDespawned(net.runelite.api.events.ItemDespawned e)
	{
		observeGroundItem("GROUND_ITEM_DESPAWNED", e.getTile(), e.getItem(), null, null);
	}

	@Subscribe
	public void onItemQuantityChanged(net.runelite.api.events.ItemQuantityChanged e)
	{
		observeGroundItem("GROUND_ITEM_QUANTITY_CHANGED", e.getTile(), e.getItem(), e.getOldQuantity(),
			e.getNewQuantity());
	}

	@Subscribe
	public void onGameObjectSpawned(net.runelite.api.events.GameObjectSpawned e)
	{
		observeObject("gameObject", e.getGameObject(), true);
	}

	@Subscribe
	public void onGameObjectDespawned(net.runelite.api.events.GameObjectDespawned e)
	{
		observeObject("gameObject", e.getGameObject(), false);
	}

	@Subscribe
	public void onGroundObjectSpawned(net.runelite.api.events.GroundObjectSpawned e)
	{
		observeObject("groundObject", e.getGroundObject(), true);
	}

	@Subscribe
	public void onGroundObjectDespawned(net.runelite.api.events.GroundObjectDespawned e)
	{
		observeObject("groundObject", e.getGroundObject(), false);
	}

	@Subscribe
	public void onWallObjectSpawned(net.runelite.api.events.WallObjectSpawned e)
	{
		observeObject("wallObject", e.getWallObject(), true);
	}

	@Subscribe
	public void onWallObjectDespawned(net.runelite.api.events.WallObjectDespawned e)
	{
		observeObject("wallObject", e.getWallObject(), false);
	}

	@Subscribe
	public void onDecorativeObjectSpawned(net.runelite.api.events.DecorativeObjectSpawned e)
	{
		observeObject("decorativeObject", e.getDecorativeObject(), true);
	}

	@Subscribe
	public void onDecorativeObjectDespawned(net.runelite.api.events.DecorativeObjectDespawned e)
	{
		observeObject("decorativeObject", e.getDecorativeObject(), false);
	}

	@Subscribe
	public void onGraphicsObjectCreated(net.runelite.api.events.GraphicsObjectCreated event)
	{
		if (captureService == null || !config.captureWorldSnapshots())
			return;
		CallbackContext context = callbackContext();
		if (!context.allowed)
			return;
		net.runelite.api.GraphicsObject graphic = event.getGraphicsObject();
		if (graphic == null)
			return;
		CallbackFieldObservation fields = new CallbackFieldObservation();
		fields.value("graphicObservationId", () -> surfaceObservationIds.id(graphic));
		fields.value("id", graphic::getId);
		Integer plane = fields.value("plane", graphic::getLevel);
		fields.value("startCycle", graphic::getStartCycle);
		fields.value("z", graphic::getZ);
		LocalPoint location = fields.sample("location", graphic::getLocation);
		Integer localViewId = null;
		if (location != null)
		{
			fields.value("localX", location::getX);
			fields.value("localY", location::getY);
			localViewId = fields.value("localWorldViewId", location::getWorldView);
		}
		else
		{
			fields.unavailable("localX", "LOCATION_UNAVAILABLE");
			fields.unavailable("localY", "LOCATION_UNAVAILABLE");
			fields.unavailable("localWorldViewId", "LOCATION_UNAVAILABLE");
		}
		net.runelite.api.WorldView view = fields.sample("worldView", graphic::getWorldView);
		Integer viewId = null;
		if (view != null)
			viewId = fields.value("worldViewId", view::getId);
		else
			fields.unavailable("worldViewId", "WORLD_VIEW_UNAVAILABLE");
		boolean known = false;
		if (context.ready && view != null && viewId != null && plane != null && plane >= 0 && plane < 4)
		{
			fields.value("nativeSceneKey", () ->
			{
				String key = captureService.additionalSceneKey(client, view, plane);
				if (key == null && !preparePrimaryCaptureScope())
					throw new IllegalStateException("primary scene unavailable");
				return key;
			});
			known = fields.known("nativeSceneKey");
		}
		else
			fields.unavailable("nativeSceneKey",
				context.ready ? "NATIVE_SCOPE_UNAVAILABLE" : "WORLD_CONTEXT_UNAVAILABLE");
		fields.put("scopeKnown", known);
		fields.put("positionScopeKnown", known && localViewId != null && localViewId.equals(viewId));
		if (!known)
			fields.put("captureStatus", "PARTIAL_CALLBACK_FIELDS");
		observe("GRAPHICS_OBJECT_CREATED", null, null, fields.data(), context);
	}

	@Subscribe
	public void onGraphicChanged(net.runelite.api.events.GraphicChanged event)
	{
		if (worldRecorder == null || !config.captureWorldSnapshots())
			return;
		Actor actor = event.getActor();
		CallbackContext c = callbackContext();
		if (!c.allowed || actor == null
			|| actor instanceof Player && actor != c.localPlayer && !config.captureOtherPlayers())
			return;
		Map<String, Object> fields = actorDataSnapshot(actor, c);
		observe("ACTOR_GRAPHICS_CHANGED", actor, null, data("actorState", fields, "graphicIds",
			fields.get("graphicIds"), "graphicsKnown", fields.get("graphicsKnown")), c);
	}

	@Subscribe
	public void onOverheadTextChanged(net.runelite.api.events.OverheadTextChanged event)
	{
		if (worldRecorder == null || !config.captureWorldSnapshots())
			return;
		Actor actor = event.getActor();
		CallbackContext c = callbackContext();
		if (!c.allowed || actor == null
			|| actor instanceof Player && actor != c.localPlayer && !config.captureOtherPlayers())
			return;
		Map<String, Object> fields = actorDataSnapshot(actor, c);
		String text = event.getOverheadText();
		if (c.ready && actor instanceof NPC && captureService != null && damageMap != null)
		{
			NPC npc = (NPC) actor;
			Object observedId = fields.get("npcId");
			Integer id = observedId instanceof Number ? ((Number) observedId).intValue() : null;
			boolean nameKnown = !(fields.get("observationIssues") instanceof Map)
				|| !((Map<?, ?>) fields.get("observationIssues")).containsKey("name");
			boolean qualified = id != null && Boolean.TRUE.equals(fields.get("normalWorldContextObserved"))
				&& damageMap.catalog().observedNameCompatible(id, (String) fields.get("name"), nameKnown);
			if (!qualified)
				captureService.clearMechanics(npc);
			else
				try
				{
					preparePrimaryCaptureScope();
					net.runelite.api.WorldView view = npc.getWorldView();
					com.tickreplaylogger.capture.ObservedMechanics mechanics = Boolean.TRUE
						.equals(fields.get("planeKnown"))
							? captureService.mechanics(client, view, ((Number) fields.get("plane")).intValue())
							: captureService.mechanics(client, view);
					mechanics.validateIdentity(damageMap.catalog(), npc, id, true);
					Object animation = fields.get("animationId");
					mechanics.observe(damageMap.catalog(), npc, id, "overheadEquals", text,
						animation instanceof Number ? ((Number) animation).intValue() : -1,
						java.util.Collections.emptyList(), eventTick(), "OVERHEAD_TEXT_CALLBACK");
				}
				catch (RuntimeException unavailable)
				{
					captureService.clearMechanics(npc);
					c.issues.add("overheadPhaseContext: " + unavailable.getClass().getSimpleName());
				}
		}
		observe("OVERHEAD_TEXT_CHANGED", actor, null,
			data("text", text, "textEvidence", "EVENT_PAYLOAD", "actorState", fields), c);
	}

	@Subscribe
	public void onStatChanged(net.runelite.api.events.StatChanged event)
	{
		CallbackFieldObservation fields = new CallbackFieldObservation();
		fields.value("skill", () ->
		{
			Skill skill = event.getSkill();
			return skill == null ? null : skill.name();
		});
		fields.value("level", event::getLevel);
		fields.value("boostedLevel", event::getBoostedLevel);
		fields.value("xp", event::getXp);
		CallbackContext context = callbackContext();
		observe("SKILL_STATE_CHANGED", context.localPlayer, null, fields.data(), context);
	}
}
