/*
 * cloud-monitor-system — monorepo settings.
 *
 * Auto-versioning lives here (see "AUTO VERSIONING" below) so that it is computed exactly
 * ONCE per build and consumed by BOTH applications through `gradle.extra`.
 */

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// ---------------------------------------------------------------------------
// AUTO VERSIONING (single source of truth)
// ---------------------------------------------------------------------------
// version.properties holds MAJOR.MINOR.
//   versionCode = `git rev-list --count HEAD`   (monotonic, requires fetch-depth: 0 in CI)
//   versionName = MAJOR.MINOR.<commit count>
//
// History of `main` is never rewritten, so the commit count only ever grows and the
// resulting versionCode is therefore always >= the previously published one.
//
// The same computation is mirrored in `scripts/version.sh` for shell/CI use and in
// `scripts/verify-update.sh` for the in-place-update proof.
//
// NOTE: this runs in the settings script so that it is available to every project
// (including convention-plugin-free module build scripts) via `gradle.extra`.
val versionProps = java.util.Properties()
val versionPropsFile = file("version.properties")
if (versionPropsFile.exists()) {
    versionPropsFile.inputStream().use { versionProps.load(it) }
}
val versionMajor: String = versionProps.getProperty("MAJOR", "1").trim()
val versionMinor: String = versionProps.getProperty("MINOR", "0").trim()
val fallbackVersionCode: Int = versionProps.getProperty("FALLBACK_VERSION_CODE", "1").trim().toInt()

val commitCount: Int = runCatching {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && output.isNotEmpty()) {
        output.toInt()
    } else {
        error("`git rev-list --count HEAD` failed: $output")
    }
}.getOrElse { error ->
    // Not a git checkout (source zip, vendored copy, ...). We refuse to silently ship a
    // versionCode of 1 in CI, because that would break in-place updates over a previous build.
    val isCi = System.getenv("CI")?.equals("true", ignoreCase = true) == true
    if (isCi) {
        throw GradleException(
            "Unable to compute versionCode from git history in CI ($error). " +
                "Check out the repository with `fetch-depth: 0` so that `git rev-list --count HEAD` works.",
        )
    }
    logger.lifecycle(
        "WARNING: could not compute versionCode from git history ($error). " +
            "Falling back to FALLBACK_VERSION_CODE=$fallbackVersionCode from version.properties. " +
            "APKs built this way are NOT guaranteed to update in place over CI builds.",
    )
    fallbackVersionCode
}

gradle.extra["appVersionCode"] = commitCount
gradle.extra["appVersionName"] = "$versionMajor.$versionMinor.$commitCount"
gradle.extra["appVersionMajor"] = versionMajor
gradle.extra["appVersionMinor"] = versionMinor

logger.lifecycle("cloud-monitor-system version: $versionMajor.$versionMinor.$commitCount (versionCode=$commitCount)")

rootProject.name = "cloud-monitor-system"

include(":shared")
include(":app-monitor")
include(":app-alert")
