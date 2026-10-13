package com.tactile.asm;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Redirects exactly one call site: {@code this.moveEntityWithHeading(moveStrafing, moveForward)} inside
 * {@code EntityLivingBase.onLivingUpdate}, to {@code TactileHooks.moveEntityWithHeading(this, strafe, forward)}.
 * The stack shape is identical (receiver plus two floats). With no handler installed, the hook makes
 * the same virtual call, so vanilla behavior is unchanged.
 *
 * <p>It fails closed: unless exactly one matching call is found, the class is returned untouched and
 * {@link HookStatus#moveHookInstalled} stays false (high-resolution mode then refuses to enable).
 */
public final class TactileTransformer implements IClassTransformer
{
    private static final String TARGET_CLASS = "net.minecraft.entity.EntityLivingBase";
    private static final String OWNER = "net/minecraft/entity/EntityLivingBase";
    private static final String HOOK_OWNER = "com/tactile/hook/TactileHooks";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass)
    {
        if (basicClass == null || !TARGET_CLASS.equals(transformedName)) return basicClass;
        try
        {
            ClassNode cn = new ClassNode();
            new ClassReader(basicClass).accept(cn, 0);
            List<MethodInsnNode> sites = new ArrayList<MethodInsnNode>();
            for (MethodNode m : cn.methods)
            {
                boolean onLivingUpdate = ("onLivingUpdate".equals(m.name) || "func_70636_d".equals(m.name)) && "()V".equals(m.desc);
                if (!onLivingUpdate) continue;
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext())
                {
                    if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                    MethodInsnNode mi = (MethodInsnNode)insn;
                    if (OWNER.equals(mi.owner) && "(FF)V".equals(mi.desc)
                        && ("moveEntityWithHeading".equals(mi.name) || "func_70612_e".equals(mi.name)))
                        sites.add(mi);
                }
            }
            if (sites.size() != 1)
            {
                HookStatus.detail = "expected 1 moveEntityWithHeading call in onLivingUpdate, found " + sites.size() + "; class left untouched";
                System.out.println("[tactile-core] " + HookStatus.detail);
                return basicClass;
            }
            MethodInsnNode site = sites.get(0);
            site.setOpcode(Opcodes.INVOKESTATIC);
            site.owner = HOOK_OWNER;
            site.name = "moveEntityWithHeading";
            site.desc = "(L" + OWNER + ";FF)V";
            site.itf = false;

            ClassWriter cw = new ClassWriter(0); // stack shape unchanged: no frame/max recomputation needed
            cn.accept(cw);
            HookStatus.moveHookInstalled = true;
            HookStatus.detail = "redirected EntityLivingBase.onLivingUpdate -> TactileHooks.moveEntityWithHeading";
            System.out.println("[tactile-core] " + HookStatus.detail);
            return cw.toByteArray();
        }
        catch (Throwable t)
        {
            HookStatus.detail = "transform failed: " + t + "; class left untouched";
            System.out.println("[tactile-core] " + HookStatus.detail);
            return basicClass;
        }
    }
}
