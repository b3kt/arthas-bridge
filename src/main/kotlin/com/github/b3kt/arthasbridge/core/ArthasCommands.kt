package com.github.b3kt.arthasbridge.core

/** Builds Arthas command lines for a class / method picked in the editor. */
object ArthasCommands {

    /** Spring proxies are subclasses, so without this every call is reported twice. */
    private const val SKIP_PROXIES = "--exclude-class-pattern *CGLIB$$*"

    fun watch(className: String, method: String, depth: Int, limit: Int) =
        "watch $className $method '{params, returnObj, throwExp}' -x $depth -n $limit $SKIP_PROXIES"

    fun trace(className: String, method: String, limit: Int) =
        "trace $className $method -n $limit $SKIP_PROXIES"

    fun stack(className: String, method: String, limit: Int) =
        "stack $className $method -n $limit $SKIP_PROXIES"

    fun monitor(className: String, method: String, limit: Int) =
        "monitor -c 5 $className $method -n $limit $SKIP_PROXIES"

    fun timeTunnel(className: String, method: String, limit: Int) =
        "tt -t $className $method -n $limit $SKIP_PROXIES"

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
