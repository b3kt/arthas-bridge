package com.github.b3kt.arthasbridge.core

import java.io.File

data class JvmProcess(val pid: Long, val name: String, val javaBin: String?) {
    override fun toString() = "$pid  $name"
}

/** Lists local JVMs owned by the current user, without needing jps or the Attach API. */
object JvmProcesses {

    private val TOOLING = listOf(
        "org.gradle.", "GradleDaemon", "GradleWorkerMain", "KotlinCompileDaemon", "org.jetbrains.",
        "com.intellij.", "org.codehaus.plexus.classworlds.launcher", "arthas-boot", "arthas-client",
        "sun.tools.jps", "jdk.jcmd", "RemoteMavenServer", "org.sonarsource.",
    )

    fun list(includeTooling: Boolean = false): List<JvmProcess> {
        val self = ProcessHandle.current().pid()
        return ProcessHandle.allProcesses()
            .filter { it.pid() != self && it.isAlive }
            .map { ph ->
                val info = ph.info()
                val command = info.command().orElse(null) ?: return@map null
                val exe = File(command).name
                if (exe != "java" && exe != "java.exe") return@map null
                val args = info.arguments().map { it.toList() }.orElse(emptyList())
                JvmProcess(ph.pid(), ArthasCommands.mainName(args), command)
            }
            .toList()
            .filterNotNull()
            .filter { includeTooling || TOOLING.none { t -> it.name.contains(t) } }
            .sortedByDescending { it.pid }
    }

    fun isAlive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
}
