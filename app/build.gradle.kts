plugins { id("com.android.application") }
android {
    namespace = "com.youmu.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.youmu.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST") }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("youmu.live", project.findProperty("liveAudio") ?: "false") }
    }
}
dependencies {
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // Robolectric's bytecode reader needs Java 25 support for Android Studio's JBR.
    listOf("asm", "asm-commons", "asm-tree", "asm-util", "asm-analysis").forEach {
        testImplementation("org.ow2.asm:$it:9.8")
    }
}
