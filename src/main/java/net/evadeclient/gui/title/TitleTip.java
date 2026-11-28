package net.evadeclient.gui.title;

/**
 * A main menu widget with a tooltip. The title screen draws the tooltip itself (above the widget, in the kit's
 * tooltip style and the menu's design units) instead of vanilla's, which would land in the wrong place while the
 * menu is drawn zoomed; widgets also narrate it as a hint.
 */
public interface TitleTip {
    /** The tooltip text, or null for none. */
    String tip();
}
