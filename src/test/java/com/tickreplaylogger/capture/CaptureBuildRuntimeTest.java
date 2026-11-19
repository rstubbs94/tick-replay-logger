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
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class CaptureBuildRuntimeTest
{
	@Test
	public void selectedClassAndVersionWitnessesQualifyWithoutClaimingAContainerHash() throws Exception
	{
		Fixture f = new Fixture();
		assertEquals("MATCHED_SELECTED_CLASSES_AND_VERSION", f.verify("test-version"));
		assertEquals(6, f.reads.get());
	}

	@Test
	public void aDifferentRuntimeVersionCannotReuseTheCompiledTarget() throws Exception
	{
		Fixture f = new Fixture();
		assertEquals("RUNTIME_VERSION_MISMATCH", f.verify("different-version"));
		assertEquals(0, f.reads.get());
		assertEquals("RUNTIME_VERSION_UNAVAILABLE", f.verify(null));
	}

	@Test
	public void changedOrMissingRuntimeClassResourcesAreUnsupported() throws Exception
	{
		Fixture f = new Fixture();
		String path = CaptureBuild.REFERENCE_CLASSES.get("runelite-api").get(0);
		f.resources.put(path, "different loaded class".getBytes(StandardCharsets.UTF_8));
		assertEquals("REFERENCE_CLASS_MISMATCH", f.verify("test-version"));
		f.resources.remove(path);
		assertEquals("REFERENCE_CLASS_UNAVAILABLE", f.verify("test-version"));
	}

	@Test
	public void missingIncompleteFutureOrExtraManifestsCannotQualify() throws Exception
	{
		Fixture f = new Fixture();
		assertEquals("REFERENCE_MANIFEST_UNAVAILABLE",
			CaptureBuild.verifyReferenceRuntime(new JsonObject(), "test-version", p -> null));
		f.info.addProperty("runtimeReferencePolicy", "future-policy");
		assertEquals("REFERENCE_MANIFEST_UNAVAILABLE", f.verify("test-version"));
		f.info.addProperty("runtimeReferencePolicy", "SELECTED_CLASSES_AND_VERSION_V1");
		JsonObject hashes = f.reference.getAsJsonObject("artifacts").getAsJsonObject("runelite-api")
			.getAsJsonObject("referenceClassSha256s");
		hashes.addProperty("extra.class", "a".repeat(64));
		assertEquals("INVALID_REFERENCE_CLASS_MANIFEST", f.verify("test-version"));
		hashes.remove("extra.class");
		hashes.remove(CaptureBuild.REFERENCE_CLASSES.get("runelite-api").get(0));
		assertEquals("INVALID_REFERENCE_CLASS_MANIFEST", f.verify("test-version"));
	}

	@Test
	public void actualClasspathKeepsSupportedBindingsWithoutCompileProvenance()
	{
		assertEquals("REVIEWED_RUNTIME_REFERENCE", CaptureBuild.artifactIdentityScope());
		assertEquals("MATCHED_SELECTED_CLASSES_AND_VERSION", CaptureBuild.runtimeReferenceVerification());
		assertEquals(CaptureBuild.runtimeVersion(), CaptureBuild.apiVersion());
		assertNull(CaptureBuild.sourceTreeHash());
		assertNull(CaptureBuild.compiledApiVersion());
		assertNull(CaptureBuild.compiledApiArtifactSha256());
		assertNull(CaptureBuild.compiledClientArtifactSha256());
		assertTrue(CaptureBuild.supportsClientReference(CaptureBuild.referenceVersion(),
			CaptureBuild.referenceClientArtifactSha256()));
		assertFalse(CaptureBuild.supportsClientReference(CaptureBuild.referenceVersion(), "0".repeat(64)));
		assertFalse(
			CaptureBuild.supportsClientReference("different-version", CaptureBuild.referenceClientArtifactSha256()));
		assertSame("Cached result, no per-tick resource scan", CaptureBuild.runtimeReferenceVerification(),
			CaptureBuild.runtimeReferenceVerification());
	}

	@Test
	public void duplicateVersionsCannotSelectAnArbitraryReference() throws Exception
	{
		Fixture f = new Fixture();
		f.info.getAsJsonArray("references").add(f.reference.deepCopy());
		assertEquals("INVALID_REFERENCE_CLASS_MANIFEST", f.verify("test-version"));
		assertEquals(0, f.reads.get());
	}

	@Test
	public void aCompileManifestCannotMasqueradeAsAReviewedRuntimeReference() throws Exception
	{
		Fixture f = new Fixture();
		f.info.addProperty("artifactIdentityScope", "COMPILE_CLASSPATH_REFERENCE");
		assertEquals("REFERENCE_MANIFEST_UNAVAILABLE", f.verify("test-version"));
		assertEquals(0, f.reads.get());
	}

	private static final class Fixture
	{
		final JsonObject info = new JsonObject();
		final JsonObject reference = new JsonObject();
		final Map<String, byte[]> resources = new HashMap<>();
		final AtomicInteger reads = new AtomicInteger();

		Fixture() throws Exception
		{
			info.addProperty("schemaVersion", 1);
			info.addProperty("artifactIdentityScope", "REVIEWED_RUNTIME_REFERENCE");
			info.addProperty("runtimeReferencePolicy", "SELECTED_CLASSES_AND_VERSION_V1");
			JsonArray references = new JsonArray();
			references.add(reference);
			info.add("references", references);
			reference.addProperty("version", "test-version");
			JsonObject artifacts = new JsonObject();
			reference.add("artifacts", artifacts);
			for (Map.Entry<String, List<String>> group : CaptureBuild.REFERENCE_CLASSES.entrySet())
			{
				JsonObject artifact = new JsonObject();
				artifact.addProperty("sha256", "a".repeat(64));
				JsonObject hashes = new JsonObject();
				artifact.add("referenceClassSha256s", hashes);
				artifacts.add(group.getKey(), artifact);
				for (String path : group.getValue())
				{
					byte[] bytes = ("independent class witness: " + path).getBytes(StandardCharsets.UTF_8);
					resources.put(path, bytes);
					StringBuilder hash = new StringBuilder();
					for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
						hash.append(String.format(Locale.ROOT, "%02x", b & 255));
					hashes.addProperty(path, hash.toString());
				}
			}
		}

		String verify(String version)
		{
			return CaptureBuild.verifyReferenceRuntime(info, version, path ->
			{
				reads.incrementAndGet();
				byte[] bytes = resources.get(path);
				return bytes == null ? null : new ByteArrayInputStream(bytes);
			});
		}
	}
}
