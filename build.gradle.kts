plugins {
    id("com.android.application") version "9.3.2" apply false
    id("com.android.library") version "9.3.2" apply false
    id("com.android.kotlin.multiplatform.library") version "9.3.2" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.compose") version "1.12.0" apply false
}

subprojects {
    tasks.withType<Test>().configureEach {
        if (project.name == "app" || project.name == "host-applemusic") {
            // Structural regressions read source text directly, including other modules.
            inputs.files(rootProject.fileTree(rootDir) {
                include(rootProject.subprojects.map { "${it.name}/src/**" })
                include("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "*/build.gradle.kts")
            }).withPropertyName("sourceContracts").withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
}
