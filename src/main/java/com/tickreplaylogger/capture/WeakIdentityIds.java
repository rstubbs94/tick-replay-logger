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

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.*;

/** Monotonic object identities without retaining unloaded API containers. Hashes are bucket hints only. */
final class WeakIdentityIds
{
	private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
	private final Map<Key, Long> ids = new HashMap<>();
	private long sequence;

	private static final class Key extends WeakReference<Object>
	{
		private final int hash;

		Key(Object value, ReferenceQueue<Object> queue)
		{
			super(value, queue);
			hash = System.identityHashCode(value);
		}

		@Override
		public int hashCode()
		{
			return hash;
		}

		@Override
		public boolean equals(Object other)
		{
			return this == other || other instanceof Key && get() != null && get() == ((Key) other).get();
		}
	}

	private void clean()
	{
		Key key;
		while ((key = (Key) queue.poll()) != null)
		{
			ids.remove(key);
		}
	}

	synchronized long id(Object value)
	{
		if (value == null)
		{
			return 0;
		}
		clean();
		Key lookup = new Key(value, null);
		Long id = ids.get(lookup);
		if (id == null)
		{
			sequence = Math.incrementExact(sequence);
			id = sequence;
			ids.put(new Key(value, queue), id);
		}
		return id;
	}

	synchronized void invalidate(Object value)
	{
		clean();
		if (value != null)
		{
			ids.remove(new Key(value, null));
		}
	}
}
