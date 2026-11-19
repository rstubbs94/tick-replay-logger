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
package com.tickreplaylogger.capture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.client.RuneLiteProperties;

/** Compile-target provenance with a bounded, cached runtime reference check; not a loaded-JAR hash. */
public final class CaptureBuild
{
	private static final JsonObject INFO = load();
	static final Map<String, List<String>> REFERENCE_CLASSES;
	static
	{
		Map<String, List<String>> paths = new LinkedHashMap<>();
		paths.put("runelite-api", Collections.unmodifiableList(
			Arrays.asList("net/runelite/api/Client.class", "net/runelite/api/VarbitComposition.class")));
		paths.put("client",
			Collections.unmodifiableList(Arrays.asList("net/runelite/client/RuneLiteProperties.class",
				"net/runelite/client/plugins/poison/PoisonPlugin.class",
				"net/runelite/client/plugins/timersandbuffs/TimersAndBuffsPlugin.class",
				"net/runelite/client/plugins/timersandbuffs/GameTimer.class")));
		REFERENCE_CLASSES = Collections.unmodifiableMap(paths);
	}

	private CaptureBuild()
	{
	}

	private static JsonObject load()
	{
		try (InputStream input = CaptureBuild.class.getResourceAsStream("/com/tickreplaylogger/capture-build.json"))
		{
			return input == null ? new JsonObject()
				: new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
		}
		catch (Exception unavailable)
		{
			return new JsonObject();
		}
	}

	private static final class RuntimeReference
	{
		static final String VERSION = loadedClientVersion();
		static final String RESULT = verifyReferenceRuntime(INFO, VERSION, CaptureBuild::runtimeResource);
	}

	private static String loadedClientVersion()
	{
		try
		{
			return RuneLiteProperties.getVersion();
		}
		catch (RuntimeException | LinkageError unavailable)
		{
			return null;
		}
	}

	private static InputStream runtimeResource(String path)
	{
		return path.startsWith("net/runelite/api/") ? Client.class.getResourceAsStream("/" + path)
			: RuneLiteProperties.class.getResourceAsStream("/" + path);
	}

	public static String sourceTreeHash()
	{
		return value("sourceTreeHash");
	}

	public static String artifactIdentityScope()
	{
		return value("artifactIdentityScope");
	}

	public static String runtimeVersion()
	{
		return RuntimeReference.VERSION;
	}

	public static String runtimeReferenceVerification()
	{
		return RuntimeReference.RESULT;
	}

	public static String compiledApiVersion()
	{
		return value("runeLiteApiVersion");
	}

	public static String compiledApiArtifactSha256()
	{
		return artifactHash("runelite-api");
	}

	public static String compiledClientArtifactSha256()
	{
		return artifactHash("client");
	}

	// Existing fields identify the compile reference target only when its selected runtime witnesses match.
	public static String apiVersion()
	{
		return runtimeReferenceMatched() ? compiledApiVersion() : null;
	}

	public static String apiArtifactSha256()
	{
		return runtimeReferenceMatched() ? compiledApiArtifactSha256() : null;
	}

	public static String clientArtifactSha256()
	{
		return runtimeReferenceMatched() ? compiledClientArtifactSha256() : null;
	}

	public static boolean runtimeReferenceMatched()
	{
		return "MATCHED_SELECTED_CLASSES_AND_VERSION".equals(RuntimeReference.RESULT);
	}

	public static boolean supportsClientReference(String version, String clientHash)
	{
		return version != null && clientHash != null && runtimeReferenceMatched()
			&& version.equals(compiledApiVersion()) && clientHash.equals(compiledClientArtifactSha256());
	}

	@FunctionalInterface
	interface ReferenceResources
	{
		InputStream open(String path) throws IOException;
	}

	static String verifyReferenceRuntime(JsonObject info, String runtimeVersion, ReferenceResources resources)
	{
		if (!"COMPILE_CLASSPATH_REFERENCE".equals(string(info, "artifactIdentityScope"))
			|| !"SELECTED_CLASSES_AND_VERSION_V1".equals(string(info, "runtimeReferencePolicy")))
			return "BUILD_METADATA_UNAVAILABLE";
		JsonObject artifacts = object(info.get("runeLiteArtifacts")), client = object(artifacts.get("client"));
		String apiVersion = string(info, "runeLiteApiVersion");
		if (apiVersion == null || !apiVersion.equals(string(object(artifacts.get("runelite-api")), "version")))
			return "INVALID_REFERENCE_CLASS_MANIFEST";
		if (runtimeVersion == null)
			return "RUNTIME_VERSION_UNAVAILABLE";
		if (!runtimeVersion.equals(string(client, "version")))
			return "RUNTIME_VERSION_MISMATCH";
		try
		{
			byte[] buffer = new byte[8192];
			for (Map.Entry<String, List<String>> group : REFERENCE_CLASSES.entrySet())
			{
				JsonObject artifact = object(artifacts.get(group.getKey()));
				String archiveHash = string(artifact, "sha256");
				JsonObject hashes = object(artifact.get("referenceClassSha256s"));
				if (archiveHash == null || !archiveHash.matches("[a-f0-9]{64}")
					|| !hashes.keySet().equals(new HashSet<>(group.getValue())))
					return "INVALID_REFERENCE_CLASS_MANIFEST";
				for (String path : group.getValue())
				{
					String expected = string(hashes, path);
					if (expected == null || !expected.matches("[a-f0-9]{64}"))
						return "INVALID_REFERENCE_CLASS_MANIFEST";
					MessageDigest digest = MessageDigest.getInstance("SHA-256");
					try (InputStream input = resources.open(path))
					{
						if (input == null)
							return "REFERENCE_CLASS_UNAVAILABLE";
						int count;
						while ((count = input.read(buffer)) > 0)
							digest.update(buffer, 0, count);
					}
					if (!hex(digest.digest()).equals(expected))
						return "REFERENCE_CLASS_MISMATCH";
				}
			}
			return "MATCHED_SELECTED_CLASSES_AND_VERSION";
		}
		catch (Exception unavailable)
		{
			return "REFERENCE_CLASS_UNAVAILABLE";
		}
	}

	private static String hex(byte[] values)
	{
		StringBuilder out = new StringBuilder(64);
		for (byte value : values)
			out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
		return out.toString();
	}

	private static JsonObject object(JsonElement value)
	{
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
	}

	private static String string(JsonObject object, String key)
	{
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString()
			: null;
	}

	private static String artifactHash(String name)
	{
		return string(object(object(INFO.get("runeLiteArtifacts")).get(name)), "sha256");
	}

	private static String value(String key)
	{
		return string(INFO, key);
	}
}
