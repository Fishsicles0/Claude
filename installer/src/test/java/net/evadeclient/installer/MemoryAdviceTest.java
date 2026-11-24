package net.evadeclient.installer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Memory advice for shared-memory laptops and PCs with a graphics card. */
class MemoryAdviceTest {
    @Test
    void integratedGraphics() {
        assertTrue(MemoryAdvice.integrated("AMD Radeon(TM) 780M"));
        assertTrue(MemoryAdvice.integrated("Intel(R) UHD Graphics 620"));
        assertFalse(MemoryAdvice.integrated("NVIDIA GeForce RTX 4060 Laptop GPU"));
        assertTrue(MemoryAdvice.discrete("AMD Radeon RX 7600"));
    }

    @Test
    void advice() {
        MemoryAdvice.Advice laptop = MemoryAdvice.advise(List.of("AMD Radeon(TM) 780M"), 16);
        assertTrue(laptop.shared());
        assertEquals(4, laptop.recommendedGb());
        assertTrue(laptop.overHalf(10));
        assertFalse(laptop.overHalf(8));
        assertEquals(3, MemoryAdvice.advise(List.of("Intel(R) Iris(R) Xe Graphics"), 8).recommendedGb());
        assertEquals(2, MemoryAdvice.advise(List.of("Intel(R) UHD Graphics"), 4).recommendedGb());
        // a laptop with both: the graphics card wins, so memory isn't shared
        assertFalse(MemoryAdvice.advise(List.of("Intel(R) UHD Graphics", "NVIDIA GeForce RTX 3050"), 16).shared());
        assertTrue(MemoryAdvice.advise(List.of(), 32).zgcSuits());
    }

    @Test
    void collectorSwitchKeepsPlayerFlags() {
        String args = "-Xss2M " + InstallerCore.G1_FLAGS;
        String z = MemoryAdvice.withCollector(args, true);
        assertTrue(z.startsWith("-Xss2M "), z);
        assertFalse(z.contains(InstallerCore.G1_FLAGS), z);
        assertEquals(args, MemoryAdvice.withCollector(z, false));
        assertEquals(z, MemoryAdvice.withCollector(z, true));
    }
}
