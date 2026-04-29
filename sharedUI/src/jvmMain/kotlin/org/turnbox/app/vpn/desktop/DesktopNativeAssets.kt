package org.turnbox.app.vpn.desktop

import org.turnbox.app.desktop.DesktopOs
import org.turnbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.exists

internal object DesktopNativeAssets {
    private const val DEFAULT_OLCRTC_REPO = "/Users/alexanderanisimov/Personal/Projects/olcrtc"

    fun resolveOlcRtcBinary(): Path {
        val fileName = olcRtcFileName()
        return resolveBinary(
            fileName = fileName,
            resourceName = "native/$fileName",
            candidates = olcRtcSourceCandidates(fileName)
        )
    }

    fun resolveHevSocks5TunnelBinary(): Path {
        val fileName = hevSocks5TunnelFileName()
        return resolveBinary(
            fileName = fileName,
            resourceName = "native/$fileName",
            candidates = hevSocks5TunnelSourceCandidates(fileName)
        )
    }

    private fun resolveBinary(
        fileName: String,
        resourceName: String,
        candidates: List<Path>
    ): Path {
        val target = DesktopPaths.appDataDir().resolve("bin").resolve(fileName)
        Files.createDirectories(target.parent)

        val resource = javaClass.classLoader.getResourceAsStream(resourceName)
        if (resource != null) {
            resource.use {
                Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
            }
            makeExecutable(target)
            return target
        }

        candidates.firstOrNull { it.exists() }?.let {
            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
            makeExecutable(target)
            return target
        }

        error("Bundled native binary is missing: $resourceName")
    }

    fun olcRtcFileName(): String {
        return when (DesktopPaths.os) {
            DesktopOs.MacOS -> "olcrtc-darwin-arm64"
            DesktopOs.Windows -> "olcrtc-windows-amd64.exe"
            DesktopOs.Linux -> "olcrtc-linux-${desktopArch()}"
            DesktopOs.Other -> error("Turnbox desktop supports macOS, Windows and Linux")
        }
    }

    fun hevSocks5TunnelFileName(): String {
        return when (DesktopPaths.os) {
            DesktopOs.Linux -> "hev-socks5-tunnel-linux-${desktopArch()}"
            else -> error("hev-socks5-tunnel desktop binary is only used on Linux")
        }
    }

    private fun desktopArch(): String {
        return when (DesktopPaths.arch) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> error("Unsupported desktop architecture: ${DesktopPaths.arch}")
        }
    }

    private fun olcRtcSourceCandidates(fileName: String): List<Path> {
        val explicitBinary = System.getenv("OLCRTC_BINARY")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        val repo = System.getenv("OLCRTC_REPO")?.takeIf { it.isNotBlank() } ?: DEFAULT_OLCRTC_REPO
        return listOfNotNull(
            explicitBinary,
            Path(repo).resolve("build").resolve(fileName),
            Path(repo).resolve(fileName.removeSuffix(".exe")),
            Path(repo).resolve("olcrtc")
        )
    }

    private fun hevSocks5TunnelSourceCandidates(fileName: String): List<Path> {
        val explicitBinary = System.getenv("HEV_SOCKS5_TUNNEL_BINARY")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        return listOfNotNull(
            explicitBinary,
            Path("androidApp").resolve("src").resolve("main").resolve("jni").resolve("hev-socks5-tunnel")
                .resolve("bin").resolve("hev-socks5-tunnel"),
            Path("desktopApp").resolve("build").resolve("generated").resolve("desktopNativeResources")
                .resolve("native").resolve(fileName)
        )
    }

    private fun makeExecutable(path: Path) {
        if (DesktopPaths.os != DesktopOs.Windows) {
            path.toFile().setExecutable(true, true)
        }
    }
}
