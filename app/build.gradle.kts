plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

android {
  namespace = "com.example"
  // 37, not 36.1: material3 1.5.0-alpha25 (Expressive — see the dependency note below) declares an
  // AAR metadata floor of API 37, and the build fails checkDebugAarMetadata below it. targetSdk
  // stays at 36 deliberately — compiling against 37 doesn't opt this app into 37's behaviour
  // changes, and that's a separate decision from getting the Expressive components.
  compileSdk { version = release(37) }

  defaultConfig {
    applicationId = "com.github.hyphen05.fuse"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Release keystore/env vars are only available in local/CI signing setups (e.g. not
  // on F-Droid's build server), so the release signingConfig is only wired up when the
  // keystore is actually present — otherwise the build produces an unsigned release APK
  // instead of failing.
  val releaseKeystoreFile = file(System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks")

  signingConfigs {
    if (releaseKeystoreFile.exists()) {
      create("release") {
        storeFile = releaseKeystoreFile
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = if (releaseKeystoreFile.exists()) {
        signingConfigs.getByName("release")
      } else {
        // Without the upload key a release build comes out unsigned and cannot be installed, so
        // there was no way to put a realistic build on a phone from a dev machine -- and debug is
        // not a fair test of anything that depends on frame timing (no R8, no baseline profile,
        // debuggable=true). Falling back to the debug key keeps release installable for on-device
        // checks. It is the same signer as the debug build, so `install -r` keeps app data.
        //
        // This APK is NOT distributable: it carries the public debug key. The real signingConfig is
        // still used whenever the upload key is present, so CI and any signing setup are unchanged.
        signingConfigs.getByName("debugConfig")
      }
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// AmbianceVideoBench reads film frames from a directory outside the repo (they are ~150MB). Gradle
// does not forward -D to the test JVM on its own, so hand this one through; the bench skips when it
// is unset, which is every ordinary run.
tasks.withType<Test>().configureEach {
  listOf("ambiance.frames", "ambiance.traces").forEach { key ->
    System.getProperty(key)?.let { systemProperty(key, it) }
  }
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  // Explicit rather than transitive-through-camera-view: CalibrationRecorder binds VideoCapture
  // directly, so the dependency is real and should be declared where it is used.
  implementation(libs.androidx.camera.video)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  // Pinned past the BOM (which carries 1.4.0) for Material 3 Expressive: LoadingIndicator,
  // MaterialShapes and ButtonGroup only exist from the 1.5.0 alpha line onward. 1.4.1 and a stable
  // 1.5.0 do not exist — don't "fix" this pin by dropping back to a release version.
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.play.services.location)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
