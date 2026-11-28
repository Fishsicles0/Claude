package net.evadeclient.module.client;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import org.lwjgl.glfw.GLFW;

/** Client-wide options, shown on the menu's Settings page. */
public class ClientSettings extends Module {
    public final KeySetting menuKey = add(new KeySetting("Menu Key", GLFW.GLFW_KEY_RIGHT_SHIFT)
            .describe("The key that opens the Evade menu."));
    public final net.evadeclient.setting.ModeSetting guiStyle =
            add(new net.evadeclient.setting.ModeSetting("GUI Style", "List", "List", "Cards", "Compact")
                    .describe("How mods are laid out in the Evade menu: a list, cards, or a compact list."));
    public final net.evadeclient.setting.ModeSetting theme =
            add(new net.evadeclient.setting.ModeSetting("Theme", "Modern", net.evadeclient.util.Theme.PRESETS)
                    .describe("The colour preset for the Evade menu and its screens."));
    public final net.evadeclient.setting.ModeSetting accent =
            add(new net.evadeclient.setting.ModeSetting("Accent Color", "Red", net.evadeclient.util.ThemeStudio.accentOptions())
                    .describe("The highlight colour used for toggles, sliders and selected items. Custom uses Custom Accent."));
    public final net.evadeclient.setting.ColorSetting customAccent = add(new net.evadeclient.setting.ColorSetting("Custom Accent", 0xFFE11D2E)
            .opaque().describe("Any accent colour you like, used when Accent Color is Custom."));
    public final net.evadeclient.setting.ColorSetting windowColor = add(new net.evadeclient.setting.ColorSetting("Window Color", 0x00000000)
            .opaque().describe("The Evade menu's background. Theme (the default) follows the Theme preset."));
    public final net.evadeclient.setting.ColorSetting cardColor = add(new net.evadeclient.setting.ColorSetting("Card Color", 0x00000000)
            .opaque().describe("Cards, rows and buttons in the Evade menu. Theme (the default) follows the Theme preset."));
    public final net.evadeclient.setting.ColorSetting textColor = add(new net.evadeclient.setting.ColorSetting("Text Color", 0x00000000)
            .opaque().describe("The menu's main text; dimmer text is mixed from it. Theme (the default) follows the Theme preset."));
    public final net.evadeclient.setting.StatusSetting copyTheme = add(new net.evadeclient.setting.StatusSetting("Copy Theme Code",
            () -> net.evadeclient.util.ThemeStudio.export(this), "click: copy").onClick(() -> net.evadeclient.util.ThemeStudio.copy(this))
            .describe("Copies your theme (preset, accent and custom colours) as a short EVTH1: code to share."));
    public final net.evadeclient.setting.StatusSetting pasteTheme = add(new net.evadeclient.setting.StatusSetting("Paste Theme Code",
            () -> "from clipboard", "click: paste").onClick(() -> net.evadeclient.util.ThemeStudio.paste(this))
            .describe("Applies an EVTH1: theme code from your clipboard."));
    public final BooleanSetting smoothFont = add(new BooleanSetting("Smooth Font", true)
            .describe("Draw the Evade menu in Evade's smooth font instead of the pixel font."));
    public final BooleanSetting autoHudScale = add(new BooleanSetting("Auto HUD Scale", true)
            .describe("Keeps Evade's HUD widgets a sensible size whatever your GUI Scale, so they don't cover the scoreboard on small screens."));
    public final net.evadeclient.setting.NumberSetting hudSize = add(new net.evadeclient.setting.NumberSetting("HUD Size", 100, 50, 200, 5)
            .describe("Makes every Evade HUD widget bigger or smaller at once, in percent."));
    public final BooleanSetting hudGrid = add(new BooleanSetting("HUD Editor Grid", false)
            .describe("Shows a grid in the HUD editor to line widgets up. Press G there to switch it."));
    public final BooleanSetting blur = add(new BooleanSetting("Blur Background", true)
            .describe("Blurs the game behind the Evade menu."));
    public final AnimationsSetting animations = add(new AnimationsSetting("Animations")
            .describe("Motion in the menus and HUD. Reduced keeps only short fades (no parallax, tilt or sliding); Off makes everything change instantly."));
    public final net.evadeclient.setting.ModeSetting density = add(new net.evadeclient.setting.ModeSetting("Density", "Dense", "Dense", "Comfortable")
            .describe("How tall list rows are in Evade's redesigned screens: Dense fits more, Comfortable gives bigger targets."));
    public final net.evadeclient.setting.NumberSetting uiScale = add(new net.evadeclient.setting.NumberSetting("UI Scale", 100, 90, 130, 5)
            .describe("Makes Evade's redesigned screens a little smaller or bigger than your GUI Scale, in percent."));
    public final BooleanSetting reduceTransparency = add(new BooleanSetting("Reduce Transparency", false)
            .describe("Solid panels instead of see-through glass, and no glow effects."));
    public final BooleanSetting highContrast = add(new BooleanSetting("High Contrast", false)
            .describe("Stronger borders and no glow, so every control stands out clearly."));
    public final BooleanSetting toggleMessages = add(new BooleanSetting("Keybind Messages", true)
            .describe("Shows a small pop-up when you turn a mod on or off with its key."));

    public ClientSettings() {
        super("Client Settings", "Menu key, theme, accent colour, blur, animations.", Category.CLIENT);
        setVisible(false);
        setEnabled(true);
    }

    /** Called every tick and by the menu each frame so a new theme shows up immediately. */
    public void applyTheme() {
        net.evadeclient.util.ThemeStudio.apply(this);
    }

    @Override
    public void onTick() { applyTheme(); }

    /** {@code .kitgallery}: every GUI kit component in every state on one screen (for designers and screenshots). */
    @Override
    public java.util.List<String> commandAliases() { return java.util.List.of("kitgallery"); }

    @Override
    public void command(String[] args) {
        // After the chat screen has closed, or it would close the gallery straight away.
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        mc.send(() -> mc.setScreen(new net.evadeclient.gui.kit.KitGalleryScreen(null)));
    }
}
