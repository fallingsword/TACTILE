package com.tactile.core;

/**
 * Movement-relevant input for one vanilla tick: key-binding states as MovementInputFromOptions reads
 * them, plus the look angles in effect when the tick runs. No timestamps by design.
 * <p>{@code use}: the player is using an item this tick (eating, drinking, drawing a bow, blocking with a sword).
 * Vanilla starts and stops item use while handling clicks at a tick start, so it is a per-tick state; in recordings
 * it is the client's actual {@code isUsingItem()}, not the right mouse button.
 */
public final class TickInput
{
    public final boolean forward, back, left, right, jump, sneak, sprint, use;
    public final float yaw, pitch;

    public TickInput(boolean forward, boolean back, boolean left, boolean right, boolean jump, boolean sneak, boolean sprint, float yaw, float pitch)
    {
        this(forward, back, left, right, jump, sneak, sprint, false, yaw, pitch);
    }

    public TickInput(boolean forward, boolean back, boolean left, boolean right, boolean jump, boolean sneak, boolean sprint, boolean use, float yaw, float pitch)
    {
        this.forward = forward;
        this.back = back;
        this.left = left;
        this.right = right;
        this.jump = jump;
        this.sneak = sneak;
        this.sprint = sprint;
        this.use = use;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public TickInput withYaw(float newYaw)
    {
        return new TickInput(forward, back, left, right, jump, sneak, sprint, use, newYaw, pitch);
    }

    public TickInput withUse(boolean newUse)
    {
        return new TickInput(forward, back, left, right, jump, sneak, sprint, newUse, yaw, pitch);
    }

    /** MovementInputFromOptions.updatePlayerMoveState: forward component. */
    public float moveForward()
    {
        float f = 0.0F;
        if (forward) ++f;
        if (back) --f;
        if (sneak) f = (float)((double)f * 0.3D);
        return f;
    }

    /** MovementInputFromOptions.updatePlayerMoveState: strafe component. */
    public float moveStrafe()
    {
        float s = 0.0F;
        if (left) ++s;
        if (right) --s;
        if (sneak) s = (float)((double)s * 0.3D);
        return s;
    }

    /** "WASDJNR" (7 slots), plus an eighth slot 'U' only while an item is in use (older recordings have 7). */
    public String keyString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(forward ? 'W' : '-').append(left ? 'A' : '-').append(back ? 'S' : '-').append(right ? 'D' : '-');
        sb.append(jump ? 'J' : '-').append(sneak ? 'N' : '-').append(sprint ? 'R' : '-');
        if (use) sb.append('U');
        return sb.toString();
    }

    public static TickInput fromKeyString(String keys, float yaw, float pitch)
    {
        String slots = "WASDJNRU";
        if (keys.length() != 7 && keys.length() != 8) throw new IllegalArgumentException("key string must have 7 or 8 slots (WASDJNR[U]): " + keys);
        for (int i = 0; i < keys.length(); ++i)
        {
            char c = keys.charAt(i);
            if (c != '-' && c != slots.charAt(i))
                throw new IllegalArgumentException("slot " + i + " of '" + keys + "' must be '" + slots.charAt(i) + "' or '-'");
        }
        return new TickInput(keys.charAt(0) == 'W', keys.charAt(2) == 'S', keys.charAt(1) == 'A', keys.charAt(3) == 'D',
            keys.charAt(4) == 'J', keys.charAt(5) == 'N', keys.charAt(6) == 'R', keys.length() == 8 && keys.charAt(7) == 'U', yaw, pitch);
    }
}
