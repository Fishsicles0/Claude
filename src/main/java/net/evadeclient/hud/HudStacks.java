package net.evadeclient.hud;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.HudElementModule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HUD stacks: widgets grouped in the HUD editor move together and sit one under another, {@link #GAP} units apart, in
 * their stack order. When one of them grows, shrinks or has nothing to show, the ones after it close up.
 *
 * <p>Membership is two hidden settings on each widget ({@code Stack} and {@code Stack Order}), so stacks are saved
 * with the config and profiles. The stack hangs from its top widget, or, when its bottom widget is anchored to the
 * bottom of the screen, stands on that one and grows upwards; every widget takes the anchor's horizontal anchoring
 * (left edges, right edges or centres line up).
 */
public final class HudStacks {
    private HudStacks() {}

    /** Units between widgets in a stack (the same as the editor's stacking snap). */
    public static final int GAP = 2;

    /** Every stack with its widgets in order (the disabled ones included). */
    public static Map<String, List<HudElementModule>> stacks() {
        Map<String, List<HudElementModule>> out = new LinkedHashMap<>();
        if (EvadeClient.modules == null) return out;
        for (HudElementModule el : EvadeClient.modules.hudElements()) {
            String id = el.stack.get();
            if (id != null && !id.isEmpty()) out.computeIfAbsent(id, k -> new ArrayList<>()).add(el);
        }
        for (List<HudElementModule> l : out.values())
            l.sort(Comparator.comparingInt((HudElementModule e) -> e.stackOrder.get().intValue()));
        return out;
    }

    /** The widgets in {@code el}'s stack in order, or just {@code el} when it isn't in one. */
    public static List<HudElementModule> members(HudElementModule el) {
        String id = el.stack.get();
        if (id == null || id.isEmpty()) return List.of(el);
        List<HudElementModule> l = stacks().get(id);
        return l == null ? List.of(el) : l;
    }

    public static boolean inStack(HudElementModule el) {
        String id = el.stack.get();
        return id != null && !id.isEmpty();
    }

    /**
     * Puts {@code els} (two or more) in a new stack, ordered top to bottom as they are on a {@code w} x {@code h} HUD,
     * and lines them up. Widgets taken from other stacks leave them. Returns the new stack's id, or null for fewer
     * than two widgets.
     */
    public static String group(Collection<HudElementModule> els, int w, int h) {
        List<HudElementModule> list = new ArrayList<>(new LinkedHashSet<>(els));
        if (list.size() < 2) return null;
        list.sort(Comparator.comparingInt((HudElementModule e) -> e.resolveY(h)).thenComparingInt(e -> e.resolveX(w)));
        Set<String> touched = new LinkedHashSet<>();
        for (HudElementModule e : list) if (inStack(e)) touched.add(e.stack.get());
        String id = newId();
        for (int i = 0; i < list.size(); i++) {
            list.get(i).stack.set(id);
            list.get(i).stackOrder.set((double) i);
        }
        dissolveSmall(touched);
        apply(w, h, true);
        return id;
    }

    /** Takes {@code els} and everything stacked with them out of their stacks (positions stay where they are). */
    public static int ungroup(Collection<HudElementModule> els) {
        Set<String> ids = new LinkedHashSet<>();
        for (HudElementModule e : els) if (inStack(e)) ids.add(e.stack.get());
        int n = 0;
        if (EvadeClient.modules == null) return 0;
        for (HudElementModule el : EvadeClient.modules.hudElements()) {
            if (!ids.contains(el.stack.get())) continue;
            el.stack.set("");
            el.stackOrder.set(0.0);
            n++;
        }
        return n;
    }

    /** Stacks left with fewer than two widgets stop being stacks. */
    private static void dissolveSmall(Set<String> ids) {
        Map<String, List<HudElementModule>> all = stacks();
        for (String id : ids) {
            List<HudElementModule> l = all.get(id);
            if (l != null && l.size() < 2) for (HudElementModule e : l) { e.stack.set(""); e.stackOrder.set(0.0); }
        }
    }

    private static String newId() {
        Set<String> used = stacks().keySet();
        for (int i = 1; ; i++) {
            String id = "s" + Integer.toString((int) (System.nanoTime() & 0x7FFFFF) + i, 36);
            if (!used.contains(id)) return id;
        }
    }

    /**
     * Lines every stack up on a {@code w} x {@code h} HUD (units). In game only widgets on screen take up room (one
     * that is off, hidden or empty leaves no gap); in the editor every enabled widget does.
     */
    public static void apply(int w, int h, boolean editor) {
        for (List<HudElementModule> stack : stacks().values()) {
            List<HudElementModule> shown = new ArrayList<>();
            for (HudElementModule e : stack) if (editor ? e.isEnabled() : e.isShowing()) shown.add(e);
            if (shown.size() < 2) continue;
            HudElementModule last = shown.getLast();
            boolean up = last.y < 0; // the bottom widget is anchored to the bottom edge: grow upwards from it
            HudElementModule anchor = up ? last : shown.getFirst();
            int ax = anchor.x;
            if (up) {
                int bottom = anchor.resolveY(h) + anchor.scaledHeight();
                for (int i = shown.size() - 1; i >= 0; i--) {
                    HudElementModule e = shown.get(i);
                    e.x = ax;
                    if (e != anchor) e.y = Math.min(-1, -(h - bottom) - 1);
                    bottom -= e.scaledHeight() + GAP;
                }
            } else {
                int top = anchor.resolveY(h);
                for (HudElementModule e : shown) {
                    e.x = ax;
                    if (e != anchor) e.y = Math.max(0, top);
                    top += e.scaledHeight() + GAP;
                }
            }
        }
    }
}
