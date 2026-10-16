"""Builds the engine for the web (WebAssembly): the same sources and the same steps as the Android build
(port/tools/android.py), with Emscripten's clang as the compiler and SDL3 built for Emscripten.

    port/tools/web.py [compile] [link] [package]        (all three when none is named)
        -> port/build/web/dist/bt3.html, bt3.js, bt3.wasm, stubs.txt

First cut: a compile spike. What already fits the web, and why:
  - the game's 4-byte pointers: wasm32 pointers are 4 bytes natively, so there is no ptr32.py step (the
    32-bit PC build has none either), and no irfix.py (it repairs an AArch64 code generator bug)
  - the PS2's float arithmetic: -msoft-float turns every float operation into a call to the libcall set
    (__addsf3, __mulsf3, ...) that port/src/softfloat_ps2.c implements, as in the other builds; an
    Emscripten that does not know the flag gets a warning and hardware IEEE floats instead
  - the renderer: the OpenGL back end (gs_gl.c, the GLES 3.2 one written for Mali) over WebGL2. gs_gpu.c
    is built too, but its SDL_GPU (Vulkan) path cannot start in a browser, so pick the GL one
    (BT3_GPU_API=gl). The GLSL is embedded as text; there is no glslc on the web path, so the SPIR-V
    arrays gs_gpu.c wants are placeholders (that back end never runs in a browser).

What the platform layer still needs (the link stubs what is missing; see dist/stubs.txt):
  - plat_mem.c / plat_file.c / plat_mc.c: mmap and a thread stack at fixed addresses, the game folder:
    Emscripten's FS instead (the ISO through File System Access or a preloaded folder, saves and
    settings in IndexedDB/OPFS)
  - threads: SharedArrayBuffer + COOP/COEP headers (or a single-threaded rework of gs_core.c's render
    thread); today the build is single-threaded
  - net.c / net_relay.c: UDP sockets -> WebRTC data channels or a WebSocket relay server
  - the .dat/.rel loader steps of android.py: the Android loader's; not done here

Environment (as android.py):
    BT3_EMCC     the Emscripten compiler (default: emcc from PATH, as CI's emsdk activation gives it)
    BT3_EMCXX    the Emscripten C++ compiler (default: em++)
    BT3_SDL3     folder with SDL3's include/ and lib/ for Emscripten
                 (default: port/build/web-toolchain/sdl3, where .github/workflows/web.yml installs it)
    BT3_JOBS     parallel compiles (default: the number of processors)
    BT3_SKELETON 1: the game's data tables blank (always for a release); else port/build/gen/data
"""
import concurrent.futures, os, pathlib, re, shutil, subprocess, sys, time

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OUT = ROOT / "port/build/web"
OBJ = OUT / "obj"
DATA = OUT / "obj_data"
GEN = OUT / "gen"
DIST = OUT / "dist"

EMCC = os.environ.get("BT3_EMCC", "emcc")
EMCXX = os.environ.get("BT3_EMCXX", "em++")
SDL3 = pathlib.Path(os.environ.get("BT3_SDL3", str(ROOT / "port/build/web-toolchain/sdl3")))

sys.path.insert(0, str(HERE))
import portsrc, eeconst  # noqa: E402


def run(cmd, **kw):
    return subprocess.run([str(c) for c in cmd], cwd=ROOT, capture_output=True, text=True, **kw)


def build_stamp():
    """The build's own number (CI's run number; a local build: the time): package() names the files after
    it and the shell's ?v= reference in html and the wasm path carry it, so one build's files cannot be
    mixed with another build's caches."""
    global BUILD_STAMP
    if BUILD_STAMP is None:
        BUILD_STAMP = os.environ.get("GITHUB_RUN_NUMBER") or str(int(time.time()))
    return BUILD_STAMP

BUILD_STAMP = None


def soft_float():
    """Probes -msoft-float once: not every Emscripten knows it, and without it the game's floats are
    hardware IEEE, not the PS2's software arithmetic (a difference to the other builds)."""
    OUT.mkdir(parents=True, exist_ok=True)
    probe = OUT / "probe.c"
    probe.write_text("float f(float a, float b) { return a * b + a; }\n")
    ok = run([EMCC, "-msoft-float", "-c", str(probe), "-o", str(OUT / "probe.o")]).returncode == 0
    probe.unlink(missing_ok=True)
    if not ok:
        print("warning: this Emscripten has no -msoft-float: the game's floats use hardware IEEE, "
              "not the PS2's software arithmetic (the game's state will differ from the other builds)")
    return ok


SOFT = ["-msoft-float"] if soft_float() else []


def llvm_nm():
    """The llvm-nm that comes with the EMCC in use."""
    p = shutil.which(EMCC)
    if p:
        cand = pathlib.Path(p).resolve().parent.parent / "bin" / "llvm-nm"
        if cand.exists():
            return str(cand)
    return "llvm-nm"


def defined_functions():
    """Which names the game's objects define as functions: the data tables' pointers to them must be
    typed (wasm has no plain-address functions; the linker refuses to guess between table and memory)."""
    objs = sorted(OBJ.glob("*.o"))
    if not objs:
        return set()
    r = run([llvm_nm(), "--defined-only"] + [str(o) for o in objs])
    if r.returncode:
        print(r.stderr)
        sys.exit(1)
    return {t[-1] for t in (l.split() for l in r.stdout.splitlines())
            if len(t) >= 2 and t[-2] in ("T", "t", "W", "w")}

# the game's code: the flags of android.py's GAME, minus the ARM-specific soft-float ABI
GAME = [EMCC, "-std=gnu89", "-c", "-O2", "-g1", "-fno-strict-aliasing", "-ffp-contract=off", "-fcommon", "-w",
        "-fno-builtin", "-fms-extensions", "-Wno-error=return-mismatch",
        "-include", "port_libm.h", "-Iinclude", "-Iport/include", "-include", "port_compat.h"] + SOFT


def compile_ee(cmd, src, o, game_text=True):
    """android.py's compile_ee for this target: preprocess, the PS2 compiler's float constants (eeconst),
    then compile the preprocessed text. No ptr32/irfix: they are for the 64-bit targets."""
    pre = [a for a in cmd if a != "-c"] + ["-E", str(src)]
    r = run(pre)
    if r.returncode:
        return r
    i = pathlib.Path(str(o)[:-2] + ".i")
    i.write_text(eeconst.transform(r.stdout))
    c2, skip = [], False
    for a in cmd:  # the forced includes are already in the preprocessed text
        if skip:
            skip = False
        elif a == "-include":
            skip = True
        else:
            c2.append(a)
    r = run(c2 + ["-x", "cpp-output", str(i), "-o", str(o)])
    if r.returncode == 0 and not os.environ.get("BT3_KEEP"):
        i.unlink(missing_ok=True)
    return r


def game_source(f):
    o = OBJ / (str(f.relative_to(ROOT / "src")).replace("/", "_")[:-2] + ".o")
    src, done, missing = portsrc.prepare(f)
    r = compile_ee(GAME + [f"-I{f.parent}"], src, o)
    return (o if r.returncode == 0 else None), f.name, r.stderr


def newlib_source(f):
    o = OBJ / ("nl_" + f.stem + ".o")
    cmd = [EMCC, "-std=gnu89", "-c", "-O2", "-g1", "-fno-strict-aliasing", "-ffp-contract=off", "-fno-builtin", "-w"] + SOFT + \
          ["-Iport/include", f"-I{f.parent}", "-include", "newlib_shim.h"]
    r = compile_ee(cmd, f, o, game_text=False)
    return (o if r.returncode == 0 else None), f.name, r.stderr


def port_source(f):
    o = OBJ / ("pc_" + f.stem + ".o")
    names = ["-include", "vu0_names.h", "-DREF_VU0_EXTERN_ARITH"] if f.parent.name == "port" and f.parent.parent.name == "src" else []
    hard = f.name in ("plat_libm.c", "plat_fastvec.c")
    cmd = [EMCC, "-std=gnu99", "-c", "-O2", "-g1", "-fno-strict-aliasing", "-ffp-contract=off", "-fno-builtin", "-w",
           "-fms-extensions", "-Iinclude", "-Iport/include", "-Iport/src", f"-I{SDL3 / 'include'}"]
    if not hard:
        cmd += SOFT + ["-DSF_FAST_CALLS", "-include", "math.h", "-include", "stdlib.h", "-include", "port_libm.h"]
    cmd += names
    if not hard and not names:
        r = compile_ee(cmd, f, o)
    else:
        r = run(cmd + [str(f), "-o", str(o)])
    return (o if r.returncode == 0 else None), f.name, r.stderr


def gs_source(f):
    o = OBJ / ("gs_" + f.stem + ".o")
    r = run([EMCC, "-std=gnu99", "-c", "-O2", "-g1", "-fno-strict-aliasing", "-ffp-contract=off", "-Wall",
             "-Wno-unused", "-Wno-misleading-indentation", f"-I{GEN / 'gs'}", f"-I{SDL3 / 'include'}", str(f), "-o", str(o)])
    return (o if r.returncode == 0 else None), f.name, r.stderr


def cxx_source(f):
    o = OBJ / ("cxx_" + f.stem + ".o")
    imgui = ROOT / "port/third_party/imgui"
    r = run([EMCXX, "-std=c++17", "-c", "-O2", "-g1", "-fno-exceptions", "-fno-rtti", "-w",
             f"-I{imgui}", f"-I{ROOT / 'port/src/gs'}", f"-I{SDL3 / 'include'}", str(f), "-o", str(o)])
    return (o if r.returncode == 0 else None), f.name, r.stderr


def shaders():
    """The GL back end's GLSL as text, NUL-terminated (gs_gl.c translates it at run time). No glslc on
    the web path: the SPIR-V arrays gs_gpu.c wants are placeholders; that back end never runs here."""
    gen = GEN / "gs"
    gen.mkdir(parents=True, exist_ok=True)
    arrays = []
    for src in sorted((ROOT / "port/src/gs/shaders").glob("*")):
        if src.suffix not in (".vert", ".frag"):
            continue
        stem, stage = src.stem.capitalize(), src.suffix[1:].capitalize()
        data = src.read_bytes() + b"\0"
        arrays.append(f"static const unsigned char k{stem}{stage}Glsl[] = {{" + ",".join(str(b) for b in data) + "};\n")
        arrays.append(f"static const unsigned char k{stem}{stage}Spv[4] = {{0}};\n")
    arrays.append("static const unsigned char kGsNdFragSpv[4] = {0};\n")
    (gen / "shaders.h").write_text(
        "/* generated from port/src/gs/shaders by port/tools/web.py (GLSL only; the web build has no glslc) */\n" + "".join(arrays))


def data_source(f, funcs):
    """The wasm assembler takes neither the ELF section syntax nor a symbol without an explicit size:
    every table goes to .data (BSS included), a .size follows each label, and a name the objects
    define as a function is said to be one (the word in the table is a table index, not an address)."""
    out, cur, size, typed = [], None, 0, set()

    def flush():
        nonlocal cur, size
        if cur is not None:
            out.append(".size %s, %d" % (cur, size))
            cur = None

    for line in f.read_text().splitlines():
        s = line.strip()
        if s.startswith(".section"):
            flush()
            out.append(".data")
        elif s.startswith(".globl"):
            flush()
            out.append(line)
        elif re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*:", s):
            flush()
            out.append(line)
            cur, size = s[:-1], 0
        else:
            if s.startswith(".long"):
                for t in re.findall(r"[A-Za-z_][A-Za-z0-9_]*", s):
                    if t in funcs and t not in typed:
                        typed.add(t)
                        out.append(".type %s, @function" % t)
            out.append(line)
            if cur is not None:
                if s.startswith(".space") or s.startswith(".zero"):
                    size += int(s.split()[1])
                elif s.startswith(".long") or s.startswith(".word") or s.startswith(".byte"):
                    step = 4 if s.startswith(".long") else 2 if s.startswith(".word") else 1
                    size += step * len([x for x in s.split(None, 1)[1].split(",") if x.strip()])
    flush()
    return "\n".join(out) + "\n"


def compile_all():
    OBJ.mkdir(parents=True, exist_ok=True)
    DATA.mkdir(parents=True, exist_ok=True)
    shaders()
    jobs = []
    jobs += [(game_source, f) for f in portsrc.sources()]
    jobs += [(newlib_source, f) for f in sorted((ROOT / "port/third_party/newlib_libm").glob("*.c"))]
    jobs += [(port_source, f) for f in [ROOT / "src/port/vu0_a.c", ROOT / "src/port/vu0_b.c"] + sorted((ROOT / "port/src").glob("*.c"))]
    jobs += [(gs_source, f) for f in sorted((ROOT / "port/src/gs").glob("*.c"))]
    jobs += [(cxx_source, f) for f in sorted((ROOT / "port/third_party/imgui").glob("*.cpp")) + sorted((ROOT / "port/src/gs").glob("*.cpp"))]
    only = os.environ.get("BT3_ONLY")  # a few files: BT3_ONLY=btl_obj,plat_mem
    if only:
        names = only.split(",")
        jobs = [j for j in jobs if any(n in j[1].name for n in names)]
    failed = []
    with concurrent.futures.ThreadPoolExecutor(int(os.environ.get("BT3_JOBS", os.cpu_count() or 2))) as ex:
        for o, name, err in ex.map(lambda j: j[0](j[1]), jobs):
            if o is None:
                failed.append(name)
                lines = [l for l in err.splitlines() if "error" in l]
                print("FAILED", name, "\n   ".join((lines or err.strip().splitlines() or ["?"])[:6])[:900], flush=True)
    funcs = defined_functions()
    failed += build_data(funcs)
    print(f"compiled: {len(jobs) - len([n for n in failed if not n.endswith('.s')])} of {len(jobs)} sources; {len(failed)} failed")
    return not failed


def build_data(funcs):
    """The game's data tables: blank (their values come from the user's disc at start) unless real ones were made."""
    real = sorted((ROOT / "port/build/gen/data").glob("*.s"))
    blank = os.environ.get("BT3_SKELETON") == "1" or not real
    for old in DATA.glob("*.o"):
        old.unlink()
    failed = []
    for f in (sorted((ROOT / "port/data").glob("*.s")) if blank else real):
        src = GEN / "data" / f.name
        src.parent.mkdir(parents=True, exist_ok=True)
        src.write_text(data_source(f, funcs))
        r = run([EMCC, "-c", str(src), "-o", str(DATA / (f.name[:-2] + ".o"))])
        if r.returncode:
            failed.append(f.name)
            print("FAILED", f.name, r.stderr.strip()[:300])
    (DATA / "SKELETON").write_text("blank\n") if blank else (DATA / "SKELETON").unlink(missing_ok=True)
    return failed


def sdl3_link_flags():
    """SDL3's emscripten build keeps its JavaScript glue in --js-library options recorded in its CMake
    config; a raw emcc link (this script) does not see them otherwise."""
    flags, seen = [], set()
    for cfg in (SDL3 / "lib/cmake").glob("**/*.cmake"):
        for m in re.finditer(r"--js-library[=; ]+([^;\"\s)]+\.js)", cfg.read_text(errors="replace")):
            p = pathlib.Path(m.group(1).strip('"'))
            p = str(p if p.is_absolute() else (cfg.parent / p))
            if p not in seen:
                seen.add(p)
                flags += ["--js-library", p]
    return flags


def link():
    """Links dist/bt3.html with one stub per symbol the web platform does not provide yet (android.py's
    link does the same for Android), then relinks with them so that the failure list is the report."""
    # D_3BE71C is a PS2 address the other builds make a symbol with --defsym; wasm-ld has none, so it
    # gets a real (empty) data object instead. heap.c only reads its address (the heap's size base), so
    # the value differs from the other builds by wherever the data section happens to sit.
    anchor = GEN / "webdata.s"
    anchor.parent.mkdir(parents=True, exist_ok=True)
    anchor.write_text(".data\n.p2align 4\n.globl D_3BE71C\nD_3BE71C:\n.space 4\n.size D_3BE71C, 4\n")
    r = run([EMCC, "-c", str(anchor), "-o", str(OBJ / "webdata.o")])
    if r.returncode:
        print(r.stderr[:600])
        return False
    objs = [str(o) for o in sorted(OBJ.glob("*.o")) + sorted(DATA.glob("*.o"))]

    def link_cmd(extra):
        return [EMCXX] + objs + ["-o", str(DIST / "bt3.html"), "-O2",
                                "-sALLOW_MEMORY_GROWTH=1", "-sINITIAL_MEMORY=134217728", "-sMAXIMUM_MEMORY=4GB",
                                "-sSTACK_SIZE=8MB", "-sFULL_ES3=1", "-sMAX_WEBGL_VERSION=2", "-sMIN_WEBGL_VERSION=2",
                                "-sGL_ENABLE_GET_PROC_ADDRESS=1", "-sEXIT_RUNTIME=0", "-sASSERTIONS=1",
                                "-sEXPORTED_FUNCTIONS=_main", "-sEXPORTED_RUNTIME_METHODS=ccall,cwrap",
                                "--no-entry", "--shell-file", str(stamped),
                                # the file layer: port/tools/webdata.js, and the async reads it makes possible
                                "--js-library", str(HERE / "webdata.js"), "-sASYNCIFY=1",
                                f"-I{SDL3 / 'include'}", f"-L{SDL3 / 'lib'}", "-lSDL3",
                                "-Wl,--wrap=Progress_Main"] + sdl3_link_flags() + extra

    DIST.mkdir(parents=True, exist_ok=True)
    # the shell gets its build stamp here: the same number packages the files under (below), so the served
    # references and the engine's wasm file name agree (a mixed set of builds is an import-table error)
    stamped = GEN / "bt3_shell.html"
    stamped.parent.mkdir(parents=True, exist_ok=True)
    stamped.write_text((HERE / "web_shell.html").read_text().replace("__BT3_BUILD__", build_stamp()))
    extra, typed, stubbed, need = [], set(), set(), []
    for i in range(20):
        r = run(link_cmd(extra))
        need = sorted(set(re.findall(r"undefined symbol: (\w+)", r.stderr)))
        mism = sorted(set(re.findall(r"symbol type mismatch: (\w+)", r.stderr)) - typed)
        mism = [m for m in mism if not re.fullmatch(r"D_[0-9A-F]+", m)]  # the --defsym anchors are addresses, not functions
        first = next((l.strip() for l in r.stderr.splitlines() if "error" in l), "")
        print(f"link {i + 1}: {len(extra)} extra objects, {len(mism)} mismatched, {len(need)} undefined: {first[-160:]}", flush=True)
        if mism:
            # a table points at a function the objects do not define (a stub yet to come): say so in the tables
            typed |= set(mism)
            print(f"data pointers typed as functions: {len(typed)}")
            if build_data(defined_functions() | typed):
                break
            continue
        if need and not set(need) <= stubbed:
            stubbed |= set(need)
            GEN.mkdir(parents=True, exist_ok=True)
            lines = ['/* Generated by port/tools/web.py: one stub per symbol the web build does not provide yet. */',
                     'extern void abort(void);',
                     'static void missing(const char *n) { (void)n; abort(); }']
            lines += [f'void {s}(void) {{ missing("{s}"); }}' for s in sorted(stubbed)]
            (GEN / "stubs.c").write_text("\n".join(lines) + "\n")
            s = run([EMCC, "-c", str(GEN / "stubs.c"), "-o", str(GEN / "stubs.o")])
            if s.returncode:
                print(s.stderr[:600])
                return False
            have = {t[-1] for t in (l.split() for l in run([llvm_nm(), "--defined-only", str(GEN / "stubs.o")]).stdout.splitlines()) if len(t) >= 2}
            print(f"stubs: {len(stubbed)} wanted, {len(have)} defined in stubs.o", flush=True)
            extra = [str(GEN / "stubs.o")]
            continue
        break
    (DIST / "stubs.txt").write_text("".join(n + "\n" for n in sorted(stubbed)))
    errs = [l for l in r.stderr.splitlines() if "error" in l]
    if r.returncode:
        print(f"{len(stubbed)} stubs; link FAILED")
        for l in errs[:20]:
            print("  " + l[-200:])
        return False
    print(f"{len(stubbed)} stubs; link OK")
    return True


def package():
    for f in ("bt3.html", "bt3.js", "bt3.wasm"):
        if not (DIST / f).exists():
            print("missing", f)
            return False
    # the build's files carry ITS number: bt3-<stamp>.html, .js, .wasm are the deployment (a whole artifact
    # is one unit; a mixed set is an import error and a stale cache mix looks exactly like one). The html's
    # script tag and the engine's wasm name are switched to the stamped names; index.html leads there.
    stamp = build_stamp()
    tag = f"bt3-{stamp}"
    html = (DIST / "bt3.html").read_text()
    html = html.replace("bt3.js", tag + ".js")
    (DIST / (tag + ".html")).write_text(html)
    shutil.move(DIST / "bt3.js", DIST / (tag + ".js"))
    shutil.move(DIST / "bt3.wasm", DIST / (tag + ".wasm"))
    (DIST / "bt3.html").unlink()
    (DIST / "index.html").write_text(f'<meta http-equiv="refresh" content="0;url={tag}.html">\n')
    (DIST / "version.txt").write_text(f"{stamp}\n")
    print("built into", DIST.relative_to(ROOT), "as", tag)
    return True


def main():
    if shutil.which(EMCC) is None:
        print(f"{EMCC} not found: activate Emscripten first (see .github/workflows/web.yml)")
        sys.exit(1)
    args = sys.argv[1:] or ["compile", "link", "package"]
    ok = True
    if "compile" in args:
        ok = compile_all()
    if "link" in args and (ok or os.environ.get("BT3_LINK_ANYWAY")):
        ok = link()
    if "package" in args and ok:
        ok = package()
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
