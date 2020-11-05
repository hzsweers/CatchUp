import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

repositories {
  mavenCentral()
  google()
  maven("https://kotlin.bintray.com/kotlinx/")
  maven("https://dl.bintray.com/kotlin/kotlin-eap")
  maven("https://dl.bintray.com/kotlin/kotlin-dev")
  maven("https://storage.googleapis.com/r8-releases/raw")
  jcenter()
}

plugins {
  kotlin("jvm") version "1.4.10"
  `kotlin-dsl`
  `java-gradle-plugin`
}

kotlinDslPluginOptions {
  experimentalWarning.set(false)
}

/**
 * These are magic shared versions that are used in both buildSrc's build file and dependencies.kt.
 * These are copied as a source into the main source set and templated for replacement.
 */
object SharedBuildVersions {
  const val agp = "4.2.0-alpha15"
  const val kotlin = "1.4.10"
  const val moshi = "1.11.0"
  const val okio = "2.9.0"
  const val kotlinJvmTarget = "1.8"
  val kotlinCompilerArgs = listOf(
      "-progressive",
      "-Xinline-classes",
      "-Xjsr305=strict",
      "-Xallow-jvm-ir-dependencies",
      "-Xskip-prerelease-check",
      "-Xopt-in=kotlin.contracts.ExperimentalContracts",
      "-Xopt-in=kotlin.experimental.ExperimentalTypeInference",
      "-Xopt-in=kotlin.ExperimentalStdlibApi",
      "-Xopt-in=kotlin.RequiresOptIn",
      "-Xopt-in=kotlin.time.ExperimentalTime",
      "-Xopt-in=kotlinx.coroutines.FlowPreview",
      "-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
      "-Xopt-in=androidx.paging.ExperimentalPagingApi",
      // New type inference front end. Matches IDE behavior, allows new behaviors and should also be a perf improvement.
      // Was originally intended to be teh default in 1.4, but TBD.
      "-Xnew-inference",
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
  implementation("com.android.tools:r8:2.1.67")

  // Explicitly declare all the kotlin bits to avoid mismatched versions
  implementation(kotlin("gradle-plugin", version = SharedBuildVersions.kotlin))
  implementation(kotlin("stdlib", version = SharedBuildVersions.kotlin))
  implementation(kotlin("stdlib-common", version = SharedBuildVersions.kotlin))
  implementation(kotlin("stdlib-jdk7", version = SharedBuildVersions.kotlin))
  implementation(kotlin("stdlib-jdk8", version = SharedBuildVersions.kotlin))
  implementation(kotlin("reflect", version = SharedBuildVersions.kotlin))

  implementation("org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.1.0")
  implementation("com.android.tools.build:gradle:${SharedBuildVersions.agp}")
  implementation("com.squareup.moshi:moshi:${SharedBuildVersions.moshi}")
  implementation("com.squareup.okio:okio:${SharedBuildVersions.okio}")
  implementation("de.undercouch:gradle-download-task:4.1.1")
}
