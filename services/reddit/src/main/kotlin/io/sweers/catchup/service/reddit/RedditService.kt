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
package io.sweers.catchup.service.reddit

import com.squareup.moshi.Moshi
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.Reusable
import dagger.multibindings.IntoMap
import dev.zacsweers.catchup.appconfig.AppConfig
import io.reactivex.Single
import io.sweers.catchup.libraries.retrofitconverters.delegatingCallFactory
import io.sweers.catchup.service.api.CatchUpItem
import io.sweers.catchup.service.api.DataRequest
import io.sweers.catchup.service.api.DataResult
import io.sweers.catchup.service.api.Mark.Companion.createCommentMark
import io.sweers.catchup.service.api.Service
import io.sweers.catchup.service.api.ServiceKey
import io.sweers.catchup.service.api.ServiceMeta
import io.sweers.catchup.service.api.ServiceMetaKey
import io.sweers.catchup.service.api.SummarizationInfo
import io.sweers.catchup.service.api.TextService
import io.sweers.catchup.service.reddit.model.RedditLink
import io.sweers.catchup.service.reddit.model.RedditObjectFactory
import io.sweers.catchup.serviceregistry.annotations.Meta
import io.sweers.catchup.serviceregistry.annotations.ServiceModule
import io.sweers.catchup.util.data.adapters.EpochInstantJsonAdapter
import io.sweers.catchup.util.nullIfBlank
import kotlinx.datetime.Instant
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Inject
import javax.inject.Qualifier

@Qualifier
private annotation class InternalApi

private const val SERVICE_KEY = "reddit"

internal class RedditService @Inject constructor(
  @InternalApi private val serviceMeta: ServiceMeta,
  private val api: RedditApi
) :
  TextService {

  override fun meta() = serviceMeta

  override fun fetchPage(request: DataRequest): Single<DataResult> {
    // We special case the front page
    return api.frontPage(25, request.pageId?.nullIfBlank())
      .map { redditListingRedditResponse ->
        @Suppress("UNCHECKED_CAST")
        val data = (redditListingRedditResponse.data.children as List<RedditLink>)
          .withIndex()
          .map { (index, submission) ->
            CatchUpItem(
              id = submission.id.hashCode().toLong(),
              title = submission.title,
              score = "+" to submission.score,
              timestamp = submission.createdUtc,
              serviceId = serviceMeta.id,
              indexInResponse = index,
              author = "/u/" + submission.author,
              source = submission.domain ?: "self",
              tag = submission.subreddit,
              itemClickUrl = submission.url,
              summarizationInfo = SummarizationInfo.from(submission.url, submission.selftext),
              mark = createCommentMark(
                count = submission.commentsCount,
                clickUrl = "https://reddit.com/comments/${submission.id}"
              )
            )
          }
        DataResult(data, redditListingRedditResponse.data.after)
      }
  }
}

@Meta
@ServiceModule
@Module
abstract class RedditMetaModule {

  @IntoMap
  @ServiceMetaKey(SERVICE_KEY)
  @Binds
  internal abstract fun redditServiceMeta(@InternalApi meta: ServiceMeta): ServiceMeta

  companion object {

    @InternalApi
    @Provides
    @Reusable
    internal fun provideRedditServiceMeta() = ServiceMeta(
      SERVICE_KEY,
      R.string.reddit,
      R.color.redditAccent,
      R.drawable.logo_reddit,
      firstPageKey = ""
    )
  }
}

@ServiceModule
@Module(includes = [RedditMetaModule::class])
abstract class RedditModule {

  @IntoMap
  @ServiceKey(SERVICE_KEY)
  @Binds
  internal abstract fun redditService(redditService: RedditService): Service

  companion object {

    @InternalApi
    @Provides
    internal fun provideMoshi(upstreamMoshi: Moshi): Moshi {
      return upstreamMoshi.newBuilder()
        .add(RedditObjectFactory.INSTANCE)
        .add(Instant::class.java, EpochInstantJsonAdapter())
        .build()
    }

    @InternalApi
    @Provides
    internal fun provideRedditOkHttpClient(
      client: OkHttpClient
    ): OkHttpClient {
      return client.newBuilder()
        .addNetworkInterceptor { chain ->
          var request = chain.request()
          val url = request.url
          request = request.newBuilder()
            .header("User-Agent", "CatchUp app by /u/pandanomic")
            .url(
              url.newBuilder()
                .encodedPath("${url.encodedPath}.json")
                .addQueryParameter("raw_json", "1") // So tokens aren't escaped
                .build()
            )
            .build()
          chain.proceed(request)
        }
        .build()
    }

    @Provides
    internal fun provideRedditApi(
      @InternalApi client: Lazy<OkHttpClient>,
      rxJavaCallAdapterFactory: RxJava2CallAdapterFactory,
      @InternalApi moshi: Moshi,
      appConfig: AppConfig
    ): RedditApi {
      val retrofit = Retrofit.Builder().baseUrl(RedditApi.ENDPOINT)
        .delegatingCallFactory(client)
        .addCallAdapterFactory(rxJavaCallAdapterFactory)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .validateEagerly(appConfig.isDebug)
        .build()
      return retrofit.create(RedditApi::class.java)
    }
  }
}
