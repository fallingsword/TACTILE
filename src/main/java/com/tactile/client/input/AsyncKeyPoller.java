package com.tactile.client.input;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Background key poller (Windows only). It reads GetAsyncKeyState for a fixed set of virtual keys at a
 * target period and appends each state change to a single-producer/single-consumer ring buffer.
 *
 * <p>Threading contract: the poller thread touches nothing but its own fields and the ring buffer. It
 * never reads or writes Minecraft state. The game thread publishes the focus flag and drains events
 * with {@link #drain}. Each event carries the focus flag that was in effect when it was observed.
 */
public final class AsyncKeyPoller implements Runnable, KeySource
{
    private static final int CAPACITY = 1 << 16;
    private final long[] times = new long[CAPACITY];
    private final int[] packed = new int[CAPACITY];
    private final AtomicLong head = new AtomicLong(); // written by producer
    private long tail;                                // read by consumer only
    private volatile long dropped;

    private final Win32.User32 user32;
    private final int[] vks;
    private final boolean[] state;
    private final long periodNanos;
    private final long spinNanos;
    private volatile boolean running;
    private volatile boolean focused;
    private Thread thread;

    // Poll-interval histogram in 50 us buckets up to 50 ms (producer writes, read after stop)
    public static final int BUCKET_NS = 50_000;
    private final long[] intervalHist = new long[1001];
    private volatile long polls;
    private volatile long maxIntervalNs;

    /**
     * @param vks         virtual-key codes to poll (index in this array = key index in events)
     * @param periodNanos target poll period
     * @param spinNanos   spin instead of parking for the last {@code spinNanos} before each poll
     *                    (0 = park only; costs CPU when > 0)
     */
    /**
     * Spin budget for a target period. The high-resolution waitable timer has about 0.5 ms granularity
     * (measured p50 0.52 ms for a 500 µs request), so periods shorter than that (above 2000 Hz) can only be met
     * by spinning, at the cost of keeping one core busy.
     */
    public static long spinFor(long periodNanos)
    {
        return periodNanos < 500_000L ? periodNanos : 0L;
    }

    public AsyncKeyPoller(Win32.User32 user32, int[] vks, long periodNanos, long spinNanos)
    {
        this.user32 = user32;
        this.vks = vks.clone();
        this.state = new boolean[vks.length];
        this.periodNanos = periodNanos;
        this.spinNanos = spinNanos;
    }

    public void setFocused(boolean focused)
    {
        this.focused = focused;
    }

    public synchronized void start()
    {
        if (running) return;
        running = true;
        thread = new Thread(this, "tactile-key-poller");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public synchronized void stop()
    {
        running = false;
        if (thread != null)
        {
            try
            {
                thread.join(200);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    @Override
    public void run()
    {
        // Initial state is emitted as events so the consumer starts from a known state.
        long now = System.nanoTime();
        for (int i = 0; i < vks.length; ++i)
        {
            state[i] = vks[i] != 0 && Win32.isDown(user32, vks[i]);
            publish(now, i, state[i], focused);
        }
        Win32.PreciseSleeper sleeper = new Win32.PreciseSleeper();
        highResolutionTimer = sleeper.isHighResolution();
        long next = now + periodNanos;
        long last = now;
        while (running)
        {
            long wait = next - System.nanoTime();
            if (wait > spinNanos) sleeper.sleepNanos(wait - spinNanos);
            while (spinNanos > 0 && System.nanoTime() < next) Thread.yield();

            long t = System.nanoTime();
            boolean f = focused;
            for (int i = 0; i < vks.length; ++i)
            {
                if (vks[i] == 0) continue;
                boolean down = Win32.isDown(user32, vks[i]);
                if (down != state[i])
                {
                    state[i] = down;
                    publish(t, i, down, f);
                }
            }

            long interval = t - last;
            last = t;
            int b = (int)Math.min(intervalHist.length - 1, interval / BUCKET_NS);
            intervalHist[b]++;
            polls = polls + 1;
            if (interval > maxIntervalNs) maxIntervalNs = interval;

            next += periodNanos;
            if (t - next > 10 * periodNanos) next = t + periodNanos; // resynchronise after a long stall
        }
        sleeper.close();
    }

    private volatile boolean highResolutionTimer;

    public boolean usesHighResolutionTimer()
    {
        return highResolutionTimer;
    }

    private void publish(long nanos, int key, boolean down, boolean f)
    {
        long h = head.get();
        if (h - tailSnapshot >= CAPACITY)
        {
            tailSnapshot = consumerTail;
            if (h - tailSnapshot >= CAPACITY)
            {
                dropped = dropped + 1;
                return;
            }
        }
        int slot = (int)(h & (CAPACITY - 1));
        times[slot] = nanos;
        packed[slot] = (key << 2) | (down ? 2 : 0) | (f ? 1 : 0);
        head.lazySet(h + 1);
    }

    private long tailSnapshot;            // producer's cached view of the consumer position
    private volatile long consumerTail;   // published by the consumer

    /** Game thread: delivers all events published so far, in order. */
    public void drain(KeySource.Sink sink)
    {
        long h = head.get();
        while (tail < h)
        {
            int slot = (int)(tail & (CAPACITY - 1));
            int p = packed[slot];
            sink.onKey(times[slot], p >>> 2, (p & 2) != 0, (p & 1) != 0);
            ++tail;
        }
        consumerTail = tail;
    }

    public long dropped()
    {
        return dropped;
    }

    /** The poller reads and hands over in one step: nothing is ever pending. */
    public long pendingNanos()
    {
        return 0L;
    }

    public boolean isAlive()
    {
        Thread th = thread;
        return th != null && th.isAlive();
    }

    public long polls()
    {
        return polls;
    }

    public long maxIntervalNs()
    {
        return maxIntervalNs;
    }

    /** Copy of the interval histogram (50 us buckets; the last bucket is overflow). Read after stop(). */
    public long[] intervalHistogram()
    {
        return intervalHist.clone();
    }

    public long periodNanos()
    {
        return periodNanos;
    }

    /** CPU time used by the poller thread so far (ns), or -1 if unavailable. */
    public long cpuNanos()
    {
        Thread th = thread;
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (th == null || !bean.isThreadCpuTimeSupported()) return -1L;
        return bean.getThreadCpuTime(th.getId());
    }
}
