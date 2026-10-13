package com.tactile.core;

/**
 * Bit-exact port of the parts of net.minecraft.util.MathHelper (1.8.9) used by player movement.
 * Kept free of Minecraft imports so the core can run in the offline harness.
 */
public final class McMath
{
    private static final float[] SIN_TABLE = new float[65536];

    static
    {
        for (int i = 0; i < 65536; ++i)
        {
            SIN_TABLE[i] = (float)Math.sin((double)i * Math.PI * 2.0D / 65536.0D);
        }
    }

    private McMath()
    {
    }

    public static float sin(float value)
    {
        return SIN_TABLE[(int)(value * 10430.378F) & 65535];
    }

    public static float cos(float value)
    {
        return SIN_TABLE[(int)(value * 10430.378F + 16384.0F) & 65535];
    }

    public static float sqrtFloat(float value)
    {
        return (float)Math.sqrt((double)value);
    }

    public static int floor(double value)
    {
        int i = (int)value;
        return value < (double)i ? i - 1 : i;
    }
}
