# Dragon Rage 0.5.0

A follow-up to 0.4.0, built from the reports of the first day: **HD texture packs in `.dds` now work on phones**, an
**OpenGL ES renderer** picked by itself for **Mali** GPUs, the **black screen after coming back from the background**
is gone, and the logs now say what a report needs.

## Highlights

### HD texture packs (.dds) on phones
- Phones' GPUs (Adreno and Mali) cannot read DXT (BC1–BC3), the format of a pack's `.dds` files. Those textures used
  to come out **white**, and the game reread them from storage every frame. Now they are unpacked once on the CPU and
  shown as they should be, with Vulkan and with OpenGL ES. Packs in `.png` worked already.
- The texture cache keeps to a **memory budget** (768 MB), so big HD packs (10 GB and more) do not fill the GPU's
  memory; textures not used for a while are let go and loaded again when needed.
- **Importing a pack** is sturdier: a zip is recognised by its content (not its name), zips made on Windows with
  accented names are read, there are clear messages for zips the app cannot read (Deflate64, LZMA, password) and for
  zips without PCSX2 texture names, the pack is unpacked straight onto the same storage (it no longer takes its room
  twice), and every step goes into the log.
- Tip: a pack can also be copied as a folder into `Android/data/com.dfdx047.dragonrage/files/textures/` (for example
  with `adb push`); the app and the game find it there. Packs that come with options (fonts, button layouts) need
  those copied into their `replacements` folder first, as their instructions say.

### OpenGL ES renderer (experimental), automatic on Mali
- The PC port's OpenGL renderer now runs on Android as **OpenGL ES 3.2**.
- **Settings › Performance › Renderer**: **Automatic** (OpenGL ES on Mali GPUs, Vulkan on the others; Adreno runs Vulkan
  best), **Vulkan** or **OpenGL ES**. The app finds the GPU by itself and shows which renderer the game will use. The
  in-game menu has the choice too.
- If the chosen renderer cannot start, the game starts with the other one.
- OpenGL ES leaves out: depth clamping (what comes very near the camera can be cut off) and the screen filters
  (FXAA, FSR, GSR). In present mode, FIFO turns VSync on and the others off.

### Fixes
- **Black screen after coming back to the game** from the background: the game now lets the screen's surface go when
  the app leaves and takes the new one when it comes back.
- The launcher no longer asks a missing function of the engine twice a second from the Controls preview (a full log
  and wasted work).

### Smaller texture cache, fewer stutters
- Without a texture pack the cache holds 3,072 textures on Android instead of 8,192: less GPU memory, and the clean-up
  that runs when it is full is shorter (it was a stutter of thousands of textures at once).

### Reports and online
- The log now has, every few seconds, the game's **speed** (100% is full speed), the pictures per second and where
  a frame's time goes. With it a report about slowness says what the bottleneck is.
- Online play: errors, the matchmaking service's answers and the relay's state now go into the log. A file
  `match_url.txt` (one line, `https://...`) in the game folder can name another matchmaking service without a new
  build.

## Performance notes
- What we learned from the logs: Snapdragon 8 Elite / 8s Gen 4 (Adreno 830 / 825) and Dimensity 8400 (Mali-G720) run
  at full speed, also at 2x and 3x. Exynos 1380 (Mali-G68) runs fights at about a quarter of full speed at 2x: on GPUs
  of that class use **1x**, and try OpenGL ES (now the default there).

## Upgrade notes
- It installs over 0.4.0 and keeps your game data, settings, saves and texture packs.
- The app still needs your own disc image of the USA release (SLUS-21678); this release does not distribute game data.
