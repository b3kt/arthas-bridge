package com.github.b3kt.arthasbridge.core

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbService
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import java.util.concurrent.ConcurrentHashMap
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.github.b3kt.arthasbridge.settings.ArthasSettings
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Busy(val message: String) : ConnectionState
    data class Connected(val host: String, val port: Int, val pid: Long?, val label: String, val version: String) : ConnectionState {
        val isLocal get() = isLoopback(host)
    }
}

interface ArthasListener {
    fun stateChanged(state: ConnectionState) {}
    fun jobChanged(running: Boolean) {}
    fun output(text: String, kind: OutputKind) {}
    /** A command the user picked in the editor while nothing is attached; put it in the input box. */
    fun prefill(command: String) {}
    fun probeAdded(probe: Probe) {}
    /** State or counters changed; [hit] is set when a new call was recorded. */
    fun probeChanged(probe: Probe, hit: ProbeHit?) {}
    fun probeRemoved(probe: Probe) {}
}

internal fun isLoopback(host: String) =
    runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)

/**
 * One Arthas connection per project: attach / connect, run commands through an HTTP API session,
 * stream results to listeners, and watch the target so a dead JVM is noticed.
 */
@Service(Service.Level.PROJECT)
class ArthasService(private val project: Project) : Disposable {

    @Volatile
    var state: ConnectionState = ConnectionState.Disconnected
        private set

    @Volatile
    var jobRunning = false
        private set

    val isConnected get() = state is ConnectionState.Connected

    private val probes = CopyOnWriteArrayList<Probe>()
    private val probeIds = AtomicInteger()

    val liveProbeCount get() = probes.count { it.state.live }

    private val listeners = CopyOnWriteArrayList<ArthasListener>()
    private val jobs = AppExecutorUtil.createBoundedApplicationPoolExecutor("Arthas Bridge Jobs", 1)

    @Volatile private var client: ArthasHttpClient? = null
    @Volatile private var session: ArthasHttpClient.Session? = null
    @Volatile private var attachedByUs = false
    @Volatile private var stopRequested = false
    private var healthCheck: ScheduledFuture<*>? = null

    fun addListener(listener: ArthasListener, parent: Disposable) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    // ---- connect / attach ----

    fun attach(process: JvmProcess) {
        if (state != ConnectionState.Disconnected) return
        val boot = ArthasBootLocator.find() ?: return offerBootJar { attach(process) }
        setState(ConnectionState.Busy("Attaching to ${process.pid}…"))
        object : Task.Backgroundable(project, "Attaching Arthas to ${process.pid} ${process.name}", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    doAttach(process, boot, indicator)
                } catch (e: Exception) {
                    failConnect("Attach to ${process.pid} failed: ${e.message}")
                }
            }

            override fun onCancel() = failConnect("Attach cancelled")
        }.queue()
    }

    fun connect(host: String, port: Int) {
        if (state != ConnectionState.Disconnected) return
        if (!isLoopback(host) && !ArthasSettings.getInstance().state.allowRemote) {
            return emit("Connecting to $host is disabled. Use kubectl port-forward to localhost, " +
                "or enable remote connections in Settings | Tools | Arthas Bridge.\n", OutputKind.ERROR)
        }
        setState(ConnectionState.Busy("Connecting to $host:$port…"))
        object : Task.Backgroundable(project, "Connecting to Arthas at $host:$port", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val c = newClient(host, port)
                    val pid = ArthasAttacher.javaPid(c)
                    connected(c, if (isLoopback(host)) pid else null, "pid ${pid ?: "?"} @ $host:$port", byUs = false)
                } catch (e: Exception) {
                    failConnect("Cannot connect to $host:$port: ${e.message}")
                }
            }
        }.queue()
    }

    private fun doAttach(process: JvmProcess, boot: File, indicator: ProgressIndicator) {
        val settings = ArthasSettings.getInstance()
        val options = settings.state
        val user = options.username?.takeIf { it.isNotBlank() }
        emit("Attaching Arthas to ${process.pid} (${process.name})…\n", OutputKind.INFO)
        val result = ArthasAttacher(
            boot, options.httpPort, options.telnetPort, user, user?.let { settings.password },
            log = { emit("$it\n", OutputKind.INFO) },
            cancelled = { indicator.isCanceled },
        ).attach(process)
        connected(result.client, process.pid, "${process.pid}  ${process.name}", byUs = true)
    }

    private fun newClient(host: String, port: Int): ArthasHttpClient {
        val settings = ArthasSettings.getInstance()
        val user = settings.state.username
        return ArthasHttpClient(host, port, user, if (user.isNullOrBlank()) null else settings.password)
    }

    private fun connected(c: ArthasHttpClient, pid: Long?, label: String, byUs: Boolean) {
        val version = ArthasAttacher.version(c)
        client = c
        session = null
        attachedByUs = byUs
        setState(ConnectionState.Connected(c.host, c.port, pid, label, version))
        emit("Connected to $label (Arthas $version). Right-click a method in the editor → Arthas, or type a command below.\n", OutputKind.INFO)
        healthCheck = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay(::checkHealth, 3, 3, TimeUnit.SECONDS)
    }

    private fun failConnect(message: String) {
        emit("$message\n", OutputKind.ERROR)
        setState(ConnectionState.Disconnected)
    }

    // ---- disconnect ----

    /** Shuts Arthas down inside the target JVM; all enhanced classes are restored. */
    fun stopArthas() = runInBackground {
        val c = client ?: return@runInBackground
        interruptQuietly()
        interruptProbes(c)
        runCatching { c.exec("stop", 5_000) }
        cleanup()
        emit("Arthas stopped. The target JVM keeps running and enhanced classes were reset.\n", OutputKind.INFO)
    }

    /** Leaves Arthas running in the target (useful for a shared/remote Arthas). */
    fun disconnect() = runInBackground {
        interruptQuietly()
        client?.let(::interruptProbes)
        session?.let { s -> client?.closeSession(s) }
        cleanup()
        emit("Disconnected. Arthas is still running in the target JVM.\n", OutputKind.INFO)
    }

    private fun checkHealth() {
        val current = state as? ConnectionState.Connected ?: return
        val reason = when {
            current.pid != null && current.isLocal && !JvmProcesses.isAlive(current.pid) -> "Target JVM ${current.pid} exited."
            !ArthasAttacher.portOpen(current.host, current.port) -> "Arthas at ${current.host}:${current.port} is no longer reachable."
            else -> return
        }
        cleanup()
        emit("$reason\n", OutputKind.ERROR)
        notify(reason, NotificationType.WARNING)
    }

    private fun cleanup() {
        healthCheck?.cancel(false)
        healthCheck = null
        client = null
        session = null
        attachedByUs = false
        setState(ConnectionState.Disconnected)
    }

    // ---- commands ----

    /** Runs a command; an already running job is interrupted first (Arthas allows one foreground job). */
    fun run(command: String) {
        val line = command.trim()
        if (line.isEmpty()) return
        val c = client ?: return emit("Not attached to a JVM.\n", OutputKind.ERROR)
        if (ProbeParser.isProbe(line)) return startProbe(c, line)
        if (jobRunning) runInBackground { interruptQuietly() }
        jobs.execute { runJob(c, line) }
    }

    /** Entry point for editor actions: run when attached, otherwise prepare the command in the tool window. */
    fun runFromEditor(command: String) {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
        if (isConnected) {
            run(command)
        } else {
            listeners.forEach { it.prefill(command) }
            emit("Pick a JVM and press Attach; the command is ready in the input box.\n", OutputKind.INFO)
        }
    }

    /** Interrupts the console job and every live probe. */
    fun stopJob() {
        runInBackground {
            if (jobRunning) interruptQuietly()
            client?.let(::interruptProbes)
        }
    }

    /**
     * Samples CPU, memory, threads and GC with a one-shot `dashboard` (its own temporary session, so it
     * doesn't disturb the console job or probes). Blocks about a second; call off the EDT. Null when not connected.
     */
    fun snapshot(): JvmSnapshot? {
        val c = client ?: return null
        val response = try {
            c.exec(JvmSnapshot.COMMAND, 8_000)
        } catch (e: ArthasException) {
            LOG.debug(e)
            return null
        }
        val results = response.getAsJsonObject("body")?.getAsJsonArray("results") ?: return null
        return results.mapNotNull { if (it.isJsonObject) JvmSnapshot.parse(it.asJsonObject) else null }.lastOrNull()
    }

    // ---- probes ----

    fun probes(): List<Probe> = probes.toList()

    fun stopProbe(probe: Probe) = runInBackground {
        probe.stopRequested = true
        val c = client ?: return@runInBackground
        probe.session?.let(c::interruptJob)
    }

    fun removeProbe(probe: Probe) {
        if (probe.state.live) stopProbe(probe)
        if (probes.remove(probe)) listeners.forEach { it.probeRemoved(probe) }
    }

    fun clearFinishedProbes() = probes.filterNot { it.state.live }.forEach(::removeProbe)

    private fun interruptProbes(c: ArthasHttpClient) = probes.filter { it.state.live }.forEach { p ->
        p.stopRequested = true
        p.session?.let { runCatching { c.interruptJob(it) } }
    }

    private fun startProbe(c: ArthasHttpClient, command: String) {
        if (liveProbeCount >= MAX_LIVE_PROBES) {
            return emit("Up to $MAX_LIVE_PROBES probes can run at once. Stop one in the Probes tab first.\n", OutputKind.ERROR)
        }
        val probe = Probe(probeIds.incrementAndGet(), command)
        probes += probe
        emit("\n> $command   (results in the Probes tab)\n", OutputKind.COMMAND)
        listeners.forEach { it.probeAdded(probe) }
        ApplicationManager.getApplication().executeOnPooledThread { runProbe(c, probe) }
    }

    private fun runProbe(c: ArthasHttpClient, probe: Probe) {
        var failed = false
        try {
            val s = c.initSession().also { probe.session = it }
            if (probe.stopRequested) return
            c.asyncExec(s, probe.command)
            while (client === c) {
                val results = c.pullResults(s)
                var finished = false
                for (element in results) {
                    val result = element.asJsonObject
                    when {
                        result.str("type") == "enhancer" && result.get("success")?.asBoolean == true ->
                            updateProbe(probe) { probe.state = ProbeState.LIVE }
                        // An interrupted job may end with a non-zero status; that is a stop, not a failure.
                        !probe.stopRequested &&
                            (isFailedCommand(result) || (ResultRenderer.isJobEnd(result) && result.get("statusCode")?.asInt != 0)) -> {
                            failed = true
                            finished = true
                            probe.message = ResultRenderer.render(result).filterIsInstance<Rendered.Text>()
                                .joinToString("") { it.text }.trim().ifEmpty { "Command failed" }
                        }
                        ResultRenderer.isJobEnd(result) -> finished = true
                        else -> ProbeParser.hits(result).forEach { hit ->
                            val added = probe.add(hit.copy(summary = mask(hit.summary), detail = renderText(hit.source ?: result)))
                            listeners.forEach { it.probeChanged(probe, added) }
                        }
                    }
                }
                if (finished) break
                if (probe.hitCount >= MAX_RESULTS && !probe.stopRequested) {
                    probe.message = "Stopped after $MAX_RESULTS calls to keep the IDE responsive."
                    probe.stopRequested = true
                    c.interruptJob(s)
                }
                if (results.size() == 0) Thread.sleep(250)
            }
        } catch (e: ArthasException) {
            if (client === c) {
                failed = true
                probe.message = e.message
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (failed) emit("${probe.command}: ${probe.message}\n", OutputKind.ERROR)
            probe.session?.let { s -> if (client === c) runCatching { c.closeSession(s) } }
            probe.session = null
            updateProbe(probe) {
                probe.state = when {
                    failed -> ProbeState.FAILED
                    probe.stopRequested || client !== c -> ProbeState.STOPPED
                    else -> ProbeState.DONE
                }
            }
        }
    }

    private fun updateProbe(probe: Probe, change: () -> Unit) {
        change()
        listeners.forEach { it.probeChanged(probe, null) }
    }

    private fun interruptQuietly() {
        val c = client ?: return
        val s = session ?: return
        stopRequested = true
        c.interruptJob(s)
    }

    private fun runJob(c: ArthasHttpClient, command: String) {
        emit("\n> $command\n", OutputKind.COMMAND)
        stopRequested = false
        val s = try {
            startJob(c, command)
        } catch (e: ArthasException) {
            return emit("${e.message}\n", OutputKind.ERROR)
        }
        setJob(true)
        var observed = 0
        try {
            while (client === c) {
                val results = c.pullResults(s)
                var finished = false
                for (element in results) {
                    val result = element.asJsonObject
                    handle(result)
                    if (result.str("type") in COUNTED) observed++
                    if (ResultRenderer.isJobEnd(result) || isFailedCommand(result)) finished = true
                }
                if (finished) break
                if (observed >= MAX_RESULTS && !stopRequested) {
                    emit("Stopped after $MAX_RESULTS results to keep the IDE responsive.\n", OutputKind.INFO)
                    interruptQuietly()
                }
                if (results.size() == 0) Thread.sleep(250)
            }
        } catch (e: ArthasException) {
            if (client === c) emit("${e.message}\n", OutputKind.ERROR)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            setJob(false)
        }
    }

    private fun startJob(c: ArthasHttpClient, command: String): ArthasHttpClient.Session {
        val s = session ?: c.initSession().also { session = it }
        return try {
            c.asyncExec(s, command)
            s
        } catch (e: ArthasException) {
            // Sessions expire after inactivity; start a fresh one and retry once.
            if (e.message?.contains("session", ignoreCase = true) != true) throw e
            c.initSession().also { session = it; c.asyncExec(it, command) }
        }
    }

    private fun isFailedCommand(result: com.google.gson.JsonObject) =
        result.str("type") == "command" && result.str("state") == "FAILED"

    private fun handle(result: com.google.gson.JsonObject) {
        for (r in render(result)) {
            when (r) {
                is Rendered.Text -> emit(r.text, r.kind)
                is Rendered.Source -> openSource(r)
                is Rendered.OutputFile -> openOutputFile(r.path)
            }
        }
    }

    /** Readable or terminal format per the settings, with sensitive text masked. */
    private fun render(result: com.google.gson.JsonObject): List<Rendered> {
        val options = ArthasSettings.getInstance().state
        val rendered = if (options.readableOutput) {
            ReadableRenderer.render(result, paramNames) ?: ResultRenderer.render(result)
        } else {
            ResultRenderer.render(result)
        }
        return rendered.map { if (it is Rendered.Text) it.copy(text = mask(it.text)) else it }
    }

    private fun renderText(result: com.google.gson.JsonObject) = render(result).filterIsInstance<Rendered.Text>()

    private fun mask(text: String) =
        if (ArthasSettings.getInstance().state.maskSensitiveData) SensitiveDataMasker.mask(text) else text

    private val paramCache = ConcurrentHashMap<String, List<String>>()

    /** Real parameter names from the project sources, when the method has a single overload with that arity. */
    private val paramNames = ParamNames { className, method, count ->
        val key = "$className#$method/$count"
        paramCache[key] ?: run {
            if (DumbService.isDumb(project)) return@ParamNames null
            val fqn = className.substringBefore("$$").replace('$', '.')
            val names = runCatching {
                ApplicationManager.getApplication().runReadAction(Computable {
                    JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
                        ?.findMethodsByName(method, true)
                        ?.filter { it.parameterList.parametersCount == count }
                        ?.singleOrNull()
                        ?.parameterList?.parameters?.map { it.name }
                })
            }.getOrNull() ?: return@ParamNames null
            names.also { paramCache[key] = it }
        }
    }

    private fun openSource(r: Rendered.Source) = ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension("java")
        val file = LightVirtualFile("${r.className.substringAfterLast('.')}.java (decompiled)", fileType, r.source)
        file.isWritable = false
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun openOutputFile(path: String) {
        val local = (state as? ConnectionState.Connected)?.isLocal == true
        val file = File(path)
        if (local && file.isFile) BrowserUtil.browse(file)
    }

    // ---- arthas-boot.jar ----

    private fun offerBootJar(onReady: () -> Unit) {
        notify(
            "arthas-boot.jar was not found. Download Arthas ${ArthasBootLocator.PINNED_VERSION} from Maven Central or pick an existing jar.",
            NotificationType.INFORMATION,
            NotificationAction.createSimpleExpiring("Download") {
                object : Task.Backgroundable(project, "Downloading arthas-boot ${ArthasBootLocator.PINNED_VERSION}", true) {
                    override fun run(indicator: ProgressIndicator) {
                        ArthasBootLocator.download(indicator)
                    }

                    override fun onSuccess() = onReady()
                    override fun onThrowable(error: Throwable) =
                        notify("Download failed: ${error.message}", NotificationType.ERROR)
                }.queue()
            },
            NotificationAction.createSimpleExpiring("Choose jar…") {
                val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("jar")
                FileChooser.chooseFile(descriptor, project, null) { vf ->
                    ArthasSettings.getInstance().state.arthasBootPath = vf.path
                    onReady()
                }
            },
        )
    }

    // ---- plumbing ----

    private fun setState(newState: ConnectionState) {
        state = newState
        listeners.forEach { it.stateChanged(newState) }
    }

    private fun setJob(running: Boolean) {
        jobRunning = running
        listeners.forEach { it.jobChanged(running) }
    }

    private fun emit(text: String, kind: OutputKind) = listeners.forEach { it.output(text, kind) }

    private fun notify(content: String, type: NotificationType, vararg actions: AnAction) {
        NotificationGroupManager.getInstance().getNotificationGroup("Arthas Bridge")
            .createNotification(content, type)
            .apply { actions.forEach { addAction(it) } }
            .notify(project)
    }

    private fun runInBackground(block: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                block()
            } catch (e: Exception) {
                LOG.warn(e)
                emit("${e.message}\n", OutputKind.ERROR)
            }
        }
    }

    override fun dispose() {
        healthCheck?.cancel(false)
        val c = client ?: return
        val s = session
        val stop = attachedByUs && ArthasSettings.getInstance().state.stopOnProjectClose
        val probeSessions = probes.mapNotNull { it.session }
        client = null
        // Best effort, bounded so closing the project never hangs.
        runCatching {
            ApplicationManager.getApplication().executeOnPooledThread {
                probeSessions.forEach { runCatching { c.interruptJob(it); c.closeSession(it) } }
                s?.let { c.interruptJob(it); c.closeSession(it) }
                if (stop) runCatching { c.exec("stop", 2_000) }
            }.get(3, TimeUnit.SECONDS)
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "Arthas"
        private const val MAX_RESULTS = 200
        private const val MAX_LIVE_PROBES = 5
        private val COUNTED = setOf("watch", "trace", "stack", "tt", "monitor")
        private val LOG = logger<ArthasService>()

        fun getInstance(project: Project): ArthasService = project.service()
    }
}
