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
package com.tickreplaylogger.ui;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.tickreplaylogger.TickReplayLoggerConfig;
import com.tickreplaylogger.capture.DamageMap;
import com.tickreplaylogger.model.TickRecording;
import com.tickreplaylogger.model.world.NpcState;
import com.tickreplaylogger.util.RecordingStorage;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class EncounterTitlePreparationTest
{
	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void refreshPreparesOffEdtAndReusesTitlesAcrossStorageWrapperCopies() throws Exception
	{
		Reads reads = new Reads();
		Gson gson = new Gson().newBuilder().registerTypeAdapterFactory(reads).create();
		DamageMap map = DamageMap.load(gson);
		try (RecordingStorage storage = storage(gson))
		{
			storage.ready().get(5, TimeUnit.SECONDS);
			storage.addDeath(recording(map, gson)).get(5, TimeUnit.SECONDS);
			TickReplayLoggerPanel[] panel = new TickReplayLoggerPanel[1];
			SwingUtilities.invokeAndWait(
				() -> panel[0] = new TickReplayLoggerPanel(storage, null, gson, new TickReplayLoggerConfig()
				{
				}, map));
			assertTrue(reads.entered.await(5, TimeUnit.SECONDS));
			SwingUtilities.invokeAndWait(() -> assertFalse(reads.onEdt.get()));
			reads.release.countDown();
			awaitEdt(() -> hasLabel(panel[0], "#1: Corrupted Hunllef"));
			int preparedReads = reads.count.get();
			assertTrue(preparedReads > 0);
			SwingUtilities.invokeAndWait(() ->
			{
				panel[0].refresh();
				panel[0].refresh();
			});
			assertEquals(preparedReads, reads.count.get());
			SwingUtilities.invokeAndWait(() -> panel[0].dispose());
		}
		finally
		{
			reads.release.countDown();
		}
	}

	@Test
	public void stalePreparationCannotReplaceRenamedRowsOrRepopulateDisposedPanel() throws Exception
	{
		Reads reads = new Reads();
		Gson gson = new Gson().newBuilder().registerTypeAdapterFactory(reads).create();
		DamageMap map = DamageMap.load(gson);
		try (RecordingStorage storage = storage(gson))
		{
			storage.ready().get(5, TimeUnit.SECONDS);
			TickRecording original = recording(map, gson);
			storage.addDeath(original).get(5, TimeUnit.SECONDS);
			TickReplayLoggerPanel[] panel = new TickReplayLoggerPanel[1];
			SwingUtilities.invokeAndWait(
				() -> panel[0] = new TickReplayLoggerPanel(storage, null, gson, new TickReplayLoggerConfig()
				{
				}, map));
			assertTrue(reads.entered.await(5, TimeUnit.SECONDS));
			storage.renameRecording(original, "Latest custom title").get(5, TimeUnit.SECONDS);
			SwingUtilities.invokeAndWait(() ->
			{
				panel[0].refresh();
				assertTrue(hasLabel(panel[0], "#1: Latest custom title"));
			});
			reads.release.countDown();
			assertTrue(reads.exited.await(5, TimeUnit.SECONDS));
			SwingUtilities.invokeAndWait(() ->
			{
				assertTrue(hasLabel(panel[0], "#1: Latest custom title"));
				assertFalse(hasLabel(panel[0], "#1: Corrupted Hunllef"));
				panel[0].dispose();
			});
			SwingUtilities.invokeAndWait(
				() -> assertEquals(0, ((Container) field(panel[0], "recordingsListContainer")).getComponentCount()));
			assertEquals(0, ((java.util.Map<?, ?>) field(panel[0], "encounterTitles")).size());
		}
		finally
		{
			reads.release.countDown();
		}
	}

	private RecordingStorage storage(Gson gson) throws Exception
	{
		return new RecordingStorage(3, gson,
			net.runelite.client.util.Filepath.Unchecked.getRooted(temporary.newFolder().toPath()));
	}

	private static TickRecording recording(DamageMap map, Gson gson)
	{
		String rich = "{\"schemaVersion\":\"1.3\",\"meta\":{\"deathTick\":1,\"referenceCatalogId\":\""
			+ map.catalog().id()
			+ "\"},\"ticks\":[{\"tick\":1,\"npcs\":[{\"npcId\":9036,\"name\":\"Corrupted Hunllef\",\"combatLevel\":894,\"size\":5,\"normalWorldContextObserved\":true}]}]}";
		return TickRecording.builder().timestamp(123).killerName("Unknown").location("Recorded encounter")
			.ticks(Collections.emptyList()).richExportJson(rich).build();
	}

	private static final class Reads implements TypeAdapterFactory
	{
		final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1),
			exited = new CountDownLatch(1);
		final AtomicInteger count = new AtomicInteger();
		final AtomicBoolean onEdt = new AtomicBoolean();

		@Override
		public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type)
		{
			if (type.getRawType() != NpcState.class)
				return null;
			TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
			return new TypeAdapter<T>()
			{
				@Override
				public void write(JsonWriter out, T value) throws IOException
				{
					delegate.write(out, value);
				}

				@Override
				public T read(JsonReader in) throws IOException
				{
					count.incrementAndGet();
					onEdt.set(SwingUtilities.isEventDispatchThread());
					entered.countDown();
					if (onEdt.get())
						throw new AssertionError("Rich title scan on EDT");
					try
					{
						if (!release.await(5, TimeUnit.SECONDS))
							throw new IOException("Test title read blocked");
						return delegate.read(in);
					}
					catch (InterruptedException unavailable)
					{
						throw new IOException(unavailable);
					}
					finally
					{
						exited.countDown();
					}
				}
			};
		}
	}

	private static Object field(Object target, String name)
	{
		try
		{
			java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return field.get(target);
		}
		catch (ReflectiveOperationException unavailable)
		{
			throw new AssertionError(unavailable);
		}
	}

	private static boolean hasLabel(Container parent, String text)
	{
		for (Component child : parent.getComponents())
		{
			if (child instanceof JLabel && text.equals(((JLabel) child).getText()))
				return true;
			if (child instanceof Container && hasLabel((Container) child, text))
				return true;
		}
		return false;
	}

	private static void awaitEdt(java.util.function.BooleanSupplier condition) throws Exception
	{
		CompletableFuture<Void> ready = new CompletableFuture<>();
		Timer[] timer = new Timer[1];
		SwingUtilities.invokeAndWait(() ->
		{
			timer[0] = new Timer(10, event ->
			{
				if (condition.getAsBoolean())
				{
					timer[0].stop();
					ready.complete(null);
				}
			});
			timer[0].start();
		});
		try
		{
			ready.get(5, TimeUnit.SECONDS);
		}
		finally
		{
			SwingUtilities.invokeAndWait(() -> timer[0].stop());
		}
	}
}
