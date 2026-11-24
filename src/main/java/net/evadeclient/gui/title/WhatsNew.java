package net.evadeclient.gui.title;

/**
 * The "What's new" card on the main menu: the headline of the current release, as on the website's changelog
 * (site-src/pages/changelog.html). Update it with each feature release; the card links to the full changelog.
 */
public final class WhatsNew {
    private WhatsNew() {}

    public static final String VERSION = "v2.3.0";
    public static final String HEADLINE = "Friend messages, a new HUD editor, OptiFine-style packs, and a launcher with logs, crash help and modpacks.";
    public static final String URL = "https://evadeclient.com/changelog";
}
