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

import androidx.paging.LoadType
import androidx.paging.LoadType.APPEND
import androidx.paging.LoadType.PREPEND
import androidx.paging.LoadType.REFRESH
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import io.sweers.catchup.data.CatchUpDatabase
import io.sweers.catchup.data.CatchUpServiceRemoteKey
import io.sweers.catchup.service.api.CatchUpItem
import io.sweers.catchup.service.api.DataRequest
import io.sweers.catchup.service.api.Service
import retrofit2.HttpException
import java.io.IOException

class PageKeyedRemoteMediator(
  private val db: CatchUpDatabase,
  private val service: Service,
  private val serviceId: String,
) : RemoteMediator<Int, CatchUpItem>() {
  private val serviceDao = db.serviceDao()
  private val remoteKeyDao = db.remoteKeys()

  override suspend fun load(
    loadType: LoadType,
    state: PagingState<Int, CatchUpItem>
  ): MediatorResult {
    try {
      // Get the closest item from PagingState that we want to load data around.
      val loadKey = when (loadType) {
        REFRESH -> null
        PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
        APPEND -> {
          // Query DB for SubredditRemoteKey for the subreddit.
          // SubredditRemoteKey is a wrapper object we use to keep track of page keys we
          // receive from the Reddit API to fetch the next or previous page.
          val remoteKey = db.withTransaction {
            remoteKeyDao.remoteKeyByService(serviceId)
          }

          // We must explicitly check if the page key is null when appending, since the
          // Reddit API informs the end of the list by returning null for page key, but
          // passing a null key to Reddit API will fetch the initial page.
          if (remoteKey.nextPageKey == null) {
            return MediatorResult.Success(endOfPaginationReached = true)
          }

          remoteKey.nextPageKey
        }
      }

      val dataResult = service
        .fetchPage(DataRequest(fromRefresh = false, multiPage = false, pageId = loadKey))
        .blockingGet()
//            val data = redditApi.getTop(
//                subreddit = subredditName,
//                after = loadKey,
//                before = null,
//                limit = when (loadType) {
//                    REFRESH -> state.config.initialLoadSize
//                    else -> state.config.pageSize
//                }
//            ).data

      val items = dataResult.data

      db.withTransaction {
        if (loadType == REFRESH) {
          remoteKeyDao.deleteByService(serviceId)
          serviceDao.deleteByService(serviceId)
        }

        remoteKeyDao.insert(CatchUpServiceRemoteKey(serviceId, dataResult.nextPageToken))
        serviceDao.insertAll(items)
      }

      return MediatorResult.Success(endOfPaginationReached = items.isEmpty())
    } catch (e: IOException) {
      return MediatorResult.Error(e)
    } catch (e: HttpException) {
      return MediatorResult.Error(e)
    }
  }
}
