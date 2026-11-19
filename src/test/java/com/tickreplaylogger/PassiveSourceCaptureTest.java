/*
 * Copyright (c) 2026, rstubbs94
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.tickreplaylogger;

import com.tickreplaylogger.capture.ObservedInventorySourceHintsTest;
import com.tickreplaylogger.model.world.DeathRecordingV2;
import com.tickreplaylogger.model.world.DamageRecord;
import com.tickreplaylogger.model.world.ObservedEvent;
import com.tickreplaylogger.model.world.SourceResolution;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Actor;
import net.runelite.api.HitsplatID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.AnimationChanged;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Uses the existing actual-handler fixture, not a parallel combat or inventory simulator. */
public class PassiveSourceCaptureTest
{
	private DeferredAttributionTest fixture;
	@Before public void setup() throws Exception { fixture=new DeferredAttributionTest(); fixture.temporary.create(); fixture.setup(); }
	@After public void cleanup() throws Exception { if (fixture!=null) { fixture.closeStorage(); fixture.temporary.delete(); fixture=null; } }
	private Object get(String name) throws Exception { Field f=DeferredAttributionTest.class.getDeclaredField(name); f.setAccessible(true); return f.get(fixture); }
	private void set(String name,Object value) throws Exception { Field f=DeferredAttributionTest.class.getDeclaredField(name); f.setAccessible(true); f.set(fixture,value); }
	private Object call(String name,Class<?>[] types,Object... args) throws Exception
	{ Method m=DeferredAttributionTest.class.getDeclaredMethod(name,types); m.setAccessible(true); return m.invoke(fixture,args); }
	private void tick() throws Exception { call("tick",new Class<?>[0]); }
	private void hit(int type,int amount) throws Exception { call("hit",new Class<?>[]{int.class,int.class},type,amount); }
	private DeathRecordingV2 export() throws Exception { return (DeathRecordingV2)call("export",new Class<?>[0]); }
	private List<DamageRecord> damage() throws Exception { return export().getEvents().getDamageEvents(); }
	private static boolean item(SourceResolution resolution)
	{ return resolution!=null && resolution.getCandidates().stream().anyMatch(c->"item-possession-rule:inventory_source_hint:tick".equals(c.getFamilyKey())); }
	private static boolean proximity(SourceResolution resolution)
	{ return resolution!=null && resolution.getCandidates().stream().anyMatch(c->c.getEvidence().stream().anyMatch(e->e.startsWith("NPC_PROXIMITY_SOURCE_RULE:"))); }
	private ItemContainer inventory(AtomicInteger reads,Item... items)
	{
		return ObservedInventorySourceHintsTest.stub(ItemContainer.class,(n,a)-> {
			if (n.equals("getItems")) { reads.incrementAndGet(); return items; }
			if (n.equals("getItem")) { int i=(Integer)a[0]; return i>=0 && i<items.length?items[i]:null; }
			return null;
		});
	}
	@SuppressWarnings("unchecked") private List<ObservedEvent> raw() throws Exception { return export().getEvents().getObservations(); }
	private static boolean excludedItem(SourceResolution resolution)
	{ return resolution!=null && resolution.getExcludedCandidates()!=null && resolution.getExcludedCandidates().stream()
		.anyMatch(c->"item-possession-rule:inventory_source_hint:tick".equals(c.getFamilyKey())
			&& c.getReasons().stream().anyMatch(r->r.startsWith("MAXIMUM_EXCEEDED:"))); }

	@Test public void originalNormalInventoryMaximumRejectsFiveButOneRemainsOnlyPossible() throws Exception
	{
		tick(); set("inventory",inventory(new AtomicInteger(),new Item(33793,1)));
		hit(HitsplatID.DAMAGE_ME,5);
		// The later settled world must not replace the original normal callback sample.
		set("worldTypes",java.util.EnumSet.of(net.runelite.api.WorldType.SEASONAL)); set("hp",94); tick();
		DamageRecord five=damage().get(0); assertEquals(5,five.getAmount()); assertFalse(item(five.getSourceResolution()));
		assertTrue(excludedItem(five.getSourceResolution())); assertTrue(five.getSourceResolution().isUnidentifiedPossible());
		set("worldTypes",java.util.EnumSet.noneOf(net.runelite.api.WorldType.class));
		hit(HitsplatID.DAMAGE_ME,1); set("hp",93); tick();
		DamageRecord one=damage().get(1); assertEquals(1,one.getAmount()); assertTrue(item(one.getSourceResolution()));
		assertTrue(one.getSourceResolution().isUnidentifiedPossible());
		SourceResolution.Candidate source=one.getSourceResolution().getCandidates().stream().filter(c->c.getFamilyKey()!=null
			&& c.getFamilyKey().startsWith("item-possession-rule:")).findFirst().get();
		assertNull(source.getActorRef()); assertNull(source.getNpcId()); assertEquals("POSSIBLE",source.getStrength());
	}

	@Test public void originalInventoryMaximumKeepsConcurrentActualNpcAndUnknownForFiveDamage() throws Exception
	{
		tick(); set("inventory",inventory(new AtomicInteger(),new Item(33793,1)));
		set("animation",990001); AnimationChanged active=new AnimationChanged(); active.setActor((NPC)get("boss"));
		((TickReplayLoggerPlugin)get("plugin")).onAnimationChanged(active);
		hit(HitsplatID.DAMAGE_ME,5); set("hp",94); tick();
		DamageRecord five=damage().get(0); assertEquals(5,five.getAmount()); assertTrue(excludedItem(five.getSourceResolution()));
		assertFalse(item(five.getSourceResolution()));
		assertTrue(five.getSourceResolution().getCandidates().stream().anyMatch(c->"Test boss".equals(c.getName())));
		assertTrue(five.getSourceResolution().isUnidentifiedPossible());
	}

	@Test public void missingOrExceptionalOriginalInventoryModeDoesNotBorrowLaterNormalContext() throws Exception
	{
		tick(); set("inventory",inventory(new AtomicInteger(),new Item(33793,1)));
		set("worldTypes",null); hit(HitsplatID.DAMAGE_ME,5);
		set("worldTypes",java.util.EnumSet.noneOf(net.runelite.api.WorldType.class)); set("hp",94); tick();
		DamageRecord missing=damage().get(0); assertEquals(5,missing.getAmount()); assertTrue(item(missing.getSourceResolution()));
		assertFalse(excludedItem(missing.getSourceResolution())); assertTrue(missing.getSourceResolution().isUnidentifiedPossible());
		set("worldTypes",java.util.EnumSet.of(net.runelite.api.WorldType.SEASONAL)); hit(HitsplatID.DAMAGE_ME,5);
		set("worldTypes",java.util.EnumSet.noneOf(net.runelite.api.WorldType.class)); set("hp",89); tick();
		DamageRecord exceptional=damage().get(1); assertEquals(5,exceptional.getAmount()); assertTrue(item(exceptional.getSourceResolution()));
		assertFalse(excludedItem(exceptional.getSourceResolution())); assertTrue(exceptional.getSourceResolution().isUnidentifiedPossible());
	}

	@Test public void originalInventoryPresenceSurvivesRemovalAndLaterInsertionCannotBeProjectedBackwards() throws Exception
	{
		AtomicInteger reads=new AtomicInteger(); tick();
		set("inventory",inventory(reads,new Item(33793,1))); hit(HitsplatID.DAMAGE_ME,1);
		set("inventory",inventory(reads)); set("hp",98); tick();
		assertEquals(1,damage().size()); DamageRecord first=damage().get(0); assertTrue(item(first.getSourceResolution()));
		assertTrue(first.getSourceResolution().isUnidentifiedPossible()); assertEquals(1,first.getAmount());
		assertTrue(first.getAttributionCandidates().stream().anyMatch(w->"ITEM_POSSESSION_CANDIDATE".equals(w.get("kind"))));
		assertTrue(first.getAttributionCandidates().stream().noneMatch(w->"SELF_DAMAGE_INPUT_CANDIDATE".equals(w.get("kind"))));
		ObservedEvent callback=raw().stream().filter(e->"HITSPLAT".equals(e.getType())).findFirst().get();
		Map<?,?> observation=(Map<?,?>)callback.getData().get("inventorySourceObservation");
		assertEquals("OBSERVED",observation.get("status")); assertEquals(first.getImpactTick(),((Number)observation.get("observedTick")).intValue());
		assertEquals(first.getObservedCycle(),observation.get("observedCycle")); assertEquals(Long.valueOf(callback.getSequence()),first.getObservationSequence());
		assertEquals(33793,((Map<?,?>)((List<?>)observation.get("slots")).get(0)).get("itemId"));
		hit(HitsplatID.DAMAGE_ME,1); set("inventory",inventory(reads,new Item(33793,1))); set("hp",97); tick();
		assertEquals(2,damage().size()); assertFalse(item(damage().get(1).getSourceResolution()));
		assertEquals(2,damage().stream().mapToInt(DamageRecord::getAmount).sum());
		assertEquals(2,raw().stream().filter(e->"HITSPLAT".equals(e.getType())).count());
	}

	@Test public void multipleStacksRemainOnePossibleSourceBesideTheActualNpcRival() throws Exception
	{
		tick(); set("inventory",inventory(new AtomicInteger(),new Item(33793,100),new Item(33793,1)));
		set("animation",990001); AnimationChanged active=new AnimationChanged(); active.setActor((NPC)get("boss"));
		((TickReplayLoggerPlugin)get("plugin")).onAnimationChanged(active);
		hit(HitsplatID.DAMAGE_ME,1); set("hp",98); tick();
		DamageRecord observed=damage().get(0); assertEquals(1,observed.getAmount()); assertTrue(item(observed.getSourceResolution()));
		assertEquals(1,observed.getSourceResolution().getCandidates().stream().filter(c->c.getFamilyKey()!=null && c.getFamilyKey().startsWith("item-possession-rule:")).count());
		assertTrue(observed.getSourceResolution().getCandidates().stream().anyMatch(c->"Test boss".equals(c.getName())));
		assertTrue(observed.getSourceResolution().isUnidentifiedPossible());
		SourceResolution.Candidate item=observed.getSourceResolution().getCandidates().stream().filter(c->c.getFamilyKey()!=null && c.getFamilyKey().startsWith("item-possession-rule:")).findFirst().get();
		assertNull(item.getActorRef()); assertNull(item.getNpcId()); assertEquals("POSSIBLE",item.getStrength());
	}

	@Test public void settingsOffCannotReadInventoryThroughTheNewAttributionPath() throws Exception
	{
		for (boolean rich:new boolean[]{true,false})
		{
			AtomicInteger reads=new AtomicInteger(); set("inventory",inventory(reads,new Item(33793,1)));
			Method inject=DeferredAttributionTest.class.getDeclaredMethod("inject",String.class,Object.class); inject.setAccessible(true);
			inject.invoke(fixture,"config",new TickReplayLoggerConfig() {
				@Override public boolean trackDamageReceived() { return !rich; }
				@Override public boolean captureWorldSnapshots() { return rich; }
				@Override public boolean captureSceneCollision() { return false; }
			});
			hit(HitsplatID.DAMAGE_ME,1); assertEquals(0,reads.get());
		}
		assertTrue(damage().isEmpty());
	}

	@Test public void statusZeroAndForeignRecipientCannotObtainPossessionCandidatesAndUnknownContainerIsExplicit() throws Exception
	{
		tick(); AtomicInteger reads=new AtomicInteger(); set("inventory",inventory(reads,new Item(33793,1)));
		hit(HitsplatID.VENOM,1); hit(HitsplatID.BLOCK_ME,0);
		call("hit",new Class<?>[]{Actor.class,int.class,int.class},get("boss"),HitsplatID.DAMAGE_ME,1);
		assertEquals(0,reads.get()); tick();
		assertTrue(damage().stream().noneMatch(d->item(d.getSourceResolution())));
		assertTrue(raw().stream().filter(e->"HITSPLAT".equals(e.getType())).allMatch(e->e.getData().get("inventorySourceObservation")==null));
		set("inventory",null); hit(HitsplatID.DAMAGE_ME,1); tick();
		ObservedEvent last=raw().stream().filter(e->"HITSPLAT".equals(e.getType())).reduce((a,b)->b).get();
		assertEquals("UNAVAILABLE",((Map<?,?>)last.getData().get("inventorySourceObservation")).get("status"));
		DamageRecord unresolved=damage().get(damage().size()-1); assertFalse(item(unresolved.getSourceResolution()));
		assertTrue(unresolved.getAttributionCandidates().stream().anyMatch(w->"INVENTORY_SOURCE_CONTEXT_UNAVAILABLE".equals(w.get("kind"))));
		assertTrue(unresolved.getSourceResolution().isUnidentifiedPossible());
	}

	@Test public void actualTaggedOddTreeUsesDiagonalProximityWithoutInventingAnAttackOrAnOverlapFootprint() throws Exception
	{
		call("useProjectileNativeView",new Class<?>[0]);
		set("npcId",16239); set("npcLevel",0); set("npcObservedName","<col=00ffff>Odd tree</col>"); set("animation",-1);
		NPC original=(NPC)get("boss"); NPC tree=(NPC)Proxy.newProxyInstance(NPC.class.getClassLoader(),new Class<?>[]{NPC.class},(p,m,a)-> {
			if (m.getName().equals("getWorldArea")) return new WorldArea(3200,3200,2,2,0);
			if (m.getName().equals("getWorldLocation")) return new WorldPoint(3200,3200,0);
			return m.invoke(original,a);
		});
		set("boss",tree); List<NPC> npcs=(List<NPC>)get("npcs"); npcs.clear(); npcs.add(tree); tick();
		int ordinal=0;
		for (int[] point:new int[][]{{3202,3202},{3201,3201},{3203,3202}})
		{
			set("position",new WorldPoint(point[0],point[1],0)); set("localPosition",new LocalPoint((point[0]-3150)*128+64,(point[1]-3150)*128+64,0));
			hit(HitsplatID.DAMAGE_ME,6); set("hp",99-6*(++ordinal)); tick(); DamageRecord observed=damage().get(damage().size()-1);
			assertEquals("point "+point[0]+","+point[1],ordinal==1,proximity(observed.getSourceResolution()));
			assertEquals(6,observed.getAmount()); assertEquals("UNKNOWN",observed.getDetermination());
			if (ordinal==1) { assertTrue(observed.getSourceResolution().isUnidentifiedPossible()); assertEquals("Odd tree",observed.getSourceResolution().getCandidates().get(0).getName()); }
		}
	}
}
