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

import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import io.sweers.catchup.service.api.CatchUpItem
import io.sweers.catchup.ui.base.CatchUpItemViewHolder

class ItemsAdapter : PagingDataAdapter<CatchUpItem, CatchUpItemViewHolder>(ITEM_COMPARATOR) {

  override fun onBindViewHolder(holder: CatchUpItemViewHolder, position: Int) {
    holder.bind(getItem(position))
  }

  override fun onBindViewHolder(
    holder: CatchUpItemViewHolder,
    position: Int,
    payloads: MutableList<Any>
  ) {
    onBindViewHolder(holder, position)
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CatchUpItemViewHolder {
    return CatchUpItemViewHolder.create(parent)
  }

  companion object {
    val ITEM_COMPARATOR = object : DiffUtil.ItemCallback<CatchUpItem>() {
      override fun areContentsTheSame(oldItem: CatchUpItem, newItem: CatchUpItem): Boolean =
        oldItem == newItem

      override fun areItemsTheSame(oldItem: CatchUpItem, newItem: CatchUpItem): Boolean =
        oldItem.id == newItem.id
    }
  }
}
