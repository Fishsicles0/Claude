package net.evadeclient.clips.core;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Windows: records what the default speakers or headphones play (WASAPI loopback), the way Windows' own sound
 * recorder does, so no "Stereo Mix" or virtual cable is needed. Plain COM calls through JNA; everything runs on the
 * {@link LoopbackAudio} pump thread. Hands back interleaved stereo float frames (mono is doubled, extra channels
 * of surround output are left out).
 */
public final class WasapiLoopback implements LoopbackAudio.Source {
    private static final String CLSID_MMDeviceEnumerator = "BCDE0395-E52F-467C-8E3D-C4579291692E";
    private static final String IID_IMMDeviceEnumerator = "A95664D2-9614-4F35-A746-DE8DB63617E6";
    private static final String IID_IAudioClient = "1CB9AD4C-DBFA-4C32-B178-C2F568A703B2";
    private static final String IID_IAudioCaptureClient = "C8ADBD64-E71E-48A0-A4DE-185C395CD317";
    private static final int CLSCTX_ALL = 23, COINIT_MULTITHREADED = 0;
    private static final int eRender = 0, eConsole = 0;
    private static final int AUDCLNT_SHAREMODE_SHARED = 0, AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    private static final int AUDCLNT_BUFFERFLAGS_SILENT = 0x2;
    private static final short WAVE_FORMAT_IEEE_FLOAT = 3, WAVE_FORMAT_EXTENSIBLE = (short) 0xFFFE;

    // vtable slots (IUnknown: 0 QueryInterface, 1 AddRef, 2 Release)
    private static final int ENUM_GetDefaultAudioEndpoint = 4, DEVICE_Activate = 3;
    private static final int CLIENT_Initialize = 3, CLIENT_GetMixFormat = 8, CLIENT_Start = 10, CLIENT_Stop = 11, CLIENT_GetService = 14;
    private static final int CAPTURE_GetBuffer = 3, CAPTURE_ReleaseBuffer = 4, CAPTURE_GetNextPacketSize = 5;

    private Pointer enumerator, device, client, capture;
    private int rate, channels, bytesPerSample, blockAlign;
    private boolean isFloat;
    private byte[] scratch = new byte[0];

    private WasapiLoopback() {}

    static WasapiLoopback open() throws IOException {
        WasapiLoopback w = new WasapiLoopback();
        try {
            w.init();
            return w;
        } catch (IOException | RuntimeException | Error e) {
            w.close();
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    private void init() throws IOException {
        int hr = ole32("CoInitializeEx", Pointer.NULL, COINIT_MULTITHREADED);
        // S_OK, S_FALSE (already) and RPC_E_CHANGED_MODE (already, another way) all leave COM usable on this thread
        if (hr < 0 && hr != 0x80010106) check(hr, "CoInitializeEx");
        PointerByReference out = new PointerByReference();
        check(ole32("CoCreateInstance", guid(CLSID_MMDeviceEnumerator), Pointer.NULL, CLSCTX_ALL, guid(IID_IMMDeviceEnumerator), out), "MMDeviceEnumerator");
        enumerator = out.getValue();
        check(call(enumerator, ENUM_GetDefaultAudioEndpoint, eRender, eConsole, out), "no speakers or headphones");
        device = out.getValue();
        check(call(device, DEVICE_Activate, guid(IID_IAudioClient), CLSCTX_ALL, Pointer.NULL, out), "IAudioClient");
        client = out.getValue();
        check(call(client, CLIENT_GetMixFormat, out), "GetMixFormat");
        Pointer wfx = out.getValue();
        try {
            short tag = wfx.getShort(0);
            channels = wfx.getShort(2);
            rate = wfx.getInt(4);
            blockAlign = wfx.getShort(12);
            int bits = wfx.getShort(14);
            bytesPerSample = bits / 8;
            // WAVEFORMATEXTENSIBLE keeps the real type in SubFormat's first field (1 = PCM, 3 = float)
            isFloat = tag == WAVE_FORMAT_IEEE_FLOAT || (tag == WAVE_FORMAT_EXTENSIBLE && wfx.getInt(24) == WAVE_FORMAT_IEEE_FLOAT);
            if (channels < 1 || rate <= 0 || (isFloat && bits != 32) || (!isFloat && bits != 16 && bits != 24 && bits != 32))
                throw new IOException("unsupported speaker format: " + bits + "-bit " + (isFloat ? "float" : "PCM") + ", " + channels + " channels");
            // 200 ms of buffer, read every 10 ms
            check(call(client, CLIENT_Initialize, AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, 2_000_000L, 0L, wfx, Pointer.NULL), "Initialize");
        } finally {
            ole32Void("CoTaskMemFree", wfx);
        }
        check(call(client, CLIENT_GetService, guid(IID_IAudioCaptureClient), out), "IAudioCaptureClient");
        capture = out.getValue();
        check(call(client, CLIENT_Start), "Start");
    }

    @Override public int rate() { return rate; }

    @Override
    public int read(float[] stereo) {
        int frames = 0, max = stereo.length / 2;
        IntByReference next = new IntByReference();
        PointerByReference data = new PointerByReference();
        IntByReference count = new IntByReference(), flags = new IntByReference();
        while (true) {
            if (call(capture, CAPTURE_GetNextPacketSize, next) < 0) return -1; // device gone (unplugged, switched)
            if (next.getValue() == 0) return frames;
            if (call(capture, CAPTURE_GetBuffer, data, count, flags, Pointer.NULL, Pointer.NULL) < 0) return -1;
            int n = count.getValue(), take = Math.min(n, max - frames);
            if (take > 0) {
                if ((flags.getValue() & AUDCLNT_BUFFERFLAGS_SILENT) != 0) {
                    java.util.Arrays.fill(stereo, frames * 2, (frames + take) * 2, 0f);
                } else {
                    convert(data.getValue(), take, stereo, frames);
                }
                frames += take;
            }
            call(capture, CAPTURE_ReleaseBuffer, n);
        }
    }

    /** Device frames to interleaved stereo float. */
    private void convert(Pointer p, int frames, float[] out, int at) {
        int len = frames * blockAlign;
        if (scratch.length < len) scratch = new byte[len];
        p.read(0, scratch, 0, len);
        ByteBuffer b = ByteBuffer.wrap(scratch, 0, len).order(ByteOrder.LITTLE_ENDIAN);
        int second = channels > 1 ? bytesPerSample : 0;
        for (int i = 0; i < frames; i++) {
            int off = i * blockAlign;
            out[(at + i) * 2] = sample(b, off);
            out[(at + i) * 2 + 1] = sample(b, off + second);
        }
    }

    private float sample(ByteBuffer b, int off) {
        if (isFloat) return b.getFloat(off);
        return switch (bytesPerSample) {
            case 2 -> b.getShort(off) / 32768f;
            case 3 -> ((b.get(off) & 0xFF) | (b.get(off + 1) & 0xFF) << 8 | b.get(off + 2) << 16) / 8388608f;
            default -> b.getInt(off) / 2147483648f;
        };
    }

    @Override
    public void close() {
        if (client != null) call(client, CLIENT_Stop);
        for (Pointer p : new Pointer[]{capture, client, device, enumerator}) if (p != null) call(p, 2);
        capture = client = device = enumerator = null;
    }

    // ------------------------------------------------------------------ COM through JNA

    /** Calls the method in vtable slot {@code slot} of COM object {@code obj}; returns its HRESULT. */
    private static int call(Pointer obj, int slot, Object... args) {
        Pointer vtable = obj.getPointer(0);
        Function f = Function.getFunction(vtable.getPointer((long) slot * Native.POINTER_SIZE), Function.ALT_CONVENTION);
        Object[] all = new Object[args.length + 1];
        all[0] = obj;
        System.arraycopy(args, 0, all, 1, args.length);
        return f.invokeInt(all);
    }

    private static int ole32(String name, Object... args) {
        return Function.getFunction("ole32", name, Function.ALT_CONVENTION).invokeInt(args);
    }

    private static void ole32Void(String name, Object... args) {
        Function.getFunction("ole32", name, Function.ALT_CONVENTION).invokeVoid(args);
    }

    private static void check(int hr, String what) throws IOException {
        if (hr < 0) throw new IOException(what + " failed (0x" + Integer.toHexString(hr) + ")");
    }

    /** A GUID in memory, from its usual "8-4-4-4-12" text form. */
    private static Memory guid(String s) {
        byte[] b = guidBytes(s);
        Memory m = new Memory(16);
        m.write(0, b, 0, 16);
        return m;
    }

    /** A GUID's 16 bytes as Windows lays them out: the first three groups little-endian, the last two as written. */
    public static byte[] guidBytes(String s) {
        String[] p = s.split("-");
        ByteBuffer b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt((int) Long.parseLong(p[0], 16));
        b.putShort((short) Integer.parseInt(p[1], 16));
        b.putShort((short) Integer.parseInt(p[2], 16));
        String rest = p[3] + p[4];
        for (int i = 0; i < 8; i++) b.put((byte) Integer.parseInt(rest.substring(i * 2, i * 2 + 2), 16));
        return b.array();
    }
}
