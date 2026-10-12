import com.barcodescanner.sdk.data.frame.NativeFrame;
import com.barcodescanner.sdk.data.msi.MsiNativeDecoder;
import java.nio.ByteBuffer;
// Host-JVM smoke test of the JNI glue in msi_jni.cpp (direct-buffer access,
// capacity/stride validation, result format) — the part the Robolectric unit
// tests cannot load and the C++ harness does not cross. See run.sh.
public class JniSmoke {
    static int fails = 0;
    static void check(String n, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + n); if (!ok) fails++; }
    // Spec MSI modules for "12345674": start 110, bits 110/100, stop 1001.
    static String modules(String full) { StringBuilder m = new StringBuilder("110"); for (char c : full.toCharArray()) { int v = c - '0'; for (int b = 3; b >= 0; --b) m.append(((v >> b) & 1) == 1 ? "110" : "100"); } return m.append("1001").toString(); }
    public static void main(String[] a) {
        System.load(a[0]);
        NativeFrame nf = new NativeFrame(); MsiNativeDecoder md = new MsiNativeDecoder();
        // Upright 800x300 tag, 5px modules -> sensor plane rotated for rotationDegrees=90, stride padded, last row unpadded.
        int UW = 800, UH = 300; byte[] up = new byte[UW * UH]; java.util.Arrays.fill(up, (byte) 200);
        String m = modules("12345674"); int x0 = (UW - m.length() * 5) / 2;
        for (int i = 0; i < m.length(); i++) if (m.charAt(i) == '1') for (int k = 0; k < 5; k++) for (int y = 90; y < 210; y++) up[y * UW + x0 + i * 5 + k] = 40;
        int SW = UH, SH = UW, stride = SW + 64;
        ByteBuffer plane = ByteBuffer.allocateDirect(stride * (SH - 1) + SW);
        for (int y = 0; y < UH; y++) for (int x = 0; x < UW; x++) plane.put((UW - 1 - x) * stride + y, up[y * UW + x]);
        // Box: whole plane minus a margin, even dims.
        int L = 10, T0 = 20, R = SW - 10, B = SH - 20; int cw = R - L, ch = B - T0; int ow = ch, oh = cw;
        ByteBuffer dst = ByteBuffer.allocateDirect(ow * oh * 3 / 2);
        check("prepare ok (stride, unpadded last row, rot 90)", nf.nativePrepare(plane, SW, SH, stride, L, T0, R, B, 90, true, dst));
        String r = md.nativeDecodeLuma(dst, ow, oh, 1, 0f, 0, 0, 0);
        check("decodeLuma -> 12345674|mod10|v|0 (got " + r + ")", r != null && r.startsWith("12345674|mod10|") && r.endsWith("|0"));
        check("decodeLuma MOD1010 required -> null", md.nativeDecodeLuma(dst, ow, oh, 1, 0f, 2, 0, 0) == null);
        // Symbol-length bounds (checks included) reach native: 8-digit symbol.
        check("decodeLuma lengths 8..8 -> read", r != null && r.equals(md.nativeDecodeLuma(dst, ow, oh, 1, 0f, 0, 8, 8)));
        check("decodeLuma lengths 9..10 -> null", md.nativeDecodeLuma(dst, ow, oh, 1, 0f, 0, 9, 10) == null);
        check("decodeLuma max 7 -> null", md.nativeDecodeLuma(dst, ow, oh, 1, 0f, 0, 0, 7) == null);
        check("prepare rejects heap src", !nf.nativePrepare(ByteBuffer.allocate(stride * SH), SW, SH, stride, L, T0, R, B, 90, true, dst));
        check("prepare rejects small dst", !nf.nativePrepare(plane, SW, SH, stride, L, T0, R, B, 90, true, ByteBuffer.allocateDirect(ow * oh - 1)));
        check("prepare rejects short src", !nf.nativePrepare(ByteBuffer.allocateDirect(stride * (SH - 1)), SW, SH, stride, L, T0, R, B, 90, true, dst));
        check("prepare rejects out-of-plane box", !nf.nativePrepare(plane, SW, SH, stride, -2, T0, R, B, 90, true, dst));
        check("prepare rejects stride < width", !nf.nativePrepare(plane, SW, SH, SW - 1, L, T0, R, B, 90, true, dst));
        check("prepare rejects null dst", !nf.nativePrepare(plane, SW, SH, stride, L, T0, R, B, 90, true, null));
        check("decodeLuma rejects small buffer", md.nativeDecodeLuma(ByteBuffer.allocateDirect(10), ow, oh, 1, 0f, 0, 0, 0) == null);
        check("decodeLuma rejects heap buffer", md.nativeDecodeLuma(ByteBuffer.allocate(ow * oh), ow, oh, 1, 0f, 0, 0, 0) == null);
        check("decodeLuma rejects overflow dims", md.nativeDecodeLuma(dst, 1 << 20, 1 << 20, 1, 0f, 0, 0, 0) == null);
        // Upside-down prepared view: 1 orientation misses, 2 reads with relRot 180.
        ByteBuffer flip = ByteBuffer.allocateDirect(ow * oh * 3 / 2);
        check("prepare rot 270 (upside-down view)", nf.nativePrepare(plane, SW, SH, stride, L, T0, R, B, 270, true, flip));
        check("upside-down, 1 orientation -> null", md.nativeDecodeLuma(flip, ow, oh, 1, 0f, 0, 0, 0) == null);
        String f = md.nativeDecodeLuma(flip, ow, oh, 2, 0f, 0, 0, 0);
        check("upside-down, 2 orientations -> relRot 180 (got " + f + ")", f != null && f.startsWith("12345674|") && f.endsWith("|180"));
        System.out.println(fails == 0 ? "JNI OK" : "JNI FAILURES=" + fails); System.exit(fails);
    }
}
