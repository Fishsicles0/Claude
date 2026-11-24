package net.evadeclient.clips.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Highlight reels: windows around each moment, merged when they overlap, inside the recording. */
class HighlightsTest {
    @Test
    void cutMergesOverlappingWindows() {
        EditSpec e = Highlights.cut(List.of(new Highlights.Moment(30, 1), new Highlights.Moment(10, 1), new Highlights.Moment(12, 1)), 60, 2, 3);
        assertEquals(2, e.segments.size());
        assertEquals(8, e.segments.get(0).start, 1e-9);
        assertEquals(15, e.segments.get(0).end, 1e-9);
        assertEquals(28, e.segments.get(1).start, 1e-9);
        assertEquals(33, e.segments.get(1).end, 1e-9);
    }

    @Test
    void windowsStayInsideTheClip() {
        EditSpec e = Highlights.cut(List.of(new Highlights.Moment(1, 1), new Highlights.Moment(59, 1)), 60, 5, 5);
        assertEquals(0, e.segments.get(0).start, 1e-9);
        assertEquals(60, e.segments.get(e.segments.size() - 1).end, 1e-9);
    }

    @Test
    void eventsGetTheirOwnWindowAndNothingKeepsAll() {
        assertTrue(Highlights.isEvent("Kill"), "a kill is a highlight in itself");
        EditSpec e = Highlights.cut(List.of(new Highlights.Moment(20, 1, "Kill")), 60, 1, 1, 6, 2);
        assertEquals(14, e.segments.get(0).start, 1e-9);
        assertEquals(22, e.segments.get(0).end, 1e-9);
        EditSpec none = Highlights.cut(List.of(), 60, 1, 1);
        assertEquals(1, none.segments.size());
        assertEquals(60, none.segments.get(0).length(), 1e-9);
    }
}
