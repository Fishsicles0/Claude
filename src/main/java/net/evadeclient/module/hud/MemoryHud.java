package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;

import java.util.ArrayList;
import java.util.List;

public class MemoryHud extends TextHudModule {
    public MemoryHud() { super("Memory", "Java memory usage.", 4, 104); }

    @Override
    protected List<String> lines(boolean preview) {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1048576, max = rt.maxMemory() / 1048576;
        return new ArrayList<>(List.of("Mem " + (used * 100 / Math.max(1, max)) + "% " + used + "/" + max + "MB"));
    }
}
