#!/bin/sh
# Builds the launcher window (port/setup/setup.cpp, native.cpp, net.cpp).
#   port/setup/build.sh        -> port/build/dbt3-launcher        (needs g++ and SDL3; Dear ImGui is in the repository)
#   port/setup/build.sh win    -> port/build/dbt3-launcher.exe    (with the same icon the game has, port/res/dbt3.rc)
#        on Linux: cross-compiled with mingw-w64 (x86_64-w64-mingw32-g++) and SDL3's mingw development package
#                  unpacked under port/build/win (or its x86_64-w64-mingw32 folder named by BT3_SDL3);
#        on Windows (MSYS2 shell): with that shell's g++ and SDL3.
cd "$(dirname "$0")/../.." || exit 1
I=port/third_party/imgui
if [ "$1" = win ]; then
    O=port/build/setup_win; OUT=port/build/dbt3-launcher.exe; LIBS="-lSDL3 -static -static-libgcc -static-libstdc++ -mwindows"
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) WINDRES=windres ;;
        *) WINDRES=x86_64-w64-mingw32-windres ;;
    esac
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) CXX=g++; SDL="" ;;
        *) CXX=x86_64-w64-mingw32-g++
           S=${BT3_SDL3:-$(ls -d port/build/win/SDL3-*/x86_64-w64-mingw32 2>/dev/null | tail -1)}
           [ -d "$S" ] || { echo "SDL3 for mingw not found: unpack SDL3-devel-*-mingw.tar.gz under port/build/win"; exit 1; }
           SDL="-I$S/include -L$S/lib" ;;
    esac
else
    O=port/build/setup; OUT=port/build/dbt3-launcher; CXX=g++; SDL=""; LIBS="-lSDL3 -lpthread -ldl -static-libstdc++ -static-libgcc -Wl,-rpath,\$ORIGIN/lib"
fi
mkdir -p $O
for f in $I/imgui.cpp $I/imgui_draw.cpp $I/imgui_tables.cpp $I/imgui_widgets.cpp $I/imgui_impl_sdl3.cpp $I/imgui_impl_sdlgpu3.cpp $I/imgui_impl_opengl3.cpp port/setup/native.cpp port/setup/net.cpp port/setup/setup.cpp; do
    o=$O/$(basename "$f" .cpp).o
    if [ ! -f "$o" ] || [ "$f" -nt "$o" ] || [ port/setup/native.h -nt "$o" ] || [ port/setup/net.h -nt "$o" ]; then
        $CXX -std=c++17 -O2 -w -I$I $SDL -c "$f" -o "$o" || exit 1
    fi
done
# -Wl,-Bdynamic for SDL3 only: the rest (C++ runtime, threads) is linked in. winhttp: the boot check and the ISO
# download; ole32 + windowscodecs: the boot screen's picture. The icon and version resource is the game's own
# (port/res/dbt3.rc), so the launcher looks like the game in the task bar and in the file properties.
if [ "$1" = win ]; then
    $WINDRES -O coff -o $O/launcher_res.o port/res/dbt3.rc 2>/dev/null || rm -f $O/launcher_res.o
    $CXX -o $OUT $O/*.o $SDL -Wl,-Bdynamic -lSDL3 -lwinhttp -lole32 -lwindowscodecs -Wl,-Bstatic -lstdc++ -lwinpthread -static-libgcc -mwindows && echo "built $OUT"
else
    $CXX -o $OUT $O/*.o $LIBS && echo "built ./$OUT"
fi
