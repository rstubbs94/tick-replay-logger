package com.tickreplaylogger.capture;

import com.google.gson.Gson;
import com.tickreplaylogger.model.world.DamageRecord;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.Test;
import static org.junit.Assert.*;

public class SourceResolutionWorkerTest
{
	@Test
	public void coalescingKeepsEveryRetainedTargetAndPublishesOnlyOnDispatch()
	{
		Queue<Runnable> work = new ArrayDeque<>(), dispatch = new ArrayDeque<>();
		List<Map<Long, DamageRecord>> results = new ArrayList<>();
		SourceResolutionWorker worker = new SourceResolutionWorker(work::add, dispatch::add, new Gson());
		DamageRecord first = hit(1), second = hit(2), third = hit(3);
		worker.submit(window(first), Collections.singletonList(first), (value, failure) ->
		{
			assertNull(failure);
			results.add(value);
		});
		worker.submit(window(first, second), Collections.singletonList(second), (value, failure) -> results.add(value));
		worker.submit(window(first, second, third), Collections.singletonList(third), (value, failure) ->
		{
			assertNull(failure);
			results.add(value);
		});
		assertEquals(1, work.size());
		work.remove().run();
		assertTrue(results.isEmpty());
		dispatch.remove().run();
		assertEquals(Collections.singleton(1L), results.get(0).keySet());
		assertEquals(1, work.size());
		work.remove().run();
		dispatch.remove().run();
		assertEquals(new java.util.LinkedHashSet<>(Arrays.asList(2L, 3L)), results.get(1).keySet());
		assertEquals(2, results.get(1).get(2L).getAmount());
		assertEquals(3, results.get(1).get(3L).getAmount());
		assertTrue(work.isEmpty());
	}

	@Test
	public void resetRejectsOldResultsWithoutStartingAnOverlappingWorker()
	{
		Queue<Runnable> work = new ArrayDeque<>(), dispatch = new ArrayDeque<>();
		List<Map<Long, DamageRecord>> results = new ArrayList<>();
		SourceResolutionWorker worker = new SourceResolutionWorker(work::add, dispatch::add, new Gson());
		DamageRecord first = hit(1), second = hit(2);
		worker.submit(window(first), Collections.singletonList(first), (value, failure) -> fail("Old generation published"));
		worker.reset();
		worker.submit(window(second), Collections.singletonList(second), (value, failure) -> results.add(value));
		assertEquals(1, work.size());
		work.remove().run();
		dispatch.remove().run();
		assertTrue(results.isEmpty());
		assertEquals(1, work.size());
		work.remove().run();
		dispatch.remove().run();
		assertEquals(Collections.singleton(2L), results.get(0).keySet());
	}

	@Test
	public void rejectedSchedulingReleasesTheQueue()
	{
		int[] submissions = {0}, failures = {0};
		SourceResolutionWorker worker = new SourceResolutionWorker(task ->
		{
			if (submissions[0]++ == 0)
				throw new java.util.concurrent.RejectedExecutionException("synthetic rejection");
			task.run();
		}, Runnable::run, new Gson());
		DamageRecord first = hit(1);
		worker.submit(window(first), Collections.singletonList(first), (value, failure) ->
		{
			assertTrue(failure instanceof java.util.concurrent.RejectedExecutionException);
			failures[0]++;
		});
		worker.submit(window(first), Collections.singletonList(first), (value, failure) -> assertNull(failure));
		assertEquals(2, submissions[0]);
		assertEquals(1, failures[0]);
	}

	private static DamageRecord hit(long sequence)
	{
		return DamageRecord.builder().direction("RECEIVED").impactTick(1).observationSequence(sequence)
			.amount((int) sequence).hitsplatType(16).hitsplatTypeKnown(true).defenderRef("player").build();
	}

	private static ResolverV1Recording.EvidenceWindow window(DamageRecord... hits)
	{
		return new ResolverV1Recording.EvidenceWindow(Collections.emptyList(), Collections.emptyList(),
			Collections.emptyList(), Arrays.asList(hits), Collections.emptyList(), 1, 1, Long.MAX_VALUE, 1);
	}
}
