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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("tickreplaylogger")
public interface TickReplayLoggerConfig extends Config
{
	@ConfigSection(name = "General", description = "General recording settings", position = 0)
	String generalSection = "general";

	@ConfigSection(name = "Event Tracking", description = "Toggle which events are recorded", position = 1)
	String trackingSection = "tracking";

	@ConfigSection(name = "Death Reconstruction", description = "Rich per-tick world capture that powers the tick-by-tick death replay. "
		+ "Heavier than the journal; all data stays on this device.", position = 2)
	String reconstructionSection = "reconstruction";

	@ConfigItem(keyName = "ticksToRecord", name = "Ticks to Record", description = "Number of ticks to keep in memory before death", position = 0, section = generalSection)
	@Range(min = 1, max = com.tickreplaylogger.util.RecordingLimits.MAX_TICKS)
	default int ticksToRecord()
	{
		return 100;
	}

	@ConfigItem(keyName = "maxDeathsToStore", name = "Max Deaths to Store", description = "Maximum number of death records to keep in storage", position = 1, section = generalSection)
	default int maxDeathsToStore()
	{
		return 3;
	}

	@ConfigItem(keyName = "enableLiveView", name = "Enable Live View", description = "Allow the optional live tick journal panel (disable for strict post-death review)", position = 2, section = generalSection)
	default boolean enableLiveView()
	{
		return true;
	}

	@ConfigItem(keyName = "collapseUnchangedTicks", name = "Collapse Unchanged Ticks", description = "Display only ticks where something changed (HP, prayer, active prayers, or an event); "
		+ "runs of unchanged ticks collapse into a compact marker. Recording is unaffected — every tick is still saved.", position = 3, section = generalSection)
	default boolean collapseUnchangedTicks()
	{
		return false;
	}

	@ConfigItem(keyName = "trackMovement", name = "Track Movement", description = "Record player movement (tiles traveled)", position = 0, section = trackingSection)
	default boolean trackMovement()
	{
		return true;
	}

	@ConfigItem(keyName = "trackDamageReceived", name = "Track Damage Received", description = "Record damage taken from enemies", position = 1, section = trackingSection)
	default boolean trackDamageReceived()
	{
		return true;
	}

	@ConfigItem(keyName = "trackDamageDealt", name = "Track Damage Dealt", description = "Record damage dealt to enemies", position = 2, section = trackingSection)
	default boolean trackDamageDealt()
	{
		return true;
	}

	@ConfigItem(keyName = "trackFood", name = "Track Food/Potions", description = "Record eating food and drinking potions", position = 3, section = trackingSection)
	default boolean trackFood()
	{
		return true;
	}

	@ConfigItem(keyName = "trackPrayers", name = "Track Prayers", description = "Record prayer activation, deactivation, and drains", position = 4, section = trackingSection)
	default boolean trackPrayers()
	{
		return true;
	}

	@ConfigItem(keyName = "trackGear", name = "Track Gear Switches", description = "Record equipment changes", position = 5, section = trackingSection)
	default boolean trackGear()
	{
		return true;
	}

	@ConfigItem(keyName = "trackAttacks", name = "Track Attacks", description = "Record attack actions on enemies", position = 6, section = trackingSection)
	default boolean trackAttacks()
	{
		return true;
	}

	@ConfigItem(keyName = "trackSpecialAttacks", name = "Track Special Attacks", description = "Record special attack usage", position = 7, section = trackingSection)
	default boolean trackSpecialAttacks()
	{
		return true;
	}

	@ConfigItem(keyName = "trackTickTime", name = "Track Tick Time", description = "Record the time between game ticks reaching the client", position = 8, section = trackingSection)
	default boolean trackTickTime()
	{
		return true;
	}

	@ConfigItem(keyName = "trackPing", name = "Track Ping", description = "Record the latest periodically sampled world ping alongside each tick", position = 9, section = trackingSection)
	default boolean trackPing()
	{
		return true;
	}

	@ConfigItem(keyName = "captureWorldSnapshots", name = "Capture World Snapshots", description = "Master switch for rich per-tick capture (player, NPCs, projectiles, hazards, context) "
		+ "used to rebuild deaths. Turn off to run only the lightweight journal.", position = 0, section = reconstructionSection)
	default boolean captureWorldSnapshots()
	{
		return true;
	}

	@ConfigItem(keyName = "captureSceneCollision", name = "Capture Scene Map", description = "Record the walkable/blocked tile grid and changes each tick so replay retains dynamic barriers.", position = 1, section = reconstructionSection)
	default boolean captureSceneCollision()
	{
		return true;
	}

	@ConfigItem(keyName = "captureSceneTerrain", name = "Capture Floor Appearance", description = "Record floor paint, overlays, textures and tile-model changes for replay. Floor appearance alone does not establish damage.", position = 4, section = reconstructionSection)
	default boolean captureSceneTerrain()
	{
		return true;
	}

	@ConfigItem(keyName = "captureOtherPlayers", name = "Capture Other Players", description = "Record nearby players (position, gear, overhead) for PvP reconstruction. "
		+ "Local only in this version.", position = 2, section = reconstructionSection)
	default boolean captureOtherPlayers()
	{
		return true;
	}

	@ConfigItem(keyName = "captureEffects", name = "Capture Combat Effects", description = "Record non-damaging effects applied to you (freeze/bind, teleblock, stun, "
		+ "knockback, stat/prayer drain, disarm, poison/venom onset).", position = 3, section = reconstructionSection)
	default boolean captureEffects()
	{
		return true;
	}
}
