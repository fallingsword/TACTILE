package com.tactile.asm;

import java.util.Map;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

/**
 * Coremod entry point. It registers one narrow transformer ({@link TactileTransformer}). Sorting index
 * 1001 means it runs after FML's deobfuscation, so class names are MCP/SRG class names and method
 * names are SRG at runtime (MCP in the dev workspace).
 */
@IFMLLoadingPlugin.MCVersion("1.8.9")
@IFMLLoadingPlugin.Name("TactileCore")
@IFMLLoadingPlugin.TransformerExclusions({"com.tactile.asm"})
@IFMLLoadingPlugin.SortingIndex(1001)
public final class TactileLoadingPlugin implements IFMLLoadingPlugin
{
    @Override
    public String[] getASMTransformerClass()
    {
        return new String[] {TactileTransformer.class.getName()};
    }

    @Override
    public String getModContainerClass()
    {
        return null;
    }

    @Override
    public String getSetupClass()
    {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data)
    {
    }

    @Override
    public String getAccessTransformerClass()
    {
        return null;
    }
}
