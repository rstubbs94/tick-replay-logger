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
package com.tickreplaylogger.model;

import lombok.*;

/**
 * Represents movement data for a tick.
 * Tracks intended movement vs actual movement completed.
 * Does NOT store raw coordinates - only actionable movement info.
 */
@Data
@Builder
public class MovementData
{
	private final int tilesIntended; // How far player clicked to move
	private final int tilesCompleted; // How far player actually moved
	private final boolean wasCancelled; // Was movement interrupted by another action
	private final boolean intentObserved; // false: completed movement alone does not prove a move-click intent

	/**
	 * Create movement data for completed movement
	 */
	public static MovementData completed(int tiles)
	{
		return MovementData.builder().tilesIntended(0).tilesCompleted(tiles).wasCancelled(false).build();
	}

	/**
	 * Create movement data for cancelled movement
	 */
	public static MovementData cancelled(int tilesIntended, int tilesCompleted)
	{
		return MovementData.builder().tilesIntended(tilesIntended).tilesCompleted(tilesCompleted).wasCancelled(true)
			.build();
	}

	/**
	 * Create movement data for no movement this tick
	 */
	public static MovementData none()
	{
		return MovementData.builder().tilesIntended(0).tilesCompleted(0).wasCancelled(false).build();
	}

	/**
	 * Get a human-readable description
	 */
	public String getDescription()
	{
		if (tilesIntended == 0 && tilesCompleted == 0)
		{
			return null; // No movement, don't display anything
		}

		if (!wasCancelled)
		{
			return "Moved " + tilesCompleted + " tiles";
		}
		else if (tilesCompleted == 0)
		{
			return "Move cancelled (0 of " + tilesIntended + " tiles)";
		}
		else
		{
			return "Moved " + tilesIntended + " tiles (cancelled after " + tilesCompleted + ")";
		}
	}

	/**
	 * Check if there was any movement or movement intent
	 */
	public boolean hasMovement()
	{
		return tilesIntended > 0 || tilesCompleted > 0;
	}
}
