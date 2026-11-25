package net.evadeclient.gui.title;

/**
 * The "What's new" card on the main menu: the headline of the current release, as on the website's changelog
 * (site-src/pages/changelog.html). Update it with each feature release; the card links to the full changelog.
 */
public final class WhatsNew {
    private WhatsNew() {}

    public static final String VERSION = "v2.8.0";
    public static final String HEADLINE = "Rebuilt pets, sharper cosmetics, free capes, faster hotkeys and a safer launcher.";
    public static final String URL = "https://evadeclient.com/changelog";
}
