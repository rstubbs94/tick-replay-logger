/*
 * Copyright (c) 2026, rstubbs94
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.tickreplaylogger.capture;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Evaluated components shared directly with the recording projector. */
final class ResolutionResult
{
	List<ResolutionCandidate> candidates = new ArrayList<>();
	JsonObject hitAnnotations = new JsonObject();
	boolean unidentifiedPossible = true;
	boolean truncated;
	String scalarAssociation;

}
