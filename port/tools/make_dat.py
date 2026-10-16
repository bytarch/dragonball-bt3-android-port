#!/usr/bin/env python3
"""Finishes a program built from the blank data tables (port/data, see make_skeleton.py): writes the list of
where every value of those tables comes from on the user's disc, which the program reads at start.

    [BT3_CC=...] port/tools/make_dat.py [--exe <program>] [--out <folder>]
        -> the program marked as one that needs its list, and next to it <program>.dat:
           (address in the program, source file, offset in it, length) for every `.space` run of port/data/*.s,
           and the checksum of port/data/index.txt. link.py runs this by itself after linking such a program.

No disc is needed for this: the addresses come from the linker's map, the rest from port/data. (strip_data.py does
the same for a program that was built WITH the tables' values, by comparing it with the disc.)"""
import argparse, pathlib, re, struct, subprocess, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import toolchain
from toolchain import ROOT, PREFIX

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--exe", default=str(toolchain.EXE))
    ap.add_argument("--out", default=None, help="folder for bt3 + bt3.dat; without it the program is marked in place and gets <program>.dat next to it")
    a = ap.parse_args()
    exe = pathlib.Path(a.exe)
    image = bytearray(exe.read_bytes())
    where, checksum = {}, None
    for l in (ROOT / "port/data/index.txt").read_text().splitlines():
        p = l.split()
        if len(p) == 3 and not l.startswith("#"):
            where[p[0]] = (int(p[1]), int(p[2], 16))
        elif len(p) == 2 and p[0] == "checksum":
            checksum = int(p[1], 16)
    placed = {}
    for l in open(str(exe) + ".map"):
        m = re.match(r"^\s*\.data\s+0x([0-9a-f]+)\s+0x([0-9a-f]+)\s+\S*obj_data\w*/(\S+)\.o\s*$", l)
        if m and int(m.group(2), 16):
            placed[m.group(3)] = (int(m.group(1), 16), int(m.group(2), 16))
    records = []
    for name in sorted(where):
        if name not in placed:
            raise SystemExit(f"{name}: not in {exe.name}.map (was the program built from port/data?)")
        which, so = where[name]
        start, size = placed[name]
        off = 0
        for l in (ROOT / "port/data" / (name + ".s")).read_text().splitlines():
            p = l.split()
            if not p or p[0].startswith("#"):
                continue
            if p[0] == ".space":
                records.append((start + off, which, so + off, int(p[1])))
                off += int(p[1])
            elif p[0] == ".zero":
                off += int(p[1])
            elif p[0] == ".long":
                off += 4
        if not off <= size < off + 16:  # (a Windows object's section is padded to its 16-byte alignment)
            raise SystemExit(f"{name}: {off} bytes in port/data, {size} in the program")
    # the mark that this program does not work without its list (gPortDataStripped, plat_mem.c)
    secs = []
    lines = subprocess.run([PREFIX + "objdump", "-h", str(exe)], capture_output=True, text=True).stdout.splitlines()
    for k, l in enumerate(lines):
        m = re.match(r"\s*\d+\s+(\S+)\s+([0-9a-f]+)\s+([0-9a-f]+)\s+([0-9a-f]+)\s+([0-9a-f]+)", l)
        if m and k + 1 < len(lines) and "CONTENTS" in lines[k + 1]:
            secs.append((int(m.group(3), 16), int(m.group(5), 16), int(m.group(2), 16)))
    flag = next((int(l.split()[0], 16) for l in subprocess.run([PREFIX + "nm", str(exe)], capture_output=True, text=True).stdout.splitlines()
                 if l.endswith(" gPortDataStripped")), None)
    off = next((o + flag - va for va, o, size in secs if flag is not None and va <= flag < va + size), None)
    if off is None:
        raise SystemExit("gPortDataStripped not found in the program's data")
    image[off:off + 4] = struct.pack("<I", 1)
    if a.out is None:  # in place: bt3_64 + bt3_64.dat, bt3.exe + bt3.dat
        prog, dat = exe, pathlib.Path(str(exe)[:-4] + ".dat" if exe.suffix == ".exe" else str(exe) + ".dat")
    else:
        out = pathlib.Path(a.out)
        out.mkdir(parents=True, exist_ok=True)
        prog = out / ("bt3.exe" if exe.suffix == ".exe" else "bt3")
        dat = out / "bt3.dat"
    prog.write_bytes(image)
    prog.chmod(0o755)
    dat.write_bytes(struct.pack("<4sIQ", b"BT3D", len(records), checksum) + b"".join(struct.pack("<IIII", *r) for r in records))
    print(f"{len(placed)} tables, {sum(r[3] for r in records)} bytes to fetch from the disc in {len(records)} runs; {dat.relative_to(ROOT) if dat.is_relative_to(ROOT) else dat}")

main()
