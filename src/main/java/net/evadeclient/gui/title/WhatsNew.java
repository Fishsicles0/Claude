package net.evadeclient.gui.title;

/**
 * The "What's new" card on the main menu: the headline of the current release, as on the website's changelog
 * (site-src/pages/changelog.html). Update it with each feature release; the card links to the full changelog.
 */
public final class WhatsNew {
    private WhatsNew() {}

    public static final String VERSION = "v2.5.0";
    public static final String HEADLINE = "Tuned for laptops, chat heads and tabs, Open to Friends, Safe Start and more.";
    public static final String URL = "https://evadeclient.com/changelog";
}
