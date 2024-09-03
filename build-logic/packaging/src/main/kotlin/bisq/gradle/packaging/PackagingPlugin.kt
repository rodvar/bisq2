package bisq.gradle.packaging

import bisq.gradle.common.OS
import bisq.gradle.common.getOS
import bisq.gradle.common.getPlatform
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RelativePath
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmImplementation
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.register
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import javax.inject.Inject

class PackagingPlugin @Inject constructor(private val javaToolchainService: JavaToolchainService) : Plugin<Project> {
    private val bisqJreToolchainResolver: BisqJreUrlResolver = BisqJreUrlResolver()

    companion object {
        const val OUTPUT_DIR_PATH = "packaging/jpackage/packages"
    }

    override fun apply(project: Project) {
        val extension = project.extensions.create<PackagingPluginExtension>("packaging")

        val installDistTask: TaskProvider<Sync> = project.tasks.named("installDist", Sync::class.java)

        val generateHashesTask = project.tasks.register<Sha256HashTask>("generateHashes") {
            inputDirFile.set(installDistTask.map { File(it.destinationDir, "lib") })
            outputFile.set(getHashFileForOs(project, extension))
        }

        val jarTask: TaskProvider<Jar> = project.tasks.named("jar", Jar::class.java)

        val javaApplicationExtension = project.extensions.findByType<JavaApplication>()
        checkNotNull(javaApplicationExtension) { "Can't find JavaApplication extension." }

        project.tasks.register("prepareJreDirectory") {
            doLast {
                val jreDirectory = project.layout.buildDirectory.dir("jre").get().asFile
                if (!jreDirectory.exists()) {
                    jreDirectory.mkdirs()
                }
            }
        }

        project.tasks.register<JPackageTask>("generateInstallers") {
            group = "distribution"
            description = "Generate the installer or the platform the project is running"

            dependsOn("prepareJreDirectory")

            val jreDownloadUri = bisqJreToolchainResolver.resolve(getJavaLanguageVersion(extension).get().asInt())
            val jreDirectoryProvider = project.providers.provider {
                project.layout.buildDirectory.dir("jre").get().asFile
            }
            this.jreDirectoryProvider.set(jreDirectoryProvider)
            this.runtimeImageDirectoryProvider.set(jreDirectoryProvider)

            doFirst {
                fun setPermissions(directory: File) {
                    val permissions = PosixFilePermissions.fromString("rwxr-xr-x")
                    Files.walk(directory.toPath()).forEach { path ->
                        Files.setPosixFilePermissions(path, permissions)
                    }
                }
                val jreDirectory = jreDirectoryProvider.get()
                jreDirectory.mkdirs()

                if (jreDownloadUri.isPresent) {
                    val isZip = jreDownloadUri.get().toString().endsWith(".zip")
                    val jreFile = project.file("${jreDirectory.absolutePath}/jre.${if (isZip) "zip" else "tar.gz"}")

                    project.logger.lifecycle("Downloading JRE from $jreDownloadUri")
                    jreDownloadUri.get().toURL().openStream().use { input ->
                        jreFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    setPermissions(jreDirectory)
                    project.logger.lifecycle("JRE dir permissions checked")
                    project.logger.lifecycle("Extracting JRE to $jreDirectory")
                    if (isZip) {
                        project.copy {
                            from(project.zipTree(jreFile))
                            into(jreDirectory)
                            eachFile {
                                val blacklistDirs = listOf("legal", "server")
                                val segments = this.relativePath.segments
                                if (segments.size > 1) {
                                    this.relativePath = RelativePath(this.relativePath.isFile, *segments.drop(1).toTypedArray())
                                }
                                if (!blacklistDirs.any { this.relativePath.toString().lowercase().contains(it) }) {
                                    try {
                                        project.logger.lifecycle("Copy ${this.relativePath}")
                                        val file = this.relativePath.getFile(jreDirectory)
                                        this.copyTo(file)
                                    } catch (e: Exception) {
                                        project.logger.error("Failed to copy file ${this.path}: ${e.message}")
                                    }
                                }
                            }
                            includeEmptyDirs = false
                        }
                    } else {
                        project.copy {
                            from(project.tarTree(jreFile))
                            into(jreDirectory)
                            eachFile {
                                val blacklistDirs = listOf("legal", "server")
                                val segments = this.relativePath.segments
                                if (segments.size > 1) {
                                    this.relativePath = RelativePath(this.relativePath.isFile, *segments.drop(1).toTypedArray())
                                }
                                if (!blacklistDirs.any { this.relativePath.toString().lowercase().contains(it) }) {
                                    try {
                                        project.logger.lifecycle("Copy ${this.relativePath}")
                                        val file = this.relativePath.getFile(jreDirectory)
                                        this.copyTo(file)
                                    } catch (e: Exception) {
                                        project.logger.error("Failed to copy file ${this.path}: ${e.message}")
                                    }
                                }
                            }
                            includeEmptyDirs = false
                        }
                    }

                    jreFile.delete()
                    project.logger.lifecycle("JRE unpacked")
                } else {
                    throw GradleException("Failed to resolve JRE download URL")
                }

//                jdkDirectory.set(jreDirectory)
//                runtimeImageDirectory.set(jreDirectory)
            }

            val webcamProject = project.parent?.childProjects?.filter { e -> e.key == "webcam-app" }?.map { e -> e.value.project }?.first()
            webcamProject?.let { webcam ->
                val desktopProject = project.parent?.childProjects?.filter { e -> e.key == "desktop" }?.map { e -> e.value.project }?.first()
                desktopProject?.let { desktop ->
                    val processResourcesInDesktop = desktop.tasks.named("processResources")
                    val processWebcamForDesktopProvider = webcam.tasks.named("processWebcamForDesktop")
                    processResourcesInDesktop.get().dependsOn(processWebcamForDesktopProvider)
                    dependsOn(processWebcamForDesktopProvider)
                }
            }

            dependsOn(generateHashesTask)

            val jdkDirectory = getJPackageJdkDirectory(extension)
            // still needed because jre does not have jpackage
            this.jdkDirectory.set(jdkDirectory)

            distDirFile.set(installDistTask.map { it.destinationDir })
            mainJarFile.set(jarTask.flatMap { it.archiveFile })

            mainClassName.set(javaApplicationExtension.mainClass)
            jvmArgs.set(javaApplicationExtension.applicationDefaultJvmArgs)

            val licenseFileProvider: Provider<File> = extension.name.map { name ->
                val licenseDir = if (name == "Bisq") project.projectDir.parentFile
                else project.projectDir.parentFile.parentFile.parentFile
                return@map File(licenseDir, "LICENSE")
            }
            licenseFile.set(licenseFileProvider)

            appName.set(extension.name)
            appVersion.set(extension.version)

            val packageResourcesDirFile = File(project.projectDir, "package")
            packageResourcesDir.set(packageResourcesDirFile)

//            runtimeImageDirectory.set(jreDirectory)

            outputDirectory.set(project.layout.buildDirectory.dir("packaging/jpackage/packages"))
        }

        val releaseBinariesTaskFactory = ReleaseBinariesTaskFactory(project)
        releaseBinariesTaskFactory.registerCopyReleaseBinariesTask()
        releaseBinariesTaskFactory.registerCopyMaintainerPublicKeysTask()
        releaseBinariesTaskFactory.registerCopySigningPublicKeyTask()
        releaseBinariesTaskFactory.registerMergeOsSpecificJarHashesTask(extension.version)
    }

    private fun getHashFileForOs(project: Project, extension: PackagingPluginExtension): Provider<RegularFile> {
        val platformName = getPlatform().platformName
        return extension.version.flatMap { version ->
            project.layout.buildDirectory.file("$OUTPUT_DIR_PATH/Bisq-$version-$platformName-all-jars.sha256")
        }
    }

    private fun getJPackageJdkDirectory(extension: PackagingPluginExtension): Provider<Directory> {
        val launcherProvider = javaToolchainService.launcherFor {
            languageVersion.set(getJavaLanguageVersion(extension))
            vendor.set(JvmVendorSpec.AZUL)
            implementation.set(JvmImplementation.VENDOR_SPECIFIC)
        }
        return launcherProvider.map { it.metadata.installationPath }
    }

    private fun getJavaLanguageVersion(extension: PackagingPluginExtension): Provider<JavaLanguageVersion> {
        val javaVersion = extension.name.map { appName ->
            if (appName == "Bisq") {
                // Bisq1
                if (getOS() == OS.MAC_OS) {
                    15
                } else {
                    17
                }
            } else {
                // Bisq2
                22
            }
        }
        return javaVersion.map { JavaLanguageVersion.of(it) }
    }
}
