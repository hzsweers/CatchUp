/*
 * Copyright (C) 2020. Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.sweers.catchup.ui.fragments.service.v2

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.paging.PagingData
import io.sweers.catchup.service.api.ServiceMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class ServiceViewModel(
  private val repository: CatchUpItemRepository,
  private val savedStateHandle: SavedStateHandle,
  private val serviceKey: String
) : ViewModel() {
  companion object {
    const val ARG_SERVICE_KEY = "serviceKey"
  }

  init {
    if (!savedStateHandle.contains(ARG_SERVICE_KEY)) {
      savedStateHandle.set(ARG_SERVICE_KEY, serviceKey)
    }
  }

  private val clearListFlow = MutableStateFlow(Unit)

  val serviceMeta: ServiceMeta get() = repository.serviceMetaFor(serviceKey)

  val posts = flowOf(
    clearListFlow.map { PagingData.empty() },
    savedStateHandle.getLiveData<String>(ARG_SERVICE_KEY)
      .asFlow()
      .flatMapLatest { repository.postsOfService(it, 30) }
  ).flattenMerge(2)

  fun shouldShowService(
    serviceId: String
  ) = savedStateHandle.get<String>(ARG_SERVICE_KEY) != serviceId

  fun showService(serviceId: String) {
    if (!shouldShowService(serviceId)) return

    clearListFlow.value = Unit

    savedStateHandle.set(ARG_SERVICE_KEY, serviceId)
  }
}
