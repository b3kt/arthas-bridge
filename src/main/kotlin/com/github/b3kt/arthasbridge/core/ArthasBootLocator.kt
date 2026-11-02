package com.github.b3kt.arthasbridge.core

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests
import com.github.b3kt.arthasbridge.settings.ArthasSettings
import java.io.File

/** Finds arthas-boot.jar, or downloads a pinned version from Maven Central. */
object ArthasBootLocator {

    const val PINNED_VERSION = "4.3.2"
    private const val CENTRAL = "https://repo1.maven.org/maven2/com/taobao/arthas/arthas-boot"

    private val home = File(System.getProperty("user.home"))
    private val downloadDir = File(home, ".arthas-bridge")

    fun find(): File? {
        ArthasSettings.getInstance().state.arthasBootPath
            ?.takeIf { it.isNotBlank() }
            ?.let { return File(it).takeIf(File::isFile) }

        File(downloadDir, "arthas-boot-$PINNED_VERSION.jar").takeIf(File::isFile)?.let { return it }

        // Layout created by arthas-boot itself: ~/.arthas/lib/<version>/arthas/arthas-boot.jar
        return File(home, ".arthas/lib").listFiles()
            ?.sortedByDescending { it.name }
            ?.map { File(it, "arthas/arthas-boot.jar") }
            ?.firstOrNull(File::isFile)
    }

    fun download(indicator: ProgressIndicator): File {
        downloadDir.mkdirs()
        val target = File(downloadDir, "arthas-boot-$PINNED_VERSION.jar")
        HttpRequests.request("$CENTRAL/$PINNED_VERSION/arthas-boot-$PINNED_VERSION.jar")
            .saveToFile(target, indicator)
        return target
    }
}
