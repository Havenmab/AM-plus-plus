plugins { id("com.android.library") }
android {
    namespace = "dev.amenhancer.plugin.api"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
tasks.register<Copy>("exportSdk") {
    dependsOn("bundleReleaseAar")
    from(zipTree(layout.buildDirectory.file("outputs/aar/plugin-api-release.aar"))) { include("classes.jar"); rename("classes.jar", "ampp-plugin-api-v1.jar") }
    into(layout.buildDirectory.dir("sdk"))
}
