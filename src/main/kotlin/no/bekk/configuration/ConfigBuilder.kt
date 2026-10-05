package no.bekk.configuration

import net.mamoe.yamlkt.YamlMap
import net.mamoe.yamlkt.toYamlElement
import no.bekk.configuration.decodeYamlFromFile
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.collections.emptyList
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.pathString

private val logger = LoggerFactory.getLogger("no.bekk.configuration.ConfigBuilder")

class ConfigBuilder {
    private lateinit var homePath: String

    private lateinit var configYaml: YamlConfig

    private val customInitPath = "conf/custom.yaml"
    private val configFiles = mutableListOf<String>()
    private val appliedEnvOverrides = mutableListOf<String>()

    private lateinit var pathsConfig: PathsConfig
    private lateinit var microsoftGraphConfig: MicrosoftGraphConfig
    private lateinit var oAuthConfig: OAuthConfig
    private lateinit var serverConfig: ServerConfig
    private lateinit var databaseConfig: DatabaseConfig
    private lateinit var answerHistoryConfig: AnswerHistoryCleanupConfig
    private lateinit var frontendDevServerConfig: FrontendDevServerConfig

    fun setHomePath(args: CommandLineArgs): ConfigBuilder {
        if (args.homePath != "") {
            homePath = args.homePath
            return this
        }

        try {
            homePath = Path("").absolutePathString()
        } catch (e: Exception) {
            System.exit(-1)
        }

        if (Path(homePath, "conf/defaults.yaml").exists()) {
            return this
        }

        if (Path(Path(homePath).parent.absolutePathString(), "conf/defaults.yaml").exists()) {
            homePath = Path(homePath).parent.absolutePathString()
            return this
        }

        return this
    }

    fun loadConfigurationFile(args: CommandLineArgs): ConfigBuilder {
        val defaultConfigFile = Path(homePath, "conf/defaults.yaml").absolutePathString()
        configFiles.add(defaultConfigFile)

        if (!Path(defaultConfigFile).exists()) {
            logger.error("Regelrett server Init Failed: Could not find config defaults, make sure homepath command line parameter is set or working directory is homepath")
            System.exit(1)
        }

        var parsedFile = decodeYamlFromFile(defaultConfigFile)

        // TODO: Implement commandline properties e.g. cfg:database_name=database

        parsedFile = try {
            loadSpecifiedConfigFile(args.configFile, parsedFile)
        } catch (e: Exception) {
            logger.error("Failed to load specified config file ${args.configFile}", e)
            System.exit(1)
            return this
        }

        parsedFile = try {
            applyEnvVariableOverrides(parsedFile)
        } catch (e: Exception) {
            logger.error("Failed to apply environment variable overrides to config file.", e)
            System.exit(1)
            return this
        }
        // TODO: Apply command line overrides
        // TODO: Init logging
        // TODO: Log config sources

        configYaml = YamlConfig(parsedFile)
        logConfigSources()

        return this
    }

    fun loadSpecifiedConfigFile(customConfigFile: String, defaultConfigYaml: YamlMap): YamlMap {
        var customFile = Path(customConfigFile)
        if (customConfigFile == "") {
            customFile = Path(homePath, customInitPath)
            if (!customFile.exists()) {
                return defaultConfigYaml
            }
        }

        val customConfigYaml = decodeYamlFromFile(customFile.pathString)

        return try {
            mergeConfigYaml(customConfigYaml, defaultConfigYaml).also {
                configFiles.add(customFile.absolutePathString())
            }
        } catch (e: Exception) {
            logger.error("Unable to merge $customConfigFile with default yaml:", e)
            throw e
        }
    }

    fun applyEnvVariableOverrides(yaml: YamlMap): YamlMap {
        val map = yaml.toMutableMap()

        for (sectionTuple in map) {
            val sectionProperties = (sectionTuple.value as? YamlMap)?.toMutableMap()
                ?: throw IllegalArgumentException("Invalid value for section '${sectionTuple.key}'. Expected YamlMap, found '${sectionTuple.value::class.qualifiedName?.split('.')?.last() ?: "null"}'")

            for (property in sectionProperties.keys) {
                val envKey = envKey(sectionTuple.key.toString(), property.toString())

                val envVal = System.getenv(envKey)
                if (envVal != null && envVal.isNotBlank()) {
                    sectionProperties.set(property, envVal.toYamlElement())
                    appliedEnvOverrides.add("$envKey=${redactedValue(envKey,envVal)}")
                }
            }
            map.set(sectionTuple.key, sectionProperties.toYamlElement())
        }
        return yaml.copy(map)
    }

    fun envKey(sectionName: String, keyName: String): String {
        val sN = sectionName
            .replace('.', '_')
            .uppercase()
            .replace('-', '_')

        val kN = keyName.replace('.', '_').uppercase()
        return "RR_${sN}_$kN"
    }

    fun redactedValue(key: String, value: String): String {
        if (value.isEmpty()) return ""

        val uppercased = key.uppercase()

        for (pattern in listOf(
            "PASSWORD",
            "SECRET",
            "PROVIDER_CONFIG",
            "PRIVATE_KEY",
            "SECRET_KEY",
            "CERTIFICATE",
            "ACCOUNT_KEY",
            "ENCRYPTION_KEY",
            "VAULT_TOKEN",
            "CLIENT_SECRET",
            "ENTERPRISE_LICENSE",
            "API_DB_PASS",
            "ID_FORWARDING_TOKEN$",
            "AUTHENTICATION_TOKEN$",
            "AUTH_TOKEN$",
            "RENDERER_TOKEN$",
            "API_TOKEN$",
            "ACCESS_TOKEN$",
            "WEBHOOK_TOKEN$",
            "INSTALL_TOKEN$",
        )) {
            if (Regex(pattern).containsMatchIn(uppercased)) return "********"
        }

        return value
    }

    fun logConfigSources() {
        for (file in configFiles) {
            logger.info("Config loaded from file: $file")
        }

        if (appliedEnvOverrides.isNotEmpty()) {
            logger.info("Environment variables used:")
            for (prop in appliedEnvOverrides) {
                logger.info("\tConfig overridden from Environment variable: $prop")
            }
        }

        logger.info("Path Home path: $homePath")
        logger.info("App mode ${configYaml.getStringOrNull("base", "mode")}")
    }

    fun buildMicrosoftGraphConfig(yaml: YamlConfig) = MicrosoftGraphConfig(
        baseUrl = yaml.getStringOrNull("microsoft_graph", "base_url") ?: "https://graph.microsoft.com",
        memberOfPath = yaml.getStringOrNull("microsoft_graph", "member_of_path") ?: "/v1.0/me/memberOf/microsoft.graph.group",
        groupFilter = yaml.getStringOrNull("microsoft_graph", "groupfilter") ?: "",
    )

    fun buildOAuthConfig(yaml: YamlConfig): OAuthConfig = OAuthConfig(
        baseUrl = yaml.getStringOrNull("oauth", "base_url") ?: "https://login.microsoftonline.com",
        tenantId = yaml.getString("oauth", "tenant_id"),
        issuerPath = yaml.getStringOrNull("oauth", "issuer_path") ?: "/v2.0",
        authPath = yaml.getStringOrNull("oauth", "auth_path") ?: "/oauth2/v2.0/authorize",
        tokenPath = yaml.getStringOrNull("oauth", "token_path") ?: "/oauth2/v2.0/token",
        jwksPath = yaml.getStringOrNull("oauth", "jwks_path") ?: "/discovery/v2.0/keys",
        clientId = yaml.getString("oauth", "client_id"),
        clientSecret = yaml.getString("oauth", "client_secret"),
        superUserGroup = yaml.getStringOrNull("oauth", "super_user_group") ?: "",
        reportingUserGroup = yaml.getStringOrNull("oauth", "reporting_user_group") ?: "",
        appReadRole = yaml.getStringOrNull("oauth", "app_read_role") ?: "",
    )

    fun buildServerConfig(yaml: YamlConfig): ServerConfig {
        val protocol = yaml.getStringOrNull("server", "protocol") ?: "http"
        val httpPort = yaml.getIntOrNull("server", "http_port") ?: 8080
        val domain = yaml.getStringOrNull("server", "domain") ?: "localhost"
        val appUrl = yaml.getStringOrNull("server", "root_url") ?: "$protocol://$domain:$httpPort"

        return ServerConfig(
            protocol = protocol,
            appUrl = appUrl,
            httpPort = httpPort,
            httpAddr = yaml.getStringOrNull("server", "http_addr") ?: "0.0.0.0",
            routerLogging = yaml.getBoolOrNull("server", "router_logging") ?: false,
            externalServiceTiming = yaml.getBoolOrNull("server", "external_service_timing") ?: false,
            allowedOrigins = yaml.getStringOrNull("server", "allowed_origins")?.split(",") ?: emptyList(),
            maxInitialLineLength = yaml.getIntOrNull("server", "max_initial_line_length") ?: 4096,
            maxHeaderSize = yaml.getIntOrNull("server", "max_header_size") ?: 8192,
            maxChunkSize = yaml.getIntOrNull("server", "max_chunk_size") ?: 8192,
        )
    }

    fun buildDatabaseConfig(yaml: YamlConfig): DatabaseConfig {
        val host = yaml.getStringOrNull("database", "host") ?: "127.0.0.1:5433"
        val name = yaml.getStringOrNull("database", "name") ?: "regelrett"
        val username = yaml.getStringOrNull("database", "user") ?: "postgres"
        val password = yaml.getStringOrNull("database", "password") ?: ""
        return DatabaseConfig(
            url = "jdbc:postgresql://$host/$name",
            username = username,
            password = password,
            migrationUsername = yaml.getStringOrNull("database", "migration_user") ?: username,
            migrationPassword = yaml.getStringOrNull("database", "migration_password") ?: password,
        )
    }

    fun buildAnswerHistoryConfig(yaml: YamlConfig): AnswerHistoryCleanupConfig = AnswerHistoryCleanupConfig(
        cleanupIntervalWeeks = yaml.getStringOrNull("answer_history_cleanup", "cleanup_interval_weeks") ?: "4",
    )

    fun buildPathsConfig(yaml: YamlConfig): PathsConfig {
        val path = Path(yaml.getStringOrNull("paths", "provisioning") ?: "conf/provisioning")
        return if (path.isAbsolute()) {
            PathsConfig(path.absolutePathString())
        } else {
            PathsConfig(Path(homePath, path.pathString).absolutePathString())
        }
    }

    fun builFrontendDevServerConfig(yaml: YamlConfig): FrontendDevServerConfig {
        val protocol = yaml.getStringOrNull("frontend_dev_server", "protocol") ?: "http"
        val host = yaml.getStringOrNull("frontend_dev_server", "host") ?: "localhost"
        val port = yaml.getIntOrNull("frontend_dev_server", "http_port") ?: 5173

        return FrontendDevServerConfig(
            httpPort = port,
            protocol = protocol,
            host = host,
            devUrl = "$protocol://$host:$port",
        )
    }

    fun build(): Config {
        microsoftGraphConfig = buildMicrosoftGraphConfig(configYaml)
        oAuthConfig = buildOAuthConfig(configYaml)
        pathsConfig = buildPathsConfig(configYaml)
        serverConfig = buildServerConfig(configYaml)
        databaseConfig = buildDatabaseConfig(configYaml)
        answerHistoryConfig = buildAnswerHistoryConfig(configYaml)
        frontendDevServerConfig = builFrontendDevServerConfig(configYaml)

        return Config(
            homePath = homePath,
            mode = configYaml.getStringOrNull("base", "mode") ?: "production",
            paths = pathsConfig,
            microsoftGraph = microsoftGraphConfig,
            oAuth = oAuthConfig,
            server = serverConfig,
            database = databaseConfig,
            answerHistoryCleanup = answerHistoryConfig,
            frontendDevServer = frontendDevServerConfig,
            raw = configYaml,
        )
    }
}

fun newConfigFromArgs(args: CommandLineArgs): Config {
    val builder = ConfigBuilder()

    try {
        return builder
            .setHomePath(args)
            .loadConfigurationFile(args)
            .build()
    } catch (e: Exception) {
        logger.error("Failed to setup application configuration.", e)
        System.exit(1)
        throw e
    }
}
