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
package com.tickreplaylogger.model.world;

import com.tickreplaylogger.model.*;
import java.util.*;
import lombok.*;

/**
 * The versioned local export envelope (export-schema-v1.3.md) written by the plugin.
 * Self-describing recorded combat data carries id→name maps and scene
 * collision alongside the observed combat data.
 */
@Data
@Builder(toBuilder = true)
public class DeathRecordingV2
{
	public static final String LATEST_SCHEMA_VERSION = "1.3";
	private final String schemaVersion; // Latest producer discriminator, independent of NPC sharing.
	private final Meta meta;
	private final Map<String, Map<String, String>> idToName; // npc/item/anim/spotanim/object/projectile
	private final List<SceneData> scenes;
	private final List<WorldSnapshot> ticks;
	private final NpcStateTable npcStateTable; // Optional shared-blocks-v1 representation within current 1.3.
	private final List<TickData> journalTicks; // original journal facts; never display-time relabelled
	private final Events events;
	private final Summary summary;

	@Data
	@Builder(toBuilder = true)
	public static class Meta
	{
		private final String recordingId;
		private final String pluginVersion;
		private final String captureBuildId;
		private final String runeLiteApiVersion;
		private final String runeLiteArtifactIdentityScope;
		private final String runeLiteRuntimeVersion;
		private final String runeLiteRuntimeReferenceVerification;
		private final String runeLiteCompiledApiVersion;
		private final String runeLiteCompiledApiArtifactSha256;
		private final String runeLiteCompiledClientArtifactSha256;
		private final String runeLiteApiArtifactSha256;
		private final String runeLiteClientArtifactSha256;
		private final String dataMapVersion;
		private final String referenceCatalogId;
		private final long exportedAtMs;
		private final String rsn;
		private final String accountType;
		private final int world;
		private final Boolean worldKnown;
		private final List<String> captureIssues;
		private final Integer deathTick; // null for non-death live snapshots
		private final Integer snapshotTick; // final selected tick for a non-death live snapshot
		private final int bufferTicks;
		private final int captureRadius;
		private final CaptureSettings captureSettings;
	}

	@Data
	@Builder
	public static class CaptureSettings
	{
		private final boolean worldSnapshots;
		private final boolean sceneCollision;
		private final Boolean sceneTerrain;
		private final boolean otherPlayers;
		private final boolean effects;
		private final boolean damageReceived;
		private final boolean damageDealt;
		private final int hazardRadiusTiles;
		private final String sceneObjectScope;
		private final String graphicsScope;
		private final String variableMode;
		private final String varbitLayoutScope;
		private final String projectileMode;
		private final String damageAmountMode;
		private final String tickConvention;
	}

	@Data
	@Builder(toBuilder = true)
	public static class Events
	{
		private final List<DamageRecord> damageEvents;
		private final List<EffectEvent> effectEvents;
		private final List<PlayerActionRecord> playerActions;
		private final List<DeathMark> deaths;
		private final List<ObservedEvent> observations;
		private final List<ObservedEventLink> observationLinks;
		private final List<CaptureGap> captureGaps;

		@Data
		@Builder
		public static class CaptureGap
		{
			private final int tick;
			private final String channel;
			private final long omittedObservations;
			private final String reason;
		}

		@Data
		@Builder(toBuilder = true)
		public static class PlayerActionRecord
		{
			private final int tick;
			private final String type; // EAT|DRINK|PRAYER_ON|PRAYER_OFF|GEAR|ATTACK|SPEC|MOVE
			private final String detail;
			private final String target;
			private final String targetRef;
			private final String itemName;
			private final Integer itemId;
			private final Integer gameCycle;
			private final Long sequence;
			private final String observationKind; // INPUT | RESOURCE_CHANGE | DERIVED; not proof a click succeeded
		}

		@Data
		@Builder
		public static class DeathMark
		{
			private final List<String> captureIssues;
			private final String actorIdentityEvidence;
			private final int tick;
			private final String actorRef;
			private final Integer gameCycle;
			private final Long sequence;
		}
	}

	@Data
	@Builder(toBuilder = true)
	public static class Summary
	{
		private final String killerRef;
		private final String killerName;
		private final String killerConfidence;
		private final String killerBasis;
		private final List<Map<String, Object>> unresolvedImpactObservations;
		private final int unresolvedImpactObservationCount;
		private final DamageRecord fatalHit;
		private final SourceResolution sourceResolution;
		private final String fatalHitBasis; // selection basis, not proof of a hidden lethal server roll
		private final List<DamageRecord> fatalHitCandidates; // positive received records in the death tick, in callback order
		private final Boolean reactable;
		private final String activity;
		private final String bossName;
		private final int totalDamageTakenInBuffer;
		private final int totalDamageDealtInBuffer;
		private final int ticksCaptured;
		private final Boolean otherPlayersPresent; // null when the selected window did not capture this channel
	}
}
