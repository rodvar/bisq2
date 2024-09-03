package bisq.gradle.toolchain_resolver

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.jvm.toolchain.*
import javax.inject.Inject

@Suppress("UnstableApiUsage")
class ToolchainResolverPlugin @Inject constructor(private val toolchainResolverRegistry: JavaToolchainResolverRegistry) : Plugin<Settings> {

    override fun apply(settings: Settings) {
        println("Using jdk toolchain resolver")
        settings.plugins.apply("jvm-toolchain-management")
        toolchainResolverRegistry.register(BisqToolchainResolver::class.java)
    }
}
@Suppress("UnstableApiUsage")
class ToolchainJreResolverPlugin @Inject constructor(private val toolchainResolverRegistry: JavaToolchainResolverRegistry) : Plugin<Settings> {

    override fun apply(settings: Settings) {
        println("Using jre toolchain resolver")
        settings.plugins.apply("jvm-toolchain-management")
        toolchainResolverRegistry.register(BisqJreToolchainResolver::class.java)
    }
}
