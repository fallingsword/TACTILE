package com.tactile.client.input;

/**
 * A background source of timestamped movement-key transitions ({@link AsyncKeyPoller}, {@link RawKeyInput}).
 *
 * <p>Threading contract: the source's thread touches nothing but its own state and its event buffer. The game
 * thread publishes the focus flag with {@link #setFocused} and drains events with {@link #drain}. Each event
 * carries the focus flag that was in effect when it was observed; events observed while unfocused belong to other
 * applications and must be discarded by the consumer.
 */
public interface KeySource
{
    interface Sink
    {
        void onKey(long nanos, int keyIndex, boolean down, boolean focused);
    }

    void setFocused(boolean focused);

    void start();

    void stop();

    /** Game thread: delivers all events published so far, in order. */
    void drain(Sink sink);

    /** Events lost because the buffer was full. */
    long dropped();

    /**
     * Wake-up time (nanoTime) of an event the source has received but not yet handed over, or 0. Only a time, never
     * which key. The engine does not simulate past it, so the event still enters at its own time when it arrives.
     */
    long pendingNanos();

    /** False once the source's thread has stopped (an error); the engine then reads keys once per frame instead. */
    boolean isAlive();
}
