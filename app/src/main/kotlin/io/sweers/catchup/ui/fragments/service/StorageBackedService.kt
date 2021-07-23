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
package io.sweers.catchup.ui.fragments.service

import dev.zacsweers.catchup.appconfig.AppConfig
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.sweers.catchup.data.ServiceDao
import io.sweers.catchup.data.ServicePage
import io.sweers.catchup.service.api.BindableCatchUpItemViewHolder
import io.sweers.catchup.service.api.CatchUpItem
import io.sweers.catchup.service.api.DataRequest
import io.sweers.catchup.service.api.DataResult
import io.sweers.catchup.service.api.Service
import io.sweers.catchup.service.api.UrlMeta
import io.sweers.catchup.util.isAfter
import io.sweers.catchup.util.kotlin.switchIf
import io.sweers.catchup.util.w
import kotlinx.coroutines.channels.SendChannel
import kotlinx.datetime.Clock
import retrofit2.HttpException
import java.io.IOException
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class StorageBackedService(
  private val dao: ServiceDao,
  private val delegate: Service,
  private val appConfig: AppConfig
) : Service {

  private var currentSessionId: Long = -1

  override fun rootService() = delegate

  override fun meta() = delegate.meta()

  override fun fetchPage(request: DataRequest): Single<DataResult> {
    if (request.multiPage) {
      // Backfill pages

      // If service has deterministic numeric paging, we can use range + concatMapEager to fast track it
      if (meta().pagesAreNumeric) {
        return Observable.range(0, request.pageId.toInt())
          .concatMapEager { getPage(it.toString(), allowNetworkFallback = false).toObservable() }
          .reduce { (data1), (data, nextPageToken) ->
            DataResult(data1 + data, nextPageToken, wasFresh = false)
          }
          .filter { it.data.isNotEmpty() }
          .toSingle() // Guaranteed to have at least one if multifetching
      }

      // Strategy is a bit interesting here. We start with a synthesized initial "result"
      // in a behavior subject. We start with this in the stream, and then from there flatmap the next
      // page for every token. If the token is not null, we send it back to the stateHandler to fetch
      // the next page. If that page is our final page or it was null, complete the state handler.
      //
      // Basically an RxJava way of generating an indefinite sequence. At the end, we reduce all the
      // results, continuously appending new data and keeping the last seen page token. The result
      // is the last emission has all the data we want plus the last "next page" token.
      val stateHandler = BehaviorSubject.createDefault(
        DataResult(emptyList(), meta().firstPageKey)
      ).toSerialized()
      return stateHandler
        .flatMapSingle { getPage(it.nextPageToken!!, allowNetworkFallback = false) }
        .doAfterNext { result ->
          val nextPage = result.nextPageToken
          if (nextPage != null) {
            // Always request the next page if it's not null. If it's the last page, we'll complete after this
            stateHandler.onNext(result)
          }
          if (nextPage == null || nextPage == request.pageId) {
            // Complete if there's no more pages or we've hit the target page
            stateHandler.onComplete()
          }
        }
        .reduce { (data1), (data, nextPageToken) ->
          DataResult(
            data = data1 + data,
            nextPageToken = nextPageToken,
            wasFresh = false
          )
        }
        .filter { it.data.isNotEmpty() }
        .switchIfEmpty(
          Single.defer {
            // Ultimately fall back to just trying to request the first page
            getPage(
              page = meta().firstPageKey,
              isRefresh = false,
              allowNetworkFallback = true
            )
          }
        )
    } else {
      return getPage(request.pageId, request.fromRefresh)
    }
  }

  private fun getPage(
    page: String,
    isRefresh: Boolean = false,
    allowNetworkFallback: Boolean = true
  ): Single<DataResult> {
    if (!isRefresh) {
      // Try from local first
      // If no prev session ID, grab the latest page
      // If we do have a prev session ID, we want those pages
      val useLatest = currentSessionId == -1L
      if (appConfig.isDebug && useLatest && page != meta().firstPageKey) {
        // This shouldn't happen. If we have no session, we should be fetching the first page
        w(
          IllegalStateException(
            "Fetching first local ($page) but not first page! Received $page"
          )
        ) {
          "invalid store state"
        }
      }
      return fetchPageFromLocal(page, useLatest)
        .filter { it.data.isNotEmpty() }
        .switchIf(!useLatest) {
          switchIfEmpty(
            Maybe.defer {
              // If we were trying to a current session but failed, fall back to first local page
              fetchPageFromLocal(page, true)
            }
          )
        }
        .filter { it.data.isNotEmpty() }
        .let {
          if (allowNetworkFallback) {
            it.switchIfEmpty(
              Single.defer {
                // Nothing local, fall to network
                fetchPageFromNetwork(page, isRefresh)
              }
            )
          } else {
            it.toSingle()
          }
        }
    } else {
      return fetchPageFromNetwork(page, true)
    }
  }

  private fun fetchPageFromLocal(
    pageId: String,
    useLatest: Boolean
  ): Maybe<DataResult> {
    val now = Clock.System.now()
    val pageFetcher = with(dao) {
      if (pageId == meta().firstPageKey && useLatest) {
        getFirstServicePage(meta().id, page = pageId)
      } else {
        getServicePage(type = meta().id, page = pageId, sessionId = currentSessionId)
      }
    }
    return pageFetcher
      .subscribeOn(Schedulers.io())
      .filter { it.expiration.isAfter(now) }
      .flatMap { servicePage ->
        if (pageId == meta().firstPageKey) {
          currentSessionId = servicePage.sessionId
        }
        // We want to preserve the ordering that we stored before, map ID -> index
        val idToIndex = servicePage.items.withIndex()
          .associateBy(keySelector = { (_, value) -> value }) { (index, _) -> index }
        dao.getItemByIds(servicePage.items.toTypedArray())
          .flattenAsObservable { it }
          .toSortedList { o1, o2 ->
            idToIndex.getValue(o1.stableId()).compareTo(idToIndex.getValue(o2.stableId()))
          }
          .toMaybe()
          .map { DataResult(it, servicePage.nextPageToken, wasFresh = false) }
      }
  }

  private fun fetchPageFromNetwork(pageId: String, isRefresh: Boolean): Single<DataResult> {
    return delegate.fetchPage(DataRequest(true, false, pageId))
      .flatMap { result ->
        val calculatedExpiration = Clock.System.now()
          .plus(2.toDuration(DurationUnit.HOURS)) // TODO preference this
        if (currentSessionId == -1L || isRefresh) {
          currentSessionId = calculatedExpiration.toEpochMilliseconds()
        }
        val putPage = dao.putPage(
          ServicePage(
            id = "${meta().id}$pageId",
            type = meta().id,
            page = pageId,
            items = result.data.map { it.stableId() },
            expiration = calculatedExpiration,
            sessionId = if (pageId == meta().firstPageKey && isRefresh) {
              calculatedExpiration.toEpochMilliseconds()
            } else {
              currentSessionId
            },
            nextPageToken = result.nextPageToken
          )
        )

        val putItems = dao.putItems(*result.data.toTypedArray())

        return@flatMap Completable.mergeArray(putPage, putItems)
          .subscribeOn(Schedulers.io())
          .andThen(Single.just(result))
      }
      .onErrorResumeNext { throwable: Throwable ->
        // At least *try* to gracefully handle it
        if (throwable is HttpException) {
          Single.error(throwable)
        } else if (pageId == meta().firstPageKey && !isRefresh && throwable is IOException) {
          fetchPageFromLocal(pageId, true)
            .switchIfEmpty(Single.error(throwable))
        } else {
          Single.error(throwable)
        }
      }
  }

  override fun bindItemView(
    item: CatchUpItem,
    holder: BindableCatchUpItemViewHolder,
    clicksReceiver: (UrlMeta) -> Boolean,
    markClicksReceiver: (UrlMeta) -> Boolean,
    longClicksReceiver: (UrlMeta) -> Boolean
  ) {
    delegate.bindItemView(item, holder, clicksReceiver, markClicksReceiver, longClicksReceiver)
  }
}
