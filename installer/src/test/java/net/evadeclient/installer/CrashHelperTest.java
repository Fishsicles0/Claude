package net.evadeclient.installer;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The crash card picks the right fix for the common crash signatures. */
class CrashHelperTest {
    private static Mods.Entry mod(String id, String name, String option) {
        return new Mods.Entry(Path.of("mods", id + ".jar"), true, id, name, "1.0", "", "", option, 1);
    }

    private static CrashHelper.Diagnosis analyse(String text, List<Mods.Entry> mods) {
        return CrashHelper.analyse(text, false, 4, mods, Path.of("crash.txt"), 0);
    }

    @Test
    void wrongJava() {
        var d = analyse("java.lang.UnsupportedClassVersionError: net/minecraft/client/main/Main has been compiled by a more recent version of the Java Runtime", List.of());
        assertEquals(CrashHelper.Fix.CLEAR_JAVA, d.fix());
    }

    @Test
    void memory() {
        assertEquals(CrashHelper.Fix.RAISE_MEMORY, analyse("java.lang.OutOfMemoryError: Java heap space", List.of()).fix());
        assertEquals(CrashHelper.Fix.LOWER_MEMORY, analyse("There is insufficient memory for the Java Runtime Environment to continue.", List.of()).fix());
    }

    @Test
    void missingDependencyNamesTheMod() {
        var d = analyse("Mod 'Cool Mod' (coolmod) 1.0 requires any version of 'Cloth Config API' (cloth-config), which is missing!",
                List.of(mod("coolmod", "Cool Mod", null)));
        assertEquals(CrashHelper.Fix.FIND_MOD, d.fix());
        assertEquals("cloth-config", d.mod());
        assertNotNull(d.jar());
    }

    @Test
    void duplicateAndIncompatible() {
        assertEquals(CrashHelper.Fix.OPEN_MODS, analyse("Found duplicate mods: sodium", List.of()).fix());
        var d = analyse("Mod 'Alpha' (alpha) 1.0 is incompatible with any version of 'Beta' (beta)", List.of(mod("alpha", "Alpha", null)));
        assertEquals(CrashHelper.Fix.DISABLE_MOD, d.fix());
        assertEquals("alpha", d.mod());
    }

    @Test
    void mixinFailureBlamesTheModNotEvade() {
        var d = analyse("org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: from mod brokenmod",
                List.of(mod("brokenmod", "Broken Mod", null), mod("evade", "Evade", "Evade")));
        assertEquals(CrashHelper.Fix.DISABLE_MOD, d.fix());
        assertEquals("brokenmod", d.mod());
    }

    @Test
    void driverCrash() {
        var d = CrashHelper.analyse("# Problematic frame:\n# C  [atio6axx.dll+0x1234]\n", true, 4, List.of(), Path.of("hs_err_pid1.log"), 0);
        assertEquals(CrashHelper.Fix.DRIVER, d.fix());
    }

    @Test
    void stackTraceSuspectAndEmpty() {
        var d = analyse("Description: Ticking entity\n\njava.lang.NullPointerException\n\tat com.example.fancymobs.Thing.tick(Thing.java:1)\n",
                List.of(mod("fancymobs", "Fancy Mobs", null)));
        assertEquals(CrashHelper.Fix.DISABLE_MOD, d.fix());
        assertEquals("fancymobs", d.mod());
        assertEquals(CrashHelper.Fix.OPEN_LOG, CrashHelper.analyse("", false, 4, List.of(), null, 0).fix());
    }
}
