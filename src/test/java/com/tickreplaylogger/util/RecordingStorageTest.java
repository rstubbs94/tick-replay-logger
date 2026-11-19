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
package com.tickreplaylogger.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSerializer;
import com.tickreplaylogger.model.TickRecording;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RecordingStorageTest
{
	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();

	private static TickRecording recording(long id, String name)
	{
		return TickRecording.builder().timestamp(id).killerName(name).ticks(Collections.emptyList())
			.richExportJson("{\"schemaVersion\":\"1.3\",\"observed\":\"火😀\"}").build();
	}

	private static Filepath rooted(Path path)
	{
		return Filepath.Unchecked.getRooted(path);
	}

	private static void await(CompletableFuture<Void> future) throws Exception
	{
		future.get(10, TimeUnit.SECONDS);
	}

	private static void fails(CompletableFuture<Void> future) throws Exception
	{
		try
		{
			await(future);
			fail("Expected protected/failing operation");
		}
		catch (ExecutionException expected)
		{
			assertNotNull(expected.getCause());
		}
	}

	@Test
	public void mutationsAndSuppliersAreOrderedOffCallerThreadAndCloseDrainsAcceptedWork() throws Exception
	{
		Path folder = temporary.newFolder().toPath();
		RecordingStorage storage = new RecordingStorage(3, new Gson(), rooted(folder));
		await(storage.ready());
		CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicReference<Thread> supplierThread = new AtomicReference<>();
		CompletableFuture<Void> first = storage.addDeath(() ->
		{
			supplierThread.set(Thread.currentThread());
			started.countDown();
			try
			{
				if (!release.await(10, TimeUnit.SECONDS))
					throw new AssertionError("Supplier not released");
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
			return recording(1, "First");
		});
		try
		{
			assertTrue(started.await(10, TimeUnit.SECONDS));
			assertNotSame(Thread.currentThread(), supplierThread.get());
			assertEquals(0, storage.getDeathCount()); // snapshots do not wait on export/disk work
			CompletableFuture<Void> clear = storage.clearDeaths();
			CompletableFuture<Void> last = storage.addDeath(recording(2, "Last"));
			storage.close(); // no blocking wait for the held supplier
			assertFalse(first.isDone());
			fails(storage.addDeath(recording(3, "Rejected")));
			release.countDown();
			await(first);
			await(clear);
			await(last);
			assertEquals("Last", storage.getDeaths().get(0).getKillerName());
			try (RecordingStorage loaded = new RecordingStorage(3, new Gson(), rooted(folder)))
			{
				await(loaded.ready());
				assertEquals(1, loaded.getDeathCount());
				assertEquals(2, loaded.getDeaths().get(0).getTimestamp());
			}
		}
		finally
		{
			release.countDown();
			storage.close();
		}
	}

	@Test
	public void callerAndUiWrapperMutationsCannotRenameStoredCaptureAndStaleUiCanStillRename() throws Exception
	{
		Path folder = temporary.newFolder().toPath();
		try (RecordingStorage storage = new RecordingStorage(3, new Gson(), rooted(folder)))
		{
			TickRecording input = recording(7, "Original");
			CompletableFuture<Void> added = storage.addDeath(input);
			input.setKillerName("Caller mutated");
			input.setRichExportJson("corrupted");
			await(added);
			TickRecording snapshot = storage.getDeaths().get(0);
			assertEquals("Original", snapshot.getKillerName());
			assertTrue(snapshot.getRichExportJson().contains("schemaVersion"));
			snapshot.setKillerName("UI mutated");
			snapshot.setCustomLabel(true);
			assertEquals("Original", storage.getDeaths().get(0).getKillerName());
			await(storage.renameRecording(snapshot, "Unknown"));
			assertEquals("Unknown", storage.getDeaths().get(0).getKillerName());
			assertEquals(Boolean.TRUE, storage.getDeaths().get(0).getCustomLabel());
			await(storage.deleteDeath(snapshot));
			assertEquals(0, storage.getDeathCount());
		}
	}

	@Test
	public void incompleteSerializationPreservesPrimaryBackupAndVisibleState() throws Exception
	{
		Path folder = temporary.newFolder().toPath();
		AtomicBoolean fail = new AtomicBoolean();
		Gson plain = new Gson();
		Gson failing = new GsonBuilder()
			.registerTypeAdapter(TickRecording.class, (JsonSerializer<TickRecording>) (value, type, context) ->
			{
				if (fail.get())
					throw new IllegalStateException("Injected incomplete serialization");
				return plain.toJsonTree(value);
			}).create();
		try (RecordingStorage storage = new RecordingStorage(3, failing, rooted(folder)))
		{
			await(storage.addDeath(recording(1, "One")));
			await(storage.addDeath(recording(2, "Two")));
			byte[] before = Files.readAllBytes(folder.resolve("deaths.json"));
			byte[] backup = Files.readAllBytes(folder.resolve("deaths.json.bak"));
			fail.set(true);
			fails(storage.addDeath(recording(3, "Three")));
			assertArrayEquals(before, Files.readAllBytes(folder.resolve("deaths.json")));
			assertArrayEquals(backup, Files.readAllBytes(folder.resolve("deaths.json.bak")));
			assertEquals(2, storage.getDeathCount());
			try (java.util.stream.Stream<Path> files = Files.list(folder))
			{
				assertFalse(files.anyMatch(path -> path.toString().endsWith(".tmp")));
			}
			fail.set(false);
			await(storage.addDeath(recording(4, "Recovery")));
			assertEquals(3, storage.getDeathCount());
		}
	}

	@Test
	public void corruptPrimaryWithoutBackupIsProtectedWhileOtherCollectionRemainsUsable() throws Exception
	{
		Path folder = temporary.newFolder().toPath();
		byte[] broken = "[{\"timestamp\":1,".getBytes(StandardCharsets.UTF_8);
		Files.write(folder.resolve("deaths.json"), broken);
		try (RecordingStorage storage = new RecordingStorage(3, new Gson(), rooted(folder)))
		{
			fails(storage.ready());
			fails(storage.addDeath(recording(2, "Do not replace")));
			fails(storage.clearDeaths());
			assertArrayEquals(broken, Files.readAllBytes(folder.resolve("deaths.json")));
			await(storage.addSavedRecording(recording(3, "Still usable")));
			assertEquals(1, storage.getSavedCount());
		}
	}

	@Test
	public void validatedBackupRecoversTruncatedPrimaryAndRetainsOriginalBytes() throws Exception
	{
		Path folder = temporary.newFolder().toPath();
		try (RecordingStorage storage = new RecordingStorage(3, new Gson(), rooted(folder)))
		{
			await(storage.addDeath(recording(1, "Last good backup")));
			await(storage.addDeath(recording(2, "Latest")));
		}
		byte[] broken = "[partial write".getBytes(StandardCharsets.UTF_8);
		Files.write(folder.resolve("deaths.json"), broken);
		try (RecordingStorage storage = new RecordingStorage(3, new Gson(), rooted(folder)))
		{
			await(storage.ready());
			assertEquals(1, storage.getDeathCount());
			assertEquals("Last good backup", storage.getDeaths().get(0).getKillerName());
			try (java.util.stream.Stream<Path> files = Files.list(folder))
			{
				Path retained = files.filter(path -> path.getFileName().toString().startsWith("deaths.json.corrupt-"))
					.findFirst().get();
				assertArrayEquals(broken, Files.readAllBytes(retained));
			}
			await(storage.addDeath(recording(3, "After recovery")));
			assertEquals(2, storage.getDeathCount());
		}
	}

	@Test
	public void savedDestinationSurvivesFailureRemovingTheRecentCopy() throws Exception
	{
		Path folder = temporary.newFolder().toPath();
		AtomicBoolean failRemoval = new AtomicBoolean();
		try (RecordingStorage storage = new RecordingStorage(3, new Gson(), rooted(folder))
		{
			@Override
			protected void publish(Filepath file, List<TickRecording> records) throws Exception
			{
				if (failRemoval.get() && file.getFileName().equals("deaths.json"))
					throw new java.io.IOException("Injected second-file failure");
				super.publish(file, records);
			}
		})
		{
			await(storage.addDeath(recording(1, "Important")));
			failRemoval.set(true);
			fails(storage.saveRecording(storage.getDeaths().get(0)));
			assertEquals(1, storage.getDeathCount());
			assertEquals(1, storage.getSavedCount());
			failRemoval.set(false);
			await(storage.saveRecording(storage.getDeaths().get(0)));
			assertEquals(0, storage.getDeathCount());
			assertEquals(1, storage.getSavedCount());
		}
		try (RecordingStorage loaded = new RecordingStorage(3, new Gson(), rooted(folder)))
		{
			await(loaded.ready());
			assertEquals(0, loaded.getDeathCount());
			assertEquals(1, loaded.getSavedCount());
		}
	}

	@Test
	public void negativeRetentionCannotCrashOrProduceNegativeIndices() throws Exception
	{
		try (RecordingStorage storage = new RecordingStorage(3, new Gson(), rooted(temporary.newFolder().toPath())))
		{
			await(storage.addDeath(recording(1, "One")));
			await(storage.setMaxDeaths(-1));
			await(storage.addDeath(recording(2, "Two")));
			assertEquals(0, storage.getDeathCount());
		}
	}

	@Test
	public void reopenedStorageLoadsAfterPriorAcceptedCaptureFinishes() throws Exception
	{
		Path folder = temporary.newFolder().toPath();
		RecordingStorage previous = new RecordingStorage(3, new Gson(), rooted(folder));
		await(previous.ready());
		CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
		CompletableFuture<Void> captured = previous.addDeath(() ->
		{
			started.countDown();
			try
			{
				if (!release.await(10, TimeUnit.SECONDS))
					throw new IllegalStateException("Not released");
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
			return recording(1, "Prior session");
		});
		try
		{
			assertTrue(started.await(10, TimeUnit.SECONDS));
			previous.close();
			try (RecordingStorage reopened = new RecordingStorage(3, new Gson(), rooted(folder)))
			{
				assertFalse(reopened.ready().isDone());
				release.countDown();
				await(captured);
				await(reopened.ready());
				assertEquals("Prior session", reopened.getDeaths().get(0).getKillerName());
				await(reopened.addDeath(recording(2, "New session")));
				assertEquals(2, reopened.getDeathCount());
			}
		}
		finally
		{
			release.countDown();
			previous.close();
		}
	}

	@Test
	public void legacyWindowsCharactersSurviveStrictFallbackAndUtf8Rewrite() throws Exception
	{
		Path folder = temporary.newFolder().toPath();
		java.nio.charset.Charset windows = java.nio.charset.Charset.forName("windows-1252");
		String rich = "{\"name\":\"Zulrah\u00a0café £\",\"zero\":0,\"fraction\":1.23456789012345,\"long\":\"9223372036854775807\"}";
		TickRecording original = recording(11, "Zulrah\u00a0café").toBuilder().richExportJson(rich).build();
		byte[] legacy = new Gson().toJson(Collections.singletonList(original)).getBytes(windows);
		Files.write(folder.resolve("deaths.json"), legacy);
		try (RecordingStorage storage = new RecordingStorage(3, new Gson(), rooted(folder), windows))
		{
			await(storage.ready());
			TickRecording loaded = storage.getDeaths().get(0);
			assertEquals(original.getKillerName(), loaded.getKillerName());
			assertEquals(rich, loaded.getRichExportJson());
			await(storage.renameRecording(loaded, "Renamed\u00a0café"));
			assertArrayEquals(legacy, Files.readAllBytes(folder.resolve("deaths.json.bak")));
			StandardCharsets.UTF_8.newDecoder()
				.decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(folder.resolve("deaths.json"))));
		}
		try (RecordingStorage storage = new RecordingStorage(3, new Gson(), rooted(folder), StandardCharsets.UTF_8))
		{
			await(storage.ready());
			assertEquals("Renamed\u00a0café", storage.getDeaths().get(0).getKillerName());
			assertEquals(rich, storage.getDeaths().get(0).getRichExportJson());
		}
	}

	@Test
	public void malformedUtf8IsProtectedInsteadOfSilentlyReplacingCharacters() throws Exception
	{
		Path folder = temporary.newFolder().toPath();
		byte[] broken = "[{\"timestamp\":1,\"killerName\":\"X\u00a0Y\"}]"
			.getBytes(java.nio.charset.Charset.forName("windows-1252"));
		Files.write(folder.resolve("deaths.json"), broken);
		try (RecordingStorage storage = new RecordingStorage(3, new Gson(), rooted(folder), StandardCharsets.UTF_8))
		{
			fails(storage.ready());
			fails(storage.addDeath(recording(2, "Must not overwrite")));
			assertEquals(0, storage.getDeathCount());
			assertArrayEquals(broken, Files.readAllBytes(folder.resolve("deaths.json")));
		}
	}

	@Test
	public void bufferedUtf8SaveKeepsUnicodeAndRawNumericTextWithLegacyCharsetAvailable() throws Exception
	{
		Path folder = temporary.newFolder().toPath();
		String rich = "{\"text\":\"火😀 café\u00a0\",\"zero\":0,\"precise\":1.2300e-10,\"long\":9223372036854775807}";
		TickRecording original = recording(12, "火😀 café\u00a0").toBuilder().richExportJson(rich).build();
		try (RecordingStorage storage = new RecordingStorage(3, new Gson(), rooted(folder)))
		{
			await(storage.addDeath(original));
		}
		// Correct UTF-8 takes precedence; decoding it as the available old charset would corrupt it.
		try (RecordingStorage storage = new RecordingStorage(3, new Gson(), rooted(folder),
			java.nio.charset.Charset.forName("windows-1252")))
		{
			await(storage.ready());
			assertEquals(original.getKillerName(), storage.getDeaths().get(0).getKillerName());
			assertEquals(rich, storage.getDeaths().get(0).getRichExportJson());
			await(storage.saveRecording(storage.getDeaths().get(0)));
		}
		try (RecordingStorage storage = new RecordingStorage(3, new Gson(), rooted(folder), StandardCharsets.UTF_8))
		{
			await(storage.ready());
			assertEquals(original.getKillerName(), storage.getSavedRecordings().get(0).getKillerName());
			assertEquals(rich, storage.getSavedRecordings().get(0).getRichExportJson());
		}
	}
}
