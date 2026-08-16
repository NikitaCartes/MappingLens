package xyz.nikitacartes.mappinglens.config

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.HoconApplicationConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class StartupSettings(
    val host: String,
    val port: Int,
    val configPath: Path,
    val appConfig: AppConfig,
    val templateCreated: Boolean,
)

internal data class CliOverrides(
    val configPath: Path? = null,
    val host: String? = null,
    val port: Int? = null,
)

object RuntimeBootstrap {
    private const val defaultConfigFileName = "application.conf"
    private const val bundledResourceName = "mappinglens-defaults.conf"
    private const val defaultHost = "0.0.0.0"
    private const val defaultPort = 8080

    fun load(args: Array<String>): StartupSettings {
        val cli = parseCliArgs(args)
        val configPath = (cli.configPath ?: Paths.get(defaultConfigFileName)).toAbsolutePath().normalize()
        val templateCreated = ensureConfigFile(configPath)
        val applicationConfig = loadApplicationConfig(configPath)

        return StartupSettings(
            host = cli.host ?: resolveString(applicationConfig, "ktor.deployment.host") ?: defaultHost,
            port = cli.port ?: resolveInt(applicationConfig, "ktor.deployment.port") ?: defaultPort,
            configPath = configPath,
            appConfig = AppConfig.load(applicationConfig),
            templateCreated = templateCreated,
        )
    }

    internal fun ensureConfigFile(configPath: Path): Boolean {
        require(!Files.isDirectory(configPath)) { "Config path points to a directory: $configPath" }

        if (Files.exists(configPath)) {
            return false
        }

        configPath.parent?.let(Files::createDirectories)
        Files.writeString(configPath, bundledTemplate())
        return true
    }

    internal fun parseCliArgs(args: Array<String>): CliOverrides {
        var configPath: Path? = null
        var host: String? = null
        var port: Int? = null

        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when {
                arg == "-config" -> {
                    index += 1
                    configPath = Paths.get(nextValue(args, index, "-config"))
                }
                arg.startsWith("-config=") -> configPath = Paths.get(optionValue(arg, "-config"))
                arg == "-host" -> {
                    index += 1
                    host = nextValue(args, index, "-host")
                }
                arg.startsWith("-host=") -> host = optionValue(arg, "-host")
                arg == "-port" -> {
                    index += 1
                    port = parsePort(nextValue(args, index, "-port"))
                }
                arg.startsWith("-port=") -> port = parsePort(optionValue(arg, "-port"))
            }
            index += 1
        }

        return CliOverrides(configPath = configPath, host = host, port = port)
    }

    private fun loadApplicationConfig(configPath: Path): ApplicationConfig {
        val externalConfig = ConfigFactory.parseFile(configPath.toFile())
        val bundledDefaults = ConfigFactory.parseResources(bundledResourceName)
        return HoconApplicationConfig(externalConfig.withFallback(bundledDefaults).resolve())
    }

    private fun bundledTemplate(): String = checkNotNull(
        RuntimeBootstrap::class.java.classLoader.getResourceAsStream(bundledResourceName),
    ) { "Bundled configuration template '$bundledResourceName' not found" }
        .bufferedReader()
        .use { it.readText() }

    private fun resolveString(config: ApplicationConfig, key: String): String? =
        config.propertyOrNull(key)?.getString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun resolveInt(config: ApplicationConfig, key: String): Int? {
        val value = resolveString(config, key) ?: return null
        return value.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid integer value for $key: '$value'")
    }

    private fun optionValue(arg: String, option: String): String =
        arg.substringAfter('=', missingDelimiterValue = "").trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Missing value for $option")

    private fun nextValue(args: Array<String>, index: Int, option: String): String =
        args.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Missing value for $option")

    private fun parsePort(value: String): Int =
        value.toIntOrNull() ?: throw IllegalArgumentException("Invalid integer value for -port: '$value'")
}