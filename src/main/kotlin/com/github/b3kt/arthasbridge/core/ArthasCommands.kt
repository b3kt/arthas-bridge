package com.github.b3kt.arthasbridge.core

/** How a probe started from the editor should run: watch depth, result limit, and an optional condition. */
data class ProbeOptions(val depth: Int, val limit: Int, val condition: String? = null)

/** Builds Arthas command lines for a class / method picked in the editor. */
object ArthasCommands {

    /** Spring proxies are subclasses, so without this every call is reported twice. */
    private const val SKIP_PROXIES = "--exclude-class-pattern *CGLIB$$*"

    fun watch(className: String, method: String, depth: Int, limit: Int, condition: String? = null) =
        "watch $className $method '{params, returnObj, throwExp}'${cond(condition)} -x $depth -n $limit $SKIP_PROXIES"

    fun trace(className: String, method: String, limit: Int, condition: String? = null) =
        "trace $className $method${cond(condition)} -n $limit $SKIP_PROXIES"

    fun stack(className: String, method: String, limit: Int, condition: String? = null) =
        "stack $className $method${cond(condition)} -n $limit $SKIP_PROXIES"

    fun monitor(className: String, method: String, limit: Int, condition: String? = null) =
        "monitor -c 5 $className $method${cond(condition)} -n $limit $SKIP_PROXIES"

    fun timeTunnel(className: String, method: String, limit: Int, condition: String? = null) =
        "tt -t $className $method${cond(condition)} -n $limit $SKIP_PROXIES"

    /**
     * An Arthas condition from an OGNL expression and/or a minimum duration (`#cost` is in ms);
     * null when neither is given.
     */
    fun condition(expression: String?, slowerThanMs: Int?): String? {
        val expr = expression?.trim()?.takeIf { it.isNotEmpty() }
        val cost = slowerThanMs?.let { "#cost > $it" }
        return when {
            expr != null && cost != null -> "($expr) && $cost"
            else -> expr ?: cost
        }
    }

    private fun cond(condition: String?): String {
        val c = condition?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        return if ('\'' in c) " \"$c\"" else " '$c'"
    }

    fun jad(className: String) = "jad $className"

    fun classInfo(className: String) = "sc -d $className"

    fun retransform(classFiles: List<String>) = "retransform " + classFiles.joinToString(" ")

    /** Commands offered in the tool window "Quick commands" menu: label to command line. */
    val QUICK = listOf(
        "Dashboard snapshot" to "dashboard -n 1",
        "Top 5 busiest threads" to "thread -n 5",
        "Find deadlock / blocking thread" to "thread -b",
        "JVM info" to "jvm",
        "Memory usage" to "memory",
        "Logger levels" to "logger",
        "Active tt records" to "tt -l",
        "Start CPU profiler" to "profiler start",
        "Stop profiler (HTML flame graph)" to "profiler stop --format html",
    )

    /**
     * Turns the args of a `java` command line into a readable name:
     * the main class, `-jar foo.jar`, or `-m module/Main`.
     */
    fun mainName(args: List<String>): String {
        val withValue = setOf("-cp", "-classpath", "--class-path", "-p", "--module-path",
            "--add-opens", "--add-exports", "--add-modules", "--add-reads", "--patch-module", "--upgrade-module-path")
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-jar" -> return args.getOrNull(i + 1)?.substringAfterLast('/') ?: "java -jar"
                arg == "-m" || arg == "--module" -> return args.getOrNull(i + 1) ?: "java -m"
                arg in withValue -> i++
                arg.startsWith("-") -> Unit
                else -> return arg
            }
            i++
        }
        return "java"
    }
}
