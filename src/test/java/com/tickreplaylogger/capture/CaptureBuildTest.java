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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CaptureBuildTest
{
	@Test
	public void standardCaptureKeepsRuntimeReferenceWithoutInventingCompileProvenance()
	{
		assertNull(CaptureBuild.sourceTreeHash());
		assertNull(CaptureBuild.compiledApiVersion());
		assertNull(CaptureBuild.compiledApiArtifactSha256());
		assertNull(CaptureBuild.compiledClientArtifactSha256());
		assertEquals("REVIEWED_RUNTIME_REFERENCE", CaptureBuild.artifactIdentityScope());
		assertEquals(CaptureBuild.runtimeVersion(), CaptureBuild.referenceVersion());
		assertEquals(CaptureBuild.referenceVersion(), CaptureBuild.apiVersion());
		assertEquals(CaptureBuild.referenceApiArtifactSha256(), CaptureBuild.apiArtifactSha256());
		assertEquals(CaptureBuild.referenceClientArtifactSha256(), CaptureBuild.clientArtifactSha256());
		assertTrue(CaptureBuild.referenceApiArtifactSha256().matches("[a-f0-9]{64}"));
		assertTrue(CaptureBuild.referenceClientArtifactSha256().matches("[a-f0-9]{64}"));
	}

	@Test
	public void allGuardedInterpretationBindingsRemainAvailableWithoutBuildMetadata()
	{
		CombatCatalog catalog = CombatCatalog.load(new Gson());
		JsonObject bindings = catalog.contextBindings();
		JsonArray statuses = bindings.getAsJsonArray("statusValueRules");
		assertEquals(7, statuses.size());
		for (JsonElement element : statuses)
		{
			JsonObject rule = element.getAsJsonObject();
			assertTrue(CaptureBuild.supportsClientReference(rule.get("clientApiVersion").getAsString(),
				rule.get("clientArtifactSha256").getAsString()));
			JsonObject range = rule.getAsJsonArray("cases").get(0).getAsJsonObject();
			assertEquals(range, catalog.localStatusCase(rule.get("key").getAsString(), range.get("min").getAsInt()));
		}
		JsonArray graphics = bindings.getAsJsonArray("graphicReferenceRules");
		assertEquals(7, graphics.size());
		for (JsonElement element : graphics)
		{
			JsonObject rule = element.getAsJsonObject();
			assertEquals(rule, catalog.localGraphicReference(rule.get("id").getAsInt()));
		}
		JsonArray modes = bindings.getAsJsonArray("encounterModeRules");
		assertEquals(1, modes.size());
		JsonObject mode = modes.get(0).getAsJsonObject();
		assertTrue(CaptureBuild.supportsClientReference(mode.get("clientApiVersion").getAsString(),
			mode.get("clientArtifactSha256").getAsString()));
		assertNull(CaptureBuild.compiledApiVersion());
	}
}
