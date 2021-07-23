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

import android.content.res.Configuration
import android.view.View.OnClickListener
import androidx.core.content.ContextCompat

interface VisualService : Service {
  override fun bindItemView(
    item: CatchUpItem,
    holder: BindableCatchUpItemViewHolder,
    clicksReceiver: (UrlMeta) -> Boolean,
    markClicksReceiver: (UrlMeta) -> Boolean,
    longClicksReceiver: (UrlMeta) -> Boolean
  ) {
    val context = holder.itemView().context
    val accentColor = ContextCompat.getColor(context, meta().themeColor)
    holder.tint(accentColor)
    holder.bind(
      item = item,
      itemClickHandler = item.itemClickUrl?.let { url ->
        OnClickListener {
          val urlMeta = UrlMeta(
            url = url,
            // Use "day" accents as those are usually the "real" accent colors
            accentColor = ContextCompat.getColor(
              context.createConfigurationContext(
                Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_NO }
              ),
              meta().themeColor
            ),
            context = context,
            imageViewerData = ImageViewerData(
              id = item.imageInfo!!.imageId,
              imageUrl = item.imageInfo.detailUrl,
              cacheKey = item.imageInfo.cacheKey,
              sourceUrl = item.imageInfo.sourceUrl,
              image = holder.itemView()
            )
          )
          clicksReceiver(urlMeta)
        }
      }
    )
  }

  fun marginDecoration(): Boolean = false

  fun spanConfig(): SpanConfig = SpanConfig.DEFAULT

  data class SpanConfig(val spanCount: Int, val spanSizeResolver: ((Int) -> Int)? = null) {
    companion object {
      val DEFAULT = SpanConfig(2, null)
    }
  }
}
