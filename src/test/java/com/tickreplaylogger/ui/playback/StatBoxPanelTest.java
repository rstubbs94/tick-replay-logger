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
package com.tickreplaylogger.ui.playback;

import java.lang.reflect.Field;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class StatBoxPanelTest
{
	@Test
	public void unavailableReadIsNotZeroAndRecoveryCannotBecomeHealing() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			StatBoxPanel panel = new StatBoxPanel("HP", true);
			panel.setObservedValue(99, 99);
			panel.setObservedValue(null, 99);
			assertEquals("?/99", label(panel, "valueLabel").getText());
			panel.setObservedValue(88, 99);
			assertEquals("88/99", label(panel, "valueLabel").getText());
			assertEquals("", label(panel, "changeLabel").getText());
		});
	}

	@Test
	public void observedZeroRemainsARealValue() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			StatBoxPanel panel = new StatBoxPanel("HP", true);
			panel.setObservedValue(0, 99);
			assertEquals("0/99", label(panel, "valueLabel").getText());
		});
	}

	private JLabel label(StatBoxPanel panel, String name)
	{
		try
		{
			Field field = StatBoxPanel.class.getDeclaredField(name);
			field.setAccessible(true);
			return (JLabel) field.get(panel);
		}
		catch (ReflectiveOperationException error)
		{
			throw new AssertionError(error);
		}
	}
}
