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

import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import net.runelite.api.*;
import net.runelite.client.*;

/** Reviewed runtime references, separate from compile provenance unavailable in standard packaging. */
public final class CaptureBuild
{
	private static final JsonObject REFERENCES = load();
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
		try (InputStream input = CaptureBuild.class.getResourceAsStream("/com/tickreplaylogger/runtime-reference.json"))
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
		static final String RESULT = verifyReferenceRuntime(REFERENCES, VERSION, CaptureBuild::runtimeResource);
		static final JsonObject REFERENCE = "MATCHED_SELECTED_CLASSES_AND_VERSION".equals(RESULT)
			? selectedReference(REFERENCES, VERSION)
			: new JsonObject();
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
		return null; // Standard packaging does not observe the source tree used by the compiler.
	}

	public static String artifactIdentityScope()
	{
		return runtimeReferenceMatched() ? "REVIEWED_RUNTIME_REFERENCE" : null;
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
		return null;
	}

	public static String compiledApiArtifactSha256()
	{
		return null;
	}

	public static String compiledClientArtifactSha256()
	{
		return null;
	}

	// Qualified reference aliases, never observations of the compiler or the complete loaded JAR.
	public static String apiVersion()
	{
		return referenceVersion();
	}

	public static String apiArtifactSha256()
	{
		return referenceApiArtifactSha256();
	}

	public static String clientArtifactSha256()
	{
		return referenceClientArtifactSha256();
	}

	public static String referenceVersion()
	{
		return string(RuntimeReference.REFERENCE, "version");
	}

	public static String referenceApiArtifactSha256()
	{
		return referenceArtifactHash("runelite-api");
	}

	public static String referenceClientArtifactSha256()
	{
		return referenceArtifactHash("client");
	}

	public static boolean runtimeReferenceMatched()
	{
		return "MATCHED_SELECTED_CLASSES_AND_VERSION".equals(RuntimeReference.RESULT);
	}

	public static boolean supportsClientReference(String version, String clientHash)
	{
		return supportsClientReference(REFERENCES, RuntimeReference.REFERENCE, runtimeReferenceMatched(), version,
			clientHash);
	}

	/** Exact reviewed artifact pairs only; an alias never changes the reported runtime reference. */
	static boolean supportsClientReference(JsonObject manifest, JsonObject runtime, boolean matched, String version,
		String clientHash)
	{
		if (!matched || manifest == null || runtime == null || version == null || clientHash == null)
		{
			return false;
		}
		if (version.equals(string(runtime, "version"))
			&& clientHash.equals(string(object(object(runtime.get("artifacts")).get("client")), "sha256")))
		{
			return true;
		}
		JsonElement aliases = runtime.get("semanticReferenceAliases");
		if (aliases == null || !aliases.isJsonArray())
		{
			return false;
		}
		int matches = 0;
		for (JsonElement value : aliases.getAsJsonArray())
		{
			JsonObject alias = object(value);
			if (!version.equals(string(alias, "version")) || !clientHash.equals(string(alias, "clientArtifactSha256")))
			{
				continue;
			}
			JsonObject prior = selectedReference(manifest, version);
			String proof = string(alias, "reviewedConsumerSetSha256");
			if (!"EXACT_REVIEWED_CLIENT_REFERENCE_PAIR_V1".equals(string(alias, "scope")) || prior == null
				|| proof == null || !proof.matches("[a-f0-9]{64}")
				|| !clientHash.equals(string(object(object(prior.get("artifacts")).get("client")), "sha256")))
			{
				return false;
			}
			matches++;
		}
		return matches == 1;
	}

	@FunctionalInterface
	interface ReferenceResources
	{
		InputStream open(String path) throws IOException;
	}

	static String verifyReferenceRuntime(JsonObject info, String runtimeVersion, ReferenceResources resources)
	{
		if (info == null || !info.has("schemaVersion") || !"1".equals(info.get("schemaVersion").toString())
			|| !"REVIEWED_RUNTIME_REFERENCE".equals(string(info, "artifactIdentityScope"))
			|| !"SELECTED_CLASSES_AND_VERSION_V1".equals(string(info, "runtimeReferencePolicy")))
		{
			return "REFERENCE_MANIFEST_UNAVAILABLE";
		}
		if (runtimeVersion == null)
		{
			return "RUNTIME_VERSION_UNAVAILABLE";
		}
		JsonObject reference = selectedReference(info, runtimeVersion);
		if (reference == null)
		{
			return "INVALID_REFERENCE_CLASS_MANIFEST";
		}
		if (reference.size() == 0)
		{
			return "RUNTIME_VERSION_MISMATCH";
		}
		JsonObject artifacts = object(reference.get("artifacts"));
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
				{
					return "INVALID_REFERENCE_CLASS_MANIFEST";
				}
				for (String path : group.getValue())
				{
					String expected = string(hashes, path);
					if (expected == null || !expected.matches("[a-f0-9]{64}"))
					{
						return "INVALID_REFERENCE_CLASS_MANIFEST";
					}
					MessageDigest digest = MessageDigest.getInstance("SHA-256");
					try (InputStream input = resources.open(path))
					{
						if (input == null)
						{
							return "REFERENCE_CLASS_UNAVAILABLE";
						}
						int count;
						while ((count = input.read(buffer)) > 0)
						{
							digest.update(buffer, 0, count);
						}
					}
					if (!hex(digest.digest()).equals(expected))
					{
						return "REFERENCE_CLASS_MISMATCH";
					}
				}
			}
			return "MATCHED_SELECTED_CLASSES_AND_VERSION";
		}
		catch (Exception unavailable)
		{
			return "REFERENCE_CLASS_UNAVAILABLE";
		}
	}

	private static JsonObject selectedReference(JsonObject manifest, String version)
	{
		JsonElement rows = manifest.get("references");
		JsonObject selected = new JsonObject();
		if (rows != null && rows.isJsonArray())
		{
			for (JsonElement value : rows.getAsJsonArray())
			{
				JsonObject row = object(value);
				if (version.equals(string(row, "version")))
				{
					if (selected.size() != 0)
					{
						return null;
					} // Conflicting declarations never select an arbitrary reference.
					selected = row;
				}
			}
		}
		return selected;
	}

	private static String hex(byte[] values)
	{
		StringBuilder out = new StringBuilder(64);
		for (byte value : values)
		{
			out.append(String.format(Locale.ROOT, "%02x", value & 255));
		}
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

	private static String referenceArtifactHash(String name)
	{
		return string(object(object(RuntimeReference.REFERENCE.get("artifacts")).get(name)), "sha256");
	}
}
