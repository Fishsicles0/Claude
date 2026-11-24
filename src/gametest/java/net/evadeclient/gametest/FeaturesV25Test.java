package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.chat.ChatLines;
import net.evadeclient.chat.Emoji;
import net.evadeclient.gui.ItemPickerScreen;
import net.evadeclient.module.hud.LookInfoHud;
import net.evadeclient.module.misc.BetterChat;
import net.evadeclient.module.misc.ChatEmoji;
import net.evadeclient.module.misc.ChatHeads;
import net.evadeclient.module.misc.ChatTabs;
import net.evadeclient.module.misc.SmoothChat;
import net.evadeclient.module.visuals.ItemHighlight;
import net.evadeclient.module.visuals.LightOverlay;
import net.evadeclient.module.visuals.PackTweaks;
import net.evadeclient.setting.ItemListSetting;
import net.evadeclient.setting.Setting;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * v2.5 in-game features: Chat Heads, Chat Tabs, Smooth Chat, Emoji shortcodes and picker, Look Info, Light Overlay,
 * the item picker setting and Item Highlight, and Pack Tweaks' overlay bundle. Everything is checked on this client
 * only; the one chat message sent (Keep Open After Sending) goes to the test's own singleplayer server.
 */
public final class FeaturesV25Test {
    private FeaturesV25Test() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    @SuppressWarnings("unchecked")
    private static <S extends Setting<?>> S setting(net.evadeclient.module.Module m, String name) {
        for (Setting<?> s : m.getSettings()) if (s.getName().equals(name)) return (S) s;
        throw new AssertionError("No setting " + name + " on " + m.getName());
    }

    private static void reset(net.evadeclient.module.Module m, boolean enabled) {
        for (Setting<?> s : m.getSettings()) s.reset();
        m.setEnabled(enabled);
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        chatHeads(ctx);
        chatTabs(ctx);
        emojiAndSmoothChat(ctx);
        lookInfoAndLightOverlay(ctx, sp);
        itemPickerAndHighlight(ctx, sp);
        packTweaks(ctx, sp);
    }

    // ------------------------------------------------------------------ Chat Heads

    private static void chatHeads(ClientGameTestContext ctx) {
        List<String> names = ctx.computeOnClient(c -> java.util.Arrays.asList(
                ChatLines.senderName("<Steve> hi"), ChatLines.senderName("[VIP] Steve: hi"),
                ChatLines.senderName("Party > [MVP+] Dex: yo"), ChatLines.senderName("Guild > Kaito [Officer]: hey"),
                ChatLines.senderName("[Evade] Dex > you: hi"), ChatLines.senderName("Steve joined the game")));
        check(names.equals(java.util.Arrays.asList("Steve", "Steve", "Dex", "Kaito", "Dex", null)),
                "Chat Heads: the sender is read from vanilla, ranked, party, guild and Evade lines, not from announcements (" + names + ")");

        Object[] r = ctx.computeOnClient(c -> {
            ChatHeads h = EvadeClient.modules.get(ChatHeads.class);
            h.setEnabled(true);
            String me = c.player.getGameProfile().name();
            c.inGameHud.getChatHud().addMessage(Text.literal("<" + me + "> hello from my own face"));
            boolean mine = ChatLines.lastHeadedForTest();
            c.inGameHud.getChatHud().addMessage(Text.literal("[Server] Restart in 5 minutes"));
            boolean server = ChatLines.lastHeadedForTest();
            BetterChat bc = BetterChat.active();
            String last = bc == null ? null : bc.lastLineText();
            return new Object[]{mine, server, last};
        });
        check((boolean) r[0] && !(boolean) r[1], "Chat Heads: a line from a player in the tab list gets a face, a server line doesn't");
        ctx.waitTicks(3);
        ctx.takeScreenshot("v25-chat-heads");
        boolean stacked = ctx.computeOnClient(c -> {
            BetterChat bc = BetterChat.active();
            if (bc == null) return true;
            String me = c.player.getGameProfile().name();
            c.inGameHud.getChatHud().addMessage(Text.literal("<" + me + "> stack me"));
            c.inGameHud.getChatHud().addMessage(Text.literal("<" + me + "> stack me"));
            return bc.stackCount() == 2 && ChatLines.lastHeadedForTest();
        });
        check(stacked, "Chat Heads: BetterChat still stacks a repeated line, and the stacked line keeps its face");
        ctx.runOnClient(c -> reset(EvadeClient.modules.get(ChatHeads.class), false));
    }

    // ------------------------------------------------------------------ Chat Tabs

    private static void chatTabs(ClientGameTestContext ctx) {
        Object[] r = ctx.computeOnClient(c -> {
            ChatTabs t = EvadeClient.modules.get(ChatTabs.class);
            t.setEnabled(true);
            net.evadeclient.setting.StringSetting name = setting(t, "Tab 1 Name"), rule = setting(t, "Tab 1 Rule");
            name.set("Trade");
            rule.set("\\btrade\\b");
            List<String> tabs = t.tabNamesForTest();
            int friends = tabs.indexOf("Friends"), trade = tabs.indexOf("Trade");
            // Lines as chat stores them (recorded the way the chat mixin does).
            ChatHudLine friendLine = new ChatHudLine(0, ChatLines.onAdd(Text.literal("[Evade] Dex > you: hi")), null, null);
            ChatHudLine tradeLine = new ChatHudLine(0, ChatLines.onAdd(Text.literal("anyone want to TRADE diamonds")), null, null);
            ChatHudLine other = new ChatHudLine(0, ChatLines.onAdd(Text.literal("the weather is nice")), null, null);
            int unreadFriends = t.unread(friends), unreadTrade = t.unread(trade);
            boolean allShows = ChatTabs.shows(friendLine) && ChatTabs.shows(tradeLine) && ChatTabs.shows(other);
            t.select(friends);
            boolean friendsTab = ChatTabs.shows(friendLine) && !ChatTabs.shows(tradeLine) && !ChatTabs.shows(other);
            int afterRead = t.unread(friends);
            t.select(trade);
            boolean tradeTab = ChatTabs.shows(tradeLine) && !ChatTabs.shows(friendLine);
            // Real chat while a tab is selected: a stacked repeat must not break the filtered view.
            c.inGameHud.getChatHud().addMessage(Text.literal("trade: 3 diamonds for an elytra"));
            c.inGameHud.getChatHud().addMessage(Text.literal("trade: 3 diamonds for an elytra"));
            c.inGameHud.getChatHud().addMessage(Text.literal("not for this tab"));
            return new Object[]{tabs.toString(), friends >= 0 && trade >= 0, unreadFriends, unreadTrade, allShows, friendsTab, afterRead, tradeTab};
        });
        check((boolean) r[1], "Chat Tabs: Evade Friends and a custom regex tab are listed, Hypixel tabs only on Hypixel (" + r[0] + ")");
        check((int) r[2] == 1 && (int) r[3] == 1, "Chat Tabs: a new line counts as unread in its tab (" + r[2] + ", " + r[3] + ")");
        check((boolean) r[4] && (boolean) r[5] && (boolean) r[7], "Chat Tabs: All shows every line, a tab only its own (any case for a custom rule)");
        check((int) r[6] == 0, "Chat Tabs: opening a tab marks it read");
        ctx.runOnClient(c -> c.setScreen(new ChatScreen("", false)));
        ctx.waitTicks(3);
        ctx.takeScreenshot("v25-chat-tabs");
        boolean back = ctx.computeOnClient(c -> {
            c.setScreen(null);
            return EvadeClient.modules.get(ChatTabs.class).selected() == -1 && !ChatTabs.filtering();
        });
        check(back, "Chat Tabs: closing chat goes back to All (Back To All On Close)");
        ctx.runOnClient(c -> reset(EvadeClient.modules.get(ChatTabs.class), false));
    }

    // ------------------------------------------------------------------ Emoji and Smooth Chat

    private static String field(ChatScreen s) {
        try {
            java.lang.reflect.Field f = ChatScreen.class.getDeclaredField("chatField");
            f.setAccessible(true);
            return ((TextFieldWidget) f.get(s)).getText();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void emojiAndSmoothChat(ClientGameTestContext ctx) {
        Object[] conv = ctx.computeOnClient(c -> {
            ChatEmoji e = EvadeClient.modules.get(ChatEmoji.class);
            e.setEnabled(true);
            boolean shorter = true;
            for (Emoji.Entry en : Emoji.ALL) shorter &= en.emoji().length() <= en.code().length() + 2;
            return new Object[]{ChatEmoji.onSend("gg :skull: that was :FIRE:"), ChatEmoji.onSend("/say :skull:"),
                    ChatEmoji.onSend(".msg Dex :skull:"), ChatEmoji.onSend("abc:skull: and :notacode:"), shorter, Emoji.ALL.size()};
        });
        check("gg 💀 that was 🔥".equals(conv[0]), "Emoji: known shortcodes in a chat message become emoji (" + conv[0] + ")");
        check("/say :skull:".equals(conv[1]) && ".msg Dex :skull:".equals(conv[2]), "Emoji: / and . commands are never changed");
        check("abc:skull: and :notacode:".equals(conv[3]), "Emoji: a code glued to a word or an unknown code is left exactly as typed");
        check((boolean) conv[4] && (int) conv[5] >= 60, "Emoji: " + conv[5] + " emoji, each no longer than its code, so a message never grows");

        // Live conversion in the chat box, the picker, and Smooth Chat's Keep Open After Sending.
        String live = ctx.computeOnClient(c -> {
            SmoothChat s = EvadeClient.modules.get(SmoothChat.class);
            s.setEnabled(true);
            net.evadeclient.setting.BooleanSetting keep = setting(s, "Keep Open After Sending");
            keep.set(true);
            ChatScreen screen = new ChatScreen("", false);
            c.setScreen(screen);
            screen.insertText("gg :fire", false);
            screen.insertText(":", false);
            return field(screen);
        });
        check("gg 🔥".equals(live), "Emoji: typing the closing colon turns :fire: into 🔥 in the box (" + live + ")");
        ctx.waitTicks(2);
        ctx.takeScreenshot("v25-emoji-box");
        Object[] kept = ctx.computeOnClient(c -> {
            ChatScreen screen = (ChatScreen) c.currentScreen;
            screen.keyPressed(new KeyInput(GLFW.GLFW_KEY_ENTER, 0, 0));
            boolean open = c.currentScreen == screen;
            String after = field(screen);
            String sent = c.inGameHud.getChatHud().getMessageHistory().peekLast();
            screen.keyPressed(new KeyInput(GLFW.GLFW_KEY_ENTER, 0, 0)); // Enter on an empty box closes it
            return new Object[]{open, after, sent, c.currentScreen == null};
        });
        check((boolean) kept[0] && "".equals(kept[1]) && "gg 🔥".equals(kept[2]),
                "Smooth Chat: Keep Open After Sending sends exactly the typed text and leaves an empty box open (" + kept[2] + ")");
        check((boolean) kept[3], "Smooth Chat: Enter on an empty box still closes chat");

        float[] slide = ctx.computeOnClient(c -> {
            c.inGameHud.getChatHud().addMessage(Text.literal("slide in please"));
            float now = EvadeClient.modules.get(SmoothChat.class).offsetForTest();
            return new float[]{now, SmoothChat.backgroundOpacity(0.5f), SmoothChat.inputColor(0x80000000) >>> 24};
        });
        check(slide[0] > 0f && slide[0] <= 3f, "Smooth Chat: a new line starts below its place and slides up (" + slide[0] + " lines)");
        ctx.waitTicks(20);
        float settled = ctx.computeOnClient(c -> EvadeClient.modules.get(SmoothChat.class).offsetForTest());
        check(settled == 0f && slide[1] == 0.5f && slide[2] == 128, "Smooth Chat: the slide settles, and the background and input opacities apply");

        ctx.runOnClient(c -> {
            ChatEmoji e = EvadeClient.modules.get(ChatEmoji.class);
            e.used("💀");
            c.setScreen(new ChatScreen("", false));
        });
        ctx.waitTicks(2);
        boolean opened = ctx.computeOnClient(c -> {
            int w = c.getWindow().getScaledWidth(), h = c.getWindow().getScaledHeight();
            // Click the emoji button at the right end of the chat box.
            return c.currentScreen.mouseClicked(new net.minecraft.client.gui.Click(w - 8, h - 8,
                    new net.minecraft.client.input.MouseInput(0, 0)), false) && net.evadeclient.chat.ChatScreenExtras.pickerOpen();
        });
        check(opened, "Emoji: the button at the end of the chat box opens the emoji grid");
        ctx.waitTicks(2);
        ctx.takeScreenshot("v25-emoji-picker");
        List<String> recents = ctx.computeOnClient(c -> {
            c.setScreen(null);
            return EvadeClient.modules.get(ChatEmoji.class).recents();
        });
        check(!recents.isEmpty() && recents.getFirst().equals("💀"), "Emoji: a used emoji is first in Recent");
        ctx.runOnClient(c -> {
            reset(EvadeClient.modules.get(ChatEmoji.class), false);
            reset(EvadeClient.modules.get(SmoothChat.class), false);
        });
    }

    // ------------------------------------------------------------------ Look Info and Light Overlay

    private static void lookInfoAndLightOverlay(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        // A closed stone room far from the other tests: dark inside, with a glass block in its floor.
        // Go there first: the server only edits loaded chunks.
        server.runCommand("tp @a 300 -60 300 0 90");
        server.runCommand("item replace entity @a weapon.mainhand with air");
        server.runCommand("gamemode survival @a");
        ctx.waitTicks(40);
        server.runCommand("fill 296 -62 296 304 -55 304 stone");
        server.runCommand("fill 297 -61 297 303 -56 303 air");
        server.runCommand("setblock 302 -62 302 glass");
        server.runCommand("tp @a 300 -61 300 0 90");
        ctx.waitTicks(40);
        sp.getClientWorld().waitForChunksRender();

        List<String> bare = ctx.computeOnClient(c -> {
            c.player.setPitch(90f);
            LookInfoHud li = EvadeClient.modules.get(LookInfoHud.class);
            li.setEnabled(true);
            c.gameRenderer.updateCrosshairTarget(1f);
            return li.textForTest();
        });
        check(bare.contains("Stone") && bare.contains("minecraft") && bare.stream().anyMatch(s -> s.startsWith("✘ Needs a pickaxe"))
                        && bare.stream().anyMatch(s -> s.startsWith("Breaks in")) && bare.stream().anyMatch(s -> s.startsWith("Light 0")),
                "Look Info: the block under the crosshair, its mod, the tool it needs, break time and light (" + bare + ")");
        server.runCommand("item replace entity @a weapon.mainhand with diamond_pickaxe");
        ctx.waitTicks(5);
        List<String> pick = ctx.computeOnClient(c -> {
            c.gameRenderer.updateCrosshairTarget(1f);
            return EvadeClient.modules.get(LookInfoHud.class).textForTest();
        });
        check(pick.contains("✔ Right tool") && pick.stream().anyMatch(s -> s.startsWith("Breaks in 0.")),
                "Look Info: with a pickaxe it says Right tool and the faster break time (" + pick + ")");

        server.runCommand("summon pig 300 -61 302 {NoAI:1b,Silent:1b,CustomName:\"Bacon\"}");
        ctx.waitTicks(10);
        List<String> mob = ctx.computeOnClient(c -> {
            Entity pig = null;
            for (Entity e : c.world.getEntities()) if (e instanceof PigEntity) pig = e;
            if (pig == null) return List.of("no pig");
            c.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, pig.getBoundingBox().getCenter());
            c.gameRenderer.updateCrosshairTarget(1f);
            return EvadeClient.modules.get(LookInfoHud.class).textForTest();
        });
        check(mob.contains("Bacon") && mob.contains("minecraft:pig") && mob.stream().noneMatch(s -> s.contains("♥") || s.matches(".*\\d+/\\d+.*")),
                "Look Info: a mob shows only its name and type, never health (" + mob + ")");
        ctx.takeScreenshot("v25-look-info");
        server.runCommand("kill @e[type=pig]");

        int[] marks = ctx.computeOnClient(c -> {
            LightOverlay lo = EvadeClient.modules.get(LightOverlay.class);
            lo.setEnabled(true);
            lo.scan();
            return new int[]{lo.marksForTest(), lo.markColorAt(298, -61, 298), lo.markColorAt(302, -61, 302),
                    lo.markColorAt(300, -54, 300), lo.getKey()};
        });
        check(marks[0] > 0 && marks[1] == 0xFFFF4D5E, "Light Overlay: dark floor inside the room is marked spawnable in red (" + marks[0] + " marks)");
        check(marks[2] == 0, "Light Overlay: glass, where nothing spawns, is not marked");
        check(marks[3] == 0xFFFFD21F, "Light Overlay: the roof, under open sky, is marked night-only in yellow");
        check(marks[4] == GLFW.GLFW_KEY_F7, "Light Overlay: toggles with F7 by default");
        server.runCommand("setblock 298 -61 298 torch");
        ctx.waitTicks(10);
        int lit = ctx.computeOnClient(c -> {
            LightOverlay lo = EvadeClient.modules.get(LightOverlay.class);
            lo.scan();
            return lo.markColorAt(299, -61, 299);
        });
        check(lit == 0, "Light Overlay: a torch's light clears the marks around it");
        ctx.runOnClient(c -> c.player.setPitch(40f));
        ctx.waitTicks(3);
        ctx.takeScreenshot("v25-light-overlay");
        ctx.runOnClient(c -> {
            reset(EvadeClient.modules.get(LightOverlay.class), false);
            reset(EvadeClient.modules.get(LookInfoHud.class), false);
        });
    }

    // ------------------------------------------------------------------ item picker and Item Highlight

    private static void itemPickerAndHighlight(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        Object[] parse = ctx.computeOnClient(c -> {
            ItemListSetting list = new ItemListSetting("Test", "diamond, minecraft:ender_pearl,bogus_item", "");
            ItemListSetting one = new ItemListSetting("One", "apple,diamond", "", true);
            ItemListSetting copy = new ItemListSetting("Copy", "", "");
            copy.setItems(List.of(Items.TOTEM_OF_UNDYING, Items.ARROW));
            return new Object[]{list.items(), one.items(), copy.get(), list.contains(Items.DIAMOND), list.contains(Items.DIRT)};
        });
        check(parse[0].equals(List.of(Items.DIAMOND, Items.ENDER_PEARL)) && parse[1].equals(List.of(Items.APPLE)),
                "Item picker setting: ids with or without minecraft:, unknown ids skipped, single keeps one (" + parse[0] + ")");
        check("minecraft:totem_of_undying,minecraft:arrow".equals(parse[2]) && (boolean) parse[3] && !(boolean) parse[4],
                "Item picker setting: saved as comma-separated ids, so old text configs still load");

        int[] picker = ctx.computeOnClient(c -> {
            ItemHighlight h = EvadeClient.modules.get(ItemHighlight.class);
            ItemPickerScreen screen = new ItemPickerScreen(null, h.itemsSetting());
            c.setScreen(screen);
            int all = screen.shownForTest();
            screen.searchForTest("ender pearl");
            return new int[]{all, screen.shownForTest()};
        });
        check(picker[0] > 1000 && picker[1] >= 1 && picker[1] < 5, "Item picker: every item, narrowed by the search box (" + picker[0] + " -> " + picker[1] + ")");
        ctx.waitTicks(2);
        ctx.takeScreenshot("v25-item-picker");

        sp.getServer().runCommand("give @a totem_of_undying");
        sp.getServer().runCommand("give @a dirt 5");
        ctx.waitTicks(5);
        boolean[] hl = ctx.computeOnClient(c -> {
            c.setScreen(null);
            ItemHighlight h = EvadeClient.modules.get(ItemHighlight.class);
            h.setEnabled(true);
            c.setScreen(new InventoryScreen(c.player));
            return new boolean[]{h.highlights(new ItemStack(Items.TOTEM_OF_UNDYING)), h.highlights(new ItemStack(Items.DIRT)),
                    h.highlights(ItemStack.EMPTY)};
        });
        check(hl[0] && !hl[1] && !hl[2], "Item Highlight: only slots holding a chosen item are tinted");
        ctx.waitTicks(3);
        ctx.takeScreenshot("v25-item-highlight");
        ctx.runOnClient(c -> {
            c.setScreen(null);
            reset(EvadeClient.modules.get(ItemHighlight.class), false);
        });
        sp.getServer().runCommand("clear @a totem_of_undying");
        sp.getServer().runCommand("clear @a dirt");
    }

    // ------------------------------------------------------------------ Pack Tweaks overlay bundle

    private static void packTweaks(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        boolean[] glass = ctx.computeOnClient(c -> {
            try (NativeImage img = new NativeImage(16, 16, false)) {
                img.fillRect(0, 0, 16, 16, 0xFFFFFFFF);
                PackTweaks.clearInside(img);
                return new boolean[]{img.getColorArgb(0, 0) == 0xFFFFFFFF, img.getColorArgb(15, 7) == 0xFFFFFFFF,
                        (img.getColorArgb(7, 7) >>> 24) == 0, (img.getColorArgb(1, 1) >>> 24) == 0};
            }
        });
        check(glass[0] && glass[1] && glass[2] && glass[3], "Pack Tweaks Clear Glass: the frame stays, everything inside it turns clear");

        sp.getServer().runCommand("item replace entity @a weapon.mainhand with filled_map");
        ctx.waitTicks(5);
        Object[] r = ctx.computeOnClient(c -> {
            PackTweaks pt = EvadeClient.modules.get(PackTweaks.class);
            boolean wasOn = pt.isEnabled();
            pt.setEnabled(true);
            boolean before = PackTweaks.stillMap();
            pt.noMapBobbing.set(true);
            pt.hideStuckArrows.set(true);
            pt.fishingLine.set(0xFF3D7BFF);
            pt.spyglassOverlay.set(40.0);
            Object[] out = {before, PackTweaks.stillMap(), PackTweaks.hidesStuckIn(c.player), PackTweaks.hidesStuckIn(new Object()),
                    PackTweaks.fishingLineColor(0xFF000000), PackTweaks.spyglassPercent()};
            pt.noMapBobbing.reset();
            pt.hideStuckArrows.reset();
            pt.fishingLine.reset();
            pt.spyglassOverlay.reset();
            pt.setEnabled(wasOn);
            return out;
        });
        check(!(boolean) r[0] && (boolean) r[1], "Pack Tweaks No Map Bobbing: holding a map keeps the hand still only when switched on");
        check((boolean) r[2] && !(boolean) r[3], "Pack Tweaks Hide Arrows Stuck In You: only your own player");
        check((int) r[4] == 0xFF3D7BFF && (int) r[5] == 40, "Pack Tweaks: Fishing Line Color and Spyglass Overlay % apply");
        sp.getServer().runCommand("item replace entity @a weapon.mainhand with air");
        sp.getServer().runCommand("tp @a 0 -60 0 0 0");
        ctx.waitTicks(10);
    }
}
