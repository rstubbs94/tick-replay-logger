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

import lombok.Builder;
import lombok.Value;

/** Native scene terrain observation. Color/texture presence is not proof of a hazard. */
@Value
@Builder
public class TileSurface
{
	String key;
	int x, y, plane, renderLevel;
	boolean bridge;
	Integer sampledSceneArrayPlane;
	Boolean observedViaBridge;
	String sampleEvidence;
	/** Definition IDs decoded from the scene grids; -1 means no definition, null unavailable. */
	Integer underlayId, overlayId;
	Integer tileShape;
	Integer paintRgb, paintTexture;
	int[] paintCorners;
	Boolean paintFlat;
	Integer modelUnderlay, modelOverlay, modelShape, modelRotation;
	int[] modelVertexX, modelVertexY, modelVertexZ;
	int[] modelFaceX, modelFaceY, modelFaceZ;
	int[] modelColorA, modelColorB, modelColorC, modelTextures;
}
