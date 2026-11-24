package net.evadeclient.installer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Home page's news: the newest changelog entries. Read from evadeclient.com/news.json (made from the changelog
 * page when the site is built); if the site doesn't have that yet, the changelog page itself is read. The last news
 * fetched is kept, so the Home page still has something to show offline.
 */
final class News {
    private News() {}

    /** {@code image}: a picture for the Home news card (an absolute URL, or a path on the site), or null. */
    record Entry(String id, String title, String date, String summary, List<String> items, String url, String image) {
        Entry(String id, String title, String date, String summary, List<String> items, String url) {
            this(id, title, date, summary, items, url, null);
        }
    }

    static List<Entry> fetch(InstallerCore core) throws IOException, InterruptedException {
        JsonObject o = null;
        try {
            JsonElement e = core.getJson(InstallerCore.SITE + "/news.json?t=" + System.currentTimeMillis());
            if (e.isJsonObject() && e.getAsJsonObject().has("entries")) o = e.getAsJsonObject();
        } catch (IOException | RuntimeException ignored) {
            // Older site without news.json: read the changelog page below.
        }
        if (o == null) {
            String page;
            try {
                page = core.getText(InstallerCore.SITE + "/changelog");
            } catch (IOException e) {
                throw new IOException("Couldn't reach " + InstallerCore.host(InstallerCore.SITE), e);
            }
            o = fromChangelog(page);
        }
        List<Entry> out = parse(o);
        try {
            Files.createDirectories(Launcher.dataDir());
            InstallerCore.writeAtomic(cacheFile(), InstallerCore.GSON.toJson(o));
        } catch (IOException ignored) { }
        return out;
    }

    /** The news fetched last time (empty if there's none). */
    static List<Entry> cached() {
        try {
            return parse(InstallerCore.readJsonObject(cacheFile()));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static Path cacheFile() { return Launcher.dataDir().resolve("news-cache.json"); }

    static List<Entry> parse(JsonObject o) {
        List<Entry> out = new ArrayList<>();
        if (!o.has("entries") || !o.get("entries").isJsonArray()) return out;
        for (JsonElement e : o.getAsJsonArray("entries")) {
            if (!e.isJsonObject()) continue;
            JsonObject x = e.getAsJsonObject();
            List<String> items = new ArrayList<>();
            if (x.has("items") && x.get("items").isJsonArray()) for (JsonElement i : x.getAsJsonArray("items")) if (i.isJsonPrimitive()) items.add(i.getAsString());
            String date = InstallerCore.str(x, "dateText");
            out.add(new Entry(nz(InstallerCore.str(x, "id")), nz(InstallerCore.str(x, "title")), date != null ? date : nz(InstallerCore.str(x, "date")),
                    nz(InstallerCore.str(x, "summary")), items, InstallerCore.str(x, "url"), image(InstallerCore.str(x, "image"))));
        }
        return out;
    }

    /** The same entries as news.json, read straight from the changelog page's HTML. */
    static JsonObject fromChangelog(String html) {
        JsonArray entries = new JsonArray();
        Matcher m = Pattern.compile("<article\\b([^>]*)>(.*?)</article>", Pattern.DOTALL).matcher(html);
        while (m.find() && entries.size() < 6) {
            Matcher id = Pattern.compile("\\bid=\"([^\"]*)\"").matcher(m.group(1));
            String body = m.group(2), vid = id.find() ? id.group(1) : "";
            JsonObject x = new JsonObject();
            x.addProperty("id", vid);
            x.addProperty("title", first(body, "<h2>(.*?)</h2>", vid));
            x.addProperty("dateText", first(body, "<time[^>]*>(.*?)</time>", ""));
            x.addProperty("summary", first(body, "<p class=\"rel-sum\">(.*?)</p>", ""));
            JsonArray items = new JsonArray();
            Matcher li = Pattern.compile("<li>(.*?)</li>", Pattern.DOTALL).matcher(body);
            while (li.find()) items.add(plain(li.group(1)));
            x.add("items", items);
            x.addProperty("url", InstallerCore.SITE + "/changelog" + (vid.isEmpty() ? "" : "#" + vid));
            entries.add(x);
        }
        JsonObject o = new JsonObject();
        o.add("entries", entries);
        return o;
    }

    private static String first(String body, String regex, String def) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(body);
        return m.find() ? plain(m.group(1)) : def;
    }

    /** HTML fragment to plain text. */
    static String plain(String s) {
        s = s.replaceAll("<[^>]+>", "");
        s = s.replace("&nbsp;", " ").replace("&ndash;", "\u2013").replace("&mdash;", "\u2014").replace("&rsaquo;", "\u203a")
                .replace("&rarr;", "\u2192").replace("&middot;", "\u00b7").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        return s.replaceAll("\\s+", " ").strip();
    }

    /** A news picture as a full https URL (site-relative paths are on evadeclient.com), or null. */
    static String image(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.strip();
        if (s.startsWith("https://")) return s;
        if (s.startsWith("/")) return InstallerCore.SITE + s;
        if (!s.contains(":")) return InstallerCore.SITE + "/" + s;
        return null;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
