package com.tactile.harness;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;

import com.tactile.client.input.AsyncKeyPoller;
import com.tactile.client.input.Win32;

/**
 * Achieved poll rate, interval percentiles and CPU cost of the background key poller per target rate
 * (gradlew pollerBench; Windows). It polls a key nobody presses (F24), so nothing is read from the user.
 */
public final class PollerBench
{
    public static void main(String[] args) throws Exception
    {
        Win32.User32 u = Win32.get();
        if (u == null)
        {
            System.out.println("poller unavailable: " + Win32.failureReason());
            return;
        }
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        System.out.println("  target    achieved   p50 ms   p99 ms   max ms   CPU (% of one core)");
        for (int hz : new int[] {1000, 2000, 4000, 8000})
        {
            long period = 1_000_000_000L / hz;
            AsyncKeyPoller p = new AsyncKeyPoller(u, new int[] {0x87 /* VK_F24 */}, period, AsyncKeyPoller.spinFor(period));
            long cpu0 = totalCpu(mx), t0 = System.nanoTime();
            p.start();
            Thread.sleep(3000);
            p.stop();
            long wall = System.nanoTime() - t0, cpu = totalCpu(mx) - cpu0;
            long[] h = p.intervalHistogram();
            System.out.println(String.format(Locale.ROOT, "  %5d Hz  %6.0f Hz  %7.3f  %7.3f  %7.3f   %5.1f%%", hz, p.polls() * 1e9 / wall,
                pct(h, 0.5), pct(h, 0.99), p.maxIntervalNs() / 1e6, 100.0 * cpu / wall));
        }
    }

    /** CPU time of all live threads (the poller thread is included while it runs; it is joined before the read). */
    private static long totalCpu(ThreadMXBean mx)
    {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean
            ? ((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime() : 0L;
    }

    private static double pct(long[] h, double q)
    {
        long n = 0;
        for (long c : h) n += c;
        long target = (long)Math.ceil(q * n), acc = 0;
        for (int i = 0; i < h.length; ++i)
        {
            acc += h[i];
            if (acc >= target) return (i + 1) * AsyncKeyPoller.BUCKET_NS / 1e6; // upper edge of the 50 us bucket
        }
        return Double.NaN;
    }
}
