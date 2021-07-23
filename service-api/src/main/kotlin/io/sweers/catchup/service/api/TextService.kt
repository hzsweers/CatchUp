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

import android.content.Context
import android.content.res.Configuration
import android.view.View.OnClickListener
import android.view.View.OnLongClickListener
import androidx.core.content.ContextCompat

interface TextService : Service {
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
    val markClickUrl = item.mark?.clickUrl
    val itemClickUrl = item.itemClickUrl ?: markClickUrl
    val finalMarkClickUrl = markClickUrl?.let { if (itemClickUrl == markClickUrl) null else it }
    holder.bind(
      item = item,
      itemClickHandler = itemClickUrl?.let { url ->
        OnClickListener {
          clicksReceiver(createUrlMeta(url, context))
        }
      },
      markClickHandler = finalMarkClickUrl?.let { url ->
        OnClickListener {
          markClicksReceiver(createUrlMeta(url, context))
        }
      },
      longClickHandler = item.summarizationInfo?.let {
        OnLongClickListener {
          // TODO
          false
        }
      }
    )
  }
}

private fun TextService.createUrlMeta(url: String, context: Context) = UrlMeta(
  url = url,
  // Use "day" accents as those are usually the "real" accent colors
  accentColor = ContextCompat.getColor(
    context.createConfigurationContext(
      Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_NO }
    ),
    meta().themeColor
  ),
  context = context
)
