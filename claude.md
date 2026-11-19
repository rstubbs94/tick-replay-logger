# Tick Replay Logger Plugin

## Project Overview
RuneLite plugin (v1.0.0) by SpoonedScrub. Records tick-by-tick observations for factual review: what happened and where recorded damage came from. It does not assess player mistakes or teach encounter mechanics. Never assume a client is running; verify process state before making that claim. Do not launch a client until the owner explicitly instructs it.

## Jagex Compliance Rules (MUST FOLLOW)
- Preserve the approved Live View, paused snapshots and manual buffer exports. Do not remove them based on a guessed policy restriction.
- INFORMATIONAL - provides no competitive advantage while alive
- NO AUTOMATION - records only, never takes actions
- NO PREDICTIVE GUIDANCE - do not show next-attack or mechanic advice to users. Preserve supporting internal timing, phase and attribution models needed for recorded-tick reconstruction, with evidence and uncertainty separate from raw observations.
- NO REAL-TIME INDICATORS - doesn't tell you where to stand or what prayer to use

The current repair removes added mechanics/reference content and forecasts while preserving existing factual recording, review and live-journal functionality. Video and exhaustive owner testing are not requirements. Source-description completeness is not a release gate.

---

## Planning & progress (read before working)
Keep documentation and comments limited to this plugin's capture, local analysis and export.
Other product plans, repository locations, hosting details and integrations belong elsewhere.

The death-reconstruction direction is planned in `docs/`:
- `project-plan.md` - plugin capture, attribution, local export and release scope.
- `death-reconstruction-architecture.md` — technical capture/attribution spec.
- `export-schema-v1.3.md` - current factual replay contract (raw observations, journal, both damage directions and complete NPC representations).
- Earlier export documents retain historical design context. Develop against1.3; older exports are best-effort, not compatibility gates.
- **`TODO.md` — the living progress checklist. KEEP IT UPDATED as you work** (tick `[x]`, mark `[~]`,
  add tasks as they surface). It is the source of truth for what's done and what's next.

The combat data map (what the plugin bundles to label damage/effects) lives in the sibling repo
`../osrs-pvm-research/data/damage_map/` (sources, effects, specials, monster variants, hazards).

---

## Architecture

### Package: `com.tickreplaylogger`

```
src/main/java/com/tickreplaylogger/
├── TickReplayLoggerPlugin.java      # Main plugin (~953 lines)
├── TickReplayLoggerConfig.java      # Config interface (~156 lines)
├── model/
│   ├── TickData.java                # Single tick snapshot
│   ├── TickRecording.java           # Complete recording (death or snapshot)
│   ├── DamageEvent.java             # Damage with source/direction
│   ├── PlayerAction.java            # Action event with ActionType enum
│   └── MovementData.java            # Movement intent vs actual
├── ui/
│   ├── TickReplayLoggerPanel.java   # Main panel with CardLayout (~763 lines)
│   ├── LiveTickPanel.java           # Live view tick rendering (~259 lines)
│   ├── RecordingEntryPanel.java     # Expandable recording entry (~558 lines)
│   └── playback/
│       ├── PlaybackPanel.java       # Interactive replay view (~1076 lines)
│       └── StatBoxPanel.java        # Reusable HP/Prayer stat display
└── util/
    ├── TickRecorder.java            # Circular buffer (~253 lines)
    └── RecordingStorage.java        # JSON persistence (~397 lines)
```

### Key Dependencies
- **Lombok** 1.18.30 (@Data, @Builder, @Slf4j)
- **Gson** for JSON serialization
- **Java 11** target

---

## Core Systems

### Recording Engine (TickReplayLoggerPlugin)
- Circular buffer (configurable, default 100 ticks) recording every GameTick
- Subscribes to: GameTick, ActorDeath, MenuOptionClicked, HitsplatApplied, InteractingChanged, VarbitChanged, GameStateChanged, ConfigChanged
- On player death: snapshots buffer, identifies killer, saves to storage

### What Gets Recorded Per Tick
- **State:** HP, prayer points, active prayers
- **Actions:** food, potions, prayer switches, gear changes, attacks, special attacks
- **Damage:** received (with source attribution) and dealt (with target)
- **Movement:** tiles moved (intent vs actual, cancellation detection)

### Player Action Types (ActionType enum)
EAT_FOOD, DRINK_POTION, PRAYER_ACTIVATED, PRAYER_DEACTIVATED, PRAYER_DRAINED, GEAR_EQUIPPED, GEAR_UNEQUIPPED, ATTACK, SPECIAL_ATTACK

### Damage Types
MELEE, RANGED, MAGIC, TYPELESS, POISON, VENOM, UNKNOWN — determined from hitsplat colors

### Prayer Conflict Group System
Tracks mutually exclusive prayer groups (overhead, defence, attack, strength, ranged, magic) to distinguish manual switches from forced drains. Filters base prayers when upgrades are active (e.g., hides Eagle Eye when Rigour is on).

### Self-Damage Potion Handling
Tracks pending self-damage from divine potions (10hp), overloads (10% HP), smelling salts (10% HP), liquid adrenaline (10% HP) to avoid duplicate hitsplat recording.

---

## Storage (RecordingStorage)
- **deaths.json** — auto-managed recent deaths (pruned by maxDeaths config)
- **saved.json** — manually saved recordings (no limit)
- Location: `~/.runelite/plugin-data/tick-replay-logger/`, through RuneLite's Filepath API.
- RuneLite moves the old `~/.runelite/tick-replay-logger/` directory when the new one is absent.
- Loading, export suppliers and atomic saves share a background queue; `.bak` retains the previous save. Unreadable files without a valid backup are protected from overwriting.
- Supports: rename, delete, save (move death to saved), export as JSON

---

## UI (Three Views via CardLayout)

### 1. Recordings View
- Lists "Recent Deaths" and "Saved Recordings" sections
- Each entry: name, time ago, location, tick count, total damage
- Buttons: Save, Rename, Delete, Copy JSON, Replay, expandable Timeline

### 2. Live View
- Real-time tick journal updating every GameTick
- Status bar, Pause/Resume, Export, Save Snapshot
- Shows last N ticks in reverse chronological order

### 3. Playback View
- Transport controls (first/prev/play-pause/next/last) with 0.5x/1x/2x speed
- HP and Prayer stat bars with change highlighting
- Cumulative damage counters (taken/dealt)
- Active prayers list
- Current tick events section
- Color-coded event log (red=damage received, blue=dealt, green=healing, purple=prayer)
- Keyboard: Space=play/pause, Left/Right=prev/next tick

---

## Config Options
| Setting | Default | Description |
|---------|---------|-------------|
| ticksToRecord | 100 | Circular buffer size |
| maxDeathsToStore | 3 | Auto-pruned recent deaths |
| enableLiveView | true | Toggle live tick journal |
| trackMovement | true | Record player movement |
| trackDamageReceived | true | Record damage taken |
| trackDamageDealt | true | Record damage dealt |
| trackFood | true | Record eating/drinking |
| trackPrayers | true | Record prayer changes |
| trackGear | true | Record equipment changes |
| trackAttacks | true | Record attack actions |
| trackSpecialAttacks | true | Record special attacks |

---

## Development Workflow

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-11.0.29.7-hotspot"
gradlew.bat build        # Compile
gradlew.bat run          # Launch RuneLite with plugin in dev mode
```

## Technical Notes
- RuneLite uses Guice for DI (@Inject)
- All event handlers need @Subscribe annotation
- Plugin lifecycle: startUp() / shutDown()
- Game ticks are ~600ms (0.6 seconds)
- Color tags stripped from menu entries: `<col=xxxxx>` pattern
- Damage source attribution: scans active NPCs targeting player, falls back to lastAttackerName, then "Unknown source"
