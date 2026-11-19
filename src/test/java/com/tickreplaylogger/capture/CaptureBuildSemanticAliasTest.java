/* Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause */
package com.tickreplaylogger.capture;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Semantic compatibility is an exact reviewed artifact pair, not a version-range guess. */
public class CaptureBuildSemanticAliasTest
{
	@Test
	public void onlyTheExactReviewedPairIsSupportedAfterRuntimeVerification() throws Exception
	{
		JsonObject manifest = resource("runtime-reference.json"), current = reference(manifest, "1.13.7");
		String oldHash = clientHash(reference(manifest, "1.13.6"));
		assertTrue(CaptureBuild.supportsClientReference(manifest, current, true, "1.13.6", oldHash));
		assertTrue(CaptureBuild.supportsClientReference(manifest, current, true, "1.13.7", clientHash(current)));
		assertFalse(CaptureBuild.supportsClientReference(manifest, current, false, "1.13.6", oldHash));
		assertFalse(CaptureBuild.supportsClientReference(manifest, current, true, "1.13.8", oldHash));
		assertFalse(CaptureBuild.supportsClientReference(manifest, current, true, "1.13.6", clientHash(current)));
	}

	@Test
	public void malformedDuplicateAndUnknownReferenceAliasesCannotEnableInterpretation() throws Exception
	{
		for (String mutation : Arrays.asList("scope", "proof", "duplicate", "unknownReference"))
		{
			JsonObject manifest = resource("runtime-reference.json"), current = reference(manifest, "1.13.7");
			JsonArray aliases = current.getAsJsonArray("semanticReferenceAliases");
			JsonObject alias = aliases.get(0).getAsJsonObject();
			String oldHash = alias.get("clientArtifactSha256").getAsString();
			if ("scope".equals(mutation))
			{
				alias.addProperty("scope", "ANY_VERSION");
			}
			if ("proof".equals(mutation))
			{
				alias.remove("reviewedConsumerSetSha256");
			}
			if ("duplicate".equals(mutation))
			{
				aliases.add(alias.deepCopy());
			}
			if ("unknownReference".equals(mutation))
			{
				manifest.getAsJsonArray("references").remove(0);
			}
			assertFalse(mutation, CaptureBuild.supportsClientReference(manifest, current, true, "1.13.6", oldHash));
		}
	}

	@Test
	public void compatibilityReviewPinsEveryExistingSixDeclarationWithoutChangingItsProvenance() throws Exception
	{
		JsonObject current = reference(resource("runtime-reference.json"), "1.13.7");
		JsonObject alias = current.getAsJsonArray("semanticReferenceAliases").get(0).getAsJsonObject();
		List<JsonObject> consumers = new ArrayList<>();
		collect(resource("runtime-mechanics.json").get("references"), "references", consumers);
		consumers.sort(Comparator.comparing(row -> row.get("path").getAsString()));
		assertEquals(18, consumers.size());
		assertEquals(15, alias.getAsJsonArray("reviewedBindings").size());
		JsonArray sorted = new JsonArray();
		for (JsonObject row : consumers)
		{
			sorted.add(canonical(row));
		}
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(sorted.toString().getBytes(StandardCharsets.UTF_8));
		StringBuilder hex = new StringBuilder();
		for (byte value : hash)
		{
			hex.append(String.format(Locale.ROOT, "%02x", value & 255));
		}
		assertEquals(alias.get("reviewedConsumerSetSha256").getAsString(), hex.toString());
	}

	private static void collect(JsonElement value, String path, List<JsonObject> result)
	{
		if (value.isJsonObject())
		{
			JsonObject row = value.getAsJsonObject();
			if (row.has("clientApiVersion") && "1.13.6".equals(row.get("clientApiVersion").getAsString()))
			{
				JsonObject consumer = new JsonObject();
				consumer.addProperty("path", path);
				consumer.add("declaration", row);
				result.add(consumer);
			}
			for (Map.Entry<String, JsonElement> field : row.entrySet())
			{
				collect(field.getValue(), path + "." + field.getKey(), result);
			}
		}
		else if (value.isJsonArray())
		{
			for (int i = 0; i < value.getAsJsonArray().size(); i++)
			{
				collect(value.getAsJsonArray().get(i), path + "[" + i + "]", result);
			}
		}
	}

	private static JsonElement canonical(JsonElement value)
	{
		if (value.isJsonObject())
		{
			JsonObject result = new JsonObject();
			for (String key : new TreeSet<>(value.getAsJsonObject().keySet()))
			{
				result.add(key, canonical(value.getAsJsonObject().get(key)));
			}
			return result;
		}
		if (value.isJsonArray())
		{
			JsonArray result = new JsonArray();
			for (JsonElement child : value.getAsJsonArray())
			{
				result.add(canonical(child));
			}
			return result;
		}
		return value;
	}

	private static JsonObject resource(String name) throws IOException
	{
		try (Reader input = new InputStreamReader(CaptureBuildSemanticAliasTest.class.getResourceAsStream("/com/tickreplaylogger/" + name), StandardCharsets.UTF_8))
		{
			return new JsonParser().parse(input).getAsJsonObject();
		}
	}

	private static JsonObject reference(JsonObject manifest, String version)
	{
		for (JsonElement row : manifest.getAsJsonArray("references"))
		{
			if (version.equals(row.getAsJsonObject().get("version").getAsString()))
			{
				return row.getAsJsonObject();
			}
		}
		throw new AssertionError("Missing reviewed reference " + version);
	}

	private static String clientHash(JsonObject reference)
	{
		return reference.getAsJsonObject("artifacts").getAsJsonObject("client").get("sha256").getAsString();
	}
}
