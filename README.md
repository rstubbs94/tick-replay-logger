# Tick Replay Logger

Tick Replay Logger records what happened on each tick before you died, so you
can look back at your damage, prayers, movement and actions. You can also save a
recording without dying using Live View.

This update improves damage-source matching, adds tick timing and ping context,
records more information in JSON exports, and makes the replay easier to read.
See the [changelog](CHANGELOG.md).

## Features

- Automatically record the ticks leading up to a death.
- See your HP, prayer points, active prayers, movement and actions on each tick.
- Review damage taken, damage dealt and where the damage came from.
- See recorded tick intervals and sampled world ping alongside the combat log.
- Step through a recording or play it back at different speeds.
- Save and rename recordings you want to keep.
- Use Live View to watch the recent tick log, pause it and save a snapshot.
- Copy recordings as JSON for later inspection or sharing.

When more than one source fits, the replay can show their names together.
Damage sources still show **Unknown** when there is not enough information
to identify them. Matching uses recorded attacks, projectiles, hit types and
positions together with known attack rules to interpret damage after it happens.
Live View shows the observations being collected. Damage-source matching runs
in the background when a death or manual snapshot is captured.
The plugin records information; it does not control your character or tell you
what to do during a fight.

## Getting started

1. Install **Tick Replay Logger** from RuneLite's Plugin Hub.
2. Open its sidebar panel and play normally.
3. After a death, click **Replay** to watch the recording, or **Timeline** to browse the ticks.
4. Click **Save** to keep a death, **Rename** to give it a name, or **Copy** to export it.
5. To record without dying, open **Live View**, pause it and click **Save Snapshot**.

Use **Copy** on a saved recording to include its full recorded information.
Live View's **Export** button copies just the basic tick log.

Playback supports 0.5x, 1x and 2x speed. Space starts or pauses playback; the left
and right arrow keys move one tick at a time.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| Ticks to Record | 100 | How many recent ticks to keep, from 1 to 1,000 |
| Max Deaths to Store | 3 | How many recent deaths to keep automatically |
| Enable Live View | Enabled | Show the live tick log |
| Capture World Snapshots | Enabled | Record extra information about the player and nearby scene |
| Capture Scene Map | Enabled | Record blocked and walkable tiles |
| Capture Floor Appearance | Enabled | Record floor tiles and changes to their appearance |
| Capture Other Players | Enabled | Record information available about nearby players |
| Capture Combat Effects | Enabled | Record effects such as poison, stuns and changes to stats |
| Track Tick Time | Enabled | Record the time between game ticks as received by the client |
| Track Ping | Enabled | Include the latest sampled ping to the current game world |

You can also choose which actions and events to record. Longer recordings and
more detailed capture use more memory and disk space. Saved recordings are kept
separately from the recent-death limit.

Tick intervals and ping provide context when reviewing a recording. Ping is
sampled periodically; its value on a tick is the latest available sample.

## JSON exports

Exports now include more information about NPCs, positions, animations,
projectiles, objects and floor tiles, alongside the tick log, damage records,
tick intervals and sampled ping.
Large exports are compressed to make them smaller without removing information.
Damage matching, saving and copying run in the background so recording can
continue while a completed capture is processed.

For file-format and decoding details, see [the export documentation](docs/export-schema-v1.3.md).

## Storage and privacy

Recordings stay on your computer. The plugin does not upload them.

- `~/.runelite/plugin-data/tick-replay-logger/deaths.json` contains recent deaths.
- `~/.runelite/plugin-data/tick-replay-logger/saved.json` contains saved recordings.

RuneLite moves the previous `~/.runelite/tick-replay-logger/` folder automatically
when the new folder does not already exist. Saves use buffered UTF-8 writes in the
background and replace files atomically, keeping a `.bak` copy of the previous
successful save. If a file cannot be read and no valid backup exists, the plugin
leaves it untouched.

Exports can include player names, locations, equipment, inventory and recorded
actions. Check what you are sharing before sending a recording to someone else.

## Development

Use Java 11. Run `./gradlew clean test jar` to check and build the plugin, or
`./gradlew run` to start the development client. On Windows, use `.\gradlew.bat`.

The Plugin Hub builds this plugin with its standard Gradle template. The
checked-in reference manifest qualifies supported RuneLite versions; it does
not claim to identify the compiler or build environment. The local `jar` task
builds the plugin, while `shadowJar` builds a separate development client. Use
`-PruneLiteVersion=<version>` to check a specific RuneLite version locally.
