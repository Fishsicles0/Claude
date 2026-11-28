package net.evadeclient.gui.menu;

import net.evadeclient.gui.ColorPicker;
import net.evadeclient.gui.kit.Kit;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ColorSetting;

import java.util.List;

/**
 * What the Evade menu's pages need from the window that hosts them ({@code gui.EvadeMenu}): its kit, scroll and
 * clock, the content area (design px), and navigation. Pages draw inside the window between the header band at
 * {@link #top()} and the bottom of {@link #ch()}.
 */
public interface MenuHost {
    Kit kit();

    MenuScroll scroll();

    /** The menu clock (ms). */
    double now();

    /** Content area: left x, list top y, width, list height; {@link #top()} is the header row's top. */
    int cx();
    int cy();
    int cw();
    int ch();
    int top();

    /** Right edge for header actions (left of the window's close button). */
    int headerRight();

    MenuPage page();

    /** The module whose settings page is open, or null. */
    Module selected();

    void openModule(Module m);

    void go(MenuPage p);

    /** Back from a module page (to the list, or the screen that opened it). */
    void leaveModulePage();

    /** The page's content changed (a tab): fade it through. */
    void pageChanged(int dir);

    /** Modules shown by the list page (search, filter, sort applied). */
    List<Module> visibleModules();

    /** Modules of a page ignoring search and filters. */
    List<Module> modulesFor(MenuPage p, String filter);

    String search();

    boolean enabledOnly();

    void enabledOnly(boolean on);

    boolean sortAz();

    void sortAz(boolean on);

    /** Open the colour picker for {@code c} next to (x, y) in design px. */
    void openPicker(ColorSetting c, int x, int y);

    ColorPicker picker();

    /** A text cut to {@code max} px with an ellipsis (cached). */
    String fit(String s, net.evadeclient.util.UiFont.Role r, int max);
}
