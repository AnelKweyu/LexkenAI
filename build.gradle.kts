// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kover) apply false
}

// Style and static analysis run the CLIs directly: no Gradle plugin has to keep up with
// AGP 9's built-in Kotlin, and both tasks are configuration-cache safe.
val ktlint: Configuration by configurations.creating
val detekt: Configuration by configurations.creating

dependencies {
    ktlint(libs.ktlint.cli) {
        attributes { attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL)) }
    }
    detekt(libs.detekt.cli)
}

val kotlinSources = fileTree("app/src") { include("**/*.kt", "**/*.kts") } + fileTree("tools") { include("**/*.kts") }

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Checks Kotlin style with ktlint (rules in .editorconfig)."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    inputs.files(kotlinSources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(".editorconfig")
    outputs.file(layout.buildDirectory.file("reports/ktlint/ktlint.txt"))
    args(
        "--relative",
        "--reporter=plain",
        "--reporter=plain,output=${layout.buildDirectory.file("reports/ktlint/ktlint.txt").get().asFile}",
        "app/src/**/*.kt",
        "app/build.gradle.kts",
        "build.gradle.kts",
        "settings.gradle.kts",
    )
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Rewrites Kotlin sources to the ktlint style."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args("--relative", "-F", "app/src/**/*.kt", "app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts")
}

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Static analysis with detekt (config/detekt/detekt.yml on top of the defaults)."
    classpath = detekt
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    inputs.files(kotlinSources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("config/detekt/detekt.yml")
    outputs.file(layout.buildDirectory.file("reports/detekt/detekt.txt"))
    args(
        "--input",
        "app/src/main/java,app/src/test/java",
        "--config",
        "config/detekt/detekt.yml",
        "--build-upon-default-config",
        "--report",
        "txt:${layout.buildDirectory.file("reports/detekt/detekt.txt").get().asFile}",
        "--report",
        "html:${layout.buildDirectory.file("reports/detekt/detekt.html").get().asFile}",
    )
}
