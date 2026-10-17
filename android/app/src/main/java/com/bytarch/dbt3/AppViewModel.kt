package com.bytarch.dbt3

import android.app.Application
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bytarch.dbt3.data.AppUpdates
import com.bytarch.dbt3.data.BuildFeed
import com.bytarch.dbt3.data.CatalogState
import com.bytarch.dbt3.data.EngineSettings
import com.bytarch.dbt3.data.Renderer
import com.bytarch.dbt3.data.Net
import com.bytarch.dbt3.data.TextureCatalog
import com.bytarch.dbt3.data.UpdateInfo
import com.bytarch.dbt3.data.UpdateState
import com.bytarch.dbt3.data.formatBytes
import com.bytarch.dbt3.data.ExtraItem
import com.bytarch.dbt3.data.ExtraKind
import com.bytarch.dbt3.data.FileMod
import com.bytarch.dbt3.data.GameDataInstaller
import com.bytarch.dbt3.data.BattleRecord
import com.bytarch.dbt3.data.GamePaths
import com.bytarch.dbt3.data.GpuDriver
import com.bytarch.dbt3.data.GpuDrivers
import com.bytarch.dbt3.data.LogExport
import com.bytarch.dbt3.data.MatchLobby
import com.bytarch.dbt3.data.ImportResult
import com.bytarch.dbt3.data.InstallState
import com.bytarch.dbt3.data.Mods
import com.bytarch.dbt3.data.TexturePack
import com.bytarch.dbt3.data.TexturePacks
import com.bytarch.dbt3.data.UiPrefs
import com.bytarch.dbt3.engine.EngineBridge
import com.bytarch.dbt3.touch.TouchConfig
import com.bytarch.dbt3.touch.TouchStore
import com.bytarch.dbt3.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** A long operation in progress (shown as a bar with its text). */
data class Busy(val title: String, val detail: String = "")

/** One step of the launcher's room flow: idle, busy with the service, waiting in the quick-match queue, hosting (its
 * code shown), opening a path to the player found, or failed with something to say. */
enum class OnlinePhase { IDLE, WORKING, SEARCHING, HOSTING, CONNECTING, FAILED }

/** The online screen's state (AppViewModel.online). */
data class OnlineState(
    val phase: OnlinePhase = OnlinePhase.IDLE,
    val code: String = "",
    val peer: String = "",
    /** The other player's public id and record, when the service has said who they are (records are public). */
    val peerId: String = "",
    val peerRecord: Pair<Int, Int>? = null,
    val error: String = "",
    val waiting: Int = 0,
    val queued: Int = 0,
)

/** What a boot check has got to: not reached yet, being done now, or done. */
enum class StepState { Waiting, Working, Ok, Warn, Fail }

/** One line of the boot screen. */
data class BootStep(val label: String, val detail: String = "", val state: StepState = StepState.Waiting)

/** The boot screen: the checks in order, and whether the app is through them. */
data class BootState(val steps: List<BootStep> = emptyList(), val done: Boolean = false)

/** Where the game ISO is fetched from when the player has no disc image of their own. */
private const val ISO_URL = "https://gcode.bytarch.com/DBT3-MOBILE.iso"

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val paths = GamePaths(app).also { it.ensure() }
    private val resolver = app.contentResolver
    private val installer = GameDataInstaller(app, paths, resolver)
    private val packs = TexturePacks(app, paths, resolver)
    private val mods = Mods(app, paths, resolver)
    private val settings = EngineSettings(paths.settings)
    private val ui = UiPrefs(app)

    val uiState = ui.state
    val engineAvailable = EngineBridge.isAvailable(app)

    private val _install = MutableStateFlow(installer.currentState())
    val install: StateFlow<InstallState> = _install.asStateFlow()

    private val _packs = MutableStateFlow<List<TexturePack>>(emptyList())
    val texturePacks: StateFlow<List<TexturePack>> = _packs.asStateFlow()

    private val _mods = MutableStateFlow<List<FileMod>>(emptyList())
    val fileMods: StateFlow<List<FileMod>> = _mods.asStateFlow()

    private val _stages = MutableStateFlow<List<ExtraItem>>(emptyList())
    val stages: StateFlow<List<ExtraItem>> = _stages.asStateFlow()

    private val _songs = MutableStateFlow<List<ExtraItem>>(emptyList())
    val songs: StateFlow<List<ExtraItem>> = _songs.asStateFlow()

    private val _settings = MutableStateFlow(settings.snapshot())
    val engineSettings: StateFlow<Map<String, Int>> = _settings.asStateFlow()

    /** This player's name, set here before a match and kept in the engine's own settings file. */
    private val _playerName = MutableStateFlow(settings.playerName())
    val playerName: StateFlow<String> = _playerName.asStateFlow()

    fun setPlayerName(name: String) {
        settings.setPlayerName(name)
        _playerName.value = settings.playerName()
    }

    /** This device's recovery code, and a way to take another device's (so one record follows the player). */
    private val _recovery = MutableStateFlow(settings.recoveryCode())
    val recoveryCode: StateFlow<String> = _recovery.asStateFlow()

    // the online battles this device has finished, as the engine wrote them down
    private val _record = MutableStateFlow<List<BattleRecord>>(emptyList())
    val record: StateFlow<List<BattleRecord>> = _record.asStateFlow()

    // this player's record at the service: the copy that follows the recovery code, and its public id
    private val _myRecord = MutableStateFlow(MatchLobby.MyRecord())
    val myRecord: StateFlow<MatchLobby.MyRecord> = _myRecord.asStateFlow()

    // what the service says is happening now: the queue, the matches, the players
    private val _stats = MutableStateFlow(MatchLobby.Stats())
    val stats: StateFlow<MatchLobby.Stats> = _stats.asStateFlow()
    private var statsJob: Job? = null

    fun setRecoveryCode(code: String): Boolean {
        val ok = settings.setRecoveryCode(code)
        if (ok) _recovery.value = settings.recoveryCode()
        return ok
    }

    private val touchStore = TouchStore(paths.root)
    private val _touch = MutableStateFlow(touchStore.load())
    /** The on-screen controller (touch_controls.json, read by the game's process at start). */
    val touch: StateFlow<TouchConfig> = _touch.asStateFlow()

    fun updateTouch(c: TouchConfig) {
        _touch.value = c
        viewModelScope.launch(Dispatchers.IO) { touchStore.save(c) }
    }

    // ---- GPU drivers (adrenotools) and bug reports

    private val drivers = GpuDrivers(app, resolver)
    private val _drivers = MutableStateFlow(drivers.list())
    val gpuDrivers: StateFlow<List<GpuDriver>> = _drivers.asStateFlow()
    private val _driver = MutableStateFlow(drivers.selected()?.id)
    /** The chosen driver's id; null = the system's. */
    val gpuDriver: StateFlow<String?> = _driver.asStateFlow()

    fun importDriver(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val d = runCatching { drivers.import(uri) }.getOrNull()
        _drivers.value = drivers.list()
        if (d == null) say(str(R.string.driver_bad_zip)) else {
            drivers.select(d.id)
            _driver.value = d.id
            say(str(R.string.driver_imported, d.name))
        }
    }

    fun selectDriver(id: String?) = viewModelScope.launch(Dispatchers.IO) {
        drivers.select(id)
        _driver.value = drivers.selected()?.id
    }

    fun deleteDriver(d: GpuDriver) = viewModelScope.launch(Dispatchers.IO) {
        drivers.delete(d)
        _drivers.value = drivers.list()
        _driver.value = drivers.selected()?.id
    }

    private val _logShare = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    /** A report saved in Downloads, to offer for sharing. */
    val logShare: SharedFlow<Uri> = _logShare.asSharedFlow()

    fun exportLog() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { LogExport(getApplication(), paths).saveToDownloads() }
            .onSuccess { (uri, name) ->
                say(str(R.string.log_saved, name))
                _logShare.tryEmit(uri)
            }
            .onFailure { say(str(R.string.log_failed, it.message ?: "")) }
    }

    private val _busy = MutableStateFlow<Busy?>(null)
    val busy: StateFlow<Busy?> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var installJob: Job? = null

    init {
        refreshAll()
    }

    // ---- renderer: Vulkan, or OpenGL ES (chosen by itself for Mali GPUs)

    private val _gpuName = MutableStateFlow("")
    /** The GPU's name as its OpenGL ES driver gives it ("" until known). */
    val gpuName: StateFlow<String> = _gpuName.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _gpuName.value = runCatching { Renderer.gpuName(paths) }.getOrDefault("")
            runCatching { Renderer.apply(settings, paths) }
            _settings.value = settings.snapshot()
        }
    }

    fun setRenderer(choice: Int) = viewModelScope.launch(Dispatchers.IO) {
        settings.set(EngineSettings.RENDERER, choice)
        runCatching { Renderer.apply(settings, paths) }
        _settings.value = settings.snapshot()
    }

    // ---- boot

    private val _boot = MutableStateFlow(BootState())
    val boot: StateFlow<BootState> = _boot.asStateFlow()
    private var bootJob: Job? = null

    /** How long each stage holds the screen before the next one starts. A check that lands in 20 ms is not something
        a player sees: this is what makes five lines read as five stages of one load. The screen is BootScreen.kt. */
    private companion object {
        const val BOOT_FILES_MS = 350L
        const val BOOT_DATA_MS = 600L
        const val BOOT_ENGINE_MS = 450L
        const val BOOT_SERVER_MS = 900L
        const val BOOT_VERSION_MS = 500L
        const val BOOT_MIN_MS = 2800L
    }

    /**
     * The launch checks, in the order a game does them: storage first (nothing works without it), then the unpacked
     * data, the engine, the server, and whether this is the newest build. Nothing here blocks the app — a failed check
     * is a line on the boot screen, and the home screen is the answer to all of them.
     */
    fun runBoot() {
        if (bootJob?.isActive == true || _boot.value.done) return
        bootJob = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            _boot.value = BootState(
                listOf(
                    BootStep(str(R.string.boot_files)),
                    BootStep(str(R.string.boot_data)),
                    BootStep(str(R.string.boot_engine)),
                    BootStep(str(R.string.boot_server)),
                    BootStep(str(R.string.boot_version)),
                )
            )

            // storage: the saves and the unpacked data live under the app's own folder on shared storage
            step(0, state = StepState.Working)
            val writable = withContext(Dispatchers.IO) { paths.ensure(); paths.root.canWrite() }
            val free = withContext(Dispatchers.IO) { formatBytes(paths.root.usableSpace) }
            step(0, detail = if (writable) str(R.string.boot_free, free) else str(R.string.boot_no_storage), state = if (writable) StepState.Ok else StepState.Fail)
            delay(BOOT_FILES_MS)

            // the game's data: the marker the installer leaves behind, which is also what the engine starts from
            step(1, state = StepState.Working)
            val data = withContext(Dispatchers.IO) { installer.currentState() }
            step(
                1,
                detail = if (data is InstallState.Installed)
                    str(if (data.modified) R.string.game_data_ok_mod else R.string.game_data_ok, data.files, formatBytes(data.bytes))
                else str(R.string.boot_no_data),
                state = if (data is InstallState.Installed) StepState.Ok else StepState.Warn,
            )
            delay(BOOT_DATA_MS)

            // the engine itself: the native library has to be in the build or there is nothing to play
            step(2, state = StepState.Working)
            step(2, detail = str(if (engineAvailable) R.string.boot_ready else R.string.boot_no_engine), state = if (engineAvailable) StepState.Ok else StepState.Warn)
            delay(BOOT_ENGINE_MS)

            // the server, and with it the newest build: no connection is not fatal, the game is all local
            step(3, state = StepState.Working)
            step(4, state = StepState.Working)
            delay(BOOT_SERVER_MS)
            val feed = withContext(Dispatchers.IO) { runCatching { BuildFeed.fetch() }.getOrNull() }
            if (feed == null) {
                step(3, detail = str(R.string.boot_offline), state = StepState.Warn)
                step(4, detail = str(R.string.boot_offline), state = StepState.Warn)
            } else {
                step(3, detail = str(R.string.boot_online), state = StepState.Ok)
                delay(BOOT_VERSION_MS)
                if (feed.newerThan(BuildConfig.VERSION_CODE)) {
                    // the update dialog (UpdateDialog) takes it from here, once the boot screen is gone
                    _update.value = UpdateState.Available(UpdateInfo(feed.version, feed.notes, feed.apkUrl, feed.size, false))
                    step(4, detail = str(R.string.boot_new_build, feed.version), state = StepState.Ok)
                } else {
                    step(4, detail = str(R.string.boot_up_to_date), state = StepState.Ok)
                }
            }

            // never a flash of a screen the player cannot read
            val left = BOOT_MIN_MS - (SystemClock.elapsedRealtime() - started)
            if (left > 0) delay(left)
            _boot.value = _boot.value.copy(done = true)
        }
    }

    private fun step(i: Int, detail: String = "", state: StepState = StepState.Waiting) {
        val steps = _boot.value.steps.toMutableList()
        if (i !in steps.indices) return
        steps[i] = steps[i].copy(detail = detail, state = state)
        _boot.value = _boot.value.copy(steps = steps)
    }

    /** The counts the home screen shows ("N online, M in the queue"), asked at once and then every fifteen seconds for
     *  as long as the launcher is in front (refreshAll is called on every resume). A service that does not answer
     *  leaves the last numbers it gave: zeros, if it never did. */
    fun refreshStats() {
        statsJob?.cancel()
        statsJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { MatchLobby(MatchLobby.service(paths.root)).stats() }.onSuccess { _stats.value = it }
                delay(15_000)
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch(Dispatchers.IO) {
            _packs.value = packs.list()
            _mods.value = mods.listMods()
            _stages.value = mods.listExtras(ExtraKind.STAGE)
            _songs.value = mods.listExtras(ExtraKind.SONG)
            settings.load()
            _settings.value = settings.snapshot()
            _touch.value = touchStore.load() // (the game's own quick menu may have changed it)
            _record.value = BattleRecord.read(paths.root) // (the game appends a line when a battle is decided)
            refreshStats()
            val id = _recovery.value
            if (id.matches(Regex("[0-9a-f]{32}"))) {
                runCatching { MatchLobby(MatchLobby.service(paths.root)).player(id, _playerName.value) }
                    .onSuccess { _myRecord.value = it }
            }
        }
    }

    // ---- game data

    fun installFromIso(uri: Uri) = startInstall { installer.install(uri) { _install.value = it } }

    /** The game's ISO from the project's own server, then the same install a picked file gets.

        The image is 2.8 GB, so it goes to the temporary directory and is deleted when it has been unpacked: the data
        is what the game reads, and the button is there if the image is ever wanted again. Net.download checks the
        coroutine between chunks, so Cancel stops it part-way and leaves nothing behind.
     */
    fun downloadIso() = startInstall {
        // the data is already there, from an earlier install: nothing to download (also guards a stale UI
        // that still shows the button, and is what the installer's own state says, not what _install shows)
        (installer.currentState() as? InstallState.Installed)?.let { return@startInstall it }
        val file = File(paths.temp, "DBT3-MOBILE.iso")
        try {
            Net.download(ISO_URL, file) { done, total ->
                _install.value = InstallState.Working(
                    str(R.string.iso_dl_step),
                    if (total > 0) done.toFloat() / total else 0f,
                    str(R.string.progress_downloading, formatBytes(done)) + if (total > 0) " / ${formatBytes(total)}" else "",
                )
            }
            installer.install(Uri.fromFile(file)) { _install.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw GameDataInstaller.Stop(str(R.string.err_iso_download, e.message ?: e.javaClass.simpleName))
        } finally {
            withContext(NonCancellable) { file.delete() }
        }
    }

    /** One install at a time, from wherever the image comes from: the same progress, the same errors, the same Cancel. */
    private fun startInstall(what: suspend () -> InstallState.Installed) {
        if (installJob?.isActive == true) return
        installJob = viewModelScope.launch {
            try {
                _install.value = what()
                say(str(R.string.msg_data_ready))
            } catch (e: CancellationException) {
                withContext(Dispatchers.IO) { installer.uninstall() }
                _install.value = InstallState.NotInstalled
                throw e
            } catch (e: GameDataInstaller.Stop) {
                _install.value = InstallState.Failed(e.message ?: str(R.string.msg_error))
            } catch (e: Exception) {
                _install.value = InstallState.Failed(str(R.string.msg_iso_read_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
    }

    fun removeGameData() = viewModelScope.launch(Dispatchers.IO) {
        installer.uninstall()
        _install.value = InstallState.NotInstalled
    }

    fun dismissInstallError() {
        if (_install.value is InstallState.Failed) _install.value = installer.currentState()
    }

    // ---- texture packs

    fun importTexturePack(uri: Uri) = work(str(R.string.busy_import_pack)) {
        val p = packs.import(uri) { setDetail(it) }
        _packs.value = packs.list()
        say(str(R.string.msg_pack_installed, p.name, p.textures))
    }

    fun setPackEnabled(pack: TexturePack, on: Boolean) = work(str(if (on) R.string.busy_pack_on else R.string.busy_pack_off)) {
        packs.setEnabled(pack, on)
        _packs.value = packs.list()
    }

    fun deletePack(pack: TexturePack) = work(str(R.string.busy_pack_remove)) {
        packs.delete(pack)
        _packs.value = packs.list()
        say(str(R.string.msg_pack_removed, pack.name))
    }

    private val _catalog = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val catalog: StateFlow<CatalogState> = _catalog.asStateFlow()

    fun loadCatalog() {
        _catalog.value = CatalogState.Loading
        viewModelScope.launch {
            _catalog.value = runCatching { CatalogState.Loaded(TextureCatalog.load()) }.getOrDefault(CatalogState.Failed)
        }
    }

    /** Downloads a texture pack (.zip, https) and installs it like one picked from the files. */
    fun downloadTexturePack(url: String, name: String?) {
        val u = url.trim()
        if (!u.startsWith("https://")) {
            say(str(R.string.err_https_only))
            return
        }
        work(str(R.string.busy_download_pack)) {
            paths.temp.mkdirs()
            val file = File(paths.temp, "download-${System.currentTimeMillis()}.zip")
            try {
                Net.download(u, file) { done, total ->
                    setDetail(str(R.string.progress_downloading, formatBytes(done)) + if (total > 0) " / ${formatBytes(total)}" else "")
                }
                if (!Net.looksLikeZip(file)) throw IOException(str(R.string.err_download_not_zip))
                val label = name ?: u.substringBefore('?').substringAfterLast('/').removeSuffix(".zip").ifBlank { "Texture pack" }
                val p = packs.importFrom(label, { file.inputStream() }) { d -> setDetail(d) }
                _packs.value = packs.list()
                say(str(R.string.msg_pack_installed, p.name, p.textures))
            } finally {
                file.delete()
            }
        }
    }

    /**
     * Downloads a catalog item and hands it to the same importer a picked file would go to: a texture pack to the
     * pack list, anything else (.unk stage, .adx song, or a zip of either) down the mod path.
     */
    fun downloadAsset(url: String, name: String?, kind: String) {
        if (kind == "texture") {
            downloadTexturePack(url, name)
            return
        }
        val u = url.trim()
        if (!u.startsWith("https://")) {
            say(str(R.string.err_https_only))
            return
        }
        work(str(R.string.busy_download_item)) {
            paths.temp.mkdirs()
            val ext = u.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").ifBlank { "zip" }
            val file = File(paths.temp, "download-${System.currentTimeMillis()}.$ext")
            try {
                Net.download(u, file) { done, total ->
                    setDetail(str(R.string.progress_downloading, formatBytes(done)) + if (total > 0) " / ${formatBytes(total)}" else "")
                }
                mods.import(Uri.fromFile(file)) { setDetail(it) }
                refreshMods()
                say(str(R.string.msg_added, name ?: file.nameWithoutExtension))
            } finally {
                file.delete()
            }
        }
    }

    // ---- app updates (GitHub releases)

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()
    private var updateFile: File? = null
    private var updateJob: Job? = null

    // (after the state above: properties are set up in order, and this reads _update)
    init {
        checkForUpdate(manual = false)
    }

    /** Looks for a newer version. At start it only speaks up when there is one; from About it also says "up to date". */
    fun checkForUpdate(manual: Boolean) {
        if (_update.value !is UpdateState.Idle) return
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            val r = runCatching { AppUpdates.check(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) }
            val info = r.getOrNull()
            _update.value = if (info != null) UpdateState.Available(info) else UpdateState.Idle
            if (manual && info == null) say(str(if (r.isSuccess) R.string.update_none else R.string.update_check_failed))
        }
    }

    fun dismissUpdate() {
        if (_update.value is UpdateState.Available || _update.value is UpdateState.NeedPermission) _update.value = UpdateState.Idle
    }

    fun cancelUpdate() {
        updateJob?.cancel()
        _update.value = UpdateState.Idle
    }

    fun startUpdate() {
        val info = (_update.value as? UpdateState.Available)?.info ?: return
        val app = getApplication<Application>()
        updateJob = viewModelScope.launch {
            _update.value = UpdateState.Downloading(info, 0, info.size)
            try {
                val dir = File(app.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
                val f = File(dir, "DBT3-update.apk")
                Net.download(info.apkUrl, f) { d, t -> _update.value = UpdateState.Downloading(info, d, if (t > 0) t else info.size) }
                val pkg = app.packageManager.getPackageArchiveInfo(f.path, 0)?.packageName
                if (pkg != app.packageName) throw IOException(str(R.string.update_bad_apk))
                updateFile = f
                installUpdate(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _update.value = UpdateState.Idle
                say(str(R.string.update_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Hands the APK to the system installer, or asks first for the "install unknown apps" permission. */
    private fun installUpdate(info: com.bytarch.dbt3.data.UpdateInfo) {
        val app = getApplication<Application>()
        val f = updateFile ?: return
        if (!app.packageManager.canRequestPackageInstalls()) {
            _update.value = UpdateState.NeedPermission(info)
            return
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", f)
        app.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        _update.value = UpdateState.Idle
    }

    fun openInstallPermission() {
        val app = getApplication<Application>()
        app.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Back from the settings screen: if the permission is on now, the install goes ahead. */
    fun resumeUpdate() {
        val s = _update.value
        if (s is UpdateState.NeedPermission && getApplication<Application>().packageManager.canRequestPackageInstalls()) installUpdate(s.info)
    }

    // ---- mods, stages, songs

    fun importMod(uris: List<Uri>) = work(str(R.string.busy_import_mods)) {
        var mods0 = 0
        var stages0 = 0
        var songs0 = 0
        for (uri in uris) {
            when (val r = mods.import(uri) { setDetail(it) }) {
                is ImportResult.Mod -> mods0++
                is ImportResult.Extras -> if (r.kind == ExtraKind.STAGE) stages0 += r.count else songs0 += r.count
            }
        }
        refreshMods()
        say(str(R.string.msg_added, listOfNotNull(
            mods0.takeIf { it > 0 }?.let { str(R.string.count_mods, it) },
            stages0.takeIf { it > 0 }?.let { str(R.string.count_stages, it) },
            songs0.takeIf { it > 0 }?.let { str(R.string.count_songs, it) },
        ).joinToString(", ")))
    }

    fun setModEnabled(mod: FileMod, on: Boolean) = work(str(R.string.busy_apply_mods)) {
        mods.setEnabled(mod, on) { setDetail(it) }
        refreshMods()
    }

    fun moveMod(mod: FileMod, delta: Int) = work(str(R.string.busy_reorder_mods)) {
        mods.move(mod, delta) { setDetail(it) }
        refreshMods()
    }

    fun deleteMod(mod: FileMod) = work(str(R.string.busy_remove_mod)) {
        mods.delete(mod) { setDetail(it) }
        refreshMods()
        say(str(R.string.msg_mod_removed, mod.name))
    }

    fun renameExtra(kind: ExtraKind, item: ExtraItem, name: String) = work(str(R.string.busy_rename)) {
        mods.rename(kind, item, name)
        refreshMods()
    }

    fun deleteExtra(kind: ExtraKind, item: ExtraItem) = work(str(R.string.busy_remove)) {
        mods.deleteExtra(kind, item)
        refreshMods()
    }

    private fun refreshMods() {
        _mods.value = mods.listMods()
        _stages.value = mods.listExtras(ExtraKind.STAGE)
        _songs.value = mods.listExtras(ExtraKind.SONG)
    }

    // ---- settings

    fun setting(name: String, value: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            settings.set(name, value)
            _settings.value = settings.snapshot()
        }
    }

    fun setTheme(mode: ThemeMode) = ui.setTheme(mode)

    /** The app's language ("" = the system's), through Android's per-app language (13 and newer). */
    val canPickLanguage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun language(): String =
        if (canPickLanguage) getApplication<Application>().getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        else ""

    fun setLanguage(tag: String) {
        if (canPickLanguage) {
            getApplication<Application>().getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        }
    }
    fun setDynamicColor(on: Boolean) = ui.setDynamic(on)

    // ---- launch

    fun play() = launchGame()

    private fun launchGame() {
        when {
            _install.value !is InstallState.Installed -> say(str(R.string.msg_install_first))
            !engineAvailable -> say(str(R.string.msg_no_engine))
            else -> {
                paths.setOnlineEnv(false)
                // the session lines belong to a match that is beginning (startMatch below): a room found earlier
                // must not pull a plain Play into one
                paths.setSessionEnv(null)
                startGame()
            }
        }
    }

    // ---- online (the launcher's own matchmaking, before any game has started)

    private val _online = MutableStateFlow(OnlineState())
    val online: StateFlow<OnlineState> = _online.asStateFlow()
    private var onlineJob: Job? = null
    private var onlineRoom: Pair<String, String>? = null // a room this side made; given up when the wait ends

    /** The launcher's way into a match: find the other player here, then start the game straight into it. The
     * engine's own lobby (with its relay, for lines that cannot go direct) stays as it is. */
    fun onlineHost() = findOpponent(quick = false, host = true, code = "")

    fun onlineJoin(code: String) = findOpponent(quick = false, host = false, code = code.trim().uppercase())

    /** Quick match: the service's queue, the same one the game's own lobby uses (net_match.c, role 3). */
    fun onlineFind() = findOpponent(quick = true, host = false, code = "")

    fun onlineCancel() {
        onlineJob?.cancel()
        onlineJob = null
        _online.value = OnlineState()
    }

    private fun findOpponent(quick: Boolean, host: Boolean, code: String) {
        when {
            _install.value !is InstallState.Installed -> say(str(R.string.msg_install_first))
            !engineAvailable -> say(str(R.string.msg_no_engine))
            !quick && !host && code.isEmpty() -> Unit
            onlineJob?.isActive == true -> Unit
            else -> {
                _online.value = OnlineState(if (quick) OnlinePhase.SEARCHING else OnlinePhase.WORKING)
                onlineJob = viewModelScope.launch {
                    val lobby = MatchLobby(MatchLobby.service(paths.root))
                    try {
                        val name = _playerName.value.ifEmpty { "Player" }
                        val id = _recovery.value   // the recovery code: the service stores its hash, beside the room
                        val addrs = withContext(Dispatchers.IO) { listOf(lobby.publicAddr(), lobby.lanAddr()) }
                            .filter { it.isNotEmpty() }
                        if (addrs.isEmpty()) throw IOException(str(R.string.online_no_internet))
                        if (quick) {
                            // the queue gives the room to whoever waited, so this side hosts only when it was first
                            var wait = withContext(Dispatchers.IO) { lobby.queueTake(name, addrs, id) }
                            val relay = withContext(Dispatchers.IO) { lobby.takeRelay(wait.turn) } // published on the next poll
                            var pair = wait.pair
                            var waited = 0L
                            while (pair == null && waited < 260_000) {
                                _online.value = OnlineState(OnlinePhase.SEARCHING, waiting = wait.ahead, queued = wait.total)
                                delay(2000)
                                waited += 2000
                                wait = withContext(Dispatchers.IO) {
                                    lobby.queuePoll(wait.key, listOfNotNull(relay), id)
                                }
                                pair = wait.pair
                            }
                            val found = pair ?: throw IOException(str(R.string.online_nobody))
                            withContext(Dispatchers.IO) { lobby.queueGiveUp() } // the place is gone: a room is left
                            beginMatch(lobby, found.peer, host = found.matched == 1, room = found.code to found.key)
                        } else if (host) {
                            val made = withContext(Dispatchers.IO) { lobby.host(name, addrs, id) }
                            val room = made.code
                            val key = made.key
                            onlineRoom = room to key
                            // a relayed address for this side, published so that whoever joins can be given one
                            withContext(Dispatchers.IO) {
                                val relay = lobby.takeRelay(made.turn)
                                if (relay != null) lobby.publishAddrs(room, key, addrs + relay)
                            }
                            _online.value = OnlineState(OnlinePhase.HOSTING, code = room)
                            var peer: MatchLobby.Peer? = null
                            while (peer == null) {
                                delay(2000)
                                peer = withContext(Dispatchers.IO) { lobby.poll(room, key) }
                            }
                            // the room has done its work as soon as both sides know each other (the engine's own
                            // lobby gives it up at the same moment)
                            withContext(Dispatchers.IO) { lobby.leave(room, key) }
                            onlineRoom = null
                            beginMatch(lobby, peer, host = true, room = room to key)
                        } else {
                            val joined = withContext(Dispatchers.IO) { lobby.join(code, name, addrs, id) }
                            beginMatch(lobby, joined.peer, host = false, room = code to joined.key)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("dbt3online", "online: $quick host=$host code=$code", e)
                        _online.value = OnlineState(OnlinePhase.FAILED, error = onlineError(e))
                    } finally {
                        val room = onlineRoom
                        onlineRoom = null
                        // the room has done its work (or the wait was given up): give it back, even on a cancel
                        if (room != null) runCatching { withContext(NonCancellable) { lobby.leave(room.first, room.second) } }
                        runCatching { withContext(NonCancellable) { lobby.queueGiveUp() } }
                        runCatching { lobby.socket.close() }
                    }
                }
            }
        }
    }

    /** The other player is known: find which of their addresses really answers, then hand the match to the engine.
     * [room] is the room the service made for the pair, when it made one; it is given up before the game starts.
     * When nothing direct answers, the other side's relayed address is used instead (only the hosting side holds
     * one, so the path then exists even through a NAT that hides both machines). */
    private suspend fun beginMatch(lobby: MatchLobby, peer: MatchLobby.Peer, host: Boolean, room: Pair<String, String>? = null) {
        _online.value = OnlineState(
            OnlinePhase.CONNECTING,
            peer = peer.name,
            peerId = peer.id,
            peerRecord = peer.record,
        )
        val wire = withContext(Dispatchers.IO) { lobby.probe(peer.addrs, 20_000, host = host) }
            ?: peer.relay.ifEmpty { throw IOException(str(R.string.online_unreachable)) }
        if (room != null) withContext(Dispatchers.IO) { lobby.leave(room.first, room.second) }
        val port = lobby.localPort
        lobby.socket.close() // the engine binds this same port in a moment: see the class comment on MatchLobby
        startMatch(port, wire, host, lobby.relayLogin, room)
        _online.value = OnlineState()
    }

    /** What net.c reads at boot for a session (the same lines it writes itself before restarting into one): this
     * side's role, the address found, the save folder that starts empty, and the relay login when this side holds a
     * relayed address (the engine takes the same allocation over, on the same port). */
    private fun startMatch(port: Int, peer: String, host: Boolean, turn: MatchLobby.Turn?, room: Pair<String, String>? = null) {
        val vars = mutableMapOf(
            "BT3_NET_SESSION" to "1",
            "BT3_NET_LOCALPORT" to "$port",
            "BT3_SAVES" to "net_session",
            "BT3_SOUND_TICKS" to "1",
            "BT3_NOMOVIE" to "1",
        )
        if (host) {
            vars["BT3_NET_HOST"] = "$port"
            vars["BT3_NET_PUNCH"] = peer
            vars["BT3_NET_RULES"] = "768" // single battle, 240 seconds: the game's defaults (net.c sRules)
        } else {
            vars["BT3_NET_JOIN"] = peer
        }
        if (turn != null) {
            vars["BT3_TURN_SERVER"] = turn.server
            vars["BT3_TURN_USER"] = turn.username
            vars["BT3_TURN_CRED"] = turn.credential
        }
        // the room this match came from, so the game can say how it went (net_match.c Match_Room)
        if (room != null) {
            vars["BT3_NET_ROOM"] = room.first
            vars["BT3_NET_KEY"] = room.second
        }
        paths.setOnlineEnv(false)
        paths.clearNetSession()
        paths.setSessionEnv(vars)
        startGame()
    }

    private fun startGame() {
        getApplication<Application>().startActivity(
            Intent(getApplication(), com.bytarch.dbt3.engine.GameActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** The service refuses with {"error": "..."} (worker.js): say it in the player's language. Anything else is this
     * side's own message (no internet, unreachable) and is shown as it stands. */
    private fun onlineError(e: Exception): String {
        val text = e.message.orEmpty()
        if (text == "version") return str(R.string.online_version) // the greeting's own word (MatchLobby.probe)
        val json = runCatching { org.json.JSONObject(text) }.getOrNull()
        if (json != null) {
            return when (json.optString("error")) {
                "version" -> str(R.string.online_version)
                "no room" -> str(R.string.online_no_room)
                "full" -> str(R.string.online_full)
                "busy" -> str(R.string.online_busy)
                "busy matches" -> str(R.string.online_busy_matches)
                "bad request" -> str(R.string.online_bad_request)
                "no room to give" -> str(R.string.online_no_room_to_give)
                "no waiting" -> str(R.string.online_nobody)
                else -> str(R.string.online_failed)
            }
        }
        return text.ifBlank { str(R.string.online_failed) }
    }

    /** A "Play" icon for the home screen: it starts the game without opening the launcher (PlayShortcutActivity). */
    fun createPlayShortcut() {
        val app = getApplication<Application>()
        if (!androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(app)) {
            say(str(R.string.shortcut_unsupported))
            return
        }
        val info = androidx.core.content.pm.ShortcutInfoCompat.Builder(app, "play-pinned")
            .setShortLabel(str(R.string.app_name))
            .setLongLabel(str(R.string.shortcut_play_long))
            .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(app, R.mipmap.ic_launcher))
            .setIntent(
                android.content.Intent(app, com.bytarch.dbt3.PlayShortcutActivity::class.java)
                    .setAction(android.content.Intent.ACTION_VIEW),
            )
            .build()
        if (androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(app, info, null)) say(str(R.string.shortcut_asked))
    }

    // ---- helpers

    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun say(text: String) {
        _messages.tryEmit(text)
    }

    private fun setDetail(text: String) {
        _busy.value = _busy.value?.copy(detail = text)
    }

    private fun work(title: String, block: suspend () -> Unit) {
        if (_busy.value != null) {
            say(str(R.string.msg_wait))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = Busy(title)
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(e.message ?: "${str(R.string.msg_error)}: ${e.javaClass.simpleName}")
            } finally {
                _busy.value = null
            }
        }
    }
}
