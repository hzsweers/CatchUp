import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

repositories {
  mavenCentral()
  google()
  maven("https://storage.googleapis.com/r8-releases/raw")
}

plugins {
  kotlin("jvm") version "1.5.21"
  `kotlin-dsl`
  `java-gradle-plugin`
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(16))
  }
}

/**
 * These are magic shared versions that are used in both buildSrc's build file and dependencies.kt.
 * These are copied as a source into the main source set and templated for replacement.
 */
object SharedBuildVersions {
  const val agp = "7.1.0-alpha04"
  const val kotlin = "1.5.30-M1"
  const val moshi = "1.12.0"
  const val okio = "3.0.0-alpha.8"
  const val kotlinJvmTarget = "11"
  val kotlinCompilerArgs = listOf(
      "-progressive",
      "-Xjsr305=strict",
      "-Xopt-in=kotlin.contracts.ExperimentalContracts",
      "-Xopt-in=kotlin.experimental.ExperimentalTypeInference",
      "-Xopt-in=kotlin.ExperimentalStdlibApi",
      "-Xopt-in=kotlin.RequiresOptIn",
      "-Xopt-in=kotlin.time.ExperimentalTime",
//      "-Xopt-in=kotlinx.coroutines.FlowPreview",
//      "-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
      // Potentially useful for static analysis tools or annotation processors.
      "-Xemit-jvm-type-annotations",
      // Match JVM assertion behavior: https://publicobject.com/2019/11/18/kotlins-assert-is-not-like-javas-assert/
      "-Xassertions=jvm",
      "-Xproper-ieee754-comparisons",
      // Generate nullability assertions for non-null Java expressions
      "-Xstrict-java-nullability-assertions",
      // Enable new jvmdefault behavior
      // https://blog.jetbrains.com/kotlin/2020/07/kotlin-1-4-m3-generating-default-methods-in-interfaces/
      "-Xjvm-default=all"
  )

  fun asTemplatesMap(): Map<String, String> {
    return mapOf(
        "agpVersion" to agp,
        "kotlinVersion" to kotlin,
        "moshiVersion" to moshi,
        "okioVersion" to okio,
        "kotlinCompilerArgs" to kotlinCompilerArgs.joinToString(", ") { "\"$it\"" },
        "kotlinJvmTarget" to kotlinJvmTarget
    )
  }
}

gradlePlugin {
  plugins {
    create("CatchUpPlugin") {
      id = "catchup"
      implementationClass = "dev.zacsweers.catchup.gradle.CatchUpPlugin"
    }
    create("LicensesJsonGeneratorPlugin") {
      id = "licensesJsonGenerator"
      implementationClass = "dev.zacsweers.catchup.gradle.LicensesJsonGeneratorPlugin"
    }
  }
}

sourceSets {
  main.configure {
    java.srcDir(project.file("$buildDir/generated/sources/version-templates/kotlin/main"))
  }
}

val copyVersionTemplatesProvider = tasks.register<Copy>("copyVersionTemplates") {
  val templatesMap = SharedBuildVersions.asTemplatesMap()
  inputs.property("buildversions", templatesMap.hashCode())
  from(layout.projectDirectory.dir("version-templates"))
  into(project.layout.buildDirectory.dir("generated/sources/version-templates/kotlin/main"))
  expand(templatesMap)
  filteringCharset = "UTF-8"
}

tasks.withType<KotlinCompile>().configureEach {
  dependsOn(copyVersionTemplatesProvider)
  kotlinOptions {
    @Suppress("SuspiciousCollectionReassignment")
    freeCompilerArgs += SharedBuildVersions.kotlinCompilerArgs
    jvmTarget = SharedBuildVersions.kotlinJvmTarget
  }
}

dependencies {
  // Explicitly declare all the kotlin bits to avoid mismatched versions
  implementation(kotlin("gradle-plugin", version = SharedBuildVersions.kotlin))
  implementation(kotlin("stdlib", version = SharedBuildVersions.kotlin))
  implementation(kotlin("stdlib-common", version = SharedBuildVersions.kotlin))
  implementation(kotlin("stdlib-jdk7", version = SharedBuildVersions.kotlin))
  implementation(kotlin("stdlib-jdk8", version = SharedBuildVersions.kotlin))
  implementation(kotlin("reflect", version = SharedBuildVersions.kotlin))

  implementation("org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.2.1")
  implementation("com.android.tools.build:gradle:${SharedBuildVersions.agp}")
  implementation("com.squareup.moshi:moshi:${SharedBuildVersions.moshi}")
  implementation("com.squareup.okio:okio:${SharedBuildVersions.okio}")
  implementation("de.undercouch:gradle-download-task:4.1.1")
  implementation("com.squareup.anvil:gradle-plugin:2.3.3")
}
