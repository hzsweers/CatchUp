/*
 * Copyright (c) 2019 Zac Sweers
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

apply(plugin = "dagger.hilt.android.plugin")

android {
  defaultConfig {
    buildConfigField("String", "SMMRY_API_KEY",
        "\"${properties["catchup_smmry_api_key"]}\"")
  }
  buildFeatures {
    buildConfig = true
  }
}

kapt {
  arguments {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
  }
}

dependencies {
  kapt(deps.dagger.hilt.apt.compiler)
  kapt(deps.dagger.apt.compiler)
  kapt(deps.moshi.sealed.compiler)
  compileOnly(deps.misc.javaxInject)
  implementation(deps.dagger.runtime)
  implementation(deps.dagger.hilt.android)
  implementation(deps.retrofit.core)
  implementation(deps.retrofit.moshi)
  implementation(project(":libraries:base-ui"))
  implementation(project(":libraries:retrofitconverters"))
  implementation(project(":libraries:util"))
  implementation(deps.misc.lottie)
  kapt(deps.moshi.compiler)
  implementation(deps.moshi.adapters)
  implementation(deps.moshi.core)
  implementation(deps.moshi.sealed.annotations)
  implementation(deps.android.androidx.room.runtime)
  implementation(deps.android.androidx.room.ktx)
  kapt(deps.android.androidx.room.apt)
  implementation(deps.android.androidx.lifecycle.ktx)
  implementation(deps.kotlin.coroutines)
  implementation(deps.kotlin.stdlib.jdk7)

  api(project(":service-api"))
  api(deps.android.androidx.annotations)
  api(deps.android.androidx.appCompat)
  api(deps.android.androidx.core)
  api(deps.android.androidx.design)
}
