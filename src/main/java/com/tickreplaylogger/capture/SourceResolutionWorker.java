/*
 * Copyright (c) 2026, rstubbs94
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.tickreplaylogger.capture;

import com.google.gson.Gson;
import com.tickreplaylogger.model.world.DamageRecord;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.*;

/** One worker and one replaceable window; merging targets preserves coalesced ticks. */
@RequiredArgsConstructor
public final class SourceResolutionWorker
{
	private final Executor executor;
	private final Consumer<Runnable> dispatch;
	private final Gson gson;
	private Request pending;
	private boolean running;
	private long generation;

	@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
	private static final class Request
	{
		final ResolverV1Recording.EvidenceWindow window;
		final Map<Long, DamageRecord> targets;
		final BiConsumer<Map<Long, DamageRecord>, RuntimeException> completion;
		final long generation;
	}

	/** Called on the client thread with detached observation DTOs, never live API objects. */
	public void submit(ResolverV1Recording.EvidenceWindow window, List<DamageRecord> hits,
		BiConsumer<Map<Long, DamageRecord>, RuntimeException> completion)
	{
		Map<Long, DamageRecord> targets = new LinkedHashMap<>();
		if (pending != null)
		{
			targets.putAll(pending.targets);
		}
		for (DamageRecord hit : hits)
		{
			if (hit != null && hit.getObservationSequence() != null)
			{
				targets.put(hit.getObservationSequence(), hit);
			}
		}
		targets.values().removeIf(hit -> !window.contains(hit));
		if (targets.isEmpty())
		{
			return;
		}
		// Resource contexts live in their original callbacks, not the ordinary HP buffer.
		// Keep queued originals as batch peers when a newer capture replaces the pending window.
		List<DamageRecord> sharedHits = new ArrayList<>(window.getHits());
		sharedHits.removeIf(hit -> targets.containsKey(hit.getObservationSequence())
			&& hit.sameNativeCallback(targets.get(hit.getObservationSequence())));
		sharedHits.addAll(targets.values());
		ResolverV1Recording.EvidenceWindow sharedWindow = new ResolverV1Recording.EvidenceWindow(window.getTicks(),
			window.getScenes(), window.getObservations(), sharedHits, window.getCaptureGaps(),
			window.getObservationLinks(), window.getFirstTick(), window.getLastTick(), window.getCutoffSequence(),
			window.getEvidenceRevision());
		pending = new Request(sharedWindow, targets, completion, generation);
		if (!running)
		{
			startNext();
		}
	}

	/** Invalidates results without allowing a second worker to overlap the old generation. */
	public void reset()
	{
		generation++;
		pending = null;
	}

	private void startNext()
	{
		Request request = pending;
		if (request == null)
		{
			return;
		}
		pending = null;
		running = true;
		try
		{
			executor.execute(() -> resolve(request));
		}
		catch (RuntimeException unavailable)
		{
			finish(request, new LinkedHashMap<>(), unavailable);
		}
	}

	private void resolve(Request request)
	{
		Map<Long, DamageRecord> results = new LinkedHashMap<>();
		RuntimeException failure = null;
		try
		{
			ResolverV1Recording resolver = new ResolverV1Recording(request.window, gson);
			for (Map.Entry<Long, DamageRecord> target : request.targets.entrySet())
			{
				results.put(target.getKey(), resolver.resolveHit(target.getValue()));
			}
		}
		catch (RuntimeException unavailable)
		{
			failure = unavailable;
		}
		RuntimeException observedFailure = failure;
		dispatch.accept(() -> finish(request, results, observedFailure));
	}

	private void finish(Request request, Map<Long, DamageRecord> results, RuntimeException failure)
	{
		try
		{
			if (request.generation == generation)
			{
				request.completion.accept(results, failure);
			}
		}
		finally
		{
			running = false;
			startNext();
		}
	}
}
