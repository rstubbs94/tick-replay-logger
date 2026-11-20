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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import com.tickreplaylogger.capture.*;
import com.tickreplaylogger.model.*;
import com.tickreplaylogger.model.world.*;
import com.tickreplaylogger.ui.TickReplayLoggerPanel;
import com.tickreplaylogger.util.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.game.WorldService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.worldhopper.ping.Ping;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;

@Slf4j
@PluginDescriptor(name = "Tick Replay Logger", description = "Records tick-by-tick combat actions for post-death analysis and replay", tags =
{ "combat", "pvm", "learning", "death", "trace", "analysis", "tick", "replay",
	"logger" }, internalName = "tick-replay-logger", legacyDataDirectory = "tick-replay-logger")
public class TickReplayLoggerPlugin extends Plugin
{
	private static final int DEFAULT_TICK_CAPACITY = 50;
	private static final int DEFAULT_MAX_DEATHS = 3;
	private static final Set<MenuAction> PLAYER_MENU_ACTIONS = EnumSet.of(MenuAction.PLAYER_FIRST_OPTION,
		MenuAction.PLAYER_SECOND_OPTION, MenuAction.PLAYER_THIRD_OPTION, MenuAction.PLAYER_FOURTH_OPTION,
		MenuAction.PLAYER_FIFTH_OPTION, MenuAction.PLAYER_SIXTH_OPTION, MenuAction.PLAYER_SEVENTH_OPTION,
		MenuAction.PLAYER_EIGHTH_OPTION, MenuAction.ITEM_USE_ON_PLAYER, MenuAction.WIDGET_TARGET_ON_PLAYER,
		MenuAction.RUNELITE_PLAYER);

	@Inject
	private Client client;

	@Inject
	private TickReplayLoggerConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private WorldService worldService;

	@Inject
	private ScheduledExecutorService executorService;

	@Inject
	private Gson gson;

	@Inject
	private ClientThread clientThread;

	private TickRecorder tickRecorder;
	private RecordingStorage recordingStorage;
	private TickReplayLoggerPanel panel;
	private NavigationButton navButton;

	// --- Rich death-reconstruction capture (WorldSnapshot buffer feeding the versioned export) ---
	private WorldCaptureService captureService;
	private final ObjectHazards.ObservationIdentities surfaceObservationIds = new ObjectHazards.ObservationIdentities();
	private WorldSnapshotRecorder worldRecorder;
	private DamageMap damageMap;
	private SourceResolutionWorker sourceWorker;
	private long sourceEvidenceRevision;
	// Attributed NPC id/spotanim bookkeeping for labelling + effect dedupe.
	private final Set<Integer> activeEffectSpotanims = new HashSet<>();
	private Integer lastEffectGraphicSampleTick;
	private long lastEffectGraphicSampleEpoch = Long.MIN_VALUE;
	private final List<ProjectileState> projectilesThisTick = new ArrayList<>();
	private final IdentityHashMap<Projectile, Long> projectileIds = new IdentityHashMap<>();
	private final IdentityHashMap<Projectile, Integer> projectileSpawnTicks = new IdentityHashMap<>();
	private final IdentityHashMap<Projectile, LocalPoint> projectileTargets = new IdentityHashMap<>();
	private final IdentityHashMap<Projectile, ProjectileState> projectileCallbackStates = new IdentityHashMap<>();
	private final IdentityHashMap<Projectile, Map<String, Object>> projectileLaunchObservations = new IdentityHashMap<>();
	private final ProjectileSourceTracker projectileSourceTracker = new ProjectileSourceTracker();
	private SourceAssociationNpcIndex sourceAssociationNpcs = new SourceAssociationNpcIndex(null);
	private final Map<Long, PendingProjectile> projectileSourceLinks = new HashMap<>();
	private List<ProjectileState> settledProjectileSamples;
	private long nextProjectileId;
	private long observationSequence;
	private boolean gameTickInProgress;
	private boolean captureSessionObserved;
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

	// Tracks incoming projectiles for correlating with hitsplats
	private final List<PendingProjectile> pendingProjectiles = new ArrayList<>();
	private final List<String> projectileCaptureIssues = new ArrayList<>();
	private final Set<Projectile> processedProjectiles = new HashSet<>();

	// Game tick counter for recency calculations
	private int currentGameTick = 0;

	private final List<PendingHit> pendingHits = new ArrayList<>();
	private final Map<Long, DamageRecord> resourceImpactContexts = new LinkedHashMap<>();
	private static final int MAX_RESOURCE_IMPACTS_PER_TICK = 256;
	private int resourceImpactQueueCount;
	/** Scoped to one callback lookup or deferred batch, restored even if an optional read fails. */
	private NativeHitScope activeHitScope;
	private CallbackContext activeHitCallbackContext;

	@RequiredArgsConstructor
	private static final class NativeHitScope
	{
		final Player localPlayer;
		final WorldView view;
		final Scene scene;
		final Integer viewId, baseX, baseY, plane, width, height;

		boolean known()
		{
			return view != null && scene != null && baseX != null && baseY != null && plane != null && plane >= 0
				&& width != null && width > 0 && height != null && height > 0;
		}
	}

	private NativeHitScope readNativeHitScope(Player localPlayer, WorldView view, Integer originalId,
		boolean idAlreadyObserved, List<String> issues, String prefix)
	{
		if (view == null)
		{
			return new NativeHitScope(localPlayer, null, null, null, null, null, null, null, null);
		}
		Integer id = originalId;
		if (!idAlreadyObserved)
		{
			try
			{
				id = view.getId();
			}
			catch (RuntimeException unavailable)
			{
				/* Raw ID availability does not choose a damage source. */}
		}
		return new NativeHitScope(localPlayer, view, readHitContext(prefix + "Scene", view::getScene, issues), id,
			readHitContext(prefix + "BaseX", view::getBaseX, issues),
			readHitContext(prefix + "BaseY", view::getBaseY, issues),
			readHitContext(prefix + "Plane", view::getPlane, issues),
			readHitContext(prefix + "Width", view::getSizeX, issues),
			readHitContext(prefix + "Height", view::getSizeY, issues));
	}

	private <T> T inNativeHitScope(NativeHitScope scope, Supplier<T> read)
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

	private <T> T inNativeHitScope(NativeHitScope scope, CallbackContext callback, Supplier<T> read)
	{
		CallbackContext previous = activeHitCallbackContext;
		activeHitCallbackContext = callback;
		try
		{
			return inNativeHitScope(scope, read);
		}
		finally
		{
			activeHitCallbackContext = previous;
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
		final List<Map<String, Object>> hazardWitnesses;
		final long epoch;
		final Integer observedCycle;
		final long sequence;
		final PrayerObservation prayerObservation;
		final ActorGraphicsState graphicsObservation;
		final NativeHitScope nativeScope;
		String impactSceneKey;
		final List<String> captureIssues = new ArrayList<>();
		String defenderIdentityEvidence = "CALLBACK_LOCAL_REFERENCE_MATCH";
		boolean hpOutcomeKnown = true;
		Map<String, Object> inventorySourceObservation;
		String defenderName;
		final List<Map<String, Object>> sourceWitnesses = new ArrayList<>();

		PendingHit(int amount, int hitsplatType, DamageEvent.DamageType damageType, List<String> prayers,
			WorldPoint position, NativeHitScope nativeScope, Integer observedCycle, long sequence,
			PrayerObservation prayerObservation, List<Map<String, Object>> hazardWitnesses, long epoch,
			ActorGraphicsState graphicsObservation)
		{
			this.amount = amount;
			this.hitsplatType = hitsplatType;
			this.damageType = damageType;
			this.prayers = new ArrayList<>(prayers);
			this.position = position;
			this.nativeScope = nativeScope;
			this.hazardWitnesses = new ArrayList<>();
			for (Map<String, Object> witness : hazardWitnesses)
			{
				this.hazardWitnesses.add(new LinkedHashMap<>(witness));
			}
			this.epoch = epoch;
			this.observedCycle = observedCycle;
			this.sequence = sequence;
			this.prayerObservation = prayerObservation;
			this.graphicsObservation = graphicsObservation;
		}
	}

	/** Primitive callback facts survive independently of optional enrichment reads. */
	@RequiredArgsConstructor
	private static class CallbackContext
	{
		final long epoch;
		GameState state;
		Player localPlayer;
		Integer cycle;
		boolean allowed, ready;
		final List<String> issues = new ArrayList<>();
		final IdentityHashMap<Actor, ActorCallbackObservation.Sample> actorSamples = new IdentityHashMap<>();
		final IdentityHashMap<Actor, String> actorReferences = new IdentityHashMap<>();
		boolean primaryAttempted, primaryKnown, localViewSampled;
		WorldView primaryView, localView;
		Integer primaryPlane, primaryViewId;
		String primarySceneKey;
	}

	private CallbackContext callbackContext()
	{
		CallbackContext c = new CallbackContext(causalSceneEpoch);
		c.state = readHitContext("callbackGameState", client::getGameState, c.issues);
		if (c.state == GameState.LOGGED_IN)
		{
			captureSessionObserved = true;
		}
		c.ready = c.state == GameState.LOGGED_IN;
		c.allowed = c.ready || captureSessionObserved && (c.state == null
			|| Arrays.asList(GameState.LOADING, GameState.CONNECTION_LOST, GameState.HOPPING, GameState.UNKNOWN)
				.contains(c.state));
		if (!c.allowed)
		{
			return c;
		}
		if (c.ready)
		{
			c.localPlayer = readHitContext("callbackLocalPlayer", client::getLocalPlayer, c.issues);
			if (c.localPlayer == null)
			{
				c.issues.add("callbackLocalPlayer: unavailable");
			}
		}
		else
		{
			c.issues.add("callbackWorldContext: unavailable");
		}
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
		ActorGraphicsState graphicsObservation;
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

	/**
	 * A projectile in flight targeting (or near) the player, awaiting hitsplat correlation.
	 */
	private static class PendingProjectile
	{
		final Projectile observedObject;
		final Long projectileObservationId;
		final Integer estimatedArrivalTick;
		Integer removedObservedTick;
		ProjectileState latestObservation;
		Map<String, Object> launchObservation = Collections.emptyMap();

		PendingProjectile(Projectile projectile, ProjectileState original)
		{
			observedObject = projectile;
			projectileObservationId = original.getObservationId();
			estimatedArrivalTick = original.getEstimatedImpactTick();
			latestObservation = original;
		}
	}

	// --- Telemetry: tick duration and ping ---
	private long lastTickTimeMs = 0;
	private volatile int currentPingMs = -1;
	private ScheduledFuture<?> pingFuture;

	// Packet loss tracking â€” rolling window of recent ping results (true = success, false = timeout)
	private static final int PACKET_LOSS_WINDOW = 10;
	private final LinkedList<Boolean> pingResults = new LinkedList<>();

	// Track player position for movement detection
	private WorldPoint lastPlayerPosition = null;
	private String lastMovementScope = null;

	// Track prayers for detecting forced deactivation (drained/disabled by NPC)
	private List<String> lastTickActivePrayers = new ArrayList<>();
	private boolean lastTickPrayerStateKnown;
	private int lastTickPrayerPoints = 0;
	private boolean lastTickPrayerPointsKnown;
	private final Set<String> manuallyDeactivatedPrayers = new HashSet<>();

	// Mutually exclusive prayer groups (only one can be active at a time)
	// All overhead prayers are mutually exclusive (protection, Retribution, Redemption, Smite)
	private static final Set<String> OVERHEAD_PRAYERS = Set.of("Protect From Melee", "Protect From Missiles",
		"Protect From Magic", "Retribution", "Redemption", "Smite");

	// Defence boosting prayers (conflict with each other)
	private static final Set<String> DEFENCE_PRAYERS = Set.of("Thick Skin", "Rock Skin", "Steel Skin", "Chivalry",
		"Piety", "Rigour", "Augury");

	// Attack boosting prayers (conflict with Chivalry/Piety)
	private static final Set<String> ATTACK_PRAYERS = Set.of("Clarity of Thought", "Improved Reflexes",
		"Incredible Reflexes", "Chivalry", "Piety");

	// Strength boosting prayers (conflict with Chivalry/Piety)
	private static final Set<String> STRENGTH_PRAYERS = Set.of("Burst of Strength", "Superhuman Strength",
		"Ultimate Strength", "Chivalry", "Piety");

	// Ranged boosting prayers (conflict with Rigour)
	private static final Set<String> RANGED_PRAYERS = Set.of("Sharp Eye", "Hawk Eye", "Eagle Eye", "Rigour");

	// Magic boosting prayers (conflict with Augury)
	private static final Set<String> MAGIC_PRAYERS = Set.of("Mystic Will", "Mystic Lore", "Mystic Might", "Augury");

	// All conflict groups for checking switches
	private static final List<Set<String>> PRAYER_CONFLICT_GROUPS = List.of(OVERHEAD_PRAYERS, DEFENCE_PRAYERS,
		ATTACK_PRAYERS, STRENGTH_PRAYERS, RANGED_PRAYERS, MAGIC_PRAYERS);

	// Track special attack energy for detecting spec usage
	private int lastSpecialAttackPercent = -1;

	// Track pending self-damage from potions (divine, overload, etc.)
	// This prevents double-counting when the hitsplat fires on the next tick
	private String pendingSelfDamageSource;
	private final SelfDamageInputs selfDamageInputs = new SelfDamageInputs();
	private Scene selfDamageScopeScene;
	private WorldView selfDamageScopeView;
	private boolean selfDamageScopeKnown;
	private int selfDamageScopeBaseX, selfDamageScopeBaseY, selfDamageScopePlane;
	private long selfDamageScopeEpoch;
	private int pendingSelfDamageTick;
	private int pendingSelfDamageItemId;
	private int pendingSelfDamageSlot;
	private Integer pendingSelfDamageQuantity;
	private long pendingSelfDamageInputSequence;
	private String pendingSelfDamageSourceRef, pendingSelfDamagePageHash;

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
		CaptureBuild.sourceTreeHash();
		tickRecorder = new TickRecorder(config.ticksToRecord());
		recordingStorage = new RecordingStorage(config.maxDeathsToStore(), gson, getPluginDirectory());
		captureService = new WorldCaptureService(surfaceObservationIds);
		damageMap = DamageMap.load(gson);
		sourceWorker = new SourceResolutionWorker(executorService, task -> clientThread.invokeLater(task), gson);
		sourceAssociationNpcs = SourceAssociationNpcIndex.load(gson);
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
		pingFuture = executorService.scheduleWithFixedDelay(this::pingCurrentWorld, 1000, 2000, TimeUnit.MILLISECONDS);

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
			{
				closingRecorder.clear();
			}
			if (closingJournal != null)
			{
				closingJournal.clear();
			}
			if (closingCapture != null)
			{
				closingCapture.resetActors();
			}
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
		{
			releaseCapture.run();
		}
		else
		{
			clientThread.invokeLater(releaseCapture);
		}
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

			WorldResult worldResult = worldService.getWorlds();
			if (worldResult == null)
			{
				return;
			}

			World currentWorld = worldResult.findWorld(client.getWorld());
			if (currentWorld == null)
			{
				return;
			}

			int ping = Ping.ping(currentWorld, true);
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
		{
			captureService.resetEncounterWaveCues();
		}
		if (Arrays.asList(GameState.STARTING, GameState.LOGIN_SCREEN, GameState.LOGIN_SCREEN_AUTHENTICATOR,
			GameState.LOGGING_IN).contains(event.getGameState()))
		{
			captureSessionObserved = false;
		}
		switch (event.getGameState())
		{
		case LOGGING_IN:
			// A genuine login is starting; the next LOGGED_IN is a fresh session
			loginPending = true;
			break;

		case LOADING:
			causalSceneEpoch++;
			if (captureService != null)
			{
				captureService.sceneLoading();
			}
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
			{
				captureService.invalidateActorTiming();
			}
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
	public void onWorldViewLoaded(WorldViewLoaded event)
	{
		worldViewLifecycle("WORLD_VIEW_LOADED", event.getWorldView());
	}

	@Subscribe
	public void onWorldViewUnloaded(WorldViewUnloaded event)
	{
		worldViewLifecycle("WORLD_VIEW_UNLOADED", event.getWorldView());
	}

	private void worldViewLifecycle(String type, WorldView view)
	{
		// A view can reload without a top-level LOADING transition. Earlier causal hypotheses
		// remain observations but cannot establish a hit across this lifetime boundary.
		causalSceneEpoch++;
		CallbackFieldObservation fields = new CallbackFieldObservation();
		if (view == null)
		{
			fields.value("worldViewId", () -> null);
		}
		else
		{
			fields.value("worldViewId", view::getId);
		}
		String status = "available";
		try
		{
			if (captureService != null)
			{
				captureService.worldViewLifecycle(client, view);
			}
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
		if (sourceWorker != null)
		{
			sourceWorker.reset();
		}
		sourceEvidenceRevision++;
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
		projectileLaunchObservations.clear();
		projectileSourceTracker.reset();
		projectileSourceLinks.clear();
		settledProjectileSamples = null;
		nextProjectileId = 0;
		observationSequence = 0;
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
		{
			captureService.resetActors();
		}
		lastBoostedCombat = null;

		activeEffectSpotanims.clear();
		lastPoisonVarp = -1;
		poisonBaselineObserved = false;
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
				{
					applyConfigChanged(event);
				}
			});
			return;
		}
		applyConfigChanged(event);
	}

	private void applyConfigChanged(ConfigChanged event)
	{
		if (event.getKey().equals("captureWorldSnapshots") && captureService != null)
		{
			captureService.resetEncounterWaveCues();
		}

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
					{
						currentPanel.storageCompleted(error);
					}
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
		{
			return;
		}
		Actor actor = event.getActor();
		if (actor == null)
		{
			return;
		}
		boolean local = actor == c.localPlayer;
		Map<String, Object> actorState = callbackActorData(actor, c);
		ActorCallbackObservation.Sample original = c.actorSamples.get(actor);
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
		{
			pendingDeath = death;
		}
		long sequence = observe("ACTOR_DEATH", actor, null, actorState, c);
		if (local)
		{
			addDeathMark("player", c.cycle, sequence, c.issues, "CALLBACK_LOCAL_REFERENCE_MATCH");
		}
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
		if (worldRecorder == null)
		{
			return;
		}
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
				&& ("HP".equals(hit.surface) || "BLOCK".equals(hit.surface) && hit.amount == 0 || damageMap == null
					|| damageMap.catalog().possibleOutcomeSurfaces(hit.type).contains("HP")))
			{
				PendingHit pending = new PendingHit(hit.amount, hit.type,
					DamageEvent.DamageType
						.fromReference(damageMap == null ? null : damageMap.catalog().hitsplatDamageType(hit.type)),
					Collections.emptyList(), null, null, c.cycle, hit.sequence, PrayerObservation.unavailable(),
					Collections.emptyList(), c.epoch, hit.graphicsObservation);
				pending.captureIssues.addAll(c.issues);
				pending.hpOutcomeKnown = "HP".equals(hit.surface) || "BLOCK".equals(hit.surface) && hit.amount == 0;
				pending.defenderIdentityEvidence = "SETTLED_LOCAL_REFERENCE_MATCH";
				if (!pending.hpOutcomeKnown && resourceImpactQueueCount++ >= MAX_RESOURCE_IMPACTS_PER_TICK)
				{
					tickRecorder
						.addCaptureIssue("resourceImpacts: attribution queue limit; original callback retained");
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
				{
					pendingDeath = death.death;
				}
				addDeathMark("player", death.context.cycle, death.sequence, death.context.issues,
					"SETTLED_LOCAL_REFERENCE_MATCH");
			}
			else
			{
				tickRecorder.markStateUnavailable("callbackIdentity");
			}
		}
		unassignedHits.clear();
		unassignedDeaths.clear();
	}

	private void recordRecipientLink(String type, long sequence)
	{
		if (worldRecorder == null)
		{
			return;
		}
		worldRecorder.addObservationLink(
			ObservedEventLink.builder().tick(currentGameTick).observationSequence(sequence).observationType(type)
				.actorRef("player").resolvedTick(currentGameTick).evidence("SETTLED_LOCAL_REFERENCE_MATCH").build());
	}

	private static boolean distinctFatalSources(List<DamageRecord> candidates)
	{
		if (candidates.size() < 2)
		{
			return false;
		}
		Set<String> sources = new LinkedHashSet<>();
		for (DamageRecord hit : candidates)
		{
			if (hit.getAttackerRef() != null && !hit.getAttackerRef().trim().isEmpty())
			{
				sources.add("actor:" + hit.getAttackerRef());
			}
			else if ("STATUS_HITSPLAT".equals(hit.getAttributionEvidence()))
			{
				sources.add("status:" + hit.getDamageType() + ":" + hit.getAttackerName());
			}
			else
			{
				return true;
			} // Equal display names do not identify a shared actor or causal hit.
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
			{
				continue;
			}
			List<DamageEvent> evs = tickHistory.get(i).getDamageEvents();
			if (evs == null)
			{
				continue;
			}
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
				&& Objects.equals(hit.getSource(), journalCandidates.get(0).getSource()));
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
		if (worldRecorder != null)
		{
			try
			{
				DeathRecordingV2.Meta meta = exportMetadata().exportedAtMs(System.currentTimeMillis())
					.rsn(deathContext.name).accountType(null).world(deathContext.world)
					.worldKnown(deathContext.worldKnown)
					.captureIssues(deathContext.issues.isEmpty() ? null : new ArrayList<>(deathContext.issues))
					.deathTick(currentGameTick).bufferTicks(RecordingLimits.ticks(config.ticksToRecord()))
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
				killerConfidence = "UNKNOWN";
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
				completed = completed.toBuilder()
					.journalTicks(ResolverV1Recording.projectJournalTicks(death.getTicks(), completed)).build();
				DeathRecordingV2.Summary resolved = completed.getSummary();
				return death.toBuilder().richExportJson(gson.toJson(completed))
					.ticks(completed.getJournalTicks() == null ? death.getTicks() : completed.getJournalTicks())
					.killerName(resolved == null ? death.getKillerName() : resolved.getKillerName())
					.killerConfidence(resolved == null ? death.getKillerConfidence() : resolved.getKillerConfidence())
					.killerBasis(resolved == null ? death.getKillerBasis() : resolved.getKillerBasis())
					.fatalSourceResolution(
						resolved == null ? death.getFatalSourceResolution() : resolved.getSourceResolution())
					.build();
			}
			return death;
		}).whenComplete((ignored, error) ->
		{
			if (currentPanel != null)
			{
				currentPanel.storageCompleted(error);
			}
			if (error != null)
			{
				log.error("Could not save the death recording", error);
			}
		});

		log.debug("Death recorded: Killed by {} at {}. {} ticks captured, {} total damage received.", killer,
			locationStr, tickHistory.size(), totalDamage);
	}

	@Subscribe

	public void onInteractingChanged(InteractingChanged event)
	{
		CallbackContext context = callbackContext();
		if (!context.allowed)
		{
			return;
		}
		Actor source = event.getSource(), target = event.getTarget();
		if (source != null && worldRecorder != null
			&& (!(source instanceof Player) || source == context.localPlayer || config.captureOtherPlayers()))
		{
			observe("INTERACTING_CHANGED", source, target,
				data("interaction",
					ActorInteractions.captureEventTarget(target, context.localPlayer, config.captureOtherPlayers(),
						actor -> callbackActorRef(actor, context)),
					"actorState", actorDataSnapshot(source, context)),
				context);
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		CallbackContext context = callbackContext();
		if (!context.allowed)
		{
			return;
		}
		Actor actor = event.getActor();
		Hitsplat hitsplat = event.getHitsplat();
		if (actor == null || hitsplat == null)
		{
			return;
		}
		Player localPlayer = context.localPlayer;

		int damage = hitsplat.getAmount();
		int hitsplatType = hitsplat.getHitsplatType();
		List<String> callbackIssues = context.issues;
		Integer callbackHp = localPlayer == null ? null
			: readHitContext("localHpAtCallback", () -> client.getBoostedSkillLevel(Skill.HITPOINTS), callbackIssues);
		String surface = damageMap == null ? null : damageMap.catalog().hitsplatSurface(hitsplatType);
		List<String> possibleSurfaces = damageMap == null ? Arrays.asList("HP", "UNKNOWN")
			: damageMap.catalog().possibleOutcomeSurfaces(hitsplatType);
		boolean possibleHp = possibleSurfaces.contains("HP");
		boolean ambiguousOutcome = possibleHp && !("HP".equals(surface) || "BLOCK".equals(surface) && damage == 0);
		Map<String, Object> actorState = callbackActorData(actor, context);
		Object graphicValue = actorState.get("graphicsState");
		ActorGraphicsState graphicsAtImpact = graphicValue instanceof ActorGraphicsState
			? (ActorGraphicsState) graphicValue
			: null;
		PrayerObservation callbackPrayer = context.ready && actor == localPlayer
			? PrayerObservation.fromActorState(client, actorState)
			: null;
		if (callbackPrayer != null)
		{
			for (Map.Entry<String, String> issue : callbackPrayer.getObservationIssues().entrySet())
			{
				callbackIssues.add("impactPrayer." + issue.getKey() + ": " + issue.getValue());
			}
		}
		Map<String, Object> inventorySourceObservation = context.ready && localPlayer != null && actor == localPlayer
			&& config.trackDamageReceived() && config.captureWorldSnapshots() && damageMap != null
			&& damageMap.catalog().inventorySourceHints().accepts(hitsplatType, damage)
				? damageMap.catalog().inventorySourceHints().capture(client, eventTick(), context.cycle)
				: null;
		// Static mode qualification belongs to this actual HP callback, not the settled tick.
		// Reuse the inventory capture's identical two scalar reads when it already needed them.
		Object numericContext = null;
		if (context.ready && localPlayer != null && actor == localPlayer && config.trackDamageReceived()
			&& ("HP".equals(surface) || "BLOCK".equals(surface)))
		{
			numericContext = inventorySourceObservation != null
				&& inventorySourceObservation.get("numericContext") instanceof Map
					? inventorySourceObservation.get("numericContext")
					: ObservedInventorySourceHints.captureNumericContext(client, eventTick(), context.cycle);
		}
		long sequence = observe("HITSPLAT", actor, null,
			data("actorState", actorState, "inventorySourceObservation", inventorySourceObservation, "numericContext",
				numericContext, "localPrayerState", callbackPrayer == null ? null : callbackPrayer.toState(),
				"receiverKind", actor instanceof NPC ? "NPC" : actor instanceof Player ? "PLAYER" : "ACTOR",
				"resourceSurface", surface == null ? "UNCLASSIFIED" : surface, "possibleOutcomeSurfaces",
				possibleSurfaces, "attributionCaptureStatus",
				actor != localPlayer ? "RECIPIENT_NOT_LOCAL_AT_CALLBACK"
					: !possibleHp && !("BLOCK".equals(surface) && damage == 0) ? "NON_HP_OUTCOME_REFERENCE"
						: !config.trackDamageReceived() ? "RECEIVED_TRACKING_DISABLED"
							: ambiguousOutcome && resourceImpactQueueCount >= MAX_RESOURCE_IMPACTS_PER_TICK
								? "RESOURCE_IMPACT_QUEUE_LIMIT"
								: "PENDING_SETTLED_CONTEXT",
				"outcomeSurfaceEvidence",
				"UNCLASSIFIED".equals(surface) || surface == null ? "UNCLASSIFIED_API_HITSPLAT"
					: "REFERENCE_OUTCOME_ALTERNATIVES",
				"localHpAtCallback", callbackHp, "captureIssues", new ArrayList<>(callbackIssues), "amount", damage,
				"hitsplatType", hitsplatType, "isMine", hitsplat.isMine(), "isOthers", hitsplat.isOthers(),
				"disappearsOnGameCycle", hitsplat.getDisappearsOnGameCycle(), "renderingKnown", false),
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
		{
			noteUnresolvedImpact(damage, hitsplatType, surface, callbackHp, sequence, context.cycle, callbackIssues);
		}
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
			ActorCallbackObservation.Sample original = context.actorSamples.get(actor);
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
			List<Map<String, Object>> contactWitnesses = new ArrayList<>();
			if (nativeScope.known() && positionScopeKnown)
			{
				readHitContext("impactHazards", () -> inNativeHitScope(nativeScope, context, () ->
				{
					observeHazardsAtReportedPosition(impactPosition, renderedPosition, contactWitnesses);
					return null;
				}), callbackIssues);
			}
			PrayerObservation prayerObservation = callbackPrayer;
			List<String> prayers = prayerObservation == null ? Collections.emptyList()
				: getActivePrayers(prayerObservation);
			WorldView view = nativeScope.view;
			PendingHit pending = new PendingHit(damage, hitsplatType, determineDamageType(hitsplat),
				prayers == null ? Collections.emptyList() : prayers, impactPosition, nativeScope, context.cycle,
				sequence, prayerObservation == null ? PrayerObservation.unavailable() : prayerObservation,
				contactWitnesses, context.epoch, graphicsAtImpact);
			pending.defenderName = actorState.get("name") instanceof String ? (String) actorState.get("name") : null;
			for (Map<String, Object> witness : contactWitnesses)
			{
				if ("NPC_ACTIVITY_OBSERVATION".equals(witness.get("kind")))
				{
					pending.sourceWitnesses.add(witness);
				}
			}
			if (nativeScope.known() && isPvpContext())
			{
				inNativeHitScope(nativeScope, () ->
				{
					for (Player other : nativePlayers())
					{
						if (other != null && other != localPlayer && other.getInteracting() == localPlayer
							&& other.getAnimation() >= 0)
						{
							pending.sourceWitnesses.add(impactActivityWitness(other, context, sequence));
						}
					}
					return null;
				});
			}
			pending.inventorySourceObservation = inventorySourceObservation;
			pending.hpOutcomeKnown = isPlayerDamageType;
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
		CallbackContext context, ActorGraphicsState graphicsAtImpact)
	{
		if (worldRecorder == null)
		{
			return;
		}
		List<String> issues = new ArrayList<>(context.issues);
		ActorCallbackObservation.Sample original = context.actorSamples.get(npc);
		WorldPoint position = context.ready && original != null ? original.world() : null;
		Object observedName = original == null ? null : original.data().get("name");
		String defenderName = context.ready && observedName instanceof String ? (String) observedName : null;
		retainActorSampleIssue(original, "worldPosition", "defenderWorldPosition", issues);
		retainActorSampleIssue(original, "name", "defenderName", issues);
		String defenderRef = context.ready ? readHitContext("defenderRef", () -> callbackActorRef(npc, context), issues)
			: null;
		String ownership = mine ? "LOCAL_PLAYER" : hitsplat.isOthers() ? "OTHER" : "UNKNOWN";
		worldRecorder.addDamage(DamageRecord.builder().direction(mine ? "DEALT" : "NPC_RECEIVED").ownership(ownership)
			.impactTick(eventTick()).launchTick(eventTick()).launchTickEstimated(true).impactContextEpoch(context.epoch)
			.finalizationContextEpoch(causalSceneEpoch).launchStateEvidence("UNAVAILABLE").attackerRef(null)
			.attackerName(null).defenderRef(defenderRef)
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
		if (amount <= 0 || !(surface == null || Arrays
			.asList("SKILL_OR_HP", "HP_OR_COMBAT_RESOURCE", "UNCLASSIFIED", "SANITY", "STACKS", "CONTEXTUAL_RESOURCE")
			.contains(surface) || "HP".equals(surface) && !config.trackDamageReceived()))
		{
			return;
		}
		int tick = eventTick();
		if (unresolvedImpactTick != tick)
		{
			unresolvedImpactTick = tick;
			unresolvedImpactCount = 0;
			unresolvedImpactObservations.clear();
		}
		if (unresolvedImpactCount < Integer.MAX_VALUE)
		{
			unresolvedImpactCount++;
		}
		if (unresolvedImpactObservations.size() < 256)
		{
			unresolvedImpactObservations
				.add(data("sequence", sequence, "tick", tick, "gameCycle", cycle, "hitsplatType", type, "amount",
					amount, "resourceSurface", surface, "localHpAtCallback", hp, "causalSceneEpoch", causalSceneEpoch,
					"captureIssues", issues.isEmpty() ? null : new ArrayList<>(issues)));
		}
	}

	private static <T> T readHitContext(String field, Supplier<T> read, List<String> issues)
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
		{
			return;
		}
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
		if (first && context.ready)
		{
			// Reserve original native identity once; this does not capture scene tiles.
			if (context.localPlayer != null)
			{
				callbackActorData(context.localPlayer, context);
			}
			prepareCallbackCaptureScope(context);
		}
		LocalPoint previous = projectileTargets.get(projectile);
		if (target == null)
		{
			projectileTargets.remove(projectile);
		}
		else
		{
			projectileTargets.put(projectile, target);
		}
		ProjectileObservation sample = sampleProjectile(projectile, eventTick(), "MOVED_CALLBACK", context.localPlayer,
			context.cycle, target);
		ProjectileState state = sample.getState();
		if (first)
		{
			projectileLaunchObservations.put(projectile, observeProjectileLaunch(state, context));
		}
		ProjectileState priorState = projectileCallbackStates.put(projectile, state);
		boolean relationshipChanged = priorState != null
			&& (!Objects.equals(priorState.getSourceRef(), state.getSourceRef())
				|| !Objects.equals(priorState.getTargetRef(), state.getTargetRef())
				|| !Objects.equals(priorState.getSourceActorKnown(), state.getSourceActorKnown())
				|| !Objects.equals(priorState.getTargetActorKnown(), state.getTargetActorKnown())
				|| !Objects.equals(priorState.getTargetsPlayer(), state.getTargetsPlayer())
				|| !Objects.equals(priorState.getSourceNpcId(), state.getSourceNpcId())
				|| !Objects.equals(priorState.getSourceObservedName(), state.getSourceObservedName()));
		boolean detailsChanged = priorState != null
			&& (!Objects.equals(priorState.getSourceWorldViewId(), state.getSourceWorldViewId())
				|| !Objects.equals(priorState.getSourcePlane(), state.getSourcePlane())
				|| !Objects.equals(priorState.getTargetWorldViewId(), state.getTargetWorldViewId())
				|| !Objects.equals(priorState.getTargetActorWorldViewId(), state.getTargetActorWorldViewId())
				|| !Objects.equals(priorState.getTargetLocalWorldViewId(), state.getTargetLocalWorldViewId())
				|| !Objects.equals(priorState.getTargetPlane(), state.getTargetPlane())
				|| !Objects.equals(priorState.getDestinationPlane(), state.getDestinationPlane())
				|| !Objects.equals(priorState.getDestinationKnown(), state.getDestinationKnown())
				|| !Objects.equals(priorState.getDestinationEvidence(), state.getDestinationEvidence())
				|| !Objects.equals(priorState.getEndWorldX(), state.getEndWorldX())
				|| !Objects.equals(priorState.getEndWorldY(), state.getEndWorldY())
				|| !Objects.equals(priorState.getEndCycle(), state.getEndCycle()));
		boolean destinationChanged = !Objects.equals(previous, target)
			|| priorState != null && (!Objects.equals(priorState.getEndWorldX(), state.getEndWorldX())
				|| !Objects.equals(priorState.getEndWorldY(), state.getEndWorldY())
				|| !Objects.equals(priorState.getDestinationPlane(), state.getDestinationPlane()));
		if (first || destinationChanged || relationshipChanged || detailsChanged)
		{
			projectilesThisTick.add(state);
			observe(
				first ? "PROJECTILE_FIRST_SEEN"
					: relationshipChanged ? "PROJECTILE_RELATIONSHIP_CHANGED"
						: destinationChanged ? "PROJECTILE_TARGET_CHANGED" : "PROJECTILE_RELATIONSHIP_CHANGED",
				sample.getSourceActor(), sample.getTargetActor(),
				projectileCallbackData(projectile, state, target, event.getZ()), context);
		}
		// A late target binding may first identify the local player after the initial visual callback.
		if (!context.ready || !Boolean.TRUE.equals(state.getTargetsPlayer()) || !processedProjectiles.add(projectile))
		{
			return;
		}
		PendingProjectile pending = new PendingProjectile(projectile, state);
		pending.launchObservation = projectileLaunchObservations.getOrDefault(projectile, Collections.emptyMap());
		pendingProjectiles.add(pending);
		projectileSourceLinks.put(state.getObservationId(), pending);
	}

	private Map<String, Object> projectileCallbackData(Projectile projectile, ProjectileState state, LocalPoint target,
		int targetZ)
	{
		Map<String, Object> fields = new LinkedHashMap<>(
			projectileLaunchObservations.getOrDefault(projectile, Collections.emptyMap()));
		fields.putAll(data("projectile", state, "projectileObservationId", state.getObservationId(), "targetZ", targetZ,
			"callbackTargetLocalX", target == null ? null : target.getX(), "callbackTargetLocalY",
			target == null ? null : target.getY()));
		return fields;
	}

	private Map<String, Object> observeProjectileLaunch(ProjectileState original, CallbackContext context)
	{
		Map<String, Object> local = context.ready && context.localPlayer != null
			? callbackActorData(context.localPlayer, context)
			: Collections.emptyMap();
		PrayerObservation prayer = context.ready ? PrayerObservation.fromActorState(client, local)
			: PrayerObservation.unavailable();
		ActorCallbackObservation.Sample sample = context.actorSamples.get(context.localPlayer);
		WorldPoint position = sample == null ? null : sample.world();
		prepareCallbackCaptureScope(context);
		return data("originalProjectileObservation", original, "projectileObservationId", original.getObservationId(),
			"firstSeenTick", original.getObservedTick(), "spawnTick", original.getSpawnTick(), "launchTickBasis",
			"FIRST_OBSERVED", "observedCycle", original.getObservedCycle(), "startCycle", original.getStartCycle(),
			"endCycle", original.getEndCycle(), "prayersAtLaunch",
			prayer.isAvailable() ? getActivePrayers(prayer) : null, "overheadAtLaunch", prayer.getOverhead(),
			"launchPrayerStateKnown", prayer.isFlagsComplete() && prayer.isOverheadResolved(), "launchPositionKnown",
			position != null, "launchX", position == null ? null : position.getX(), "launchY",
			position == null ? null : position.getY(), "launchPlane", position == null ? null : position.getPlane(),
			"launchSceneKey", context.primaryKnown ? context.primarySceneKey : null, "launchWorldViewId",
			sample == null ? null : integerField(sample.data(), "worldViewId"));
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

	private void forgetProjectile(Projectile projectile)
	{
		projectileIds.remove(projectile);
		projectileSpawnTicks.remove(projectile);
		projectileTargets.remove(projectile);
		projectileCallbackStates.remove(projectile);
		projectileLaunchObservations.remove(projectile);
		processedProjectiles.remove(projectile);
		projectileSourceTracker.forget(projectile);
		for (PendingProjectile pending : pendingProjectiles)
		{
			if (pending.observedObject == projectile && pending.removedObservedTick == null)
			{
				pending.removedObservedTick = currentGameTick;
			}
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

	private ProjectileObservation sampleProjectile(Projectile projectile, int tick, String evidence, Player local,
		Integer cycle, LocalPoint callbackTarget)
	{
		boolean includeOthers = config.captureOtherPlayers();
		final ProjectileSourceCandidate[] proximity = new ProjectileSourceCandidate[1];
		ProjectileObservation sample = new ProjectileObservation(projectile, client, local, includeOthers,
			damageMap == null ? null : damageMap.catalog(),
			actor -> actor instanceof Player && actor != local && !includeOthers ? null
				: captureService == null ? null : captureService.actorRef(actor, local),
			(point, excluded) ->
			{
				NPC nearest = findClosestCombatNpcToPoint(point, excluded);
				proximity[0] = nearest == null ? null : captureProximitySource(nearest, local);
				return proximity[0] == null ? null : proximity[0].getName();
			}, projectileIds.get(projectile), projectileSpawnTicks.get(projectile), tick, cycle, evidence,
			callbackTarget, (state, source) ->
			{
				ProjectileState.ProjectileStateBuilder original = state.toBuilder()
					.proximitySourceCandidate(proximity[0]);
				if (projectileSourceTracker.needsOriginalActorMetadata(projectile, state.getObservationId(), source))
				{
					try
					{
						WorldView view = source.getWorldView();
						if (view != null && state.getSourceWorldViewId() != null && state.getSourcePlane() != null)
						{
							String scope = WorldViews.scope(view, state.getSourcePlane(), state.getSourceWorldViewId());
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
		{
			pending.latestObservation = sample.getState();
		}
		return sample;
	}

	private ProjectileSourceCandidate captureProximitySource(NPC npc, Player local)
	{
		List<String> issues = new ArrayList<>();
		String name = readHitContext("proximity.name", npc::getName, issues);
		Integer index = readHitContext("proximity.index", npc::getIndex, issues);
		WorldView view = readHitContext("proximity.view", npc::getWorldView, issues);
		Integer viewId = view == null ? null : readHitContext("proximity.viewId", view::getId, issues);
		WorldPoint position = readHitContext("proximity.position", npc::getWorldLocation, issues);
		Integer level = readHitContext("proximity.level", npc::getCombatLevel, issues);
		Integer animation = readHitContext("proximity.animation", npc::getAnimation, issues);
		Map<String, String> definitionIssues = new LinkedHashMap<>();
		NpcDefinitionObservation definition = NpcDefinitionObservation.capture(npc, definitionIssues, "proximity.");
		issues.addAll(definitionIssues.keySet());
		NpcOverrides overrides = readHitContext("proximity.overrides", npc::getModelOverrides, issues);
		Boolean normal = issues.stream().anyMatch(issue -> issue.startsWith("proximity.overrides")) ? null
			: readHitContext("proximity.context",
				() -> NpcIdentityContext.normalWorldObservedWithoutOverrides(client, overrides == null), issues);
		String ref = captureService == null ? null
			: readHitContext("proximity.actorRef", () -> captureService.observedActorRef(npc, local, viewId, index),
				issues);
		String sceneKey = null;
		if (view != null && position != null && captureService != null)
		{
			sceneKey = readHitContext("proximity.scene",
				() -> captureService.additionalSceneKey(client, view, position.getPlane()), issues);
		}
		if (sceneKey == null && view != null && position != null && viewId != null)
		{
			String originalScope = readHitContext("proximity.primaryScope",
				() -> WorldViews.scope(view, position.getPlane(), viewId), issues);
			if (originalScope != null && originalScope.equals(sceneScopeSignature))
			{
				sceneKey = currentSceneKey;
			}
		}
		Integer id = definition.getId();
		String family = id == null || damageMap == null ? null
			: damageMap.catalog().attributionFamily(id, name, level, Boolean.TRUE.equals(normal));
		return ProjectileSourceCandidate.builder().name(name).actorRef(ref).npcId(id).baseNpcId(definition.getBaseId())
			.combatLevel(level).animationId(animation)
			.modelOverridesPresent(
				issues.stream().anyMatch(issue -> issue.startsWith("proximity.overrides")) ? null : overrides != null)
			.familyKey(family).normalWorldContextObserved(normal).worldViewId(viewId)
			.plane(position == null ? null : position.getPlane()).sceneKey(sceneKey)
			.evidence("ORIGINAL_PROJECTILE_ORIGIN_PROXIMITY; not API source actor")
			.unavailableFields(issues.isEmpty() ? null : Collections.unmodifiableList(issues)).build();
	}

	private List<ProjectileState> captureProjectiles()
	{
		projectileCaptureIssues.clear();
		Set<Projectile> listed = Collections.newSetFromMap(new IdentityHashMap<>());
		boolean complete = false;
		try
		{
			Iterable<? extends Projectile> collection = client.getProjectiles();
			if (collection == null)
			{
				projectileCaptureIssues.add("projectiles: global collection unavailable");
			}
			else
			{
				for (Projectile projectile : collection)
				{
					if (projectile != null)
					{
						listed.add(projectile);
						registerProjectile(projectile);
					}
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
		{
			states.put(state.getObservationId(), state);
		}
		Player local = readHitContext("projectiles.localPlayer", client::getLocalPlayer, projectileCaptureIssues);
		Integer cycle = readHitContext("projectiles.gameCycle", client::getGameCycle, projectileCaptureIssues);
		Set<Projectile> removed = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Projectile projectile : new ArrayList<>(projectileIds.keySet()))
		{
			if (complete && !listed.contains(projectile))
			{
				removed.add(projectile);
				continue;
			}
			ProjectileObservation sample = sampleProjectile(projectile, currentGameTick, "TICK_SAMPLE", local, cycle,
				null);
			ProjectileState state = sample.getState();
			if (state.getObservationIssues() != null)
			{
				for (Map.Entry<String, String> issue : state.getObservationIssues().entrySet())
				{
					projectileCaptureIssues.add("projectiles: " + issue.getKey() + ": " + issue.getValue());
				}
			}
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
			forgetProjectile(projectile);
		}
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
			boolean hasAttackOption = actions != null && Arrays.asList(actions).contains("Attack");

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
			WorldArea area = null;
			try
			{
				area = npc.getWorldArea();
			}
			catch (RuntimeException unavailable)
			{
				/* retain the known anchor fallback */ }
			if (area != null && area.getPlane() != sourcePoint.getPlane())
			{
				continue;
			}
			int distance = area == null ? npcPoint.distanceTo2D(sourcePoint) : area.distanceTo2D(sourcePoint);
			if (distance < closestDistance)
			{
				closestDistance = distance;
				closest = npc;
				tied = false;
			}
			else if (distance == closestDistance)
			{
				tied = true;
			}
		}

		return tied ? null : closest;
	}

	private void observeObjectSurface(List<Map<String, Object>> witnesses, TileObject object, String kind,
		ObjectHazards.Resolved hazard, LocalPoint contact, int plane, boolean serverTargetKnown)
	{
		if (hazard == null)
		{
			return;
		}
		if (witnesses != null && damageMap.objectHazardRule(hazard.getBaseId()) != null)
		{
			Map<String, Object> witness = new LinkedHashMap<>(data("kind", "OBJECT_SURFACE", "name", hazard.getName(),
				"objectId", hazard.getId(), "baseObjectId", hazard.getBaseId(), "objectKind", kind,
				"objectObservationId", surfaceObservationIds.id(object), "worldViewId", nativeViewId(), "plane", plane,
				"sceneKey", nativeView() != null && nativeScene() != null ? currentSceneKey : null, "scopeEvidence",
				"NATIVE_SCENE_TILE_MEMBERSHIP", "contactWorldX", nativeBaseX() + contact.getSceneX(), "contactWorldY",
				nativeBaseY() + contact.getSceneY(), "state", hazard.getState(), "damaging", hazard.getDamaging(),
				"sourceEligibility", hazard.getSourceEligibility(), "surfaceRuleKey", hazard.getRuleKey(),
				"stateEvidence", hazard.getEvidence(), "mapRef", hazard.getMapRef(), "observedCycle",
				client.getGameCycle(), "observationStage",
				gameTickInProgress ? "DEFERRED_RESOLUTION" : "HITSPLAT_CALLBACK", "defenderPositionEvidence",
				serverTargetKnown ? "SERVER_REPORTED_TILE" : "CLIENT_RENDER_FALLBACK"));
			DamageMap.ObjectHazardRule surfaceRule = damageMap.objectHazardRule(hazard.getBaseId());
			if (surfaceRule != null && ("CONDITIONAL_HP_OBJECT_FOOTPRINT".equals(surfaceRule.getSurfaceOperator())
				|| "CONDITIONAL_HP_TILE".equals(surfaceRule.getSurfaceOperator())))
			{
				CallbackFieldObservation scope = new CallbackFieldObservation();
				WorldView own = scope.sample("worldView", object::getWorldView);
				Integer ownId = own == null ? null : scope.value("worldViewId", own::getId);
				Integer ownPlane = scope.value("plane", object::getPlane);
				witness.put("objectNativeScopeKnown",
					ownId != null && ownId >= 0 && ownPlane != null && ownPlane >= 0 && ownPlane <= 3);
				witness.put("objectWorldViewId", ownId);
				witness.put("objectPlane", ownPlane);
				witness.put("objectNativeScopeObservation", scope.data());
			}
			if (!witnesses.contains(witness))
			{
				witnesses.add(Collections.unmodifiableMap(witness));
			}
		}
	}

	/** Server tile may lead the rendered actor during movement; surfaces use tile occupancy. */
	private void observeHazardsAtReportedPosition(WorldPoint reported, LocalPoint rendered,
		List<Map<String, Object>> witnesses)
	{
		if (reported == null)
		{
			observeHazardsOnTile(rendered, nativePlane(), witnesses, false);
			return;
		}
		int x = reported.getX() - nativeBaseX(), y = reported.getY() - nativeBaseY();
		WorldView view = nativeView();
		int width = activeHitScope != null ? activeHitScope.width : view == null ? 104 : view.getSizeX(),
			height = activeHitScope != null ? activeHitScope.height : view == null ? 104 : view.getSizeY();
		// A known tile outside this view cannot be replaced by a different rendered tile.
		if (x < 0 || y < 0 || x >= width || y >= height)
		{
			return;
		}
		observeHazardsOnTile(new LocalPoint(x * 128 + 64, y * 128 + 64), reported.getPlane(), witnesses, true);
	}

	private void observeHazardsOnTile(LocalPoint pl, int plane, List<Map<String, Object>> witnesses,
		boolean serverTargetKnown)
	{
		if (damageMap == null)
		{
			return;
		}
		if (pl == null)
		{
			return;
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
			{
				continue;
			}
			WorldView graphicView = go.getWorldView();
			if (graphicView != null && nativeView() != null && graphicView != nativeView())
			{
				continue;
			}
			Integer graphicViewId = graphicView == null ? null : graphicView.getId();
			int graphicId = go.getId();
			CombatCatalog.GraphicSurface surface = damageMap.catalog().graphicSurface(graphicId);
			String name = damageMap.catalog().damagingGraphicName(graphicId);
			if (witnesses != null)
			{
				Map<String, Object> witness = data("kind", "GRAPHIC_SURFACE", "name", name, "graphicId", graphicId,
					"graphicObservationId", surfaceObservationIds.id(go), "worldViewId", graphicViewId, "plane", plane,
					"sceneKey", graphicView != null && graphicView == nativeView() ? currentSceneKey : null,
					"contactWorldX", nativeBaseX() + pl.getSceneX(), "contactWorldY", nativeBaseY() + pl.getSceneY(),
					"worldX", nativeBaseX() + loc.getSceneX(), "worldY", nativeBaseY() + loc.getSceneY(), "startCycle",
					go.getStartCycle(), "finished", false, "observedCycle", client.getGameCycle(), "state",
					surface == null ? null : surface.getState(), "damaging",
					surface == null ? null : surface.getDamaging(), "sourceEligibility",
					surface == null || surface.getDamaging() == null ? "UNCLASSIFIED_GRAPHIC_SURFACE"
						: surface.getDamaging() ? "ELIGIBLE_REFERENCE_SURFACE" : "NOT_DAMAGE_SURFACE",
					"surfaceRuleKey", surface == null ? null : surface.getRuleKey(), "mapRef",
					surface == null ? null : surface.getMapRef(), "observationStage",
					gameTickInProgress ? "DEFERRED_RESOLUTION" : "HITSPLAT_CALLBACK", "defenderPositionEvidence",
					serverTargetKnown ? "SERVER_REPORTED_TILE" : "CLIENT_RENDER_FALLBACK");
				if (!witnesses.contains(witness))
				{
					witnesses.add(witness);
				}
			}
		}

		// Non-interactive NPCs can be contact hazards without an attack animation or target.
		for (NPC npc : nativeNpcs())
		{
			if (npc == null)
			{
				continue;
			}
			WorldView contactView = npc.getWorldView();
			if (contactView != null && contactView != nativeView())
			{
				continue;
			}
			WorldPoint position = npc.getWorldLocation();
			if (position == null || position.getPlane() != plane)
			{
				continue;
			}
			if (witnesses != null && activeHitCallbackContext != null && !gameTickInProgress
				&& npc.getInteracting() == nativeLocalPlayer() && npc.getAnimation() >= 0)
			{
				witnesses.add(impactActivityWitness(npc, activeHitCallbackContext, observationSequence));
			}
			NPCComposition definition = npc.getTransformedComposition();
			if (definition == null)
			{
				definition = npc.getComposition();
			}
			if (definition == null || definition.getSize() < 1 || definition.getSize() > 64)
			{
				continue;
			}
			int size = definition.getSize(), targetX = nativeBaseX() + pl.getSceneX(),
				targetY = nativeBaseY() + pl.getSceneY();
			// Presence is a recorded source alternative, not contact, activation or an observed attack.
			if (witnesses != null && !gameTickInProgress && sourceAssociationNpcs.contains(definition.getId())
				&& contactView != null && nativeScene() != null)
			{
				Map<String, Object> witness = data("kind", "NPC_EMITTER", "npcId", definition.getId(), "baseNpcId",
					npc.getId(), "actorRef", actorRef(npc), "observedName", npc.getName(), "combatLevel",
					npc.getCombatLevel(), "worldX", position.getX(), "worldY", position.getY(), "plane",
					position.getPlane(), "observedSize", size, "worldViewId", contactView.getId(), "sceneKey",
					currentSceneKey, "scopeEvidence", "NPC_WORLD_VIEW_READ", "observedCycle", client.getGameCycle(),
					"observationStage", "HITSPLAT_CALLBACK", "sourceEligibility", "DECLARED_SOURCE_ASSOCIATION");
				if (!witnesses.contains(witness))
				{
					witnesses.add(witness);
				}
			}
			boolean coherentFootprint = false;
			if (size == 1)
			{
				if (position.getX() != targetX || position.getY() != targetY)
				{
					continue;
				}
			}
			else
			{
				// Require the API area, server anchor and transformed definition to agree; never
				// substitute a rendered footprint lagging behind a known server position.
				WorldArea area = npc.getWorldArea();
				if (area == null || area.getPlane() != plane || area.getX() != position.getX()
					|| area.getY() != position.getY() || area.getWidth() != size || area.getHeight() != size)
				{
					continue;
				}
				if (targetX < area.getX() || targetY < area.getY() || (long) targetX >= ((long) area.getX() + size)
					|| (long) targetY >= ((long) area.getY() + size))
				{
					continue;
				}
				coherentFootprint = true;
			}
			int contactNpcId = observedNpcId(npc), contactCombatLevel = npc.getCombatLevel();
			boolean contactNormalContext = referenceContextObserved(npc);
			String name = damageMap.catalog().npcContactHazardName(contactNpcId, contactCombatLevel,
				contactNormalContext, size, coherentFootprint);
			if (name != null)
			{
				if (witnesses != null)
				{
					int baseNpcId = npc.getId();
					String sourceRef = actorRef(npc), observedName = npc.getName();
					Integer sourceViewId = contactView == null ? null : contactView.getId();
					// This is the source state already read for this exact contact, not a later actor sample.
					Map<String, Object> originalActorState = data("actorKind", "NPC", "npcId", contactNpcId,
						"baseNpcId", baseNpcId, "name", observedName, "combatLevel", contactCombatLevel,
						"normalWorldContextObserved", contactNormalContext, "modelOverridesPresent",
						contactNormalContext ? Boolean.FALSE : null, "worldViewId", sourceViewId, "worldPositionKnown",
						true, "planeKnown", true, "worldX", position.getX(), "worldY", position.getY(), "plane",
						position.getPlane(), "size", size, "worldAreaKnown", coherentFootprint,
						"worldAreaWorldViewKnown", coherentFootprint && sourceViewId != null, "worldAreaWorldViewId",
						coherentFootprint ? sourceViewId : null, "worldAreaEvidence",
						coherentFootprint ? "ACTOR_GET_WORLD_AREA" : null, "worldArea",
						coherentFootprint
							? data("minX", position.getX(), "minY", position.getY(), "maxX",
								(long) position.getX() + size - 1, "maxY", (long) position.getY() + size - 1, "plane",
								position.getPlane())
							: null);
					Map<String, Object> witness = data("name", name, "kind", "NPC_CONTACT", "npcId", contactNpcId,
						"combatLevel", contactCombatLevel, "normalWorldContextObserved", contactNormalContext,
						"originalActorState", originalActorState, "baseNpcId", baseNpcId, "actorRef", sourceRef,
						"observedName", observedName, "worldX", position.getX(), "worldY", position.getY(), "plane",
						position.getPlane(), "observedSize", size, "contactPredicate",
						coherentFootprint ? "COHERENT_SERVER_FOOTPRINT" : "SAME_SERVER_TILE", "footprintWidth", size,
						"footprintHeight", size, "worldViewId", sourceViewId, "sceneKey",
						contactView != null && contactView == nativeView() && nativeScene() != null ? currentSceneKey
							: null,
						"scopeEvidence", "NPC_WORLD_VIEW_READ", "observedCycle", client.getGameCycle(),
						"observationStage", gameTickInProgress ? "DEFERRED_RESOLUTION" : "HITSPLAT_CALLBACK",
						"defenderPositionEvidence",
						serverTargetKnown ? "SERVER_REPORTED_TILE" : "CLIENT_RENDER_FALLBACK", "sourceEligibility",
						"ELIGIBLE_REFERENCE_SURFACE", "damaging", true);
					if (!witnesses.contains(witness))
					{
						witnesses.add(witness);
					}
				}
			}
		}

		// 2) Ground / game objects on the player's exact scene tile.
		{
			Scene scene = nativeScene();
			if (scene != null && scene.getTiles() != null && plane >= 0 && plane < scene.getTiles().length)
			{
				int sx = pl.getSceneX();
				int sy = pl.getSceneY();
				Tile[][] planeTiles = scene.getTiles()[plane];
				if (sx >= 0 && sx < planeTiles.length && sy >= 0 && sy < planeTiles[sx].length)
				{
					Tile tile = planeTiles[sx][sy];
					if (tile != null)
					{
						GroundObject gnd = tile.getGroundObject();
						if (gnd != null)
						{
							ObjectHazards.Resolved hazard = ObjectHazards.resolve(client, gnd, damageMap);
							observeObjectSurface(witnesses, gnd, "groundObject", hazard, pl, plane, serverTargetKnown);
						}
						GameObject[] gos = tile.getGameObjects();
						if (gos != null)
						{
							for (GameObject g : gos)
							{
								if (g != null)
								{
									ObjectHazards.Resolved hazard = ObjectHazards.resolve(client, g, damageMap);
									observeObjectSurface(witnesses, g, "gameObject", hazard, pl, plane,
										serverTargetKnown);
								}
							}
						}
					}
				}
			}
		}
		// Read failures propagate to the per-hit guard. Missing evidence must not select a rival source.
	}

	/** Local actor from the original hit scope, when one is bound. */
	private Player nativeLocalPlayer()
	{
		return activeHitScope == null ? client.getLocalPlayer() : activeHitScope.localPlayer;
	}

	private WorldView nativeView()
	{
		return activeHitScope == null ? WorldViews.active(client) : activeHitScope.view;
	}

	private Integer nativeViewId()
	{
		if (activeHitScope != null)
		{
			return activeHitScope.viewId;
		}
		WorldView view = nativeView();
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

	private Scene nativeScene()
	{
		return activeHitScope != null ? activeHitScope.scene
			: nativeView() == null ? client.getScene() : nativeView().getScene();
	}

	private List<NPC> nativeNpcs()
	{
		List<NPC> out = new ArrayList<>();
		if (activeHitScope != null && activeHitScope.view == null)
		{
			return out;
		}
		Iterable<? extends NPC> actors = nativeView() == null ? client.getNpcs() : nativeView().npcs();
		if (actors != null)
		{
			for (NPC actor : actors)
			{
				out.add(actor);
			}
		}
		return out;
	}

	private List<Player> nativePlayers()
	{
		List<Player> out = new ArrayList<>();
		if (activeHitScope != null && activeHitScope.view == null)
		{
			return out;
		}
		Iterable<? extends Player> actors = nativeView() == null ? client.getPlayers() : nativeView().players();
		if (actors != null)
		{
			for (Player actor : actors)
			{
				out.add(actor);
			}
		}
		return out;
	}

	private Iterable<? extends GraphicsObject> nativeGraphics()
	{
		if (activeHitScope != null && activeHitScope.view == null)
		{
			return Collections.emptyList();
		}
		Iterable<? extends GraphicsObject> graphics = nativeView() == null ? client.getGraphicsObjects()
			: nativeView().getGraphicsObjects();
		return graphics == null ? Collections.emptyList() : graphics;
	}

	private boolean isPvpContext()
	{
		try
		{
			return client.getVarbitValue(Varbits.PVP_SPEC_ORB) == 1;
		}
		catch (RuntimeException unavailable)
		{
			return false;
		}
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
		{
			captureSessionObserved = true;
		}
		else if (state != null && !Arrays
			.asList(GameState.LOADING, GameState.CONNECTION_LOST, GameState.HOPPING, GameState.UNKNOWN).contains(state))
		{
			captureSessionObserved = false;
			return;
		}
		if (!captureSessionObserved)
		{
			return;
		}
		if (state == null && settledIssues.isEmpty())
		{
			settledIssues.add("settledGameState: unavailable");
		}
		Player localPlayer = state == GameState.LOGGED_IN
			? readHitContext("settledLocalPlayer", client::getLocalPlayer, settledIssues)
			: null;
		if (state == GameState.LOGGED_IN && localPlayer == null && settledIssues.isEmpty())
		{
			settledIssues.add("settledLocalPlayer: unavailable");
		}
		currentGameTick++;
		tickRecorder.startNewTick();
		if (worldRecorder != null)
		{
			worldRecorder.advanceTick(currentGameTick);
		}
		Integer apiTick = readHitContext("boundaryApiTickCount", client::getTickCount, settledIssues);
		Integer cycle = readHitContext("boundaryGameCycle", client::getGameCycle, settledIssues);
		tickRecorder.setBoundaryContext(state == null ? null : state.name(), apiTick, cycle);
		settleUnassignedIntake(localPlayer);
		if (localPlayer == null)
		{
			tickRecorder.markStateUnavailable("localPlayer");
			for (PendingHit hit : pendingHits)
			{
				hit.captureIssues.addAll(settledIssues);
			}
		}
		try
		{
			runSettledStage("boundaryTelemetry", this::recordBoundaryTelemetry);
			if (state == GameState.LOGGED_IN)
			{
				captureReadyGameTick(localPlayer, settledIssues);
			}
			else
			{
				captureUnreadyGameTick(
					state == null ? "game state unavailable" : "world state not ready: " + state.name());
			}
		}
		finally
		{
			runSettledStage("resourceImpactContext", this::flushResourceImpactContexts);
			projectilesThisTick.clear();
			for (String issue : settledIssues)
			{
				tickRecorder.addCaptureIssue(issue);
			}
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
		tickRecorder.updateObservedState(null, null, null, null, Collections.emptyList(), false);
		for (PendingHit hit : pendingHits)
		{
			hit.captureIssues.add(issue);
		}
		resolvePendingHits(null);
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
		// Expire old pending projectiles (arrival tick + grace window passed)
		pendingProjectiles
			.removeIf(pp -> pp.estimatedArrivalTick != null ? currentGameTick > pp.estimatedArrivalTick + 2
				: pp.removedObservedTick != null && currentGameTick > pp.removedObservedTick + 2);
		Set<Long> activePendingIds = new HashSet<>();
		for (PendingProjectile candidate : pendingProjectiles)
		{
			activePendingIds.add(candidate.projectileObservationId);
		}
		projectileSourceLinks.keySet().retainAll(activePendingIds);

		// Cleanup is also required when rich world snapshots are disabled.
		runSettledStage("projectileExpiry", () ->
		{
			for (Projectile projectile : new ArrayList<>(projectileIds.keySet()))
			{
				if (projectileExpiryObserved(projectile, "projectileExpiry"))
				{
					forgetProjectile(projectile);
				}
			}
		});

		// Movement failure must not prevent already observed hits from settling.
		try
		{
			WorldPoint currentPosition = localPlayer.getWorldLocation();
			if (currentPosition == null)
			{
				throw new IllegalStateException("position unavailable");
			}
			String movementScope = WorldViews.scope(WorldViews.active(client));
			if (movementScope == null)
			{
				movementScope = "legacy:" + nativeBaseX() + ":" + nativeBaseY() + ":" + nativePlane();
			}
			Integer distance = WorldViews.settledDistance(lastPlayerPosition, currentPosition, lastMovementScope,
				movementScope);
			if (!Objects.equals(lastMovementScope, movementScope))
			{
				lastCaptureWorld = null;
			}
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
		{
			activePrayers = Collections.emptyList();
		}

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
			settledProjectileSamples = new ArrayList<>(projectilesThisTick);
			if (!runSettledStage("projectileSampling", () -> settledProjectileSamples = captureProjectiles()))
			{
				projectileCaptureIssues
					.add("projectiles: settled sampling unavailable; callback observations retained");
			}
		}
		resolvePendingHits(localPlayer);

		// Detect combat effects from UNAMBIGUOUS observed signals only (freeze/bind/teleblock graphics,
		// poison/venom varp, stat-level drops). Knockback and disarm were removed: neither can be told
		// apart from ordinary movement / voluntary-or-death weapon changes, so they produced wrong
		// labels (observed-over-predicted).
		if (config.captureEffects())
		{
			if (!runSettledStage("statEffects", this::detectStatDrains))
			{
				lastBoostedCombat = null;
			}
			if (!runSettledStage("spotEffects", () -> detectEffectSpotanims(localPlayer)))
			{
				activeEffectSpotanims.clear();
			}
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
		if (worldRecorder == null || sourceWorker == null)
		{
			return;
		}
		flushResourceImpactContexts();
		List<DamageRecord> hits = new ArrayList<>(worldRecorder.receivedAtTick(currentGameTick));
		hits.addAll(resourceImpactContexts.values());
		resourceImpactContexts.clear();
		if (hits.isEmpty())
		{
			return;
		}
		WorldSnapshotRecorder recorder = worldRecorder;
		TickRecorder journal = tickRecorder;
		ResolverV1Recording.EvidenceWindow window = recorder.freezeSourceEvidence(currentGameTick, observationSequence,
			++sourceEvidenceRevision, hits);
		sourceWorker.submit(window, hits, (resolved, failure) ->
		{
			if (worldRecorder != recorder || tickRecorder != journal)
			{
				return;
			}
			if (failure != null)
			{
				log.warn("Could not resolve recorded source observations", failure);
				return;
			}
			Map<Integer, Map<Long, DamageRecord>> byTick = new LinkedHashMap<>();
			for (Map.Entry<Long, DamageRecord> result : resolved.entrySet())
			{
				byTick.computeIfAbsent(result.getValue().getImpactTick(), ignored -> new LinkedHashMap<>())
					.put(result.getKey(), result.getValue());
			}
			for (Map.Entry<Integer, Map<Long, DamageRecord>> tick : byTick.entrySet())
			{
				recorder.applyResolvedDamageRecords(tick.getKey(), tick.getValue());
				journal.applyResolvedDamageRecords(tick.getKey(), tick.getValue());
			}
		});
	}

	private void flushResourceImpactContexts()
	{
		if (resourceImpactContexts.isEmpty())
		{
			return;
		}
		Map<Long, Map<String, Object>> enrichments = new LinkedHashMap<>();
		for (DamageRecord context : resourceImpactContexts.values())
		{
			List<String> possible = damageMap == null ? Arrays.asList("HP", "UNKNOWN")
				: damageMap.catalog().possibleOutcomeSurfaces(context.getHitsplatType());
			enrichments.put(context.getObservationSequence(),
				data("attributionContext",
					context.toBuilder().attributionCandidates(null).observedHazardContacts(null).build(),
					"attributionCandidates", context.getAttributionCandidates(), "observedHazardContacts",
					context.getObservedHazardContacts(), "possibleOutcomeSurfaces", possible,
					"attributionCaptureStatus", "RETAINED_IMPACT_CONTEXT", "resourceOutcomeStatus", "UNRESOLVED"));
			tickRecorder.addEffectNote(resourceImpactNote(context.getAmount(), possible, "Unknown"),
				context.getObservationSequence());
		}
		if (worldRecorder != null)
		{
			worldRecorder.enrichHitsplatObservations(currentGameTick, enrichments);
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
		{
			runSettledStage("actorTimingReset", captureService::invalidateActorTiming);
		}
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
		fields.put("actorState", callbackActorData(context.localPlayer, context));
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
		MenuAction menuAction = fields.sample("menuAction", event::getMenuAction);
		fields.put("menuAction", menuAction == null ? null : menuAction.name());
		MenuEntry entry = fields.sample("menuEntry", event::getMenuEntry);
		Actor related = null;
		if (entry != null)
		{
			related = fields.sample("relatedActor", entry::getActor);
		}
		else
		{
			fields.unavailable("relatedActor", "MENU_ENTRY_UNAVAILABLE");
		}
		if (!config.captureOtherPlayers() && (related instanceof Player || PLAYER_MENU_ACTIONS.contains(menuAction))
			&& (related == null || related != context.localPlayer))
		{
			cleanTarget = cleanTarget == null ? null : "Other player";
			fields.put("target", cleanTarget);
			fields.put("identifier", null);
			fields.put("otherPlayerRedacted", true);
		}
		if (related instanceof NPC)
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
			{
				selfDamageInputs.observe(damageMap == null ? null : damageMap.catalog(), itemId, option, eventTick(),
					sequence);
			}
		}
		catch (RuntimeException unavailable)
		{
			tickRecorder.addCaptureIssue("menuSelfDamageContext: " + unavailable.getClass().getSimpleName());
		}
		if (option == null)
		{
			return;
		}

		if (option.toLowerCase(Locale.ROOT).contains("deactivate")
			&& (option + " " + cleanTarget).toLowerCase(Locale.ROOT).replace("-", " ").contains("quick prayer"))
		{
			manuallyDeactivatedPrayers.addAll(lastTickActivePrayers);
		}
		PlayerAction action = null;

		// Eating food
		if (option.equals("Pickpocket"))
		{
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
				JsonObject rule = itemId == null || damageMap == null ? null
					: damageMap.catalog().selfDamageInput(itemId, option);
				if (rule != null)
				{
					pendingSelfDamageSource = cleanTarget;
					pendingSelfDamageTick = eventTick();
					pendingSelfDamageItemId = itemId == null ? -1 : itemId;
					pendingSelfDamageSlot = param0 == null ? -1 : param0;
					pendingSelfDamageQuantity = null;
					pendingSelfDamageInputSequence = sequence;
					pendingSelfDamageSourceRef = rule.has("page") ? "wiki:" + rule.get("page").getAsString() : null;
					pendingSelfDamagePageHash = rule.has("pageHash") ? rule.get("pageHash").getAsString() : null;
					try
					{
						ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
						Item item = inventory == null || pendingSelfDamageSlot < 0 ? null
							: inventory.getItem(pendingSelfDamageSlot);
						if (item != null && item.getId() == pendingSelfDamageItemId)
						{
							pendingSelfDamageQuantity = item.getQuantity();
						}
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
		{
			return;
		}
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
			{
				captureService.resetEncounterWaveCues();
			}
			else if (channel == ChatMessageType.GAMEMESSAGE)
			{
				if (preparePrimaryCaptureScope())
				{
					captureService.observeEncounterWaveCue(client, currentSceneKey, context.state, channel, message,
						eventTick(), context.cycle, sequence);
				}
				else
				{
					captureService.resetEncounterWaveCues();
				}
			}
		}

	}

	/** Reference applicability includes observed name compatibility; raw world context stays separate. */
	private boolean referenceContextObserved(NPC npc)
	{
		try
		{
			int id = observedNpcId(npc);
			return id >= 0 && damageMap != null && damageMap.catalog() != null
				&& NpcIdentityContext.normalWorldObserved(client, npc)
				&& damageMap.catalog().observedNameCompatible(id, npc.getName(), true);
		}
		catch (RuntimeException unavailable)
		{
			return false;
		}
	}

	private int observedNpcId(NPC npc)
	{
		Integer id = NpcDefinitionObservation.capture(npc, new LinkedHashMap<>(), "").getId();
		return id == null ? -1 : id;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		CallbackContext context = callbackContext();
		ObservedVarbitLayout layout = worldRecorder != null && config.captureWorldSnapshots() && context.allowed
			? VarbitLayoutCapture.capture(client, event.getVarbitId())
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
		{
			return;
		}
		// Global values/baselines survive failures of optional actor and scene enrichment.
		try
		{

			// Special attack energy is stored as 0-1000 (divide by 10 for percentage)
			if (previousSpecPercent >= 0 && currentSpecPercent < previousSpecPercent)
			{
				ensureSelfDamageScope();
				ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
				Item weapon = equipment == null ? null : equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
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
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment == null)
		{
			return "Unarmed";
		}

		Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
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
		{
			throw new IllegalStateException("Prayer sample unavailable or unresolved");
		}
		return getActivePrayers(sample);
	}

	private List<String> getActivePrayers(PrayerObservation observation)
	{
		List<String> active = new ArrayList<>();
		Set<Prayer> activePrayerSet = EnumSet.noneOf(Prayer.class);

		// First pass: collect all active prayers
		for (String prayer : observation.getActive())
		{
			activePrayerSet.add(Prayer.valueOf(prayer));
		}

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
			activePrayerSet.removeAll(EnumSet.of(Prayer.EAGLE_EYE, Prayer.HAWK_EYE, Prayer.SHARP_EYE));
		}

		// Magic: Mystic Might < Augury or Mystic Vigour variant
		if (activePrayerSet.contains(Prayer.AUGURY) || hasMysticVigour)
		{
			activePrayerSet.removeAll(EnumSet.of(Prayer.MYSTIC_MIGHT, Prayer.MYSTIC_LORE, Prayer.MYSTIC_WILL));
		}

		// Melee: Steel Skin/Ultimate Strength/Incredible Reflexes < Piety < Chivalry
		if (activePrayerSet.contains(Prayer.PIETY))
		{
			activePrayerSet.remove(Prayer.CHIVALRY);
		}
		if (activePrayerSet.contains(Prayer.PIETY) || activePrayerSet.contains(Prayer.CHIVALRY))
		{
			activePrayerSet
				.removeAll(EnumSet.of(Prayer.ULTIMATE_STRENGTH, Prayer.INCREDIBLE_REFLEXES, Prayer.STEEL_SKIN));
		}

		// Convert to formatted names
		for (Prayer prayer : activePrayerSet)
		{
			active.add(Text.titleCase(prayer));
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
		for (Set<String> group : PRAYER_CONFLICT_GROUPS)
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
	 * Capture the rich per-tick {@link com.tickreplaylogger.model.world.WorldSnapshot} into the
	 * death-reconstruction buffer. Captures the scene collision grid on load (Â§4b) and the full
	 * world (all NPCs/players/projectiles/hazards) each tick. Local only â€” no network.
	 */
	/** Reserve an epoch before callbacks, then populate scene metadata at the settled tick. */
	private boolean prepareCallbackCaptureScope(CallbackContext context)
	{
		if (context.primaryAttempted)
		{
			return context.primaryKnown;
		}
		context.primaryAttempted = true;
		if (worldRecorder == null || captureService == null)
		{
			return false;
		}
		try
		{
			ActorCallbackObservation.Sample local = context.actorSamples.get(context.localPlayer);
			Object issues = local == null ? null : local.data().get("observationIssues");
			if (issues instanceof Map && ((Map<?, ?>) issues).containsKey("worldView"))
			{
				return false;
			}
			context.localViewSampled = true;
			context.localView = local != null ? local.view()
				: context.localPlayer == null ? null : context.localPlayer.getWorldView();
			WorldView active = context.localView;
			if (active == null)
			{
				active = client.getTopLevelWorldView();
			}
			context.primaryView = active;
			context.primaryPlane = active == null ? client.getPlane() : active.getPlane();
			Integer viewId = null;
			if (active != null)
			{
				boolean sampled = false;
				for (ActorCallbackObservation.Sample sample : context.actorSamples.values())
				{
					if (sample.view() == active)
					{
						viewId = integerField(sample.data(), "worldViewId");
						sampled = true;
						break;
					}
				}
				if (!sampled)
				{
					viewId = active.getId();
				}
				if (viewId == null)
				{
					return false;
				}
			}
			context.primaryViewId = viewId;
			String scope = active == null ? client.getBaseX() + ":" + client.getBaseY() + ":" + context.primaryPlane
				: WorldViews.scope(active, context.primaryPlane, viewId);
			if (currentSceneKey == null || !Objects.equals(scope, sceneScopeSignature)
				|| (needSceneCapture && !primarySceneReserved))
			{
				int[] regions = active == null ? client.getMapRegions() : active.getMapRegions();
				boolean instance = active == null ? client.isInInstancedRegion() : active.isInstance();
				currentSceneKey = Integer.toHexString(Arrays.hashCode(regions) ^ (instance ? 1 : 0)) + ":"
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
		if (worldRecorder == null || captureService == null)
		{
			return false;
		}
		try
		{
			WorldView active = WorldViews.active(client);
			String scope = active == null ? nativeBaseX() + ":" + nativeBaseY() + ":" + nativePlane()
				: WorldViews.scope(active);
			if (currentSceneKey == null || !Objects.equals(scope, sceneScopeSignature)
				|| (needSceneCapture && !primarySceneReserved))
			{
				int[] regions = active == null ? client.getMapRegions() : active.getMapRegions();
				boolean instance = active == null ? client.isInInstancedRegion() : active.isInstance();
				int regionHash = Arrays.hashCode(regions) ^ (instance ? 1 : 0);
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
					SceneData metadata = captureService.captureScene(client, currentSceneKey, sceneLoadSeq,
						config.captureSceneCollision());
					worldRecorder.putScene(metadata);
					if (metadata != null)
					{
						needSceneCapture = false;
					}
					else
					{
						snapshotIssues.add("sceneMetadata: unavailable");
					}
				}
				catch (RuntimeException unavailable)
				{
					snapshotIssues.add("sceneMetadata: " + unavailable.getClass().getSimpleName());
				}
				if (!snapshotIssues.isEmpty())
				{
					tickRecorder.markStateUnavailable("sceneMetadata");
					for (String issue : snapshotIssues)
					{
						tickRecorder.addCaptureIssue(issue);
					}
				}
			}

			List<ProjectileState> observedProjectiles = settledProjectileSamples == null ? captureProjectiles()
				: settledProjectileSamples;
			WorldSnapshot snapshot = scopeKnown
				? captureService.captureTick(client, currentGameTick, currentSceneKey, observedProjectiles,
					lastCaptureWorld, config.captureOtherPlayers(), config.captureSceneCollision(),
					config.captureSceneTerrain())
				: captureService.captureUnscopedTick(client, localPlayer, currentGameTick, observedProjectiles,
					config.captureOtherPlayers(), "primary scene scope unavailable");

			if (snapshot != null)
			{
				if (!projectileCaptureIssues.isEmpty() || !snapshotIssues.isEmpty())
				{
					Map<String, String> coverage = new LinkedHashMap<>(snapshot.getCaptureCoverage());
					if (!projectileCaptureIssues.isEmpty())
					{
						coverage.put("projectiles", "partial");
					}
					if (!snapshotIssues.isEmpty())
					{
						coverage.put("sceneMetadata", "unavailable");
					}
					List<String> issues = new ArrayList<>(snapshot.getCaptureIssues());
					issues.addAll(projectileCaptureIssues);
					issues.addAll(snapshotIssues);
					snapshot = snapshot.toBuilder().captureCoverage(coverage).captureIssues(issues).build();
				}
				worldRecorder.addSnapshot(snapshot);
				PlayerState player = snapshot.getPlayer();
				lastCaptureWorld = !Boolean.FALSE.equals(snapshot.getNativeContextKnown()) && player != null
					&& Boolean.TRUE.equals(player.getWorldPositionKnown())
						? new WorldPoint(player.getWorldX(), player.getWorldY(), player.getPlane())
						: null;
			}
			else
			{
				unavailableWorldSnapshot("no snapshot returned");
			}
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
				for (Projectile projectile : new ArrayList<>(projectileIds.keySet()))
				{
					if (projectileExpiryObserved(projectile, "projectileCleanup"))
					{
						forgetProjectile(projectile);
					}
				}
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
			{
				pendingSelfDamageSource = null;
			}
			return;
		}
		List<String> issues = new ArrayList<>();
		WorldView view = localPlayer == null ? null
			: readHitContext("settledImpactWorldView", localPlayer::getWorldView, issues);
		NativeHitScope scope = readNativeHitScope(localPlayer, view, null, false, issues, "settledImpact");
		for (PendingHit hit : pendingHits)
		{
			hit.captureIssues.addAll(issues);
		}
		inNativeHitScope(scope, () ->
		{
			resolvePendingHitsInScope();
			return null;
		});
	}

	private void resolvePendingHitsInScope()
	{
		if (resourceImpactQueueCount > MAX_RESOURCE_IMPACTS_PER_TICK)
		{
			tickRecorder.addCaptureIssue("resourceImpacts: attribution queue limit; original callbacks retained");
		}
		pendingHits.sort(Comparator.comparingLong(hit -> hit.sequence));
		for (PendingHit hit : pendingHits)
		{
			if (hit.hpOutcomeKnown)
			{
				tickRecorder
					.addDamageEvent(DamageEvent.received(hit.amount, hit.damageType, "Unknown", "UNKNOWN", "UNKNOWN")
						.toBuilder().observationSequence(hit.sequence).build());
			}
			buildReceivedDamageRecord(hit);
		}
		pendingHits.clear();
		resourceImpactQueueCount = 0;
		if (currentGameTick - pendingSelfDamageTick > 2)
		{
			pendingSelfDamageSource = null;
		}
	}

	private Map<String, Object> selfConsumptionObservation()
	{
		if (pendingSelfDamageSource == null || pendingSelfDamageItemId <= 0 || pendingSelfDamageSlot < 0
			|| pendingSelfDamageSlot >= 28 || currentGameTick - pendingSelfDamageTick > 2
			|| pendingSelfDamageQuantity == null)
		{
			return null;
		}
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory == null)
		{
			return null;
		}
		Item item = inventory.getItem(pendingSelfDamageSlot);
		int after = item == null || item.getId() != pendingSelfDamageItemId ? 0 : item.getQuantity();
		if (after >= pendingSelfDamageQuantity)
		{
			return null;
		}
		return data("kind", "ITEM_CONSUMPTION_CANDIDATE", "name", pendingSelfDamageSource, "itemId",
			pendingSelfDamageItemId, "slot", pendingSelfDamageSlot, "quantityBefore", pendingSelfDamageQuantity,
			"quantityAfter", after, "observedItemIdAfter", item == null ? null : item.getId(), "inputTick",
			pendingSelfDamageTick, "inputSequence", pendingSelfDamageInputSequence, "sourceRef",
			pendingSelfDamageSourceRef, "pageHash", pendingSelfDamagePageHash, "observedTick", currentGameTick,
			"observedCycle", client.getGameCycle(), "evidence", "OBSERVED_INVENTORY_DECREASE_NOT_CAUSAL_PROOF");
	}

	private void ensureSelfDamageScope()
	{
		Scene currentScene = nativeScene();
		WorldView currentView = nativeView();
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

	/** Called only on user save/pause; buffered observations need no off-thread client API calls. */
	String captureLiveExport(List<TickData> journalTicks)
	{
		return prepareLiveExport(journalTicks).get();
	}

	/** Shared factual provenance for death and manually saved recordings. */
	private DeathRecordingV2.Meta.MetaBuilder exportMetadata()
	{
		return DeathRecordingV2.Meta.builder().recordingId(UUID.randomUUID().toString()).pluginVersion("1.0.0")
			.captureBuildId(CaptureBuild.sourceTreeHash()).runeLiteApiVersion(CaptureBuild.apiVersion())
			.runeLiteArtifactIdentityScope(CaptureBuild.artifactIdentityScope())
			.runeLiteRuntimeVersion(CaptureBuild.runtimeVersion())
			.runeLiteRuntimeReferenceVerification(CaptureBuild.runtimeReferenceVerification())
			.runeLiteCompiledApiVersion(CaptureBuild.compiledApiVersion())
			.runeLiteCompiledApiArtifactSha256(CaptureBuild.compiledApiArtifactSha256())
			.runeLiteCompiledClientArtifactSha256(CaptureBuild.compiledClientArtifactSha256())
			.runeLiteApiArtifactSha256(CaptureBuild.apiArtifactSha256())
			.runeLiteClientArtifactSha256(CaptureBuild.clientArtifactSha256())
			.dataMapVersion(damageMap == null ? "unknown" : damageMap.getVersion())
			.referenceCatalogId(damageMap == null ? null : damageMap.catalog().id());
	}

	/** Freeze finished DTOs now; the returned task performs no RuneLite API reads. */
	private Supplier<String> prepareLiveExport(List<TickData> journalTicks)
	{
		if (journalTicks.isEmpty() || worldRecorder == null)
		{
			return () -> null;
		}
		WorldSnapshotRecorder recorder = worldRecorder;
		int first = journalTicks.get(0).getTickNumber();
		int last = journalTicks.get(journalTicks.size() - 1).getTickNumber();
		DeathRecordingV2.Meta meta = exportMetadata().exportedAtMs(System.currentTimeMillis()).rsn(capturedRsn)
			.world(capturedWorld).snapshotTick(last).bufferTicks(journalTicks.size()).captureSettings(captureSettings())
			.build();
		DeathRecordingV2.Summary summary = DeathRecordingV2.Summary.builder()
			.totalDamageTakenInBuffer(TickRecording.calculateTotalDamage(journalTicks))
			.ticksCaptured(journalTicks.size()).build();
		DeathRecordingV2 frozen = recorder.freezeExport(meta, summary, first, last).toBuilder()
			.journalTicks(new ArrayList<>(journalTicks)).build();
		return () ->
		{
			DeathRecordingV2 completed = recorder.completeExport(frozen);
			return gson.toJson(completed.toBuilder()
				.journalTicks(ResolverV1Recording.projectJournalTicks(journalTicks, completed)).build());
		};
	}

	private void buildReceivedDamageRecord(PendingHit hit)
	{
		if (worldRecorder == null)
		{
			return;
		}
		List<Map<String, Object>> witnesses = new ArrayList<>(hit.sourceWitnesses);
		if (hit.epoch != causalSceneEpoch)
		{
			hit.captureIssues
				.add("impactContextEpoch: CHANGED_BEFORE_FINALIZATION " + hit.epoch + " -> " + causalSceneEpoch);
		}
		if (hit.nativeScope != null && hit.nativeScope.known() && activeHitScope != null && activeHitScope.known()
			&& hit.epoch == causalSceneEpoch && hit.nativeScope.view == nativeView()
			&& hit.nativeScope.scene == nativeScene()
			&& (hit.position == null ? hit.nativeScope.plane : hit.position.getPlane()) == nativePlane()
			&& hit.nativeScope.baseX == nativeBaseX() && hit.nativeScope.baseY == nativeBaseY())
		{
			try
			{
				ensureSelfDamageScope();
				witnesses.addAll(selfDamageInputs.candidates(currentGameTick));
				Map<String, Object> consumption = selfConsumptionObservation();
				if (consumption != null)
				{
					witnesses.add(consumption);
				}
			}
			catch (RuntimeException unavailable)
			{
				hit.captureIssues.add("selfInputObservation: " + unavailable.getClass().getSimpleName());
			}
		}
		for (PendingProjectile projectile : pendingProjectiles)
		{
			if (projectile.latestObservation != null)
			{
				Map<String, Object> witness = new LinkedHashMap<>(projectile.launchObservation);
				witness.put("kind", "PROJECTILE_OBSERVATION");
				witness.put("projectileObservationId", projectile.projectileObservationId);
				witness.put("projectile", projectile.latestObservation);
				witness.put("projectileId", projectile.latestObservation.getProjectileId());
				witness.put("sourceHistory", projectile.latestObservation.getSourceHistory());
				witness.put("name", projectile.latestObservation.getSourceName());
				witness.put("observedTick", projectile.latestObservation.getObservedTick());
				witness.put("observedCycle", projectile.latestObservation.getObservedCycle());
				witnesses.add(Collections.unmodifiableMap(witness));
			}
		}
		if (damageMap != null && hit.inventorySourceObservation != null)
		{
			witnesses.addAll(
				damageMap.catalog().inventorySourceHints().witnesses(hit.inventorySourceObservation, hit.sequence));
		}
		WorldPoint position = hit.position;
		DamageRecord record = DamageRecord.builder().direction(hit.hpOutcomeKnown ? "RECEIVED" : "RESOURCE_IMPACT")
			.defenderRef("player").defenderName(hit.defenderName).defenderIdentityEvidence(hit.defenderIdentityEvidence)
			.captureIssues(hit.captureIssues.isEmpty() ? null : new ArrayList<>(hit.captureIssues))
			.impactTick(currentGameTick).observedCycle(hit.observedCycle).observationSequence(hit.sequence)
			.impactContextEpoch(hit.epoch).finalizationContextEpoch(causalSceneEpoch).amount(hit.amount)
			.hitsplatType(hit.hitsplatType).hitsplatTypeKnown(true).damageType(damageTypeString(hit.damageType))
			.damageTypeEvidence(hit.damageType == DamageEvent.DamageType.UNKNOWN ? "UNKNOWN" : "HITSPLAT_TYPE")
			.damageTypeCandidates(Collections.emptyList())
			.damageRecurrence(typedStatusHit(hit.hitsplatType) ? "UNKNOWN" : null)
			.damageRecurrenceEvidence(typedStatusHit(hit.hitsplatType) ? "HITSPLAT_TYPE_ONLY" : null)
			.attackerName("Unknown").attribution("UNKNOWN").attributionEvidence("UNRESOLVED_OBSERVATIONS")
			.determination("UNKNOWN").launchTick(currentGameTick).launchTickEstimated(true).launchPositionKnown(false)
			.launchPrayerStateKnown(false).launchStateEvidence("UNOBSERVED").impactPositionKnown(position != null)
			.impactSceneKey(hit.impactSceneKey)
			.impactWorldViewId(hit.nativeScope == null ? null : hit.nativeScope.viewId)
			.impactPlane(hit.nativeScope == null ? null : hit.nativeScope.plane)
			.impactX(position == null ? 0 : position.getX()).impactY(position == null ? 0 : position.getY())
			.defenderGraphicsAtImpact(hit.graphicsObservation)
			.defenderPrayerStateAtImpact(hit.prayerObservation.toState())
			.impactPrayerStateKnown(
				hit.prayerObservation.isFlagsComplete() && hit.prayerObservation.isOverheadResolved())
			.prayersAtImpact(hit.prayerObservation.isAvailable() ? hit.prayers : null)
			.overheadAtImpact(hit.prayerObservation.getOverhead()).overheadIconAtImpact(hit.prayerObservation.getIcon())
			.reportedPrayersAtImpact(
				hit.prayerObservation.getFlags().isEmpty() ? null : hit.prayerObservation.getReported())
			.prayerStateConflictAtImpact(
				hit.prayerObservation.isAvailable() ? hit.prayerObservation.isConflict() : null)
			.overheadAtImpactEvidence(hit.prayerObservation.getEvidence())
			.observedHazardContacts(Collections.unmodifiableList(new ArrayList<>(hit.hazardWitnesses)))
			.attributionCandidates(Collections.unmodifiableList(witnesses)).build();
		if (hit.hpOutcomeKnown)
		{
			worldRecorder.addDamage(record);
		}
		else
		{
			resourceImpactContexts.put(hit.sequence, record);
		}
	}

	private static String resourceImpactNote(int amount, List<String> surfaces, String source)
	{
		String outcome = surfaces.contains("SKILL") ? "HP/stat"
			: surfaces.contains("COMBAT_RESOURCE") ? "HP/resource" : "HP/unknown";
		String label = SourceResolutionLabels.usableName(source);
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
		{
			return;
		}
		if (localPlayer == null)
		{
			throw new IllegalStateException("local graphic actor unavailable");
		}
		if (lastEffectGraphicSampleTick == null || lastEffectGraphicSampleTick + 1 != currentGameTick
			|| lastEffectGraphicSampleEpoch != causalSceneEpoch)
		{
			activeEffectSpotanims.clear();
		}
		Set<Integer> present = new HashSet<>();
		List<String> issues = new ArrayList<>();
		boolean collectionKnown = false, primaryKnown = false;
		try
		{
			IterableHashTable<ActorSpotAnim> graphics = localPlayer.getSpotAnims();
			if (graphics != null)
			{
				for (ActorSpotAnim graphic : graphics)
				{
					if (graphic != null)
					{
						present.add(graphic.getId());
					}
				}
				collectionKnown = true;
			}
			else
			{
				issues.add("effectGraphics: active graphic collection unavailable");
			}
		}
		catch (RuntimeException unavailable)
		{
			issues.add("effectGraphics: " + unavailable.getClass().getSimpleName());
		}
		try
		{
			int graphic = localPlayer.getGraphic();
			if (graphic >= 0)
			{
				present.add(graphic);
			}
			primaryKnown = true;
		}
		catch (RuntimeException unavailable)
		{
			issues.add("effectGraphics primary: " + unavailable.getClass().getSimpleName());
		}
		for (String issue : issues)
		{
			tickRecorder.addCaptureIssue(issue);
		}
		if (!collectionKnown || !primaryKnown)
		{
			tickRecorder.markStateUnavailable("effectGraphics");
		}
		if (!collectionKnown && !primaryKnown && present.isEmpty())
		{
			throw new IllegalStateException("local graphics unavailable");
		}
		for (int id : present)
		{
			if (activeEffectSpotanims.contains(id))
			{
				continue;
			}
			JsonObject rule = damageMap.catalog().localGraphicReference(id);
			if (rule.size() == 0)
			{
				continue;
			}
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
		{
			poisonBaselineObserved = false;
		}
		String previousState = damageMap.catalog().localStatusState("poison_venom_client_state", lastPoisonVarp);
		String currentState = damageMap.catalog().localStatusState("poison_venom_client_state", poison);
		if (currentState == null)
		{
			poisonBaselineObserved = false;
			return;
		}
		if (previousState == null)
		{
			poisonBaselineObserved = false;
		}
		if ("inactive".equals(previousState))
		{
			previousState = "clear";
		}
		if ("inactive".equals(currentState))
		{
			currentState = "clear";
		}
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
		return dt.name().toLowerCase(Locale.ROOT);
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
		return actor != null && worldRecorder != null
			&& (!(actor instanceof Player) || actor == nativeLocalPlayer() || config.captureOtherPlayers());
	}

	private String actorRef(Actor actor)
	{
		if (actor == null)
		{
			return null;
		}
		if (actor == nativeLocalPlayer())
		{
			return "player";
		}
		if (actor instanceof Player && !config.captureOtherPlayers())
		{
			return null;
		}
		return captureService == null ? null : captureService.actorRef(actor, nativeLocalPlayer());
	}

	private static Map<String, Object> data(Object... pairs)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			result.put((String) pairs[i], pairs[i + 1]);
		}
		return Collections.unmodifiableMap(result);
	}

	private Map<String, Object> actorDataSnapshot(Actor actor, CallbackContext c)
	{
		if (c.actorSamples.containsKey(actor))
		{
			return c.actorSamples.get(actor).data();
		}
		ActorCallbackObservation.Sample sample = ActorCallbackObservation.captureSample(actor, c.ready ? client : null,
			c.localPlayer, config.captureOtherPlayers(), target -> callbackActorRef(target, c),
			(target, metadata) -> callbackActorRef(target, c, metadata));
		c.actorSamples.put(actor, sample);
		return sample.data();
	}

	private void observeActorCallback(String type, Actor actor)
	{
		observeActorCallback(type, actor, Collections::emptyMap);
	}

	private void observeActorCallback(String type, Actor actor, Supplier<Map<String, Object>> payload)
	{
		if (worldRecorder == null)
		{
			return;
		}
		CallbackContext c = callbackContext();
		if (!c.allowed || actor == null
			|| actor instanceof Player && actor != c.localPlayer && !config.captureOtherPlayers())
		{
			return;
		}
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
		{
			return null;
		}
		if (actor == c.localPlayer)
		{
			return "player";
		}
		if (actor instanceof Player && (c.localPlayer == null || !config.captureOtherPlayers()))
		{
			return null;
		}
		if (c.actorReferences.containsKey(actor))
		{
			return c.actorReferences.get(actor);
		}
		String result = null;
		try
		{
			if (metadata == null && c.actorSamples.containsKey(actor))
			{
				metadata = c.actorSamples.get(actor).data();
			}
			if (captureService != null)
			{
				result = metadata == null ? captureService.actorRef(actor, c.localPlayer)
					: captureService.observedActorRef(actor, c.localPlayer, integerField(metadata, "worldViewId"),
						integerField(metadata, actor instanceof NPC ? "sceneId" : "playerId"));
			}
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

	private static void retainActorSampleIssue(ActorCallbackObservation.Sample sample, String field, String label,
		List<String> issues)
	{
		Object original = sample == null ? null : sample.data().get("observationIssues");
		Object reason = original instanceof Map ? ((Map<?, ?>) original).get(field) : null;
		if (reason instanceof String)
		{
			issues.add(label + ": " + reason);
		}
	}

	@Subscribe
	public void onSoundEffectPlayed(SoundEffectPlayed event)
	{
		if (event == null || worldRecorder == null || !config.captureWorldSnapshots())
		{
			return;
		}
		CallbackContext c = callbackContext();
		if (!c.allowed)
		{
			return;
		}
		observe("SOUND_EFFECT", null, null,
			SoundCallbackObservation.capture(event, c.localPlayer, c.ready, config.captureOtherPlayers(),
				actor -> captureService == null ? null : captureService.existingActorRef(actor, c.localPlayer)),
			c);
	}

	@Subscribe
	public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed event)
	{
		if (event == null || worldRecorder == null || !config.captureWorldSnapshots())
		{
			return;
		}
		CallbackContext c = callbackContext();
		if (!c.allowed)
		{
			return;
		}
		observe("AREA_SOUND_EFFECT", null, null,
			SoundCallbackObservation.capture(event, c.localPlayer, c.ready, config.captureOtherPlayers(),
				actor -> captureService == null ? null : captureService.existingActorRef(actor, c.localPlayer)),
			c);
	}

	private Map<String, Object> callbackActorData(Actor actor, CallbackContext c)
	{
		if (!c.ready || actor instanceof Player && actor != c.localPlayer && !config.captureOtherPlayers())
		{
			return Collections.emptyMap();
		}
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

	/** A pose sampled during an HP callback is an observation, never an observed attack. */
	private Map<String, Object> impactActivityWitness(Actor actor, CallbackContext context, long hitSequence)
	{
		boolean redacted = actor instanceof Player && actor != context.localPlayer && !config.captureOtherPlayers();
		Map<String, Object> state = redacted ? Collections.emptyMap() : callbackActorData(actor, context);
		ActorCallbackObservation.Sample original = context.actorSamples.get(actor);
		Integer viewId = integerField(state, "worldViewId"), plane = integerField(state, "plane");
		boolean nativeScope = original != null && original.view() != null && original.view() == nativeView()
			&& viewId != null && Objects.equals(viewId, nativeViewId()) && Boolean.TRUE.equals(state.get("planeKnown"))
			&& Objects.equals(plane, nativePlane());
		return data("kind", actor instanceof NPC ? "NPC_ACTIVITY_OBSERVATION" : "PLAYER_ACTIVITY_OBSERVATION",
			"actorState", state, "actorRef", redacted ? null : callbackActorRef(actor, context), "name",
			redacted ? "Other player" : state.get("name"), "npcId", state.get("npcId"), "combatLevel",
			state.get("combatLevel"), "animationId", state.get("animationId"), "targetRef", "player", "sceneKey",
			nativeScope ? currentSceneKey : null, "worldViewId", viewId, "plane",
			Boolean.TRUE.equals(state.get("planeKnown")) ? plane : null, "sourceCollectionEvidence",
			"NATIVE_ACTOR_COLLECTION_MEMBERSHIP", "observedTick", eventTick(), "observedCycle", context.cycle,
			"impactSequence", hitSequence, "observationStage", "HITSPLAT_CALLBACK", "evidence",
			"CURRENT_ENGAGED_POSE_NOT_ATTACK_PROOF");
	}

	private long observe(String type, Actor actor, Actor related, Map<String, Object> values)
	{
		return observe(type, actor, related, values, callbackContext());
	}

	private long observe(String type, Actor actor, Actor related, Map<String, Object> values, CallbackContext c)
	{
		long sequence = ++observationSequence;
		if (worldRecorder == null || !c.allowed)
		{
			return sequence;
		}
		boolean privateActor = actor instanceof Player && actor != c.localPlayer && !config.captureOtherPlayers();
		if (privateActor)
		{
			if (c.localPlayer != null || !("HITSPLAT".equals(type) || "ACTOR_DEATH".equals(type)))
			{
				return sequence;
			}
			// Preserve anonymous primitive combat facts when local identity is unavailable, never another player's metadata.
			Map<String, Object> safe = new LinkedHashMap<>();
			for (String key : Arrays.asList("amount", "hitsplatType", "resourceSurface", "isMine", "isOthers",
				"disappearsOnGameCycle", "renderingKnown", "receiverKind"))
			{
				if (values != null && values.containsKey(key))
				{
					safe.put(key, values.get(key));
				}
			}
			values = safe;
			actor = null;
			related = null;
		}
		Map<String, Object> recorded = values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
		recorded.put("causalSceneEpoch", c.epoch);
		String ref = actor == c.localPlayer && actor != null ? "player" : null, name = null, relatedRef = null,
			sceneKey = null;
		List<String> observationOnlyIssues = new ArrayList<>();
		final Actor observedActor = actor, observedRelated = related;
		if (c.ready)
		{
			if (ref == null)
			{
				ref = readHitContext("observationActorRef", () -> callbackActorRef(observedActor, c), c.issues);
			}
			ActorCallbackObservation.Sample original = c.actorSamples.get(actor);
			retainActorSampleIssue(original, "name", "observationActorName", c.issues);
			name = actor == null ? null
				: original == null ? readHitContext("observationActorName", actor::getName, c.issues)
					: (String) original.data().get("name");
			relatedRef = readHitContext("observationRelatedRef", () -> callbackActorRef(observedRelated, c), c.issues);
		}
		values = recorded;
		if (c.ready)
		{
			try
			{
				boolean primaryScopeKnown = prepareCallbackCaptureScope(c);
				if (values != null && damageMap != null)
				{
					values = new LinkedHashMap<>(values);
					CombatCatalog catalog = damageMap.catalog();
					Set<String> candidates = new LinkedHashSet<>();
					Object animation = values.get("animationId");
					if (animation instanceof Number)
					{
						candidates.addAll(catalog.observationReferences("animation", ((Number) animation).intValue()));
					}
					if (type.equals("GRAPHICS_OBJECT_CREATED") && values.get("id") instanceof Number)
					{
						candidates
							.addAll(catalog.observationReferences("graphic", ((Number) values.get("id")).intValue()));
					}
					Object object = values.get("object");
					if (object instanceof ObservedObjectState)
					{
						Integer id = ((ObservedObjectState) object).getId();
						if (id != null)
						{
							candidates.addAll(catalog.observationReferences("object", id));
						}
					}
					if (!candidates.isEmpty())
					{
						values.put("referenceCandidates", new ArrayList<>(candidates));
					}
					if (actor instanceof NPC)
					{
						Object nested = values.get("actorState");
						Map<?, ?> metadata = nested instanceof Map ? (Map<?, ?>) nested : values;
						Integer id = null;
						if (metadata.containsKey("definitionEvidence"))
						{
							Object observedId = metadata.get("npcId");
							if (observedId instanceof Number)
							{
								id = ((Number) observedId).intValue();
							}
						}
						else
						{
							Map<String, String> definitionIssues = new LinkedHashMap<>();
							id = NpcDefinitionObservation.capture((NPC) actor, definitionIssues, "").getId();
							if (!definitionIssues.isEmpty())
							{
								values.put("actorResearchIssues", definitionIssues);
							}
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
				ActorCallbackObservation.Sample original = c.actorSamples.get(actor);
				retainActorSampleIssue(original, "worldPosition", "observationActorWorldPosition", c.issues);
				retainActorSampleIssue(original, "worldView", "observationActorWorldView", c.issues);
				// The ID diagnostic describes raw placement; it adds no damage-attribution guard.
				retainActorSampleIssue(original, "worldViewId", "observationActorWorldViewId", observationOnlyIssues);
				WorldPoint actorPosition = actor == null ? null
					: original == null ? actor.getWorldLocation() : original.world();
				WorldView actorView = actor == null ? null
					: original != null ? original.view()
						: actor == c.localPlayer && c.localViewSampled ? c.localView : actor.getWorldView();
				Integer actorViewId = original != null ? integerField(original.data(), "worldViewId")
					: actorView == null ? null : actorView == c.primaryView ? c.primaryViewId : actorView.getId();
				boolean unknownActorScope = actor != null
					&& (actorPosition == null || actorView == null || actorViewId == null);
				boolean foreignActor = actor != null && (unknownActorScope || actorView != c.primaryView
					|| !Objects.equals(actorPosition.getPlane(), c.primaryPlane));
				if (!(nativeScene instanceof String) && foreignActor && !unknownActorScope && primaryScopeKnown
					&& captureService != null)
				{
					try
					{
						nativeScene = captureService.observedSceneKey(actorView, actorPosition.getPlane(), actorViewId,
							c.primaryView, c.primaryPlane);
					}
					catch (RuntimeException unavailable)
					{
						c.issues.add("observationActorScope: " + unavailable.getClass().getSimpleName());
					}
				}
				sceneKey = nativeScene instanceof String ? (String) nativeScene
					: foreignActor || !primaryScopeKnown || Boolean.FALSE.equals(values.get("scopeKnown")) ? null
						: c.primarySceneKey;
			}
			catch (RuntimeException unavailable)
			{
				c.issues.add("observationContext: " + unavailable.getClass().getSimpleName());
			}
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
		{
			return;
		}
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
	public void onPlayerChanged(PlayerChanged event)
	{
		observeActorCallback("PLAYER_CHANGED", event.getPlayer());
	}

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		CallbackContext context = callbackContext();
		if (!context.allowed)
		{
			return;
		}
		CallbackFieldObservation fields = new CallbackFieldObservation();
		Integer id = fields.value("id", event::getIndex);
		if (id != null)
		{
			fields.value("value", () -> client.getVarcIntValue(id));
		}
		else
		{
			fields.unavailable("value", "ID_UNAVAILABLE");
		}
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
		observeActorCallback("NPC_DESPAWNED", event.getNpc());
		if (captureService != null)
		{
			captureService.clearMechanics(event.getNpc());
		}
		if (captureService != null)
		{
			captureService.forgetActor(event.getNpc());
		}
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event)
	{
		observeActorCallback("PLAYER_DESPAWNED", event.getPlayer());
		if (captureService != null)
		{
			captureService.forgetActor(event.getPlayer());
		}
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned event)
	{
		observeActorCallback("PLAYER_SPAWNED", event.getPlayer());
	}

	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		observeActorCallback("NPC_CHANGED", event.getNpc(), () -> data("oldComposition",
			ActorCallbackObservation.oldComposition(event.getOld()), "oldCompositionProvided", event.getOld() != null));
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		CallbackFieldObservation fields = new CallbackFieldObservation();
		Integer id = fields.value("containerId", event::getContainerId);
		if (id == null || id != InventoryID.INVENTORY.getId() && id != InventoryID.EQUIPMENT.getId())
		{
			return;
		}
		ItemContainer container = fields.sample("itemContainer", event::getItemContainer);
		Item[] items = null;
		if (container != null)
		{
			items = fields.sample("items", () ->
			{
				Item[] value = container.getItems();
				return value == null ? null : value.clone();
			});
		}
		else
		{
			fields.unavailable("items", "ITEM_CONTAINER_UNAVAILABLE");
		}
		fields.put("itemsKnown", items != null);
		if (items == null)
		{
			fields.unavailable("itemIds", "ITEMS_UNAVAILABLE");
			fields.unavailable("quantities", "ITEMS_UNAVAILABLE");
		}
		else
		{
			final Item[] observed = items;
			fields.value("itemIds", () ->
			{
				int[] values = new int[observed.length];
				for (int i = 0; i < values.length; i++)
				{
					values[i] = observed[i] == null ? -1 : observed[i].getId();
				}
				return values;
			});
			fields.value("quantities", () ->
			{
				int[] values = new int[observed.length];
				for (int i = 0; i < values.length; i++)
				{
					values[i] = observed[i] == null ? 0 : observed[i].getQuantity();
				}
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

	/** Reuse the original sampled point; unavailable coordinates remain individually marked. */
	private static Integer observeLocalPosition(CallbackFieldObservation fields, LocalPoint location)
	{
		if (location == null)
		{
			for (String field : Arrays.asList("localX", "localY", "localWorldViewId"))
			{
				fields.unavailable(field, "LOCATION_UNAVAILABLE");
			}
			return null;
		}
		fields.value("localX", location::getX);
		fields.value("localY", location::getY);
		return fields.value("localWorldViewId", location::getWorldView);
	}

	private void observeObject(String kind, TileObject object, boolean spawn)
	{
		if (object == null || captureService == null || !config.captureWorldSnapshots())
		{
			return;
		}
		CallbackContext context = callbackContext();
		if (!context.allowed)
		{
			return;
		}
		if (!context.ready)
		{
			CallbackFieldObservation fields = new CallbackFieldObservation();
			fields.put("kind", kind);
			fields.value("id", object::getId);
			fields.value("plane", object::getPlane);
			LocalPoint location = fields.sample("location", object::getLocalLocation);
			observeLocalPosition(fields, location);
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
			WorldView view = fields.sample("worldView", object::getWorldView);
			if (view != null)
			{
				fields.value("worldViewId", view::getId);
			}
			else
			{
				fields.unavailable("worldViewId", "WORLD_VIEW_UNAVAILABLE");
			}
			fields.put("scopeKnown", false);
			fields.put("positionScopeKnown", false);
			fields.unavailable("nativeSceneKey", "WORLD_CONTEXT_UNAVAILABLE");
			fields.put("callbackMetadataEvidence", "API_REPORTED_WITHOUT_READY_CONTEXT");
			observe(spawn ? "OBJECT_SPAWNED" : "OBJECT_DESPAWNED", null, null, fields.data(), context);
			return;
		}
		preparePrimaryCaptureScope();
		ObjectStateTracker tracker = null;
		Integer plane = null;
		try
		{
			plane = object.getPlane();
			tracker = captureService.objects(client, object.getWorldView(), plane);
			ObservedObjectState state = tracker.observation(client, kind, object);
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
			{
				tracker.despawned(object);
			}
		}
	}

	private void observeGroundItem(String type, Tile tile, TileItem item, Integer oldQuantity, Integer newQuantity)
	{
		if (item == null || captureService == null || !config.captureWorldSnapshots())
		{
			return;
		}
		CallbackContext context = callbackContext();
		if (!context.allowed)
		{
			return;
		}
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
			observeLocalPosition(fields, location);
			fields.put("scopeKnown", false);
			fields.put("positionScopeKnown", false);
			fields.unavailable("nativeSceneKey", "WORLD_CONTEXT_UNAVAILABLE");
			fields.put("callbackMetadataEvidence", "API_REPORTED_WITHOUT_READY_CONTEXT");
			observe(type, null, null, fields.data(), context);
			return;
		}
		if (tile == null)
		{
			return;
		}
		GroundItemTracker tracker = null;
		Map<String, Object> values = new LinkedHashMap<>(
			data("oldQuantity", oldQuantity, "newQuantity", newQuantity, "scopeKnown", false));
		try
		{
			preparePrimaryCaptureScope();
			LocalPoint point = tile.getLocalLocation();
			WorldView nativeView = point == null ? null : client.getWorldView(point.getWorldView());
			if (nativeView == null && point != null && point.getWorldView() == WorldView.TOPLEVEL)
			{
				nativeView = client.getTopLevelWorldView();
			}
			boolean known = WorldViews.containsTile(nativeView, tile);
			String key = known ? captureService.additionalSceneKey(client, nativeView, tile.getPlane()) : null;
			if (known)
			{
				tracker = captureService.groundItems(client, nativeView, tile.getPlane());
				tracker.beginScope(key == null ? currentSceneKey : key);
			}
			GroundItemTracker reader = tracker == null ? new GroundItemTracker() : tracker;
			GroundItemState state = reader.observation(tile, item, known ? nativeView.getId() : null);
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
			{
				tracker.despawned(item);
			}
		}
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned e)
	{
		observeGroundItem("GROUND_ITEM_SPAWNED", e.getTile(), e.getItem(), null, null);
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned e)
	{
		observeGroundItem("GROUND_ITEM_DESPAWNED", e.getTile(), e.getItem(), null, null);
	}

	@Subscribe
	public void onItemQuantityChanged(ItemQuantityChanged e)
	{
		observeGroundItem("GROUND_ITEM_QUANTITY_CHANGED", e.getTile(), e.getItem(), e.getOldQuantity(),
			e.getNewQuantity());
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned e)
	{
		observeObject("gameObject", e.getGameObject(), true);
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned e)
	{
		observeObject("gameObject", e.getGameObject(), false);
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned e)
	{
		observeObject("groundObject", e.getGroundObject(), true);
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned e)
	{
		observeObject("groundObject", e.getGroundObject(), false);
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned e)
	{
		observeObject("wallObject", e.getWallObject(), true);
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned e)
	{
		observeObject("wallObject", e.getWallObject(), false);
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned e)
	{
		observeObject("decorativeObject", e.getDecorativeObject(), true);
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned e)
	{
		observeObject("decorativeObject", e.getDecorativeObject(), false);
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated event)
	{
		if (captureService == null || !config.captureWorldSnapshots())
		{
			return;
		}
		CallbackContext context = callbackContext();
		if (!context.allowed)
		{
			return;
		}
		GraphicsObject graphic = event.getGraphicsObject();
		if (graphic == null)
		{
			return;
		}
		CallbackFieldObservation fields = new CallbackFieldObservation();
		fields.value("graphicObservationId", () -> surfaceObservationIds.id(graphic));
		fields.value("id", graphic::getId);
		Integer plane = fields.value("plane", graphic::getLevel);
		fields.value("startCycle", graphic::getStartCycle);
		fields.value("z", graphic::getZ);
		LocalPoint location = fields.sample("location", graphic::getLocation);
		Integer localViewId = observeLocalPosition(fields, location);
		WorldView view = fields.sample("worldView", graphic::getWorldView);
		Integer viewId = null;
		if (view != null)
		{
			viewId = fields.value("worldViewId", view::getId);
		}
		else
		{
			fields.unavailable("worldViewId", "WORLD_VIEW_UNAVAILABLE");
		}
		boolean known = false;
		if (context.ready && view != null && viewId != null && plane != null && plane >= 0 && plane < 4)
		{
			fields.value("nativeSceneKey", () ->
			{
				String key = captureService.additionalSceneKey(client, view, plane);
				if (key == null && !preparePrimaryCaptureScope())
				{
					throw new IllegalStateException("primary scene unavailable");
				}
				return key;
			});
			known = fields.known("nativeSceneKey");
		}
		else
		{
			fields.unavailable("nativeSceneKey",
				context.ready ? "NATIVE_SCOPE_UNAVAILABLE" : "WORLD_CONTEXT_UNAVAILABLE");
		}
		fields.put("scopeKnown", known);
		fields.put("positionScopeKnown", known && localViewId != null && localViewId.equals(viewId));
		if (!known)
		{
			fields.put("captureStatus", "PARTIAL_CALLBACK_FIELDS");
		}
		observe("GRAPHICS_OBJECT_CREATED", null, null, fields.data(), context);
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		if (worldRecorder == null || !config.captureWorldSnapshots())
		{
			return;
		}
		Actor actor = event.getActor();
		CallbackContext c = callbackContext();
		if (!c.allowed || actor == null
			|| actor instanceof Player && actor != c.localPlayer && !config.captureOtherPlayers())
		{
			return;
		}
		Map<String, Object> fields = actorDataSnapshot(actor, c);
		observe("ACTOR_GRAPHICS_CHANGED", actor, null, data("actorState", fields, "graphicIds",
			fields.get("graphicIds"), "graphicsKnown", fields.get("graphicsKnown")), c);
	}

	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event)
	{
		if (worldRecorder == null || !config.captureWorldSnapshots())
		{
			return;
		}
		Actor actor = event.getActor();
		CallbackContext c = callbackContext();
		if (!c.allowed || actor == null
			|| actor instanceof Player && actor != c.localPlayer && !config.captureOtherPlayers())
		{
			return;
		}
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
			{
				captureService.clearMechanics(npc);
			}
			else
			{
				try
				{
					preparePrimaryCaptureScope();
					WorldView view = npc.getWorldView();
					ObservedMechanics mechanics = Boolean.TRUE.equals(fields.get("planeKnown"))
						? captureService.mechanics(client, view, ((Number) fields.get("plane")).intValue())
						: captureService.mechanics(client, view);
					mechanics.validateIdentity(damageMap.catalog(), npc, id, true);
					Object animation = fields.get("animationId");
					mechanics.observe(damageMap.catalog(), npc, id, "overheadEquals", text,
						animation instanceof Number ? ((Number) animation).intValue() : -1, Collections.emptyList(),
						eventTick(), "OVERHEAD_TEXT_CALLBACK");
				}
				catch (RuntimeException unavailable)
				{
					captureService.clearMechanics(npc);
					c.issues.add("overheadPhaseContext: " + unavailable.getClass().getSimpleName());
				}
			}
		}
		observe("OVERHEAD_TEXT_CHANGED", actor, null,
			data("text", text, "textEvidence", "EVENT_PAYLOAD", "actorState", fields), c);
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
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
