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

import androidx.paging.Pager
import androidx.paging.PagingConfig
import dagger.hilt.android.scopes.ActivityScoped
import io.sweers.catchup.data.CatchUpDatabase
import io.sweers.catchup.injection.DaggerMap
import io.sweers.catchup.service.api.Service
import io.sweers.catchup.service.api.ServiceMeta
import io.sweers.catchup.ui.activity.FinalServices
import javax.inject.Inject
import javax.inject.Provider

/**
 * Repository implementation that uses a database backed [androidx.paging.PagingSource] and
 * [androidx.paging.RemoteMediator] to load pages from network when there are no more items cached
 * in the database to load.
 */
@ActivityScoped
class CatchUpItemRepository @Inject constructor(
  private val db: CatchUpDatabase,
  private val serviceMetas: DaggerMap<String, ServiceMeta>,
  @FinalServices private val services: DaggerMap<String, Provider<Service>>
) {

  fun postsOfService(serviceId: String, pageSize: Int) = Pager(
    config = PagingConfig(pageSize),
    remoteMediator = PageKeyedRemoteMediator(db, services.getValue(serviceId).get(), serviceId)
  ) {
    db.serviceDao().itemsByService(serviceId)
  }.flow

  fun serviceMetaFor(serviceId: String): ServiceMeta = serviceMetas.getValue(serviceId)
}
