package net.evadeclient.module.visuals.tooltip;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.text.OrderedText;

import java.util.List;

/**
 * A book's first page as a small sheet of paper: the page text wrapped to the book screen's line width, at most
 * {@link #MAX_LINES} lines (then "..."), and the page count underneath.
 */
public final class BookPreviewComponent implements TooltipComponent {
    /** The book screen's text width. */
    static final int LINE_WIDTH = 114;
    static final int MAX_LINES = 8;
    private static final int PAD = 4;
    private static final int PAPER = 0xFFF4EBD6, PAPER_EDGE = 0xFFC8B89A, INK = 0xFF2A2118, COUNT = 0xFFAAAAAA;

    private final BookPreviewData data;
    private List<OrderedText> lines;
    private boolean cut;

    public BookPreviewComponent(BookPreviewData data) {
        this.data = data;
    }

    private List<OrderedText> lines(TextRenderer tr) {
        if (lines == null) {
            List<OrderedText> all = tr.wrapLines(data.firstPage(), LINE_WIDTH);
            cut = all.size() > MAX_LINES;
            lines = cut ? all.subList(0, MAX_LINES) : all;
        }
        return lines;
    }

    private String countText() {
        return data.pages() == 1 ? "1 page" : data.pages() + " pages";
    }

    @Override
    public int getHeight(TextRenderer tr) {
        int n = Math.max(1, lines(tr).size()) + (cut ? 1 : 0);
        return PAD * 2 + n * 9 + 2 + 11;
    }

    @Override
    public int getWidth(TextRenderer tr) {
        return Math.max(LINE_WIDTH + PAD * 2, tr.getWidth(countText()));
    }

    @Override
    public void drawText(DrawContext ctx, TextRenderer tr, int x, int y) {
        List<OrderedText> l = lines(tr);
        int n = Math.max(1, l.size()) + (cut ? 1 : 0);
        int h = PAD * 2 + n * 9;
        ctx.fill(x, y, x + LINE_WIDTH + PAD * 2, y + h, PAPER_EDGE);
        ctx.fill(x + 1, y + 1, x + LINE_WIDTH + PAD * 2 - 1, y + h - 1, PAPER);
        int ty = y + PAD;
        for (OrderedText line : l) {
            ctx.drawText(tr, line, x + PAD, ty, INK, false);
            ty += 9;
        }
        if (l.isEmpty()) ctx.drawText(tr, "(empty page)", x + PAD, ty, 0xFF8A7F6E, false);
        if (cut) ctx.drawText(tr, "...", x + PAD, ty, INK, false);
        ctx.drawText(tr, countText(), x, y + h + 3, COUNT, true);
    }
}
