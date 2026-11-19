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

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Runs the actual pinned Plugin migration API, isolated before RuneLite's static home is loaded. */
public class PluginStorageMigrationTest
{
	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void legacyDirectoryMovesByteExactlyWithoutOverwritingAnExistingDestination() throws Exception
	{
		Path home = temporary.newFolder().toPath();
		Set<String> classpath = new LinkedHashSet<>(
			Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));
		for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent())
		{
			if (loader instanceof URLClassLoader)
			{
				for (URL url : ((URLClassLoader) loader).getURLs())
					if (url.getProtocol().equals("file"))
						classpath.add(Path.of(url.toURI()).toString());
			}
		}
		Path output = home.resolve("migration-test.log");
		Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
			"-Duser.home=" + home, "-Djava.awt.headless=true", "-cp", String.join(File.pathSeparator, classpath),
			Probe.class.getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
		try
		{
			assertTrue("Migration subprocess timed out", child.waitFor(30, TimeUnit.SECONDS));
			assertEquals(Files.readString(output), 0, child.exitValue());
		}
		finally
		{
			if (child.isAlive())
				child.destroyForcibly();
		}
	}

	@PluginDescriptor(name = "Storage migration test", internalName = "tick-replay-logger", legacyDataDirectory = "tick-replay-logger")
	public static class MigrationPlugin extends Plugin
	{
		Filepath directory() throws Exception
		{
			return getPluginDirectory();
		}
	}

	public static class Probe
	{
		public static void main(String[] args) throws Exception
		{
			Path base = Path.of(System.getProperty("user.home"), ".runelite");
			Path legacy = base.resolve("tick-replay-logger");
			Path destination = base.resolve("plugin-data").resolve("tick-replay-logger");
			byte[] deaths = "[{\"timestamp\":7,\"killerName\":\"火😀\"}]".getBytes(StandardCharsets.UTF_8);
			byte[] saved = "[{\"timestamp\":9,\"richExportJson\":\"{exact bytes}\"}]".getBytes(StandardCharsets.UTF_8);
			Files.createDirectories(legacy);
			Files.write(legacy.resolve("deaths.json"), deaths);
			Files.write(legacy.resolve("saved.json"), saved);
			Filepath actual = new MigrationPlugin().directory();
			if (!actual.toString().equals(destination.toAbsolutePath().toString()))
				throw new AssertionError("Unexpected production directory: " + actual);
			if (Files.exists(legacy) || !Arrays.equals(deaths, Files.readAllBytes(destination.resolve("deaths.json")))
				|| !Arrays.equals(saved, Files.readAllBytes(destination.resolve("saved.json"))))
				throw new AssertionError("Migration changed/lost recording bytes");
			Files.createDirectories(legacy);
			Files.writeString(legacy.resolve("deaths.json"), "old conflicting content");
			new MigrationPlugin().directory();
			if (!Arrays.equals(deaths, Files.readAllBytes(destination.resolve("deaths.json")))
				|| !Files.readString(legacy.resolve("deaths.json")).equals("old conflicting content"))
				throw new AssertionError("Existing destination must not be overwritten/merged");
		}
	}
}
