package net.evadeclient.clips.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The sound library for effects: built-in sounds (synthesized by ffmpeg the first time they're used, so nothing
 * third-party ships with the app) plus anything in the user's own sounds folder (WAV, MP3, OGG, M4A, FLAC).
 */
public final class Sounds {
    private Sounds() {}

    /** name → ffmpeg lavfi source + filter. */
    private static final Map<String, String[]> BUILT_IN = new LinkedHashMap<>();
    static {
        BUILT_IN.put("Hitmarker", new String[]{"aevalsrc='0.55*sin(2*PI*2600*t)*exp(-55*t)+0.3*sin(2*PI*5200*t)*exp(-80*t)+0.25*(random(0)*2-1)*exp(-120*t)':s=48000:d=0.14", "highpass=f=900"});
        BUILT_IN.put("Boom", new String[]{"aevalsrc='0.95*sin(2*PI*(42*t+90*(1-exp(-7*t))/7))*exp(-2.4*t)+0.25*(random(0)*2-1)*exp(-18*t)':s=48000:d=1.8", "lowpass=f=900"});
        BUILT_IN.put("Whoosh", new String[]{"aevalsrc='(random(0)*2-1)*pow(sin(PI*t/0.65),2)*0.9':s=48000:d=0.65", "highpass=f=500,lowpass=f=4000,volume=1.6"});
        BUILT_IN.put("Ding", new String[]{"aevalsrc='0.5*sin(2*PI*1568*t)*exp(-3*t)+0.22*sin(2*PI*3136*t)*exp(-5*t)+0.1*sin(2*PI*4704*t)*exp(-8*t)':s=48000:d=1.3", "anull"});
        BUILT_IN.put("Air horn", new String[]{"aevalsrc='(0.3*sgn(sin(2*PI*415*t))+0.22*sgn(sin(2*PI*523*t))+0.18*sgn(sin(2*PI*622*t)))*lt(mod(t,0.36),0.3)*min(1,40*mod(t,0.36))':s=48000:d=1.1", "lowpass=f=3200,volume=0.8"});
        BUILT_IN.put("Riser", new String[]{"aevalsrc='0.5*sin(2*PI*(180*t+260*t*t*t))*t/1.6+0.12*(random(0)*2-1)*t/1.6':s=48000:d=1.6", "highpass=f=120"});
        BUILT_IN.put("Sniper shot", new String[]{"aevalsrc='0.9*(random(0)*2-1)*exp(-35*t)+0.8*sin(2*PI*68*t)*exp(-5*t)':s=48000:d=1.0", "lowpass=f=5000"});
        BUILT_IN.put("Pop", new String[]{"aevalsrc='0.7*sin(2*PI*(900*t-2500*t*t))*exp(-30*t)':s=48000:d=0.15", "anull"});
        BUILT_IN.put("Glitch", new String[]{"aevalsrc='(random(0)*2-1)*lt(mod(t,0.06),0.03)*0.55+0.3*sgn(sin(2*PI*180*t))*gt(mod(t,0.12),0.08)':s=48000:d=0.45", "anull"});
        BUILT_IN.put("Bass drop", new String[]{"aevalsrc='0.9*sin(2*PI*(110*t-30*t*t))*min(1,30*t)*exp(-1.1*t)':s=48000:d=2.2", "lowpass=f=600"});
    }

    static final List<String> EXT = List.of("wav", "mp3", "ogg", "m4a", "flac", "aac");

    public static Path userDir() { return Settings.appDir().resolve("sounds"); }
    private static Path builtInDir() { return Settings.appDir().resolve("sounds-builtin"); }

    /** Every sound by name: built-in first, then the user's (named after the file). */
    public static List<String> list() {
        List<String> out = new ArrayList<>(BUILT_IN.keySet());
        try (var s = Files.list(userDir())) {
            s.filter(p -> EXT.contains(Library.ext(p))).sorted().forEach(p -> out.add(userName(p)));
        } catch (IOException ignored) {}
        return out;
    }

    public static boolean isBuiltIn(String name) { return BUILT_IN.containsKey(name); }

    private static String userName(Path p) {
        String n = p.getFileName().toString();
        return n.substring(0, n.lastIndexOf('.'));
    }

    /** The file for a sound (made the first time for a built-in one), or null if it doesn't exist. */
    public static synchronized Path file(String name) throws IOException {
        if (name == null || name.isBlank()) return null;
        String[] synth = BUILT_IN.get(name);
        if (synth != null) {
            Path f = builtInDir().resolve(name.toLowerCase(Locale.ROOT).replace(' ', '-') + ".wav");
            if (!Files.exists(f) || Files.size(f) < 100) {
                Files.createDirectories(f.getParent());
                Ffmpeg.run(List.of("-y", "-f", "lavfi", "-i", synth[0], "-af", synth[1] + ",aformat=sample_fmts=s16:channel_layouts=stereo,alimiter=limit=0.95",
                        "-ar", "48000", f.toString()));
            }
            return f;
        }
        try (var s = Files.list(userDir())) {
            return s.filter(p -> EXT.contains(Library.ext(p)) && userName(p).equals(name)).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Opens (creating it if needed) the folder where people put their own sounds. */
    public static Path ensureUserDir() throws IOException {
        Files.createDirectories(userDir());
        return userDir();
    }
}
