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
	private static <T> T stub(Class<T> type,java.util.function.BiFunction<String,Object[],Object> values)
	{
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)-> {
			if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
			if (method.getName().equals("equals")) return proxy==args[0];
			Object result=values.apply(method.getName(),args); if (result!=null) return result;
			if (method.getReturnType()==boolean.class) return false;
			if (method.getReturnType()==int.class) return 0;
			if (method.getReturnType()==long.class) return 0L;
			return null;
		}));
	}
	private ItemContainer inventory(AtomicInteger reads,Item... items)
	{
		return stub(ItemContainer.class,(n,a)-> {
			if (n.equals("getItems")) { reads.incrementAndGet(); return items; }
			if (n.equals("getItem")) { int i=(Integer)a[0]; return i>=0 && i<items.length?items[i]:null; }
			return null;
		});
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

}
