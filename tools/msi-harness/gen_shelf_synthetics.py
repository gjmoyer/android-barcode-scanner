#!/usr/bin/env python3
"""Generate public-safe synthetic MSI barcode fixtures for the six shelf SKUs.

The real shelf photos stay OUT of the repo (private labels, store policy,
bulk-collection concerns). These PNGs encode the SAME full codewords
(payload + check digits) rendered from the MSI spec.

Render choice (verified by measurement, not aesthetics): crisp 3px modules,
no blur, no gradient. Rationale, all confirmed with the host harness:
- even module sizes (4/6/8px) phase-lock against the decoder's xstep=2 ROI
  sampler and go blind; only odd sizes decode at all;
- heavier softening (box/Gaussian blur, illumination gradients) pushes crisp
  renders OUT of the template model's sweet spot and drops leading narrow
  digits or invents phantoms — real captures carry sensor texture the
  renderer cannot cheaply fake, so crisp-deterministic is the honest choice;
- at 3px, yakult/dixie/starbucks read EXACT full codewords; quakotml/silkalm
  truncate and ondeg honest-misses — the same profile as the real photos,
  so the fixtures pin floor behavior instead of baking misparses.

Independent implementation cross-checks (do NOT copy from these sources):
- module spec: Wikipedia "MSI Barcode" (START 110, bit 1 -> 110, bit 0 ->
  100, STOP 1001) — matches MsiCodeTable + the decoder's kDigitPat.
- checksums: Luhn (literature vectors 1234567->4, 8052->3) + IBM Mod11.

Usage:
    ./gen_shelf_synthetics.py [out_dir]
Default out_dir is ../../barcode-scanner-sdk/src/test/resources/msi-shelf.

Only the Python standard library is used (struct, zlib); output is 8-bit
grayscale PNG. Deterministic — no randomness.
"""

import os
import struct
import sys
import zlib

# (label, payload, policy) — ground truth, checksum-verified below.
CASES = [
    ("yakult", "0828147", "mod10"),
    ("quakotml", "0186477", "mod1010"),
    ("ondeg", "0243523", "mod10"),
    ("dixie", "0087573", "mod10"),
    ("starbucks", "0168971", "mod1010"),
    ("silkalm", "0826593", "mod1010"),
]


def mod10(payload):
    s = 0
    dbl = True
    for ch in reversed(payload):
        d = int(ch)
        if dbl:
            d *= 2
            if d > 9:
                d -= 9
        s += d
        dbl = not dbl
    return (10 - (s % 10)) % 10


def mod11(payload):
    s = 0
    w = 2
    for ch in reversed(payload):
        s += int(ch) * w
        w = 2 if w == 7 else w + 1
    r = (11 - (s % 11)) % 11
    return -1 if r == 10 else r


def full_codeword(payload, policy):
    if policy == "mod10":
        return payload + str(mod10(payload))
    if policy == "mod11":
        c = mod11(payload)
        assert c >= 0, payload
        return payload + str(c)
    if policy == "mod1010":
        c1 = mod10(payload)
        return payload + str(c1) + str(mod10(payload + str(c1)))
    if policy == "mod1110":
        c1 = mod11(payload)
        assert c1 >= 0, payload
        return payload + str(c1) + str(mod10(payload + str(c1)))
    raise ValueError(policy)


def modules_for(full):
    mods = "110"
    for ch in full:
        v = int(ch)
        for b in (3, 2, 1, 0):
            mods += "110" if (v & (1 << b)) else "100"
    return mods + "1001"


def render_gray(full, module_px=3, bar_h=110, width=0, height=300, top_y=95):
    mods = modules_for(full)
    if width == 0:
        width = len(mods) * module_px + 2 * 12 * module_px
    x0 = (width - len(mods) * module_px) // 2
    px = bytearray([255]) * (width * height)
    for mi, m in enumerate(mods):
        v = 0 if m == "1" else 255
        for k in range(module_px):
            x = x0 + mi * module_px + k
            if 0 <= x < width:
                base = top_y * width + x
                for y in range(top_y, min(top_y + bar_h, height)):
                    px[y * width + x] = v
    return px, width, height


def write_png_gray(path, px, w, h):
    def chunk(typ, data):
        c = struct.pack(">I", len(data)) + typ + data
        return c + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 0, 0, 0, 0)
    raw = bytearray()
    for y in range(h):
        raw.append(0)  # filter type 0 (None)
        raw += px[y * w:(y + 1) * w]
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", ihdr))
        f.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
        f.write(chunk(b"IEND", b""))


def main():
    # Literature cross-checks before generating anything.
    assert mod10("1234567") == 4, "Luhn Wikipedia vector"
    assert mod10("8052") == 3, "Luhn Morovia vector"
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "../../barcode-scanner-sdk/src/test/resources/msi-shelf",
    )
    os.makedirs(out_dir, exist_ok=True)
    for label, payload, policy in CASES:
        full = full_codeword(payload, policy)
        # 3px crisp (see module docstring). Canvas sized from the strip.
        mods = modules_for(full)
        width = len(mods) * 3 + 2 * 12 * 3
        px, w, h = render_gray(full, module_px=3, bar_h=110, width=width,
                               height=300, top_y=95)
        name = "syn-msi-%s-%s.png" % (label, payload)
        write_png_gray(os.path.join(out_dir, name), px, w, h)
        print("%s: full=%s policy=%s %dx%d" % (name, full, policy, w, h))


if __name__ == "__main__":
    main()
