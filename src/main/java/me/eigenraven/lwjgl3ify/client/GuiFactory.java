package me.eigenraven.lwjgl3ify.client;

import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.client.IModGuiFactory;

/**
 * 1.10.2 uses the older IModGuiFactory shape.
 *
 * Upstream 1.12.2 implements hasConfigGui() and createConfigGui(GuiScreen); the
 * 1.10.2 interface instead asks for mainConfigGuiClass() and getHandlerFor().
 * A null handler and an empty category set are what vanilla Forge mods passed
 * for this version, and FML treats both as "no runtime categories".
 */
@SuppressWarnings("unused")
public class GuiFactory implements IModGuiFactory {

    @Override
    public void initialize(Minecraft minecraftInstance) {}

    @Override
    public Class<? extends GuiScreen> mainConfigGuiClass() {
        return GuiConfigLwjgl3ify.class;
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return null;
    }

    @Override
    public RuntimeOptionGuiHandler getHandlerFor(RuntimeOptionCategoryElement element) {
        return null;
    }
}
