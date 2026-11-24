package net.evadeclient.mods;

/**
 * A small decoder for lossless WebP (the VP8L bitstream, RFC 9649 section 3), which is what Modrinth serves most
 * project icons as. Lossy WebP (VP8) is not supported: {@link #decode} throws, and the caller falls back to another
 * icon or a placeholder. Pure Java, no Minecraft classes, safe on any thread.
 */
public final class WebpLossless {
    private WebpLossless() {}

    /** A decoded image: {@code argb} row-major, {@code width} x {@code height}. */
    public record Image(int width, int height, int[] argb) {}

    /** Largest side accepted, so a hostile file can't ask for gigabytes. Icons are 96..512 px. */
    private static final int MAX_SIDE = 2048;

    /** Whether {@code data} is a RIFF/WEBP file at all (lossy or lossless). */
    public static boolean isWebp(byte[] data) {
        return data != null && data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    /** Decode a lossless WebP file. Throws IllegalArgumentException for anything else or a broken stream. */
    public static Image decode(byte[] data) {
        if (!isWebp(data)) throw new IllegalArgumentException("not a WebP file");
        int pos = 12;
        while (pos + 8 <= data.length) {
            String fourcc = new String(data, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long size = (data[pos + 4] & 0xFFL) | (data[pos + 5] & 0xFFL) << 8 | (data[pos + 6] & 0xFFL) << 16 | (data[pos + 7] & 0xFFL) << 24;
            int start = pos + 8;
            if (size < 0 || start + size > data.length) throw new IllegalArgumentException("truncated chunk");
            if (fourcc.equals("VP8L")) return new WebpLossless.Decoder(data, start, (int) size).decodeMain();
            if (fourcc.equals("VP8 ")) throw new IllegalArgumentException("lossy WebP is not supported");
            pos = start + (int) size + (int) (size & 1);
        }
        throw new IllegalArgumentException("no image data");
    }

    // ------------------------------------------------------------------ bitstream

    private static final class BitReader {
        private final byte[] buf;
        private int pos;
        private final int end;
        private long bits;
        private int nbits;

        BitReader(byte[] buf, int off, int len) {
            this.buf = buf;
            this.pos = off;
            this.end = off + len;
        }

        int read(int n) {
            if (n == 0) return 0;
            while (nbits < n) {
                if (pos >= end) throw new IllegalArgumentException("unexpected end of data");
                bits |= (long) (buf[pos++] & 0xFF) << nbits;
                nbits += 8;
            }
            int v = (int) (bits & ((1L << n) - 1));
            bits >>>= n;
            nbits -= n;
            return v;
        }
    }

    /** A canonical prefix code, decoded one bit at a time (icons are tiny; this is plenty fast). */
    private static final class Huffman {
        private final int[] counts = new int[16];
        private final int[] symbols;
        /** Set when the code has a single symbol: it is read with zero bits. */
        private int single = -1;

        Huffman(int[] lengths) {
            int n = 0, nonZero = 0, last = 0;
            for (int i = 0; i < lengths.length; i++) {
                if (lengths[i] > 0) { counts[lengths[i]]++; n++; nonZero++; last = i; }
            }
            symbols = new int[Math.max(1, n)];
            if (nonZero == 0) { single = 0; return; }
            if (nonZero == 1) { single = last; return; }
            int[] offs = new int[16];
            for (int len = 1; len < 16; len++) offs[len] = offs[len - 1] + counts[len - 1];
            for (int i = 0; i < lengths.length; i++) if (lengths[i] > 0) symbols[offs[lengths[i]]++] = i;
            counts[0] = 0;
        }

        int read(BitReader br) {
            if (single >= 0) return single;
            int code = 0, first = 0, index = 0;
            for (int len = 1; len < 16; len++) {
                code |= br.read(1);
                int count = counts[len];
                if (code - first < count) return symbols[index + code - first];
                index += count;
                first += count;
                first <<= 1;
                code <<= 1;
            }
            throw new IllegalArgumentException("bad prefix code");
        }
    }

    private static final int[] CODE_LENGTH_ORDER = {17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    /** (xi, yi) offsets for distance codes 1..120 (RFC 9649, 3.6.1.2), flattened. */
    private static final int[] DIST_MAP = {
            0, 1, 1, 0, 1, 1, -1, 1, 0, 2, 2, 0, 1, 2, -1, 2,
            2, 1, -2, 1, 2, 2, -2, 2, 0, 3, 3, 0, 1, 3, -1, 3,
            3, 1, -3, 1, 2, 3, -2, 3, 3, 2, -3, 2, 0, 4, 4, 0,
            1, 4, -1, 4, 4, 1, -4, 1, 3, 3, -3, 3, 2, 4, -2, 4,
            4, 2, -4, 2, 0, 5, 3, 4, -3, 4, 4, 3, -4, 3, 5, 0,
            1, 5, -1, 5, 5, 1, -5, 1, 2, 5, -2, 5, 5, 2, -5, 2,
            4, 4, -4, 4, 3, 5, -3, 5, 5, 3, -5, 3, 0, 6, 6, 0,
            1, 6, -1, 6, 6, 1, -6, 1, 2, 6, -2, 6, 6, 2, -6, 2,
            4, 5, -4, 5, 5, 4, -5, 4, 3, 6, -3, 6, 6, 3, -6, 3,
            0, 7, 7, 0, 1, 7, -1, 7, 5, 5, -5, 5, 7, 1, -7, 1,
            4, 6, -4, 6, 6, 4, -6, 4, 2, 7, -2, 7, 7, 2, -7, 2,
            3, 7, -3, 7, 7, 3, -7, 3, 5, 6, -5, 6, 6, 5, -6, 5,
            8, 0, 4, 7, -4, 7, 7, 4, -7, 4, 8, 1, 8, 2, 6, 6,
            -6, 6, 8, 3, 5, 7, -5, 7, 7, 5, -7, 5, 8, 4, 6, 7,
            -6, 7, 7, 6, -7, 6, 8, 5, 7, 7, -7, 7, 8, 6, 8, 7,
    };

    private record Transform(int type, int bits, int xsize, int[] data) {}

    private static final class Decoder {
        private final BitReader br;

        Decoder(byte[] data, int off, int len) {
            br = new BitReader(data, off, len);
        }

        Image decodeMain() {
            if (br.read(8) != 0x2F) throw new IllegalArgumentException("bad VP8L signature");
            int w = br.read(14) + 1, h = br.read(14) + 1;
            br.read(1); // alpha hint
            if (br.read(3) != 0) throw new IllegalArgumentException("unknown VP8L version");
            if (w > MAX_SIDE || h > MAX_SIDE) throw new IllegalArgumentException("image too large");

            java.util.List<Transform> transforms = new java.util.ArrayList<>();
            int xsize = w;
            int seen = 0;
            while (br.read(1) == 1) {
                int type = br.read(2);
                if ((seen & (1 << type)) != 0) throw new IllegalArgumentException("repeated transform");
                seen |= 1 << type;
                switch (type) {
                    case 0, 1 -> {
                        int bits = br.read(3) + 2;
                        int bw = divRound(xsize, bits), bh = divRound(h, bits);
                        transforms.add(new Transform(type, bits, xsize, decodeImage(bw, bh, false)));
                    }
                    case 2 -> transforms.add(new Transform(2, 0, xsize, null));
                    default -> {
                        int size = br.read(8) + 1;
                        int[] pal = decodeImage(size, 1, false);
                        for (int i = 1; i < size; i++) pal[i] = addPixels(pal[i], pal[i - 1]);
                        int bits = size <= 2 ? 3 : size <= 4 ? 2 : size <= 16 ? 1 : 0;
                        transforms.add(new Transform(3, bits, xsize, pal));
                        xsize = divRound(xsize, bits);
                    }
                }
            }
            int[] px = decodeImage(xsize, h, true);
            for (int i = transforms.size() - 1; i >= 0; i--) px = inverse(transforms.get(i), px, h);
            return new Image(w, h, px);
        }

        private static int divRound(int n, int bits) { return (n + (1 << bits) - 1) >> bits; }

        /** One entropy-coded image (the main image when {@code level0}, else a transform's or the prefix map's). */
        private int[] decodeImage(int xsize, int ysize, boolean level0) {
            int cacheBits = 0;
            if (br.read(1) == 1) {
                cacheBits = br.read(4);
                if (cacheBits < 1 || cacheBits > 11) throw new IllegalArgumentException("bad color cache");
            }
            int prefixBits = 0, prefixW = 0;
            int[] prefixMap = null;
            int groups = 1;
            if (level0 && br.read(1) == 1) {
                prefixBits = br.read(3) + 2;
                prefixW = divRound(xsize, prefixBits);
                prefixMap = decodeImage(prefixW, divRound(ysize, prefixBits), false);
                int max = 0;
                for (int i = 0; i < prefixMap.length; i++) {
                    prefixMap[i] = (prefixMap[i] >> 8) & 0xFFFF;
                    max = Math.max(max, prefixMap[i]);
                }
                groups = max + 1;
            }
            int cacheSize = cacheBits > 0 ? 1 << cacheBits : 0;
            Huffman[][] codes = new Huffman[groups][5];
            for (int g = 0; g < groups; g++) {
                codes[g][0] = readCode(256 + 24 + cacheSize);
                codes[g][1] = readCode(256);
                codes[g][2] = readCode(256);
                codes[g][3] = readCode(256);
                codes[g][4] = readCode(40);
            }
            int[] cache = cacheSize > 0 ? new int[cacheSize] : null;
            int total = xsize * ysize;
            int[] out = new int[total];
            int pos = 0, lastCached = 0;
            while (pos < total) {
                Huffman[] c = codes[prefixMap == null ? 0 : prefixMap[((pos / xsize) >> prefixBits) * prefixW + ((pos % xsize) >> prefixBits)]];
                int s = c[0].read(br);
                if (s < 256) {
                    int r = c[1].read(br), b = c[2].read(br), a = c[3].read(br);
                    out[pos++] = a << 24 | r << 16 | s << 8 | b;
                } else if (s < 256 + 24) {
                    int len = prefixValue(s - 256);
                    int dsym = c[4].read(br);
                    int dcode = prefixValue(dsym);
                    int dist;
                    if (dcode > 120) dist = dcode - 120;
                    else {
                        dist = DIST_MAP[(dcode - 1) * 2] + DIST_MAP[(dcode - 1) * 2 + 1] * xsize;
                        if (dist < 1) dist = 1;
                    }
                    if (dist > pos || pos + len > total) throw new IllegalArgumentException("bad back-reference");
                    for (int i = 0; i < len; i++, pos++) out[pos] = out[pos - dist];
                } else {
                    if (cache == null) throw new IllegalArgumentException("color cache not present");
                    // flush the cache with the pixels decoded since the last insert, then look up
                    while (lastCached < pos) insert(cache, cacheBits, out[lastCached++]);
                    out[pos++] = cache[s - 280];
                }
                if (cache != null) while (lastCached < pos) insert(cache, cacheBits, out[lastCached++]);
            }
            return out;
        }

        private static void insert(int[] cache, int bits, int argb) {
            cache[(0x1E35A7BD * argb) >>> (32 - bits)] = argb;
        }

        private int prefixValue(int prefix) {
            if (prefix < 4) return prefix + 1;
            int extra = (prefix - 2) >> 1;
            int offset = (2 + (prefix & 1)) << extra;
            return offset + br.read(extra) + 1;
        }

        private Huffman readCode(int alphabet) {
            int[] lengths = new int[alphabet];
            if (br.read(1) == 1) {
                int n = br.read(1) + 1;
                int first8 = br.read(1);
                int s0 = br.read(first8 == 1 ? 8 : 1);
                if (s0 >= alphabet) throw new IllegalArgumentException("bad simple code");
                lengths[s0] = 1;
                if (n == 2) {
                    int s1 = br.read(8);
                    if (s1 >= alphabet) throw new IllegalArgumentException("bad simple code");
                    lengths[s1] = 1;
                }
                return new Huffman(lengths);
            }
            int[] clen = new int[19];
            int num = br.read(4) + 4;
            for (int i = 0; i < num; i++) clen[CODE_LENGTH_ORDER[i]] = br.read(3);
            Huffman lenCode = new Huffman(clen);
            int maxSymbol = alphabet;
            if (br.read(1) == 1) {
                int nbits = 2 + 2 * br.read(3);
                maxSymbol = 2 + br.read(nbits);
                if (maxSymbol > alphabet) throw new IllegalArgumentException("bad max symbol");
            }
            int sym = 0, prev = 8;
            while (sym < alphabet) {
                if (maxSymbol-- == 0) break;
                int c = lenCode.read(br);
                if (c < 16) {
                    lengths[sym++] = c;
                    if (c != 0) prev = c;
                } else {
                    int repeat, val;
                    if (c == 16) { repeat = 3 + br.read(2); val = prev; }
                    else if (c == 17) { repeat = 3 + br.read(3); val = 0; }
                    else { repeat = 11 + br.read(7); val = 0; }
                    if (sym + repeat > alphabet) throw new IllegalArgumentException("bad code lengths");
                    for (int i = 0; i < repeat; i++) lengths[sym++] = val;
                }
            }
            return new Huffman(lengths);
        }

        // ---- inverse transforms

        private int[] inverse(Transform t, int[] px, int h) {
            switch (t.type) {
                case 0 -> predictor(t, px, h);
                case 1 -> colorTransform(t, px, h);
                case 2 -> {
                    for (int i = 0; i < px.length; i++) {
                        int p = px[i], g = (p >> 8) & 0xFF;
                        int r = ((p >> 16) + g) & 0xFF, b = (p + g) & 0xFF;
                        px[i] = (p & 0xFF00FF00) | r << 16 | b;
                    }
                }
                default -> px = colorIndex(t, px, h);
            }
            return px;
        }

        private static void predictor(Transform t, int[] px, int h) {
            int w = t.xsize, bw = divRound(w, t.bits);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    int pred;
                    if (y == 0) pred = x == 0 ? 0xFF000000 : px[i - 1];
                    else if (x == 0) pred = px[i - w];
                    else {
                        int mode = (t.data[(y >> t.bits) * bw + (x >> t.bits)] >> 8) & 0xF;
                        int L = px[i - 1], T = px[i - w], TL = px[i - w - 1], TR = px[i - w + 1];
                        pred = switch (mode) {
                            case 0 -> 0xFF000000;
                            case 1 -> L;
                            case 2 -> T;
                            case 3 -> TR;
                            case 4 -> TL;
                            case 5 -> avg(avg(L, TR), T);
                            case 6 -> avg(L, TL);
                            case 7 -> avg(L, T);
                            case 8 -> avg(TL, T);
                            case 9 -> avg(T, TR);
                            case 10 -> avg(avg(L, TL), avg(T, TR));
                            case 11 -> select(L, T, TL);
                            case 12 -> clampFull(L, T, TL);
                            case 13 -> clampHalf(avg(L, T), TL);
                            default -> 0xFF000000; // 14, 15: reserved, treated as mode 0 like libwebp
                        };
                    }
                    px[i] = addPixels(px[i], pred);
                }
            }
        }

        private static void colorTransform(Transform t, int[] px, int h) {
            int w = t.xsize, bw = divRound(w, t.bits);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int e = t.data[(y >> t.bits) * bw + (x >> t.bits)];
                    int g2r = (byte) e, g2b = (byte) (e >> 8), r2b = (byte) (e >> 16);
                    int i = y * w + x, p = px[i];
                    int g = (byte) (p >> 8);
                    int r = ((p >> 16) + ((g2r * g) >> 5)) & 0xFF;
                    int b = (p + ((g2b * g) >> 5) + ((r2b * (byte) r) >> 5)) & 0xFF;
                    px[i] = (p & 0xFF00FF00) | r << 16 | b;
                }
            }
        }

        private static int[] colorIndex(Transform t, int[] px, int h) {
            int w = t.xsize, bits = t.bits;
            int packedW = divRound(w, bits);
            int[] pal = t.data;
            int[] out = new int[w * h];
            int bpp = 8 >> bits, mask = (1 << bpp) - 1, per = 1 << bits;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int packed = (px[y * packedW + (x >> bits)] >> 8) & 0xFF;
                    int idx = bits == 0 ? packed : (packed >> ((x & (per - 1)) * bpp)) & mask;
                    out[y * w + x] = idx < pal.length ? pal[idx] : 0;
                }
            }
            return out;
        }

        private static int addPixels(int a, int b) {
            int ag = (a & 0xFF00FF00) + (b & 0xFF00FF00);
            int rb = (a & 0x00FF00FF) + (b & 0x00FF00FF);
            return (ag & 0xFF00FF00) | (rb & 0x00FF00FF);
        }

        private static int avg(int a, int b) {
            return (((a ^ b) & 0xFEFEFEFE) >>> 1) + (a & b);
        }

        private static int ch(int p, int shift) { return (p >>> shift) & 0xFF; }

        private static int select(int L, int T, int TL) {
            int pL = 0, pT = 0;
            for (int s = 0; s < 32; s += 8) {
                int p = ch(L, s) + ch(T, s) - ch(TL, s);
                pL += Math.abs(p - ch(L, s));
                pT += Math.abs(p - ch(T, s));
            }
            return pL < pT ? L : T;
        }

        private static int clamp(int v) { return v < 0 ? 0 : Math.min(255, v); }

        private static int clampFull(int a, int b, int c) {
            int out = 0;
            for (int s = 0; s < 32; s += 8) out |= clamp(ch(a, s) + ch(b, s) - ch(c, s)) << s;
            return out;
        }

        private static int clampHalf(int a, int b) {
            int out = 0;
            for (int s = 0; s < 32; s += 8) out |= clamp(ch(a, s) + (ch(a, s) - ch(b, s)) / 2) << s;
            return out;
        }
    }
}
