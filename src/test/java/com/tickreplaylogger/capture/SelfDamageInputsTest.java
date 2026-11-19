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
import org.junit.Test;
import static org.junit.Assert.*;

public class SelfDamageInputsTest
{
	@Test
	public void delayedOverloadsRemainCandidatesAndUnrelatedDropOperationsDoNot()
	{
		SelfDamageInputs tracker = new SelfDamageInputs();
		CombatCatalog catalog = CombatCatalog.load(new Gson());
		tracker.observe(catalog, 11730, "Drink", 10, 1);
		assertEquals(1, tracker.candidates(18).size());
		assertTrue(tracker.candidates(23).isEmpty());
		tracker.observe(catalog, 23685, "Drop", 30, 2);
		assertTrue(tracker.candidates(30).isEmpty());
		tracker.observe(catalog, 22081, "Feel", 30, 3);
		assertEquals("Locator orb", tracker.candidates(30).get(0).get("name"));
	}

	@Test
	public void specialEnergyAndWeaponOnlyProvideAQualifiedCandidate()
	{
		SelfDamageInputs tracker = new SelfDamageInputs();
		CombatCatalog catalog = CombatCatalog.load(new Gson());
		tracker.observeResourceDecrease(catalog, 28997, 40, 10);
		assertEquals("SELF_DAMAGE_RESOURCE_CANDIDATE", tracker.candidates(40).get(0).get("kind"));
		assertEquals("Blood Infusion (Dual macuahuitl)", tracker.candidates(40).get(0).get("name"));
		assertTrue(tracker.candidates(43).isEmpty());
		tracker.observeResourceDecrease(catalog, 4151, 44, 11);
		assertTrue(tracker.candidates(44).isEmpty());
	}

	@Test
	public void aReferenceOnlyItemVariantCannotEnterTheScopedSelfDamageOperation()
	{
		SelfDamageInputs tracker = new SelfDamageInputs();
		CombatCatalog catalog = CombatCatalog.load(new Gson());
		com.google.gson.JsonObject device = null;
		for (com.google.gson.JsonElement value : catalog.selfDamageInputs())
			if ("Strange device".equals(value.getAsJsonObject().get("page").getAsString()))
				device = value.getAsJsonObject();
		assertNotNull(device);
		assertEquals("Both collected variants remain reference identities", 2, device.getAsJsonArray("itemIds").size());
		assertEquals("Only the source-qualified operation variant can supply an HP hint", 1, device.getAsJsonArray("candidateItemIds").size());
		assertEquals(19939, device.getAsJsonArray("candidateItemIds").get(0).getAsInt());
		tracker.observe(catalog, 23183, "Feel", 10, 1);
		assertTrue(tracker.candidates(10).isEmpty());
		tracker.observe(catalog, 19939, "Feel", 11, 2);
		assertEquals(1, tracker.candidates(11).size());
		assertEquals("INPUT_CORRELATION_NOT_EXECUTION_PROOF", tracker.candidates(11).get(0).get("evidence"));
		assertEquals(Integer.valueOf(19939), tracker.candidates(11).get(0).get("itemId"));
	}

	@Test
	public void distinctOperationsOfTheSameItemRemainSeparateSourceWitnesses()
	{
		SelfDamageInputs tracker = new SelfDamageInputs();
		CombatCatalog catalog = CombatCatalog.load(new Gson());
		tracker.observe(catalog, 7509, "Eat", 10, 1);
		tracker.observe(catalog, 7509, "Guzzle", 10, 2);
		tracker.observe(catalog, 7509, "Guzzle", 10, 2); // Duplicate delivery of the same observation.
		java.util.List<java.util.Map<String, Object>> candidates = tracker.candidates(10);
		assertEquals(2, candidates.size());
		assertEquals("Eat", candidates.get(0).get("option"));
		assertEquals("Guzzle", candidates.get(1).get("option"));
		assertEquals(candidates.get(0).get("sourceRef"), candidates.get(1).get("sourceRef"));
		assertEquals(Long.valueOf(1), candidates.get(0).get("inputSequence"));
		assertEquals(Long.valueOf(2), candidates.get(1).get("inputSequence"));
	}
}
