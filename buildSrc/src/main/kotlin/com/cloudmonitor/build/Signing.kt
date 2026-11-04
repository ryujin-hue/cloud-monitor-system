package com.cloudmonitor.build

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.util.Properties

/**
 * Signing material for release builds.
 *
 * Resolution order (first hit wins):
 *
 *  1. Environment variables — used by CI:
 *       KEYSTORE_BASE64   base64 of the keystore file
 *       KEYSTORE_PASSWORD keystore store password
 *       KEY_ALIAS         key alias inside the keystore
 *       KEY_PASSWORD      key password
 *
 *  2. `keystore.properties` in the repository root — used for local release builds.
 *       storeFile=/absolute/or/relative/path.jks
 *       storePassword=...
 *       keyAlias=...
 *       keyPassword=...
 *
 *  3. Nothing at all:
 *       * in CI  -> the build FAILS. Falling back to the debug key would produce an APK that
 *                    can never update an already published build, because debug keys differ
 *                    per machine.
 *       * locally -> the build falls back to the debug signing config and prints a loud
 *                    warning that the resulting APK is NOT updatable over CI builds.
 */
data class SigningMaterial(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

private const val KEYSTORE_PROPERTIES = "keystore.properties"

private val ENV_KEYSTORE_BASE64 = "KEYSTORE_BASE64"
private val ENV_KEYSTORE_PASSWORD = "KEYSTORE_PASSWORD"
private val ENV_KEY_ALIAS = "KEY_ALIAS"
private val ENV_KEY_PASSWORD = "KEY_PASSWORD"

fun Project.isRunningInCi(): Boolean =
    System.getenv("CI")?.equals("true", ignoreCase = true) == true ||
        System.getenv("GITHUB_ACTIONS")?.equals("true", ignoreCase = true) == true

/**
 * Resolve signing material, or `null` when none is configured (and we are not in CI).
 */
fun Project.resolveSigningMaterial(): SigningMaterial? {
    fromEnvironment()?.let { return it }
    return fromKeystoreProperties()
}

private fun Project.fromEnvironment(): SigningMaterial? {
    val base64 = System.getenv(ENV_KEYSTORE_BASE64)?.trim()
    if (base64.isNullOrEmpty()) return null

    val storePassword = System.getenv(ENV_KEYSTORE_PASSWORD)
    val keyAlias = System.getenv(ENV_KEY_ALIAS)
    val keyPassword = System.getenv(ENV_KEY_PASSWORD)

    val missing = listOf(
        ENV_KEYSTORE_PASSWORD to storePassword,
        ENV_KEY_ALIAS to keyAlias,
        ENV_KEY_PASSWORD to keyPassword,
    ).filter { it.second.isNullOrEmpty() }.map { it.first }

    if (missing.isNotEmpty()) {
        throw GradleException(
            "Incomplete signing configuration: $ENV_KEYSTORE_BASE64 is set but " +
                "${missing.joinToString(", ")} ${if (missing.size == 1) "is" else "are"} missing.",
        )
    }

    val bytes = try {
        java.util.Base64.getDecoder().decode(base64)
    } catch (e: IllegalArgumentException) {
        throw GradleException("$ENV_KEYSTORE_BASE64 is not valid base64: ${e.message}", e)
    }

    // The keystore never lives in the repository. It is materialised into the Gradle user
    // home so that it survives across builds without touching the source tree.
    val targetDir = File(gradle.gradleUserHomeDir, "cloud-monitor-keystores")
    targetDir.mkdirs()
    val target = File(targetDir, "cloud-monitor-release.jks")
    if (!target.exists() || target.length() != bytes.size.toLong()) {
        target.writeBytes(bytes)
    }
    // Best effort: keep the materialised keystore readable only by the build user.
    target.setReadable(false, false)
    target.setReadable(true, true)
    target.setWritable(false, false)
    target.setWritable(true, true)

    return SigningMaterial(
        storeFile = target,
        storePassword = storePassword!!,
        keyAlias = keyAlias!!,
        keyPassword = keyPassword!!,
    )
}

private fun Project.fromKeystoreProperties(): SigningMaterial? {
    val file = rootProject.file(KEYSTORE_PROPERTIES)
    if (!file.exists()) return null

    val properties = Properties().apply {
        file.inputStream().use { load(it) }
    }

    val storePath = properties.getProperty("storeFile")?.trim()
    if (storePath.isNullOrEmpty()) return null

    val resolved = if (File(storePath).isAbsolute) File(storePath) else rootProject.file(storePath)
    if (!resolved.exists()) {
        throw GradleException("$KEYSTORE_PROPERTIES points at a missing keystore: $resolved")
    }

    return SigningMaterial(
        storeFile = resolved,
        storePassword = properties.getProperty("storePassword")
            ?: throw GradleException("$KEYSTORE_PROPERTIES is missing 'storePassword'."),
        keyAlias = properties.getProperty("keyAlias")
            ?: throw GradleException("$KEYSTORE_PROPERTIES is missing 'keyAlias'."),
        keyPassword = properties.getProperty("keyPassword")
            ?: throw GradleException("$KEYSTORE_PROPERTIES is missing 'keyPassword'."),
    )
}

/**
 * Called from an app module's `build.gradle.kts`. Throws in CI when signing material is
 * missing, and warns loudly otherwise.
 */
fun Project.requireReleaseSigning(): SigningMaterial? {
    val material = resolveSigningMaterial()
    if (material != null) {
        logger.lifecycle("Release signing: using keystore ${material.storeFile.name} (alias ${material.keyAlias}).")
        return material
    }

    if (isRunningInCi()) {
        throw GradleException(
            """
            |Release signing material is missing in CI.
            |
            |Required GitHub Actions secrets:
            |  KEYSTORE_BASE64   (base64 of the release keystore)
            |  KEYSTORE_PASSWORD
            |  KEY_ALIAS
            |  KEY_PASSWORD
            |
            |The build deliberately fails here instead of falling back to the debug signing
            |key: debug keys are machine specific, so such an APK could never be installed
            |over an already published release, which would break in-place updates.
            """.trimMargin(),
        )
    }

    logger.warn(
        """
        |
        |############################################################################
        |#  WARNING: NO RELEASE SIGNING KEY FOUND
        |#
        |#  This build falls back to the DEBUG signing key.
        |#  The resulting APK is NOT updatable over APKs built by CI and must never be
        |#  distributed to real devices.
        |#
        |#  Run ./scripts/setup-signing.sh to create a release keystore, or export
        |#  KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD.
        |############################################################################
        |
        """.trimMargin(),
    )
    return null
}
