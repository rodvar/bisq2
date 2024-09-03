package bisq.gradle.packaging

import org.gradle.jvm.toolchain.JavaToolchainDownload
import org.gradle.jvm.toolchain.JavaToolchainRequest
import java.net.URI
import java.util.*

@Suppress("UnstableApiUsage")
class BisqJreUrlResolver {

    fun resolve(javaVersion: Int): Optional<URI> {
        val operatingSystem = getOperatingSystem()
        // the following is a trick to still use vendor-specific setup but with JRE bins
        println("$operatingSystem OS requesting version $javaVersion")
        val toolchainUrl: String = when (operatingSystem) {
            "LINUX" -> getToolchainUrlForLinux(javaVersion, "jre")
            "MAC_OS" -> getToolchainUrlForMacOs(javaVersion, "jre")
            "WINDOWS" -> getToolchainUrlForWindows(javaVersion, "jre")
            else -> null

        } ?: return Optional.empty()

        val uri = URI(toolchainUrl)
        return Optional.of(
                uri
        )
    }

    private fun getOperatingSystem(): String {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("win") -> "WINDOWS"
            osName.contains("mac") -> "MAC_OS"
            osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> "LINUX"
            else -> "UNKNOWN"
        }
    }

    private fun getToolchainUrlForLinux(javaVersion: Int, binType: String): String? =
            when (javaVersion) {
                11 -> "https://cdn.azul.com/zulu/bin/zulu11.66.15-ca-${binType}11.0.20-linux_x64.zip"
                17 -> "https://cdn.azul.com/zulu/bin/zulu17.44.15-ca-${binType}17.0.8-linux_x64.zip"
                22 -> "https://cdn.azul.com/zulu/bin/zulu22.30.13-ca-${binType}22.0.1-linux_x64.zip"
                else -> null
            }

    private fun getToolchainUrlForMacOs(javaVersion: Int, binType: String): String? {
        val osArch = System.getProperty("os.arch").lowercase(Locale.US)
        // Note, they use x64 not x86 (or x86_64)
        val macOsArchName = if (osArch.contains("aarch64")) "aarch64" else "x64"
        return when (javaVersion) {
            11 -> "https://cdn.azul.com/zulu/bin/zulu11.66.15_1-ca-${binType}11.0.20-macosx_" + macOsArchName + ".tar.gz"
            15 -> "https://cdn.azul.com/zulu/bin/zulu15.46.17-ca-${binType}15.0.10-macosx_" + macOsArchName + ".tar.gz"
            17 -> "https://cdn.azul.com/zulu/bin/zulu17.44.15_1-ca-${binType}17.0.8-macosx_" + macOsArchName + ".tar.gz"
            22 -> "https://cdn.azul.com/zulu/bin/zulu22.30.13-ca-${binType}22.0.1-macosx_" + macOsArchName + ".tar.gz"
            else -> null
        }
    }

    private fun getToolchainUrlForWindows(javaVersion: Int, binType: String): String? =
            when (javaVersion) {
                11 -> "https://cdn.azul.com/zulu/bin/zulu11.66.15-ca-${binType}11.0.20-win_x64.zip"
                17 -> "https://cdn.azul.com/zulu/bin/zulu17.44.15-ca-${binType}17.0.8-win_x64.zip"
                22 -> "https://cdn.azul.com/zulu/bin/zulu22.30.13-ca-${binType}22.0.1-win_x64.zip"
                else -> null
            }
}