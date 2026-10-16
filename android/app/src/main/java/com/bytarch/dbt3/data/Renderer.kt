package com.bytarch.dbt3.data

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log
import java.io.File

/**
 * Which renderer the game starts with. The engine reads "gpu_api" from its settings (0 Vulkan, 1 OpenGL ES 3.2); the
 * player's choice is "dr_renderer" (0 automatic, 1 Vulkan, 2 OpenGL ES). Automatic: OpenGL ES for Mali GPUs (their
 * OpenGL ES drivers are the mature ones; on weaker Mali the game's many small draws and render-target changes cost
 * less there), Vulkan for everything else (Adreno runs Vulkan very well).
 */
object Renderer {
    const val AUTO = 0
    const val VULKAN = 1
    const val OPENGL = 2

    /** The GPU's name (as its OpenGL ES driver gives it, e.g. "Mali-G68 MC4"), found once and kept in the game folder. */
    fun gpuName(paths: GamePaths): String {
        val cache = File(paths.root, "gpu_name.txt")
        runCatching { cache.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val name = queryGlRenderer() ?: return ""
        runCatching { cache.writeText(name + "\n") }
        return name
    }

    fun isMali(gpu: String) = gpu.contains("Mali", ignoreCase = true)

    /** The renderer the engine is to use: 1 = OpenGL ES, 0 = Vulkan. */
    fun gpuApi(choice: Int, gpu: String): Int = when (choice) {
        VULKAN -> 0
        OPENGL -> 1
        else -> if (isMali(gpu)) 1 else 0
    }

    /** Writes "gpu_api" from the choice and the GPU (the launcher on start and on a change; the game before it starts). */
    fun apply(settings: EngineSettings, paths: GamePaths) {
        val choice = settings.get(EngineSettings.RENDERER, AUTO)
        val api = gpuApi(choice, if (choice == AUTO) gpuName(paths) else "")
        if (settings.get(EngineSettings.GPU_API, -1) != api) settings.set(EngineSettings.GPU_API, api)
    }

    /** GL_RENDERER from a small off-screen OpenGL ES context; null when none can be made. */
    private fun queryGlRenderer(): String? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] < 1) return null
        val config = configs[0] ?: return null
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) return null
        val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        return try {
            if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) null
            else GLES20.glGetString(GLES20.GL_RENDERER)?.trim()
        } catch (t: Throwable) {
            Log.w("bt3", "GPU name could not be read", t)
            null
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            // (the display is not terminated: the game's own OpenGL ES, in its process, initialises it again anyway)
        }
    }
}
