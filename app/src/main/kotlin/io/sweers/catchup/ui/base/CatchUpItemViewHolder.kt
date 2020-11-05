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
package io.sweers.catchup.ui.base

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnClickListener
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.text.PrecomputedTextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import io.sweers.catchup.R
import io.sweers.catchup.databinding.ListItemGeneralBinding
import io.sweers.catchup.service.api.BindableCatchUpItemViewHolder
import io.sweers.catchup.service.api.CatchUpItem
import io.sweers.catchup.service.api.Mark
import io.sweers.catchup.service.api.TemporaryScopeHolder
import io.sweers.catchup.service.api.temporaryScope
import io.sweers.catchup.util.hide
import io.sweers.catchup.util.kotlin.format
import io.sweers.catchup.util.primaryLocale
import io.sweers.catchup.util.show
import io.sweers.catchup.util.showIf
import kotlinx.datetime.Instant

class CatchUpItemViewHolder private constructor(
  binding: ListItemGeneralBinding
) : ViewHolder(binding.root), BindableCatchUpItemViewHolder, TemporaryScopeHolder by temporaryScope() {

  companion object {
    fun create(parent: ViewGroup): CatchUpItemViewHolder {
      val binding = ListItemGeneralBinding.inflate(LayoutInflater.from(parent.context), parent, false)
      return CatchUpItemViewHolder(binding)
    }
  }

  internal val container = binding.container
  internal val tagsContainer = binding.tagsContainer
  internal val title = binding.title
  internal val score = binding.score
  internal val scoreDivider = binding.scoreDivider
  internal val timestamp = binding.timestamp
  internal val author = binding.author
  internal val authorDivider = binding.authorDivider
  internal val source = binding.source
  internal val mark = binding.mark
  internal val tag = binding.tag
  internal val tagDivider = binding.tagDivider

  private val markBackground: Drawable
  private val constraintSet: ConstraintSet

  init {
    markBackground = mark.background
    constraintSet = ConstraintSet()
  }

  override fun itemView(): View = itemView

  override fun tint(@ColorInt color: Int) {
    score.setTextColor(color)
    tag.setTextColor(color)
    scoreDivider.setTextColor(color)
    tintMark(color)
  }

  private fun tintMark(@ColorInt color: Int) {
    if (mark.background == null) {
      mark.background = markBackground.mutate()
    }
    DrawableCompat.setTintList(mark.compoundDrawables[1], ColorStateList.valueOf(color))
  }

  fun setLongClickHandler(longClickHandler: OnLongClickListener?) {
    container.setOnLongClickListener(longClickHandler)
  }

  override fun bind(
    item: CatchUpItem?,
    itemClickHandler: OnClickListener?,
    markClickHandler: OnClickListener?,
    longClickHandler: OnLongClickListener?
  ) {
    if (item == null) {
      // TODO do we want to support clearing here?
      return
    }
    title(item.title.trim())
    score(item.score)
    timestamp(item.timestamp)
    author(item.author?.trim())
    source(item.source?.trim())
    tag(item.tag?.trim())

    container.setOnClickListener(itemClickHandler)
    setLongClickHandler(longClickHandler)

    item.mark?.let { sourceMark ->
      mark(sourceMark)
      if (markClickHandler != null) {
        mark.isClickable = true
        mark.isFocusable = true
        mark.setOnClickListener(markClickHandler)
      } else {
        mark.background = null
        mark.isClickable = false
        mark.isFocusable = false
      }
    } ?: run { hideMark() }
  }

  fun title(titleText: CharSequence?) {
    title.setTextFuture(
      PrecomputedTextCompat.getTextFuture(
        titleText ?: "",
        TextViewCompat.getTextMetricsParams(title),
        null
      )
    )
  }

  fun score(scoreValue: Pair<String, Int>?) {
    score.text = scoreValue?.let {
      "${scoreValue.first} ${scoreValue.second.toLong().format()}"
    }
    updateDividerVisibility()
  }

  fun tag(text: String?) {
    tag.text = text?.capitalize(tag.context.primaryLocale)
    updateDividerVisibility()
  }

  private fun updateDividerVisibility() {
    val numVisible = arrayOf(score, tag, timestamp)
      .asSequence()
      .map { (it to it.text.isBlank()) }
      .onEach { (view, isBlank) ->
        if (isBlank) {
          view.hide()
        } else {
          view.show()
        }
      }
      .count { (_, isBlank) -> !isBlank }
    tagsContainer showIf (numVisible > 0)
    when (numVisible) {
      0, 1 -> {
        scoreDivider.hide()
        tagDivider.hide()
      }
      2 -> {
        when {
          timestamp.isVisible -> {
            tagDivider.show()
            scoreDivider.hide()
          }
          score.isVisible -> {
            scoreDivider.show()
            tagDivider.hide()
          }
          else -> {
            tagDivider.show()
            scoreDivider.hide()
          }
        }
      }
      3 -> {
        scoreDivider.show()
        tagDivider.show()
      }
    }
  }

  fun timestamp(instant: Instant?) = timestamp(instant?.toEpochMilliseconds())

  private fun timestamp(date: Long?) {
    timestamp.text = date?.let {
      DateUtils.getRelativeTimeSpanString(
        it,
        System.currentTimeMillis(),
        0L,
        DateUtils.FORMAT_ABBREV_ALL
      )
    }
    updateDividerVisibility()
  }

  fun author(authorText: CharSequence?) {
    author.text = authorText
    updateAttributionVisibility()
  }

  fun source(sourceText: CharSequence?) {
    source.text = sourceText
    updateAttributionVisibility()
  }

  private fun updateAttributionVisibility() {
    val sourceBlank = source.text.isBlank()
    val authorBlank = author.text.isBlank()
    with(authorDivider) {
      if (sourceBlank || authorBlank) {
        hide()
      } else {
        show()
      }
    }
    if (sourceBlank) {
      source.hide()
    } else {
      source.show()
    }
    if (authorBlank) {
      author.hide()
    } else {
      author.show()
    }

    constraintSet.apply {
      clone(container)
      // Set the vertical bias on the timestamp view since it is the head of the vertical chain.
      setVerticalBias(R.id.timestamp, getVerticalBias(sourceBlank, authorBlank))
      applyTo(container)
    }
  }

  @SuppressLint("SetTextI18n")
  fun mark(sourceMark: Mark) {
    mark.show()
    sourceMark.text?.let { text ->
      val finalText = if (sourceMark.formatTextAsCount) {
        text.toLong().format()
      } else text
      mark.text = "${sourceMark.textPrefix.orEmpty()}$finalText"
    }

    sourceMark.icon?.let { color ->
      mark.setCompoundDrawablesWithIntrinsicBounds(
        null,
        AppCompatResources.getDrawable(mark.context, color),
        null,
        null
      )

      tintMark(sourceMark.iconTintColor ?: score.currentTextColor)
    }
  }

  fun hideMark() = mark.hide()

  private fun getVerticalBias(
    sourceBlank: Boolean,
    authorBlank: Boolean
  ) = if (sourceBlank && authorBlank) {
    0.5f // Center
  } else if (sourceBlank) {
    0f // Top
  } else {
    0.5f // Center
  }
}
