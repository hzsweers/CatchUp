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
package io.sweers.catchup.service.uplabs

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
import io.sweers.catchup.service.api.ImageInfo
import io.sweers.catchup.service.api.Service
import io.sweers.catchup.service.api.ServiceKey
import io.sweers.catchup.service.api.ServiceMeta
import io.sweers.catchup.service.api.ServiceMetaKey
import io.sweers.catchup.service.api.VisualService
import io.sweers.catchup.serviceregistry.annotations.Meta
import io.sweers.catchup.serviceregistry.annotations.ServiceModule
import io.sweers.catchup.util.data.adapters.ISO8601InstantAdapter
import kotlinx.datetime.Instant
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Inject
import javax.inject.Qualifier

@Qualifier
private annotation class InternalApi

private const val SERVICE_KEY = "uplabs"

internal class UplabsService @Inject constructor(
  @InternalApi private val serviceMeta: ServiceMeta,
  private val api: UplabsApi
) :
  VisualService {

  override fun meta() = serviceMeta

  override fun fetchPage(request: DataRequest): Single<DataResult> {
    val page = request.pageId?.toInt() ?: 0
    return api.getPopular(page, 1)
      .flattenAsObservable { it.withIndex() }
      .map { (index, post) ->
        CatchUpItem(
          id = post.id.hashCode().toLong(),
          title = post.name,
          score = "▲" to post.points,
          timestamp = post.showcasedAt,
          serviceId = serviceMeta.id,
          indexInResponse = index,
          author = post.makerName,
          source = post.label,
          tag = post.category,
          itemClickUrl = if (post.animated) post.animatedTeaserUrl else post.teaserUrl,
          imageInfo = ImageInfo(
            url = if (post.animated) post.animatedTeaserUrl else post.teaserUrl,
            detailUrl = post.previewUrl, // Both animated and not are the preview url
            animatable = post.animated,
            sourceUrl = post.url,
            bestSize = null,
            imageId = post.id.toString()
          )
        )
      }
      .toList()
      .map { DataResult(it, (page + 1).toString()) }
  }
}

@Meta
@ServiceModule
@Module
abstract class UplabsMetaModule {

  @IntoMap
  @ServiceMetaKey(SERVICE_KEY)
  @Binds
  internal abstract fun uplabsServiceMeta(@InternalApi meta: ServiceMeta): ServiceMeta

  companion object {

    @InternalApi
    @Provides
    @Reusable
    internal fun provideUplabsServiceMeta() = ServiceMeta(
      SERVICE_KEY,
      R.string.uplabs,
      R.color.uplabsAccent,
      R.drawable.logo_uplabs,
      isVisual = true,
      pagesAreNumeric = true,
      firstPageKey = "0"
    )
  }
}

@ServiceModule
@Module(includes = [UplabsMetaModule::class])
abstract class UplabsModule {

  @IntoMap
  @ServiceKey(SERVICE_KEY)
  @Binds
  internal abstract fun uplabsService(uplabsService: UplabsService): Service

  companion object {

    @Provides
    @InternalApi
    internal fun provideUplabsMoshi(moshi: Moshi): Moshi {
      return moshi.newBuilder()
        .add(Instant::class.java, ISO8601InstantAdapter())
        .build()
    }

    @Provides
    internal fun provideUplabsService(
      client: Lazy<OkHttpClient>,
      @InternalApi moshi: Moshi,
      rxJavaCallAdapterFactory: RxJava2CallAdapterFactory,
      appConfig: AppConfig
    ): UplabsApi {
      return Retrofit.Builder().baseUrl(UplabsApi.ENDPOINT)
        .delegatingCallFactory(client)
        .addCallAdapterFactory(rxJavaCallAdapterFactory)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .validateEagerly(appConfig.isDebug)
        .build()
        .create(UplabsApi::class.java)
    }
  }
}
