plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val buildTsnetAndroid by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("bash", "scripts/build-tsnet-android.sh", layout.buildDirectory.dir("generated/tsnet-jniLibs").get().asFile.absolutePath)
    inputs.files(rootProject.fileTree("tailscale") { include("go.mod", "go.sum", "**/*.go") })
    inputs.file(rootProject.file("scripts/build-tsnet-android.sh"))
    outputs.dir(layout.buildDirectory.dir("generated/tsnet-jniLibs"))
}

val dshAndroidAbis = System.getenv("DSH_ANDROID_ABIS")
    ?.split(',')
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.distinct()
    ?.takeIf { it.isNotEmpty() }
    ?: listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val dshVersionName = System.getenv("DSH_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
val dshVersionCode = dshVersionName.substringBefore('-').split('.').mapNotNull(String::toIntOrNull).let { parts ->
    parts.getOrElse(0) { 0 } * 10_000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
}.coerceAtLeast(1)

val releaseKeystoreFile = System.getenv("DSH_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val releaseKeystorePassword = System.getenv("DSH_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("DSH_KEY_ALIAS")
val releaseKeyPassword = System.getenv("DSH_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "dev.dsh.mobile.mesh"
    compileSdk = 35
    ndkVersion = "27.1.12297006"

    defaultConfig {
        // Keep the reference source/JNI namespace stable while installing as a distinct app.
        applicationId = "dev.dsh.mobile.mesh"
        minSdk = 26
        targetSdk = 35
        ndk { abiFilters += dshAndroidAbis }
        versionCode = dshVersionCode
        versionName = dshVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        externalNativeBuild { cmake { arguments += "-DTSNET_LIB_DIR=${layout.buildDirectory.dir("generated/tsnet-jniLibs").get().asFile.absolutePath}" } }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.create("environmentRelease") {
                    storeFile = file(requireNotNull(releaseKeystoreFile))
                    storePassword = releaseKeystorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                }
            } else {
                // Keep local/review builds installable without putting a private key in Git.
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    externalNativeBuild { cmake { path = file("CMakeLists.txt"); version = "3.30.5" } }
    sourceSets {
        getByName("main").java.srcDir("../third_party/libzt/src/bindings/java")
        getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/tsnet-jniLibs"))
    }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
    lint { error += listOf("MissingTranslation", "ImpliedQuantity") }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.sshj)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":mock-harness"))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.named("preBuild").configure { dependsOn(buildTsnetAndroid) }
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("externalNativeBuild") }.configureEach { dependsOn(buildTsnetAndroid) }
