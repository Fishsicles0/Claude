package net.evadeclient.mods;

import net.evadeclient.EvadeClient;
import net.evadeclient.mods.ModrinthApi.Dependency;
import net.evadeclient.mods.ModrinthApi.Version;
import net.evadeclient.mods.ModrinthApi.VersionFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Installs, updates and removes mods in one mods folder, from Modrinth only. Blocking: call from a background thread.
 * Nothing here runs by itself; every change starts from an explicit call (the player's click).
 *
 * <p>Downloads land as {@code <name>.evade-new} (Fabric only loads {@code .jar} files), are checked against Modrinth's
 * SHA-512, and are renamed into place only once every file of an install has arrived, so a failed install leaves the
 * folder as it was. A jar the game still holds open (Windows) is removed when the game closes: see {@link #processPending}.
 */
public final class ModManager {
    /** Fabric API's Modrinth project id. */
    public static final String FABRIC_API_PROJECT = "P7dR8mSH";
    /** Mod ids that can never be removed from the browser: Evade itself and Fabric API, which Evade needs. */
    public static final Set<String> PROTECTED = Set.of("evade", "fabric-api");
    /** Well-known Modrinth projects and their Fabric mod ids, for spotting them installed when the hash lookup fails. */
    static final Map<String, String> KNOWN_IDS = Map.of(
            FABRIC_API_PROJECT, "fabric-api", "AANobbMI", "sodium", "YL57xq9U", "iris", "mOgUt4GM", "modmenu",
            "9s6osm5g", "cloth-config", "Ha28R6CL", "fabric-language-kotlin", "gvQqBUqZ", "lithium");
    /** Stops a runaway dependency chain. */
    private static final int MAX_STEPS = 32;
    static final String STAGED = ".evade-new", PART = ".evade-part";

    private final Path mods;
    private final ModIndex index;
    private final ModrinthApi api;
    /** sha1 of a jar -> its Modrinth project id ("" when Modrinth doesn't know the file). */
    private final Map<String, String> projectBySha1 = new ConcurrentHashMap<>();
    /** Scans by path, reused while a jar's size and modified time stay the same (hashing every jar is slow). */
    private final Map<Path, Cached> scanCache = new ConcurrentHashMap<>();

    private record Cached(long size, long modified, InstalledMod mod) {}

    public ModManager(Path mods, Path indexFile, ModrinthApi api) {
        this.mods = mods;
        this.index = new ModIndex(indexFile);
        this.api = api;
    }

    public Path modsDir() { return mods; }
    public ModIndex index() { return index; }
    public ModrinthApi api() { return api; }

    // ------------------------------------------------------------------ installed mods

    /** The jars in the mods folder now. */
    public List<InstalledMod> scan() {
        List<InstalledMod> out = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        if (Files.isDirectory(mods)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "*.jar")) {
                for (Path p : ds) {
                    try {
                        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                        if (!a.isRegularFile()) continue;
                        long mod = a.lastModifiedTime().toMillis();
                        Cached c = scanCache.get(p);
                        if (c == null || c.size != a.size() || c.modified != mod) {
                            c = new Cached(a.size(), mod, InstalledMod.read(p));
                            scanCache.put(p, c);
                        }
                        seen.add(p);
                        out.add(c.mod);
                    } catch (IOException ignored) {
                        // vanished meanwhile
                    }
                }
            } catch (IOException ignored) {
                // unreadable folder: empty
            }
        }
        scanCache.keySet().retainAll(seen);
        out.sort(Comparator.comparing(m -> m.displayName().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Ask Modrinth which project each jar is, by its sha1 (best-effort, remembered for the session). */
    public void identify(List<InstalledMod> list) {
        List<String> ask = new ArrayList<>();
        for (InstalledMod m : list) if (m.sha1() != null && !projectBySha1.containsKey(m.sha1())) ask.add(m.sha1());
        if (ask.isEmpty()) return;
        try {
            Map<String, Version> found = api.versionsByHash(ask);
            for (String h : ask) {
                Version v = found.get(h);
                projectBySha1.put(h, v != null && v.projectId() != null ? v.projectId() : "");
            }
        } catch (IOException e) {
            EvadeClient.LOG.debug("Mod Browser: hash lookup failed: {}", e.toString());
        }
    }

    /** {@code m}'s Modrinth project id, if known (installed by the browser, or identified by its hash). */
    public String projectIdOf(InstalledMod m) {
        ModIndex.Entry e = index.byFile(m.fileName());
        if (e != null) return e.projectId();
        String p = m.sha1() == null ? null : projectBySha1.get(m.sha1());
        if (p != null && !p.isEmpty()) return p;
        if (m.id() != null) for (var en : KNOWN_IDS.entrySet()) if (en.getValue().equals(m.id())) return en.getKey();
        return null;
    }

    /** Project ids of every mod in {@code list} that we can name. */
    public Set<String> projectIds(List<InstalledMod> list) {
        Set<String> out = new HashSet<>();
        for (InstalledMod m : list) {
            String p = projectIdOf(m);
            if (p != null) out.add(p);
        }
        return out;
    }

    /** Whether the file is waiting to be removed when the game closes. */
    public boolean removalPending(InstalledMod m) {
        return index.pendingDelete(m.fileName());
    }

    /** Why {@code m} may not be removed, or null when it may. */
    public static String protectedReason(InstalledMod m) {
        if ("evade".equals(m.id())) return "Evade can't remove itself";
        if ("fabric-api".equals(m.id()) || m.provides().contains("fabric-api")) return "Evade needs Fabric API";
        return null;
    }

    /** The other mods in {@code all} that list one of {@code m}'s ids as a required dependency. */
    public List<InstalledMod> requiredBy(InstalledMod m, List<InstalledMod> all) {
        List<InstalledMod> out = new ArrayList<>();
        if (m.provides().isEmpty()) return out;
        for (InstalledMod o : all) {
            if (o == m || o.path().equals(m.path()) || removalPending(o)) continue;
            // a second copy of the same mod doesn't need it
            if (o.id() != null && o.id().equals(m.id())) continue;
            for (String d : o.depends()) if (m.provides().contains(d)) { out.add(o); break; }
        }
        return out;
    }

    // ------------------------------------------------------------------ install

    /** One mod of an install: what will be downloaded, or why it is skipped ({@code skip} non-null). */
    public record Step(String projectId, String title, Version version, VersionFile file, boolean dependency, String skip) {}

    public record Plan(String projectId, List<Step> steps) {
        public List<Step> toInstall() {
            List<Step> out = new ArrayList<>();
            for (Step s : steps) if (s.skip() == null) out.add(s);
            return out;
        }

        /** The mod asked for. */
        public Step root() { return steps.isEmpty() ? null : steps.get(0); }
    }

    public record InstallResult(List<String> installed, List<String> skipped) {}

    /** The newest compatible version: the newest release, else the newest beta or alpha. */
    public static Version pickNewest(List<Version> versions) {
        List<Version> sorted = new ArrayList<>(versions);
        sorted.removeIf(v -> !v.compatible() || v.primaryFile() == null);
        sorted.sort(Comparator.comparing((Version v) -> v.published() == null ? "" : v.published()).reversed());
        for (Version v : sorted) if ("release".equals(v.type())) return v;
        return sorted.isEmpty() ? null : sorted.get(0);
    }

    /**
     * Work out what installing {@code projectId} takes: its newest version for this game version on Fabric, and the
     * same for each required dependency (recursively, honouring a pinned dependency version when it is compatible).
     * Mods already in the folder are kept as they are and marked skipped. Changes nothing on disk.
     */
    public Plan plan(String projectId) throws IOException {
        List<InstalledMod> installed = scan();
        identify(installed);
        List<InstalledMod> present = new ArrayList<>(installed);
        present.removeIf(this::removalPending);
        Set<String> haveProjects = projectIds(present);
        Set<String> haveIds = new HashSet<>(), haveFiles = new HashSet<>();
        for (InstalledMod m : installed) {
            if (removalPending(m)) continue;
            haveIds.addAll(m.provides());
            haveFiles.add(m.fileName().toLowerCase(Locale.ROOT));
        }

        record Want(String projectId, String versionId, boolean dependency) {}
        Deque<Want> queue = new ArrayDeque<>();
        queue.add(new Want(projectId, null, false));
        Set<String> seen = new HashSet<>();
        List<Step> steps = new ArrayList<>();
        String rootId = projectId;
        while (!queue.isEmpty()) {
            Want w = queue.poll();
            if (w.projectId == null || !seen.add(w.projectId)) continue;
            if (steps.size() >= MAX_STEPS) throw new IOException("Too many dependencies");
            Version v = null;
            if (w.versionId != null) {
                try {
                    v = api.version(w.versionId);
                    if (!v.compatible() || v.primaryFile() == null) v = null;
                } catch (IOException e) {
                    v = null;
                }
            }
            if (v == null) v = pickNewest(api.versions(w.projectId));
            if (v == null) {
                String title = w.projectId;
                try { title = api.project(w.projectId).title(); } catch (IOException ignored) {}
                throw new IOException(w.dependency ? "Needs " + title + ", which has no Fabric " + ModrinthApi.GAME_VERSION + " version"
                        : title + " has no Fabric " + ModrinthApi.GAME_VERSION + " version");
            }
            // a slug asked for: from here on, the project's real id
            String pid = v.projectId() != null ? v.projectId() : w.projectId;
            if (!pid.equals(w.projectId) && !seen.add(pid)) continue;
            if (steps.isEmpty()) rootId = pid;
            VersionFile f = v.primaryFile();
            if (f.filename() == null || !safeFileName(f.filename()) || !f.filename().endsWith(".jar"))
                throw new IOException("Modrinth listed an unusable file name");
            String skip = null;
            String known = KNOWN_IDS.get(pid);
            if (haveProjects.contains(pid) || haveProjects.contains(w.projectId) || (known != null && haveIds.contains(known))
                    || haveFiles.contains(f.filename().toLowerCase(Locale.ROOT))) skip = "Already installed";
            steps.add(new Step(pid, pid, v, f, w.dependency, skip));
            if (skip != null) continue;
            for (Dependency d : v.dependencies()) {
                if (!"required".equals(d.type())) continue;
                String dep = d.projectId();
                if (dep == null && d.versionId() != null) {
                    try { dep = api.version(d.versionId()).projectId(); } catch (IOException ignored) {}
                }
                if (dep != null) queue.add(new Want(dep, d.versionId(), true));
            }
        }
        // titles for the list (one request)
        Set<String> ids = new LinkedHashSet<>();
        for (Step s : steps) ids.add(s.projectId());
        Map<String, ModrinthApi.Project> projects = Collections.emptyMap();
        try { projects = api.projects(ids); } catch (IOException ignored) {}
        List<Step> named = new ArrayList<>();
        for (Step s : steps) {
            ModrinthApi.Project p = projects.get(s.projectId());
            named.add(new Step(s.projectId(), p != null ? p.title() : s.version().name(), s.version(), s.file(), s.dependency(), s.skip()));
        }
        return new Plan(rootId, named);
    }

    /**
     * Install {@code plan}: download every mod it doesn't skip, check each one, then put them all in place. A mod whose
     * Fabric id turns out to be in the folder already (under another file name) is left out. {@code progress} gets
     * (mod title, 0..1).
     */
    public InstallResult install(Plan plan, BiConsumer<String, Double> progress) throws IOException {
        Files.createDirectories(mods);
        List<Step> todo = new ArrayList<>(plan.toInstall());
        Collections.reverse(todo); // dependencies first
        Map<Step, Path> staged = new LinkedHashMap<>();
        List<String> installed = new ArrayList<>(), skipped = new ArrayList<>();
        for (Step s : plan.steps()) if (s.skip() != null) skipped.add(s.title());
        try {
            for (Step s : todo) {
                Path to = mods.resolve(s.file().filename() + STAGED);
                if (progress != null) progress.accept(s.title(), 0.0);
                api.download(s.file(), to, p -> { if (progress != null) progress.accept(s.title(), p); });
                staged.put(s, to);
            }
            Set<String> haveIds = new HashSet<>();
            for (InstalledMod m : scan()) if (!removalPending(m)) haveIds.addAll(m.provides());
            for (var en : staged.entrySet()) {
                Step s = en.getKey();
                Path from = en.getValue(), target = mods.resolve(s.file().filename());
                String fid = InstalledMod.fabricId(from);
                if (Files.exists(target) || (fid != null && haveIds.contains(fid))) {
                    Files.deleteIfExists(from);
                    skipped.add(s.title());
                    continue;
                }
                Files.move(from, target, StandardCopyOption.ATOMIC_MOVE);
                if (fid != null) haveIds.add(fid);
                index.put(new ModIndex.Entry(s.projectId(), s.file().filename(), s.version().id(), s.version().versionNumber(),
                        s.title(), fid, s.file().sha512(), s.dependency()));
                installed.add(s.title());
            }
        } finally {
            for (Path p : staged.values()) try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        }
        return new InstallResult(installed, skipped);
    }

    // ------------------------------------------------------------------ updates

    /** Newer versions for this game version on Fabric, by file name, for the jars Modrinth knows. */
    public Map<String, Version> checkUpdates(List<InstalledMod> list) throws IOException {
        Map<String, InstalledMod> bySha1 = new LinkedHashMap<>();
        for (InstalledMod m : list) {
            if (m.sha1() == null || "evade".equals(m.id()) || removalPending(m)) continue;
            bySha1.put(m.sha1(), m);
        }
        Map<String, Version> out = new LinkedHashMap<>();
        Map<String, Version> latest = api.latestByHash(bySha1.keySet());
        for (var en : latest.entrySet()) {
            InstalledMod m = bySha1.get(en.getKey());
            Version v = en.getValue();
            if (m == null || v == null || !v.compatible()) continue;
            VersionFile f = v.primaryFile();
            if (f == null || f.filename() == null || !safeFileName(f.filename()) || !f.filename().endsWith(".jar")) continue;
            if (en.getKey().equalsIgnoreCase(f.sha1())) continue; // already the newest
            boolean same = false;
            for (VersionFile vf : v.files()) if (en.getKey().equalsIgnoreCase(vf.sha1())) same = true;
            if (!same) out.put(m.fileName(), v);
            if (v.projectId() != null) projectBySha1.put(m.sha1(), v.projectId());
        }
        return out;
    }

    public enum Outcome { DONE, ON_RESTART }

    /** Replace {@code m} with version {@code v}. The old jar goes once the new one has arrived and checked out. */
    public Outcome update(InstalledMod m, Version v, java.util.function.DoubleConsumer progress) throws IOException {
        VersionFile f = v.primaryFile();
        if (f == null || f.filename() == null || !safeFileName(f.filename()) || !f.filename().endsWith(".jar"))
            throw new IOException("Modrinth listed an unusable file name");
        Path staged = mods.resolve(f.filename() + STAGED);
        api.download(f, staged, progress);
        try {
            String fid = InstalledMod.fabricId(staged);
            if (m.id() != null && fid != null && !m.id().equals(fid)) throw new IOException("The update is a different mod (" + fid + ")");
            Path target = mods.resolve(f.filename());
            if (!target.equals(m.path()) && Files.exists(target)) throw new IOException(f.filename() + " is already in the mods folder");
            ModIndex.Entry old = index.byFile(m.fileName());
            String pid = v.projectId() != null ? v.projectId() : old != null ? old.projectId() : projectIdOf(m);
            ModIndex.Entry entry = new ModIndex.Entry(pid, f.filename(), v.id(), v.versionNumber(),
                    old != null ? old.title() : m.displayName(), fid, f.sha512(), old != null && old.dependency());
            Outcome out;
            if (tryDelete(m.path())) {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
                out = Outcome.DONE;
            } else {
                // The old jar is in use: the new one waits beside it (not as a .jar, so it can't load twice) and the
                // swap happens when the game closes.
                ModIndex.Pending p = new ModIndex.Pending(m.fileName(), staged.getFileName().toString(), f.filename());
                index.addPending(p);
                afterExit(List.of(p));
                staged = null;
                out = Outcome.ON_RESTART;
            }
            index.removeFile(m.fileName());
            if (pid != null) index.put(entry);
            return out;
        } finally {
            if (staged != null) try { Files.deleteIfExists(staged); } catch (IOException ignored) {}
        }
    }

    // ------------------------------------------------------------------ remove

    /** Remove {@code m} from the mods folder (now, or when the game closes if the jar is in use). */
    public Outcome remove(InstalledMod m) throws IOException {
        String why = protectedReason(m);
        if (why != null) throw new IOException(why);
        if (!m.path().getParent().equals(mods) || !safeFileName(m.fileName())) throw new IOException("Not in the mods folder");
        if (tryDelete(m.path())) {
            index.removeFile(m.fileName());
            return Outcome.DONE;
        }
        ModIndex.Pending p = new ModIndex.Pending(m.fileName(), null, null);
        index.addPending(p);
        index.removeFile(m.fileName());
        afterExit(List.of(p));
        return Outcome.ON_RESTART;
    }

    /** Delete {@code p}; true when it is gone (or never existed), false when it is locked. */
    private static boolean tryDelete(Path p) {
        try {
            Files.delete(p);
            return true;
        } catch (NoSuchFileException e) {
            return true;
        } catch (IOException e) {
            return !Files.exists(p);
        }
    }

    /**
     * Finish file changes left from an earlier session (call once at startup, before the menu is used): delete jars
     * that were in use, move updates into place, and clear stray downloads. Whatever still can't be done (the jar is
     * loaded again) is handed to a helper that does it when the game closes. Returns how many changes finished.
     */
    public int processPending() {
        int done = 0;
        List<ModIndex.Pending> left = new ArrayList<>();
        Set<String> keep = new HashSet<>();
        for (ModIndex.Pending p : index.pending()) {
            boolean gone = p.delete() == null || tryDelete(mods.resolve(p.delete()));
            if (!gone) {
                left.add(p);
                if (p.from() != null) keep.add(p.from());
                continue;
            }
            if (p.from() != null) {
                Path from = mods.resolve(p.from()), to = mods.resolve(p.to());
                try {
                    if (Files.exists(from) && !Files.exists(to)) Files.move(from, to);
                    else Files.deleteIfExists(from);
                } catch (IOException e) {
                    EvadeClient.LOG.warn("Mod Browser: couldn't finish updating {}: {}", p.to(), e.toString());
                }
            }
            done++;
        }
        index.setPending(left);
        // stray partial or staged downloads (a crash mid-install)
        if (Files.isDirectory(mods)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods)) {
                for (Path f : ds) {
                    String n = f.getFileName().toString();
                    if ((n.endsWith(PART) || n.endsWith(STAGED)) && !keep.contains(n)) try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                }
            } catch (IOException ignored) {
                // nothing to clean
            }
        }
        if (!left.isEmpty()) afterExit(left);
        return done;
    }

    /**
     * On Windows a jar the game has loaded can't be deleted until the game exits: start a small hidden PowerShell job
     * that waits for this process to end, then does the changes. Elsewhere files can be deleted while open, so this
     * never has anything to do. If it fails, {@link #processPending} retries on the next start.
     */
    private void afterExit(List<ModIndex.Pending> ops) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        StringBuilder ps = new StringBuilder();
        ps.append("Wait-Process -Id ").append(ProcessHandle.current().pid()).append(" -ErrorAction SilentlyContinue; Start-Sleep -Milliseconds 500;\n");
        for (ModIndex.Pending p : ops) {
            String d = p.delete() == null ? null : quote(mods.resolve(p.delete()));
            if (d != null) {
                ps.append("$d=").append(d).append("; for($i=0;$i -lt 20 -and (Test-Path -LiteralPath $d);$i++){")
                        .append("Remove-Item -LiteralPath $d -Force -ErrorAction SilentlyContinue;")
                        .append("if(Test-Path -LiteralPath $d){Start-Sleep -Milliseconds 500}}\n");
            }
            if (p.from() != null) {
                String cond = d == null ? "$true" : "-not (Test-Path -LiteralPath $d)";
                ps.append("if(").append(cond).append("){$f=").append(quote(mods.resolve(p.from()))).append(";$t=").append(quote(mods.resolve(p.to())))
                        .append("; if((Test-Path -LiteralPath $f) -and -not (Test-Path -LiteralPath $t)){Move-Item -LiteralPath $f -Destination $t}}\n");
            }
        }
        try {
            String enc = Base64.getEncoder().encodeToString(ps.toString().getBytes(StandardCharsets.UTF_16LE));
            new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", enc)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException | RuntimeException e) {
            EvadeClient.LOG.warn("Mod Browser: couldn't schedule file changes for when the game closes: {}", e.toString());
        }
    }

    /** A path as a single-quoted PowerShell literal. */
    private static String quote(Path p) {
        return "'" + p.toAbsolutePath().toString().replace("'", "''") + "'";
    }

    /** A plain file name that stays in the mods folder: no separators, no "..", no control characters. */
    public static boolean safeFileName(String n) {
        if (n == null || n.isEmpty() || n.length() > 200 || n.equals(".") || n.equals("..")) return false;
        if (n.startsWith(".") || n.contains("..")) return false;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 32 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') return false;
        }
        return true;
    }
}
