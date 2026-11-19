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
import com.tickreplaylogger.model.world.NpcDefinitionStateReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class DefinitionStateReferenceTest
{
	private final CombatCatalog catalog = CombatCatalog.load(new Gson());

	@Test
	public void sourceDisagreementsDoNotTurnMeleeOrSupportIntoSpawningBosses()
	{
		assertEquals("Spawning form", ref(10786, "Nylocas Vasilias", 301, 4).getForm());
		assertEquals("Melee form", ref(10787, "Nylocas Vasilias", 301, 4).getForm());
		assertEquals("Magic form", ref(10788, "Nylocas Vasilias", 301, 4).getForm());
		assertEquals("Ranged form", ref(10789, "Nylocas Vasilias", 301, 4).getForm());
		NpcDefinitionStateReference support = ref(10790, null, 0, 3);
		assertEquals("Support", support.getRole());
		assertEquals("Nylocas room support", support.getName());
	}

	@Test
	public void identityShapeAndContextEachHaveToBeObserved()
	{
		assertNotNull(ref(10823, "The Maiden of Sugadinti", 1128, 6));
		assertNull(ref(10823, "Other creature", 1128, 6));
		assertNull(ref(10823, "The Maiden of Sugadinti", 940, 6));
		assertNull(ref(10823, "The Maiden of Sugadinti", 1128, 5));
		assertNull(catalog.definitionStateReference(10823, "The Maiden of Sugadinti", false, 1128, 6, true));
		assertNull(catalog.definitionStateReference(10823, "The Maiden of Sugadinti", true, null, 6, true));
		assertNull(catalog.definitionStateReference(10823, "The Maiden of Sugadinti", true, 1128, null, true));
		assertNull(catalog.definitionStateReference(10823, "The Maiden of Sugadinti", true, 1128, 6, false));
		assertNull(catalog.definitionStateReference(null, "The Maiden of Sugadinti", true, 1128, 6, true));
	}

	@Test
	public void pooledWikiAndInactiveFormsCannotSupplyAnotherModesState()
	{
		assertEquals("Entry", ref(10821, "Blood spawn", 35, 1).getModeReference());
		assertNull(ref(10821, "Blood spawn", 55, 1));
		assertEquals("Combat form (phases 2 / 3)", ref(8340, "Xarpus", 960, 5).getForm());
		assertEquals("Inactive form (pre-fight / maze)", ref(8387, "Sotetseg", 995, 5).getForm());
		assertNull(ref(8250, "Verzik Vitur", 0, 5));
		assertNull(ref(11178, "Verzik Vitur", 0, 1));
	}

	@Test
	public void unnamedDefinitionsStillRequireAKnownNameReadAndPreserveSourceIdentity()
	{
		NpcDefinitionStateReference tornado = ref(10863, "null", 1, 1);
		assertEquals("Hazard", tornado.getRole());
		assertEquals("Verzik tornado", tornado.getName());
		assertEquals(catalog.id(), tornado.getCatalogId());
		assertFalse(tornado.getSourceRefs().isEmpty());
		assertNull(catalog.definitionStateReference(10863, null, false, 1, 1, true));
		assertNull(ref(10863, "Unrelated creature", 1, 1));
	}

	@Test
	public void verzikEnrageCueRequiresTheThirdPhaseAndCannotSurviveItsDeathDefinition()
	{
		net.runelite.api.NPC npc = PlayerSnapshotCaptureTest.stub(net.runelite.api.NPC.class, (name, args) -> null);
		ObservedMechanics mechanics = new ObservedMechanics();
		String cue = "I'm not finished with you just yet!";
		mechanics.observe(catalog, npc, 8370, "overheadEquals", cue, -1, java.util.Collections.singleton(npc), 1,
			"OVERHEAD_TEXT_SNAPSHOT");
		assertNull(mechanics.phase(npc));
		mechanics.observe(catalog, npc, 8374, "overheadEquals", cue, -1, java.util.Collections.singleton(npc), 2,
			"OVERHEAD_TEXT_SNAPSHOT");
		assertEquals("Enraged", mechanics.phase(npc).getLabel());
		mechanics.validateIdentity(catalog, npc, 8375, true);
		assertNull(mechanics.phase(npc));
	}

	private NpcDefinitionStateReference ref(int id, String name, int level, int size)
	{
		return catalog.definitionStateReference(id, name, true, level, size, true);
	}
}
