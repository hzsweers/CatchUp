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
  id(deps.ksp.pluginId)
}

dependencies {
  ksp(deps.android.androidx.room.xerial)
  ksp(deps.android.androidx.room.apt)

  implementation(deps.kotlin.coroutinesAndroid)
  implementation(deps.kotlin.coroutinesRx)
  implementation(deps.kotlin.datetime)

  api(project(":service-registry:service-registry-annotations"))
  api(project(":libraries:appconfig"))
  api(project(":libraries:retrofitconverters"))
  api(project(":libraries:gemoji"))
  api(deps.android.androidx.room.runtime)
  api(deps.android.androidx.annotations)
  api(deps.android.androidx.coreKtx)
  api(deps.android.androidx.fragment)
  api(deps.dagger.runtime)
  api(deps.kotlin.coroutines)
  api(deps.rx.java)
  api(deps.okhttp.core)
  api(deps.retrofit.core)
  api(deps.retrofit.rxJava2)
  api(deps.apollo.runtime)
}
