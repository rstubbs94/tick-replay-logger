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

import java.util.*;
import lombok.*;

/**
 * Rich per-tick world state for death reconstruction (architecture §4). One per game tick in the
 * rolling buffer: the player, every NPC, nearby players, projectiles in flight, hazards, and the
 * variance context. Serialized into the versioned local recording export. Kept as compact
 * objects; all JSON work is deferred to export time (§6).
 */
@Data
@Builder(toBuilder = true)
public class WorldSnapshot
{
	private final int tick;
	private final long wallClockMs;
	private final int gameCycle;
	private final Boolean gameCycleKnown;
	private final Boolean nativeContextKnown; // false: independent actor data retained without a coordinate-frame assignment
	private final String sceneKey; // references the SceneData collision grid active this tick
	private final Integer worldViewId;
	private final int plane;
	// Flat additional native frames, never silently projected into the primary coordinate frame.
	private final List<WorldSnapshot> additionalViews;
	private final SceneData nativeScene; // Additional-view checkpoint metadata; omitted from unchanged exported frames.
	private final List<WorldEntityState> worldEntities;
	private final Map<String, Object> planeCoverage;

	private final PlayerState player;
	private final Map<Integer, ObservedVarbitLayout> varbitLayouts;
	private final List<ObservedStatusState> localStatuses;
	private final List<NpcState> npcs;
	private final List<Integer> npcStateRefs; // Current optional sharing: complete ordered full-state rows, never a delta.
	private final List<OtherPlayerState> players;
	private final List<ProjectileState> projectiles;
	private final List<HazardState> hazards;
	private final Map<String, Object> hazardCoverage;
	private final transient List<GroundItemState> observedGroundItems;
	private final Boolean groundItemBaseline;
	private final List<GroundItemState> groundItemChanges;
	private final List<Long> groundItemRemovals;
	private final Map<String, Object> groundItemCoverage;
	private final Integer apiTickCount;
	private final transient List<ObservedObjectState> observedObjects; // Shared immutable states; compacted to baseline/deltas at export.
	private final transient int[] observedVarps;
	private final transient NumericVariableState observedNumericVariables;
	private final Map<Integer, Integer> clientIntegerBaseline;
	private final Map<Integer, Integer> clientIntegerChanges;
	private final List<Integer> clientIntegerRemovals;
	private final int[] serverVarpBaseline;
	private final Map<Integer, Integer> serverVarpChanges;
	private final String[] varpLongBaseline;
	private final Map<Integer, String> varpLongChanges;
	private final String[] serverVarpLongBaseline;
	private final Map<Integer, String> serverVarpLongChanges;
	private final transient int[][] observedCollision;
	private final transient List<TileSurface> observedTerrain;
	private final Map<String, Object> terrainCoverage;
	private final Boolean terrainBaseline;
	private final List<TileSurface> terrainChanges;
	private final List<String> terrainRemovals;
	private final Boolean collisionBaseline;
	private final Integer collisionWidth;
	private final Integer collisionHeight;
	private final List<SceneData.CollisionTile> collisionChanges;
	private final Boolean objectBaseline;
	private final List<ObservedObjectState> objectChanges;
	private final List<Long> objectRemovals;
	private final int[] varpBaseline;
	private final Map<Integer, Integer> varpChanges;
	private final Map<String, Object> objectCoverage;
	private final Map<String, String> captureCoverage;
	private final List<String> captureIssues;

	private final ContextBlock context;
}
