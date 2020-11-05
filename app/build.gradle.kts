/*
 * Copyright (c) 2018 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
  id("com.android.application")
  kotlin("android")
  kotlin("kapt")
  id("com.apollographql.apollo")
  id("licensesJsonGenerator")
//  id("com.bugsnag.android.gradle")
//  id("com.github.triplet.play")
}

apply(plugin = "dagger.hilt.android.plugin")

apply {
  from(rootProject.file("gradle/config-kotlin-sources.gradle"))
}

val useDebugSigning: Boolean = providers.gradleProperty("useDebugSigning")
    .forUseAtConfigurationTime()
    .orElse("false")
    .map { it.toBoolean() }
    .get()

android {
  defaultConfig {
    applicationId = "io.sweers.catchup"

    // These are for debug only. Release versioning is set by CatchUpPlugin
    versionCode = 1
    versionName = "1.0"

    the<BasePluginConvention>().archivesBaseName = "catchup"

    buildConfigField("String", "GITHUB_DEVELOPER_TOKEN",
        "\"${properties["catchup_github_developer_token"]}\"")
    resValue("string", "changelog_text", "\"${getChangelog()}\"")
    manifestPlaceholders(mapOf("BUGSNAG_API_KEY" to "placeholder"))
  }
  buildFeatures {
    buildConfig = true
    compose = true
  }
  signingConfigs {
    if (!useDebugSigning && rootProject.file("signing/app-release.jks").exists()) {
      create("release") {
        keyAlias = "catchupkey"
        storeFile = rootProject.file("signing/app-release.jks")
        storePassword = properties["catchup_signing_store_password"].toString()
        keyPassword = properties["catchup_signing_key_password"].toString()
      }
    } else {
      create("release").initWith(getByName("debug"))
    }
  }
  packagingOptions {
    exclude("**/*.dot")
    exclude("**/*.kotlin_metadata")
    exclude("**/*.properties")
    exclude("*.properties")
    exclude("kotlin/**")
    exclude("LICENSE.txt")
    exclude("LICENSE_OFL")
    exclude("LICENSE_UNICODE")
    exclude("META-INF/*.kotlin_module")
    exclude("META-INF/*.version")
    exclude("META-INF/androidx.*")
    exclude("META-INF/CHANGES")
    exclude("META-INF/com.uber.crumb/**")
    exclude("META-INF/LICENSE")
    exclude("META-INF/LICENSE.txt")
    exclude("META-INF/NOTICE")
    exclude("META-INF/NOTICE.txt")
    exclude("META-INF/README.md")
    exclude("META-INF/rxjava.properties")
    exclude("META-INF/services/javax.annotation.processing.Processor")
  }
  buildTypes {
    getByName("debug") {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-dev"
      buildConfigField("String", "IMGUR_CLIENT_ACCESS_TOKEN",
          "\"${project.properties["catchup_imgur_access_token"]}\"")
    }
    getByName("release") {
      buildConfigField("String", "BUGSNAG_KEY",
          "\"${properties["catchup_bugsnag_key"]}\"")
      manifestPlaceholders(mapOf("BUGSNAG_API_KEY" to properties["catchup_bugsnag_key"].toString()))
      signingConfig = signingConfigs.getByName(if (useDebugSigning) "debug" else "release")
      postprocessing.apply {
        proguardFiles("proguard-rules.pro")
        isOptimizeCode = true
        isObfuscate = true
        isRemoveUnusedCode = true
        isRemoveUnusedResources = true
      }
    }
  }
  dexOptions {
    javaMaxHeapSize = "2g"
  }
  splits {
    density {
      isEnable = true
      reset()
      include("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
    }
    abi {
      isEnable = true
      reset()
      include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
      isUniversalApk = true
    }
  }
  composeOptions {
    kotlinCompilerVersion = deps.versions.kotlin
    kotlinCompilerExtensionVersion = deps.android.androidx.compose.version
  }
  afterEvaluate {
    val firebaseVariants = setOf("release", "debug")
    applicationVariants.forEach { variant ->
      // Configure firebase
      fun firebaseProperty(property: String, resolveName: Boolean = true) {
        val buildTypeName = variant.buildType.name
        if (buildTypeName in firebaseVariants) {
          val name = if (resolveName && buildTypeName == "debug") {
            "$property.debug"
          } else property
          val value = project.properties[name].toString()
          variant.resValue("string", property.removePrefix("catchup."), value)
        } else {
          return
        }
      }
      firebaseProperty("catchup.google_api_key")
      firebaseProperty("catchup.google_app_id")
      firebaseProperty("catchup.firebase_database_url")
      firebaseProperty("catchup.ga_trackingId")
      firebaseProperty("catchup.gcm_defaultSenderId")
      firebaseProperty("catchup.google_storage_bucket")
      firebaseProperty("catchup.default_web_client_id")
      firebaseProperty("catchup.google_crash_reporting_api_key")
      firebaseProperty("catchup.project_id", false)
    }
  }
}

//bugsnag {
//  // Prevent bugsnag from wiring build UUIDs into debug builds
//  variantFilter {
//    setEnabled("debug" !in name.toLowerCase(Locale.US))
//  }
//}

kapt {
  arguments {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("dagger.experimentalDaggerErrorMessages", "enabled")
  }
}

//play {
//  track = "alpha"
//  serviceAccountEmail = properties["catchup_play_publisher_account"].toString()
//  serviceAccountCredentials = rootProject.file("signing/play-account.p12")
//}

apollo {
  service("github") {
    @Suppress("UnstableApiUsage")
    customTypeMapping.set(mapOf(
        "DateTime" to "kotlinx.datetime.Instant",
        "URI" to "okhttp3.HttpUrl"
    ))
    generateKotlinModels.set(true)
    rootPackageName.set("io.sweers.catchup.data.github")
    schemaFile.set(file("src/main/graphql/io/sweers/catchup/data/github/schema.json"))
  }
}

open class CutChangelogTask : DefaultTask() {

  @get:Input
  lateinit var versionName: String

  @TaskAction
  fun run() {
    val changelog = project.rootProject.file("CHANGELOG.md")

    val whatsNewPath = "${project.projectDir}/src/main/play/release-notes/en-US/default.txt"
    val newChangelog = getChangelog(changelog, "").let {
      if (it.length > 500) {
        logger.log(LogLevel.WARN,
            "Changelog length (${it.length}) exceeds 500 char max. Truncating...")
        val warning = "\n(Truncated due to store restrictions. Full changelog in app!)"
        val warningLength = warning.length
        val remainingAmount = 500 - warningLength
        val builder = StringBuilder()
        for (line in it.lineSequence()) {
          if (builder.length + line.length + 1 < remainingAmount) {
            builder.appendln(line)
          } else {
            break
          }
        }
        builder.append(warning).toString()
      } else it
    }
    if (newChangelog.isNotEmpty()) {
      project.file(whatsNewPath).writer().use {
        it.write(newChangelog)
      }
    }

    val currentContent = changelog.reader().readText()
    changelog.writer().use {
      it.apply {
        write("\n\n## $versionName (${SimpleDateFormat("yyyy-MM-dd").format(Date())})\n")
        append(currentContent)
      }
    }
  }

  private fun getChangelog(changelog: File, defaultIfEmpty: String): String {
    val log = StringBuilder()
    changelog.reader().useLines { lines ->
      val iterator = lines.iterator()
      while (iterator.hasNext()) {
        val line = iterator.next()
        if (line.startsWith("#")) {
          break
        } else if (line.isNotEmpty()) {
          log.append(line).append("\n")
        }
      }
    }

    val finalLog = log.toString().trim()
    return if (finalLog.isEmpty()) defaultIfEmpty else finalLog
  }
}

tasks.register("cutChangelog", CutChangelogTask::class.java) {
  versionName = deps.build.gitTag(project)
  group = "build"
  description = "Cuts the current changelog version and updates the play store changelog file"
}

fun getChangelog(): String {
  // Only return a changelog if we're publishing a release
  if (!hasProperty("includeChangelog")) {
    return ""
  }

  val changelog = rootProject.file("CHANGELOG.md")

  val log = StringBuilder()
  changelog.reader().useLines { lines ->
    val iterator = lines.iterator()
    var seenChanges = false
    var headerCount = 0
    while (iterator.hasNext()) {
      val line = iterator.next()
      if (line.startsWith("#")) {
        // We might see the first header from the last cut here. If so, skip it till the next
        if (seenChanges || headerCount > 0) {
          break
        } else {
          headerCount++
        }
      } else if (line.isNotEmpty()) {
        seenChanges = true
        log.append(line).append("\n")
      }
    }
  }
  return log.toString().trim()
}

open class UpdateVersion : DefaultTask() {

  @set:Option(option = "updateType",
      description = "Configures the version update type. Can be (major|minor|patch).")
  @get:Input
  lateinit var type: String

  @TaskAction
  fun run() {
    val workingDir = project.rootProject.projectDir
    val latestTag = "git describe --abbrev=0 --tags".execute(workingDir, "dev")
    if (latestTag == "dev") {
      throw IllegalStateException("No recent tag found!")
    }
    var (major, minor, patch) = latestTag.split(".").map(String::toInt)
    when (type) {
      "major" -> {
        major++
        minor = 0
        patch = 0
      }
      "minor" -> {
        minor++
        patch = 0
      }
      "patch" -> {
        patch++
      }
      else -> {
        throw IllegalArgumentException("Unrecognized updateType \"$type\"")
      }
    }
    val latestVersionString = "$major.$minor.$patch"
    println("Updating version to $latestVersionString")
    ByteArrayOutputStream().use { os ->
      project.rootProject.exec {
        commandLine("git",
            "tag",
            "-a", latestVersionString,
            "-m", "\"Version $latestVersionString.\"")
        standardOutput = os
        errorOutput = os
      }
      val newTag = "git describe --abbrev=0 --tags".execute(workingDir, "dev")
      if (newTag == latestTag) {
        throw AssertionError("Git tag didn't work! ${os.toString().trim()}")
      }
    }
  }
}

tasks.create("updateVersion", UpdateVersion::class.java) {
  group = "build"
  description = "Updates the current version. Supports CLI option --updateType={type} where type is (major|minor|patch)"
}

dependencies {
  kapt(project(":libraries:tooling:spi-visualizer"))

  implementation(deps.markwon.core)
  implementation(deps.markwon.extStrikethrough)
  implementation(deps.markwon.extTables)
  implementation(deps.markwon.extTasklist)
  implementation(deps.markwon.html)
  implementation(deps.markwon.image)
  implementation(deps.markwon.imageCoil)
  implementation(deps.markwon.linkify)
//  implementation(deps.markwon.syntaxHighlight) // https://github.com/noties/Markwon/issues/148
  implementation(project(":service-api"))
  implementation(project(":service-registry:service-registry"))
  implementation(project(":libraries:base-ui"))
  implementation(project(":libraries:appconfig"))
  implementation(project(":libraries:gemoji"))
  implementation(project(":libraries:kotlinutil"))
  implementation(project(":libraries:smmry"))
  implementation(project(":libraries:util"))
  implementation(project(":libraries:flowbinding"))
  implementation(deps.misc.ticktock)

  // Support libs
  implementation(deps.android.androidx.annotations)
  implementation(deps.android.androidx.appCompat)
  implementation(deps.android.androidx.core)
  implementation(deps.android.androidx.constraintLayout)
  implementation(deps.android.androidx.customTabs)
  implementation(deps.android.androidx.design)
  implementation(deps.android.androidx.emoji)
  implementation(deps.android.androidx.emojiAppcompat)
  implementation(deps.android.androidx.fragment)
  implementation(deps.android.androidx.fragmentKtx)
  debugImplementation(deps.android.androidx.drawerLayout)
  implementation(deps.android.androidx.paging.runtime)
  implementation(deps.android.androidx.preference)
  implementation(deps.android.androidx.preferenceKtx)
  implementation(deps.android.androidx.viewPager2)
  implementation(deps.android.androidx.swipeRefresh)

  // Arch components
  implementation(deps.android.androidx.lifecycle.extensions)
  kapt(deps.android.androidx.lifecycle.apt)
  implementation(deps.android.androidx.room.runtime)
  implementation(deps.android.androidx.room.rxJava2)
  implementation(deps.android.androidx.room.ktx)
  kapt(deps.android.androidx.room.apt)

  // Compose
  implementation(project(":libraries:compose-extensions"))
  implementation(deps.android.androidx.compose.uiTooling)
  implementation(deps.android.androidx.compose.foundation)
  implementation(deps.android.androidx.compose.material)

  // Kotlin
  implementation(deps.android.androidx.coreKtx)
  implementation(deps.kotlin.stdlib.jdk7)
  implementation(deps.kotlin.coroutines)
  implementation(deps.kotlin.coroutinesAndroid)
  implementation(deps.kotlin.coroutinesRx)
  implementation(deps.android.androidx.lifecycle.ktx)

  // Moshi
  kapt(deps.moshi.compiler)
  implementation(deps.moshi.core)
  implementation(deps.moshi.shimo)

  // Firebase
  implementation(deps.android.firebase.core)
  implementation(deps.android.firebase.database)

  // Square/JW
  implementation(deps.okhttp.core)
  implementation(deps.misc.okio)
  implementation(deps.retrofit.core)
  implementation(deps.retrofit.moshi)
  implementation(deps.retrofit.rxJava2)
  implementation(deps.rx.android)
  implementation(deps.rx.java)
  implementation(deps.misc.tapTargetView)
  implementation(deps.misc.timber)
  implementation(deps.misc.debug.processPhoenix)
  debugImplementation(deps.misc.debug.madge)
  debugImplementation(deps.misc.debug.scalpel)
  debugImplementation(deps.misc.debug.telescope)
  debugImplementation(deps.okhttp.debug.loggingInterceptor)
  debugImplementation(deps.retrofit.debug.mock)

  releaseImplementation(deps.misc.bugsnag)

  // Coil
  implementation(deps.coil.base)
  implementation(deps.coil.default)
  implementation(deps.coil.gif)

  // Misc
  implementation(deps.autoDispose.core)
  implementation(deps.autoDispose.android)
  implementation(deps.autoDispose.lifecycle)
  implementation(deps.misc.byteunits)
  implementation(deps.misc.flick)
  implementation(deps.misc.gestureViews)
  implementation(deps.misc.inboxRecyclerView)
  implementation(deps.misc.lottie)
  implementation(deps.misc.recyclerViewAnimators)
  implementation(deps.rx.relay)
  implementation(deps.rx.dogTag)
  implementation(deps.rx.dogTagAutoDispose)
  implementation(deps.misc.moshiLazyAdapters)
  implementation(deps.autoDispose.androidArch)
  implementation(deps.misc.kotpref)
  implementation(deps.misc.kotprefEnum)
  implementation(deps.kotlin.datetime)

  // Apollo
  implementation(deps.apollo.androidSupport)
  implementation(deps.apollo.httpcache)
  implementation(deps.apollo.runtime)
  implementation(deps.apollo.rx2Support)

  // Flipper
  debugImplementation(deps.misc.debug.flipper)
  debugImplementation(deps.misc.debug.flipperNetwork)
  debugImplementation(deps.misc.debug.soLoader)

  // To force a newer version that doesn't conflict ListenableFuture
  debugImplementation(deps.misc.debug.guava)

  // Hyperion
//  releaseImplementation(deps.hyperion.core.release)
//  debugImplementation(deps.hyperion.core.debug)
//  debugImplementation(deps.hyperion.plugins.appInfo)
//  debugImplementation(deps.hyperion.plugins.attr)
//  debugImplementation(deps.hyperion.plugins.chuck)
//  debugImplementation(deps.hyperion.plugins.crash)
//  debugImplementation(deps.hyperion.plugins.disk)
//  debugImplementation(deps.hyperion.plugins.geigerCounter)
//  debugImplementation(deps.hyperion.plugins.measurement)
//  debugImplementation(deps.hyperion.plugins.phoenix)
//  debugImplementation(deps.hyperion.plugins.recorder)
//  debugImplementation(deps.hyperion.plugins.sharedPreferences)
//  debugImplementation(deps.hyperion.plugins.timber)

  // Dagger
  kapt(deps.dagger.hilt.apt.compiler)
  kapt(deps.dagger.apt.compiler)
  compileOnly(deps.misc.javaxInject)
  implementation(deps.dagger.runtime)
  implementation(deps.dagger.hilt.android)

  // Inspector exposed for dagger
  implementation(deps.inspector.core)

  implementation(deps.misc.jsr305)

  // Test
  testImplementation(deps.rx.relay)
  androidTestImplementation(deps.rx.java)
  androidTestImplementation(deps.misc.jsr305)
  testImplementation(deps.misc.jsr305)
  testImplementation(deps.test.junit)
  testImplementation(deps.test.truth)

  // LeakCanary
  debugImplementation(deps.misc.leakCanary)
  releaseImplementation(deps.misc.leakCanaryObjectWatcherAndroid)

  // Chuck
//  debugImplementation(deps.chuck.debug)
//  releaseImplementation(deps.chuck.release)
}
