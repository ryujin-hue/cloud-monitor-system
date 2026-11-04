/*
 * Root build script for the cloud-monitor-system monorepo.
 *
 * There is deliberately no `plugins { ... apply false }` block and no `subprojects { }`
 * configuration: AGP 9 removed the legacy "apply to all subprojects" pattern and it is
 * error-prone anyway. Each module declares the plugins it needs.
 *
 * The auto-versioning lives in settings.gradle.kts and is consumed through `gradle.extra`.
 */

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
