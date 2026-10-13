package com.tactile.client.input;

import java.lang.reflect.Method;

/**
 * Maps Minecraft KeyBinding codes (LWJGL key codes; mouse buttons as code - 100) to Windows virtual-key
 * codes. The inverse is built from LWJGL's own WindowsKeycodes.mapVirtualKeyToLWJGLCode, so it
 * covers every key LWJGL can report on Windows. Left/right modifiers map to the side-specific VKs.
 */
public final class VkMap
{
    private static final int[] LWJGL_TO_VK = new int[256];
    private static String status;

    static
    {
        try
        {
            Class<?> c = Class.forName("org.lwjgl.opengl.WindowsKeycodes");
            Method m = c.getDeclaredMethod("mapVirtualKeyToLWJGLCode", int.class);
            m.setAccessible(true);
            int mapped = 0;
            for (int vk = 1; vk < 255; ++vk)
            {
                int code = (Integer)m.invoke(null, vk);
                if (code > 0 && code < 256 && LWJGL_TO_VK[code] == 0)
                {
                    LWJGL_TO_VK[code] = vk;
                    ++mapped;
                }
            }
            status = mapped + " keys mapped from LWJGL WindowsKeycodes";
        }
        catch (Throwable t)
        {
            status = "LWJGL mapping unavailable: " + t;
        }
        // Side-specific modifiers (LWJGL distinguishes them; GetAsyncKeyState supports the specific VKs).
        LWJGL_TO_VK[42] = 0xA0;  // LSHIFT
        LWJGL_TO_VK[54] = 0xA1;  // RSHIFT
        LWJGL_TO_VK[29] = 0xA2;  // LCONTROL
        LWJGL_TO_VK[157] = 0xA3; // RCONTROL
        LWJGL_TO_VK[56] = 0xA4;  // LMENU
        LWJGL_TO_VK[184] = 0xA5; // RMENU
    }

    private VkMap()
    {
    }

    /** Windows VK for a KeyBinding code, or 0 if it cannot be polled. */
    public static int toVk(int keyBindingCode)
    {
        if (keyBindingCode < 0)
        {
            switch (keyBindingCode + 100)
            {
                case 0: return 0x01; // VK_LBUTTON
                case 1: return 0x02; // VK_RBUTTON
                case 2: return 0x04; // VK_MBUTTON
                case 3: return 0x05; // VK_XBUTTON1
                case 4: return 0x06; // VK_XBUTTON2
                default: return 0;
            }
        }
        return keyBindingCode < 256 ? LWJGL_TO_VK[keyBindingCode] : 0;
    }

    public static String status()
    {
        return status;
    }
}
