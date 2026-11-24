package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.StringSetting;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.screen.pack.ResourcePackOrganizer;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Favourite resource packs in the game's resource pack screen (which already has a search box): right-click a pack
 * to star it, starred packs sit at the top of the Available list, and the ★ button next to the search box shows only
 * favourites. The Selected list is never reordered, since its order is the packs' load order. Data pack screens are
 * left alone.
 *
 * <p>Hooks: {@code PackListWidgetMixin} (order and filter), {@code PackEntryMixin} (the star and right-click),
 * {@code PackScreenAccessor} (the screen's folder and list refresh).
 */
public class PackOrganizer extends Module {
    private static final String SEP = "|";

    public final BooleanSetting favouritesFirst = add(new BooleanSetting("Favourites First", true)
            .describe("Put starred packs at the top of the Available list."));
    public final BooleanSetting favouritesOnly = add(new BooleanSetting("Favourites Only", false)
            .describe("Show only starred packs in the Available list. The ★ button next to the search box switches this."));
    /** Starred pack ids ("file/My Pack.zip"), separated by '|' (a character pack file names can't hold on Windows). */
    private final StringSetting favourites = add(new StringSetting("Favourite Packs", "", "").maxLength(8192).hidden());

    private static PackOrganizer INSTANCE;
    /** The open pack screen's lists need rebuilding (after a star changed); done on its next tick, not mid-click. */
    private static boolean dirty;

    private Set<String> cache;
    private String cacheFor;

    public PackOrganizer() {
        super("Pack Organizer", "Star resource packs to keep them at the top of the list.", Category.MISC);
        INSTANCE = this;
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof PackScreen ps) onPackScreen(ps);
        });
    }

    @Override
    protected boolean alwaysRunHooks() { return true; }

    public static PackOrganizer active() {
        PackOrganizer p = INSTANCE;
        return p != null && p.isEnabled() ? p : null;
    }

    /** Whether the module is on and {@code screen} is the resource pack screen (not a data pack one). */
    public static boolean appliesTo(Screen screen) {
        if (active() == null || !(screen instanceof PackScreen)) return false;
        if (!(screen instanceof net.evadeclient.mixin.PackScreenAccessor a)) return false;
        Path file = a.evade$getFile();
        Path dir = mc.getResourcePackDir();
        try {
            return file != null && dir != null && file.toAbsolutePath().normalize().equals(dir.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ favourites

    private Set<String> favourites() {
        String raw = favourites.get();
        if (cache == null || !raw.equals(cacheFor)) {
            Set<String> s = new LinkedHashSet<>();
            for (String id : raw.split("\\|")) if (!id.isBlank()) s.add(id);
            cache = s;
            cacheFor = raw;
        }
        return cache;
    }

    public static boolean isFavourite(String packId) {
        PackOrganizer p = active();
        return p != null && packId != null && p.favourites().contains(packId);
    }

    /** Stars or un-stars {@code packId} and asks the open screen to rebuild its lists. */
    public static void toggleFavourite(String packId) {
        PackOrganizer p = active();
        if (p == null || packId == null || packId.contains(SEP)) return;
        Set<String> s = new LinkedHashSet<>(p.favourites());
        if (!s.remove(packId)) s.add(packId);
        String joined = String.join(SEP, s);
        if (joined.length() > p.favourites.getMaxLength()) return; // full: keep what's there
        p.favourites.set(joined);
        dirty = true;
        if (EvadeClient.config != null) EvadeClient.config.save();
    }

    /**
     * PackListWidgetMixin, HEAD of PackListWidget.set: the packs to list. Lists with an enabled pack are the Selected
     * list (load order) and are left as they are; the Available list gets favourites first / favourites only.
     */
    public static Stream<ResourcePackOrganizer.Pack> arrange(Screen screen, Stream<ResourcePackOrganizer.Pack> packs) {
        PackOrganizer p = active();
        if (p == null || !appliesTo(screen)) return packs;
        List<ResourcePackOrganizer.Pack> all = packs.toList();
        for (ResourcePackOrganizer.Pack pack : all) if (pack.isEnabled()) return all.stream();
        Set<String> fav = p.favourites();
        List<ResourcePackOrganizer.Pack> out = new ArrayList<>(all.size());
        if (p.favouritesFirst.get() || p.favouritesOnly.get())
            for (ResourcePackOrganizer.Pack pack : all) if (fav.contains(pack.getName())) out.add(pack);
        if (!p.favouritesOnly.get())
            for (ResourcePackOrganizer.Pack pack : all) if (!p.favouritesFirst.get() || !fav.contains(pack.getName())) out.add(pack);
        return out.stream();
    }

    // ------------------------------------------------------------------ screen

    private void onPackScreen(PackScreen screen) {
        if (!appliesTo(screen)) return;
        dirty = false;
        TextFieldWidget search = null;
        for (ClickableWidget w : Screens.getButtons(screen)) if (w instanceof TextFieldWidget t) search = t;
        int x = search != null ? search.getX() + search.getWidth() + 4 : screen.width / 2 + 104;
        int y = search != null ? search.getY() - 2 : 28;
        ButtonWidget star = ButtonWidget.builder(starLabel(), b -> {
                    favouritesOnly.toggle();
                    b.setMessage(starLabel());
                    dirty = true;
                })
                .dimensions(x, y, 20, 20)
                .tooltip(Tooltip.of(Text.literal("Show only favourite packs. Right-click a pack to star it.")))
                .build();
        Screens.getButtons(screen).add(star);
        ScreenEvents.afterTick(screen).register(s -> {
            if (!dirty) return;
            dirty = false;
            if (s instanceof net.evadeclient.mixin.PackScreenAccessor a) a.evade$updatePackLists(null);
        });
    }

    private Text starLabel() {
        return Text.literal(favouritesOnly.get() ? "§e★" : "☆");
    }
}
