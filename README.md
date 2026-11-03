# Arthas Bridge (IntelliJ plugin)

Attach [Alibaba Arthas](https://arthas.aliyun.com) to a running JVM and drive it from IntelliJ IDEA: watch, trace and decompile code from the editor without restarting the app or adding logs.

## Use it

1. Open the **Arthas** tool window (bottom bar).
2. Pick your app in the **JVM** list. JVMs whose main class is in the open project are listed first. Press **Attach**.
   - The app already runs in a pod? Run `kubectl port-forward pod/<name> 8563:8563`, then click **Connect to address…**
3. Right-click a method in the editor and choose **Arthas**:

   | Menu item | Arthas command | Answers |
   |---|---|---|
   | Watch Params / Return | `watch C m '{params, returnObj, throwExp}' -x 2 -n 5` | What goes in and out? |
   | Trace | `trace C m -n 5` | Where is the time spent? |
   | Stack | `stack C m -n 5` | Who calls this? |
   | Monitor | `monitor -c 5 C m -n 5` | How many calls fail, and what is the average time? |
   | Record Calls (Time Tunnel) | `tt -t C m -n 5` | What happened in past calls? Replay them with `tt -i <index>` |
   | Decompile Loaded Class | `jad C` | Is my code really deployed? The source opens in an editor tab. |
   | Class Info | `sc -d C` | Which jar and class loader loaded the class? |
   | Hot-Swap Compiled Class… | `retransform <build output>` | Push a fixed method body without restarting the app. |

   If no JVM is attached yet, the command is placed in the input box so you can attach and then press **Run**.
4. Type any other Arthas command in the input box at the bottom. Use ↑/↓ for history. The **Quick Commands** (⚡) menu has dashboard, busy threads, deadlock check, memory and the profiler (the flame graph opens in your browser).
5. Press **Stop Arthas** when you're done. The app keeps running and all enhanced classes are restored. **Disconnect** leaves Arthas running in the target.

## Output styles

Toggle with the 👁 button in the tool window toolbar, or in **Settings | Tools | Arthas Bridge | Output**.

- **Readable summary** (default). Watch, Record Calls, Trace, Stack and Monitor results are rewritten in plain language:
  - Inputs are labelled with the real parameter names from your code.
  - Objects are shown field by field, and empty fields are hidden.
  - A `Mono`/`Flux` result is explained instead of dumped.
  - Errors are shortened to one line with the location, e.g. `IllegalArgumentException: … (at MathGame.primeFactors line 46)`.
  - Trace lists the 3 slowest steps above the full call tree.
  - Stack hides framework frames (Spring, Reactor, Netty, JDK).
- **Arthas terminal format.** Exactly what the Arthas console prints.

Commands created from the right-click menu add `--exclude-class-pattern *CGLIB$$*`, so calls through a Spring proxy aren't reported twice.

## Behavior

- **One job at a time.** Starting a new command interrupts the running one. The ■ button stops it.
- **Result limits.** Streaming commands use `-n` from Settings (default 5). The plugin also stops a job after 200 results.
- **Masking.** Card-like numbers (13–19 digits that pass a Luhn check) are shown as `411111******1111`. Other PII, such as names, emails and phone numbers, is **not** masked.
- **Ports.** HTTP uses port 8563 by default. Telnet is off (`0`), because the plugin only needs HTTP. If the port is already used by Arthas attached to the same JVM, the plugin reuses that Arthas. If it is attached to a different JVM, attach fails with a clear message.
- **Safety.** Only localhost connections are allowed unless you enable remote connections in Settings. Arthas is stopped when the project closes (configurable). Don't attach to production JVMs.

Settings are under **Settings | Tools | Arthas Bridge**. They cover the arthas-boot.jar path, ports, `-x`/`-n` defaults, output style and font size, masking, remote opt-in and auth.

arthas-boot.jar is found in this order:
1. The path set in Settings.
2. A jar the plugin downloaded earlier to `~/.arthas-bridge/`.
3. `~/.arthas/lib/*/arthas/arthas-boot.jar`.

If none exists, a notification offers to download version 4.3.2 from Maven Central.

## Build

Needs JDK 21. The build compiles against the IDE path in `gradle.properties` (`localIdePath`), so it doesn't download an SDK. Delete that line to download `platformType`/`platformVersion` instead.

```bash
JAVA_HOME=~/.sdkman/candidates/java/21.0.10-tem ./gradlew buildPlugin
```

To install, use **Settings | Plugins | ⚙ | Install Plugin from Disk…** and select `build/distributions/arthas-idea-bridge-0.3.0.zip`.

```bash
./gradlew runIde
```

`runIde` opens a sandbox IDE with the plugin installed.

### Tests

Unit tests use real Arthas 4.3.2 payloads. The live tests are skipped unless you set their environment variables:

```bash
cd ~/Programs/arthas && java -jar math-game.jar &
```

```bash
ARTHAS_ATTACH_PID=<math-game pid> ARTHAS_BOOT_JAR=~/Programs/arthas/arthas-boot.jar ./gradlew test
```

`ArthasLiveTest` also needs `ARTHAS_LIVE_PORT`, the HTTP port of an Arthas that is already attached to math-game.

## Layout

```
core/      ArthasHttpClient (HTTP API), ArthasAttacher (arthas-boot), ArthasService (per-project session, jobs, health check),
           ResultRenderer (JSON → text), SensitiveDataMasker, JvmProcesses, ArthasCommands, ArthasBootLocator
ui/        Tool window: JVM picker, console, command bar, quick commands
actions/   Editor / Project view "Arthas" menu (Java + Kotlin via UAST), hot-swap
settings/  Persistent settings + Settings page
```
# arthas-bridge
