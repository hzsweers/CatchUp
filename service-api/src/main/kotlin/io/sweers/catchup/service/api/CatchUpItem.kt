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

import androidx.annotation.Keep
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

@Keep
@Entity(tableName = "items")
data class CatchUpItem(
  @PrimaryKey(autoGenerate = true) val generatedId: Int = 0,
  val id: Long,
  val title: String,
  val timestamp: Instant?,
  val serviceId: String,
  val indexInResponse: Int,
  val score: Pair<String, Int>? = null,
  val tag: String? = null,
  val author: String? = null,
  val source: String? = null,
  val itemClickUrl: String? = null,
  @Embedded val summarizationInfo: SummarizationInfo? = null,
  @Embedded val imageInfo: ImageInfo? = null,
  @Embedded val mark: Mark? = null,
  val detailKey: String? = null
) : DisplayableItem {

  override fun stableId() = id

  override fun realItem() = this
}
