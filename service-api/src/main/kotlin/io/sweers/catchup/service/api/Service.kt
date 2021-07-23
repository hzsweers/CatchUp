/*
 * Copyright (C) 2019. Zac Sweers
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
package io.sweers.catchup.service.api

import io.reactivex.Single
import kotlinx.coroutines.channels.SendChannel

interface Service {
  fun meta(): ServiceMeta
  fun fetchPage(request: DataRequest): Single<DataResult>
  fun bindItemView(
    item: CatchUpItem,
    holder: BindableCatchUpItemViewHolder,
    clicksReceiver: (UrlMeta) -> Boolean,
    markClicksReceiver: (UrlMeta) -> Boolean,
    longClicksReceiver: (UrlMeta) -> Boolean
  )

  fun rootService(): Service = this
}
