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

plugins {
  id("com.android.library")
  kotlin("android")
  kotlin("kapt")
}

apply {
  from(rootProject.file("gradle/config-kotlin-sources.gradle"))
}

kapt {
  arguments {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
  }
}

dependencies {
  implementation(project(":libraries:util"))

  kapt(deps.android.androidx.room.apt)
  kapt(deps.dagger.apt.compiler)
  compileOnly(deps.misc.jsr250)

  api(deps.android.androidx.room.runtime)
  api(deps.dagger.runtime)
  api(deps.kotlin.stdlib.jdk7)
  api(deps.moshi.core)

  testImplementation(deps.test.junit)
  testImplementation(deps.test.truth)
}
