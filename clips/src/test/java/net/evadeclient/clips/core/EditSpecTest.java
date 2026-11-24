package net.evadeclient.clips.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The editor's model: cuts, speed ramps, freeze frames, rewinds and where source time lands in the export. */
class EditSpecTest {
    private static final double E = 1e-9;

    @Test
    void wholeClipIsUntouched() {
        EditSpec e = EditSpec.whole(10);
        assertTrue(e.isUntouched(10));
        assertEquals(10, e.outputDuration(), E);
        e.zoom = 1.5;
        assertFalse(e.isUntouched(10));
    }

    @Test
    void splitKeepsLengthAndRefusesEdges() {
        EditSpec e = EditSpec.whole(10);
        assertTrue(e.splitAt(4));
        assertEquals(2, e.segments.size());
        assertEquals(10, e.outputDuration(), E);
        assertFalse(e.splitAt(4.05), "too close to the new edge");
        assertFalse(e.splitAt(11), "outside the clip");
        assertEquals(0, e.segmentAt(1));
        assertEquals(1, e.segmentAt(4));
        assertEquals(-1, e.segmentAt(10));
    }

    @Test
    void speedChangesStretchTheOutput() {
        EditSpec e = EditSpec.whole(10);
        e.splitAt(5);
        e.segments.get(1).speed = 0.5;
        assertEquals(5 + 10, e.outputDuration(), E);
        assertEquals(5 + 2, e.toOutput(6), E); // 1 s of source at half speed is 2 s of output
        e.segments.remove(0); // cut the first half
        assertEquals(0, e.toOutput(2), E);
        assertEquals(2, e.toOutput(6), E);
    }

    @Test
    void rampsAreSmoothAndContinuous() {
        EditSpec.Segment s = new EditSpec.Segment(0, 3, 1);
        s.rampTo = 0.25;
        assertTrue(s.ramped());
        assertEquals(1, s.speedAt(0), E);
        assertEquals(0.25, s.speedAt(3), E);
        List<EditSpec.Piece> p = s.pieces();
        assertTrue(p.size() >= 4 && p.size() <= 40, "pieces " + p.size());
        for (int i = 1; i < p.size(); i++) assertEquals(p.get(i - 1).end(), p.get(i).start(), E);
        assertTrue(s.outputLength() > 3, "slowing down makes it longer");
        // ramp around a moment: slower in the middle, back to normal at both ends
        EditSpec e = EditSpec.whole(10);
        assertTrue(e.rampAround(5, 0.3, 1.5, 1.5));
        assertEquals(10, e.segments.stream().mapToDouble(EditSpec.Segment::length).sum(), 1e-6);
        assertTrue(e.outputDuration() > 10);
        assertEquals(0.3, e.segments.get(e.segmentAt(5)).speedAt(5), 1e-6);
        assertFalse(e.rampAround(20, 0.3, 1, 1));
    }

    @Test
    void freezeFrameAndRewind() {
        EditSpec.Segment s = new EditSpec.Segment(2, 4, 1);
        s.holdAfter = 10; // clamped to 3 s
        assertEquals(EditSpec.MAX_HOLD, s.hold(), E);
        assertEquals(2 + 3, s.outputLength(), E);
        s.holdAfter = 0;
        s.rewind = true;
        assertTrue(s.rewinds());
        assertEquals(6, s.outputLength(), E); // forwards, backwards, forwards
        assertTrue(s.pieces().get(1).reverse());
        EditSpec.Segment longOne = new EditSpec.Segment(0, EditSpec.MAX_REWIND + 1, 1);
        longOne.rewind = true;
        assertFalse(longOne.rewinds(), "too long to rewind");
    }

    @Test
    void splitMovesTheFreezeFrameRight() {
        EditSpec e = EditSpec.whole(10);
        e.segments.get(0).holdAfter = 1;
        e.segments.get(0).rewind = true;
        e.splitAt(5);
        assertEquals(0, e.segments.get(0).holdAfter, E);
        assertFalse(e.segments.get(0).rewind);
        assertEquals(1, e.segments.get(1).holdAfter, E);
    }

    @Test
    void copyIsDeep() {
        EditSpec e = EditSpec.whole(10);
        e.captions.add(new EditSpec.Caption("gg", 1, 2, "bottom", "clean"));
        e.zooms.add(new EditSpec.ZoomKey(3, 2, 0.5, 0.5));
        EditSpec c = e.copy();
        c.segments.get(0).end = 5;
        c.captions.get(0).text = "changed";
        assertEquals(10, e.segments.get(0).end, E);
        assertEquals("gg", e.captions.get(0).text);
        assertEquals(1, c.zooms.size());
    }

    @Test
    void fillRepairsOldSavedEdits() {
        EditSpec e = new EditSpec();
        e.segments = null;
        e.captions = null;
        e.camShare = 0;
        e.camOffset = Double.NaN;
        e.music = new EditSpec.Music();
        e.fill();
        assertNotNull(e.segments);
        assertNotNull(e.captions);
        assertTrue(e.camShare > 0);
        assertEquals(0, e.camOffset, E);
        assertNull(e.music, "music without a file is dropped");
    }
}
