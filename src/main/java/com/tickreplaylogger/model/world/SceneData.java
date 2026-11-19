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

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * Collision/map context for one loaded scene (§4b), with observed geometry retained for
 * local recording analysis. Scene changes have separate observation evidence. Referenced by
 * {@link WorldSnapshot#getSceneKey()}. Collision is stored sparsely — only non-zero
 * {@code CollisionDataFlag} tiles.
 */
@Data
@Builder(toBuilder = true)
public class SceneData
{
	private final String sceneKey; // regionHash:loadSeq
	private final Integer worldViewId;
	private final Boolean instanced;
	private final int[][][] instanceTemplateChunks;
	private final int[] regionIds;
	private final int plane;
	private final int baseX; // world coord of local (0,0)
	private final int baseY;
	private final int width; // 104
	private final int height; // 104

	private final List<CollisionTile> collision; // sparse: non-zero flags only
	private final List<SceneObject> objects; // optional obstacle footprints for visual fidelity

	@Data
	@Builder
	public static class CollisionTile
	{
		private final int x; // scene tile 0..103
		private final int y;
		private final int flag; // CollisionDataFlag bitmask
	}

	@Data
	@Builder
	public static class SceneObject
	{
		private final int id;
		private final int x;
		private final int y;
		private final int sizeX;
		private final int sizeY;
		private final int orientation;
		private final String type; // game | wall | ground | decor
	}
}
