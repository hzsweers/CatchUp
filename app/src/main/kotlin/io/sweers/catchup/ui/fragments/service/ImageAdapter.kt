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

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnClickListener
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import androidx.annotation.ArrayRes
import androidx.annotation.ColorInt
import androidx.core.animation.doOnEnd
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource.MEMORY_CACHE
import coil.drawable.MovieDrawable
import coil.load
import coil.request.ErrorResult
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.transition.Transition
import coil.transition.TransitionTarget
import io.sweers.catchup.R
import io.sweers.catchup.base.ui.ColorUtils
import io.sweers.catchup.base.ui.ImageLoadingColorMatrix
import io.sweers.catchup.base.ui.generateAsync
import io.sweers.catchup.service.api.BindableCatchUpItemViewHolder
import io.sweers.catchup.service.api.CatchUpItem
import io.sweers.catchup.service.api.TemporaryScopeHolder
import io.sweers.catchup.service.api.UrlMeta
import io.sweers.catchup.service.api.temporaryScope
import io.sweers.catchup.ui.base.DataLoadingSubject
import io.sweers.catchup.ui.widget.BadgedFourThreeImageView
import io.sweers.catchup.util.UiUtil
import io.sweers.catchup.util.UiUtil.fastOutSlowInInterpolator
import io.sweers.catchup.util.isInNightMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.roundToLong

internal class ImageAdapter(
  context: Context,
  spanCount: Int,
  private val bindDelegate: (ImageItem, ImageHolder, clicksReceiver: (UrlMeta) -> Boolean) -> Unit
) :
  DisplayableItemAdapter<ImageItem, ViewHolder>(columnCount = spanCount),
  DataLoadingSubject.DataLoadingCallbacks {

  companion object {
    const val PRELOAD_AHEAD_ITEMS = 6

    @ColorInt
    private const val INITIAL_GIF_BADGE_COLOR = 0x40ffffff
  }

  private val loadingPlaceholders: Array<ColorDrawable>
  private var showLoadingMore = false

  init {
    setHasStableIds(true)
    @ArrayRes val loadingColorArrayId = if (context.isInNightMode()) {
      R.array.loading_placeholders_dark
    } else {
      R.array.loading_placeholders_light
    }
    loadingPlaceholders = context.resources.getIntArray(loadingColorArrayId)
      .iterator()
      .asSequence()
      .map(::ColorDrawable)
      .toList()
      .toTypedArray()
  }

  // TODO preload in Coil
//  override fun getPreloadItems(position: Int) =
//      data.subList(position, minOf(data.size, position + 5))
//
//  override fun getPreloadRequestBuilder(item: ImageItem): RequestBuilder<Drawable> {
//    val (x, y) = item.imageInfo.bestSize ?: Pair(0, 0)
//    return GlideApp.with(context)
//        .asDrawable()
//        .apply(RequestOptions()
//            .diskCacheStrategy(DiskCacheStrategy.DATA)
//            .fitCenter()
//            .override(x, y))
//        .load(item.realItem())
//  }

  override fun getItemId(position: Int): Long {
    if (getItemViewType(position) == TYPE_LOADING_MORE) {
      return RecyclerView.NO_ID
    }
    return data[position].realItem().id
  }

  @TargetApi(Build.VERSION_CODES.M)
  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val layoutInflater = LayoutInflater.from(parent.context)
    return when (viewType) {
      TYPE_ITEM -> {
        ImageHolder(
          LayoutInflater.from(parent.context)
            .inflate(R.layout.image_item, parent, false),
          loadingPlaceholders
        )
          .apply {
            image.setBadgeColor(
              INITIAL_GIF_BADGE_COLOR
            )
            image.foreground = UiUtil.createColorSelector(0x40808080, null)
            // play animated GIFs whilst touched
            image.setOnTouchListener { _, event ->
              // check if it's an event we care about, else bail fast
              val action = event.action
              if (!(
                  action == MotionEvent.ACTION_DOWN ||
                    action == MotionEvent.ACTION_UP ||
                    action == MotionEvent.ACTION_CANCEL
                  )
              ) {
                return@setOnTouchListener false
              }

              // get the image and check if it's an animated GIF
              // TODO rework this with MovieDrawable from Coil
//                val gif: GifDrawable = when (val drawable = image.drawable
//                    ?: return@setOnTouchListener false) {
//                  is GifDrawable -> drawable
//                  is TransitionDrawable -> (0 until drawable.numberOfLayers).asSequence()
//                      .map(drawable::getDrawable)
//                      .filterIsInstance<GifDrawable>()
//                      .firstOrNull()
//                  else -> null
//                } ?: return@setOnTouchListener false
//                // GIF found, start/stop it on press/lift
//                when (action) {
//                  MotionEvent.ACTION_DOWN -> gif.start()
//                  MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gif.stop()
//                }
              false
            }
          }
      }
      TYPE_LOADING_MORE -> LoadingMoreHolder(
        layoutInflater.inflate(R.layout.infinite_loading, parent, false)
      )
      else -> TODO("Unknown type")
    }
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    when (getItemViewType(position)) {
      TYPE_ITEM -> {
        val imageItem = data[position]
        val imageHolder = holder as ImageHolder
        if (imageHolder.backingImageItem?.stableId() == imageItem.stableId()) {
          // This is the same item, no need to reset. Possible from a refresh.
          return
        }
        // TODO This is kind of ugly but not sure what else to do. Holder can't be an inner class to avoid mem leaks
        imageHolder.backingImageItem = imageItem
        bindDelegate(imageItem, holder, clicksReceiver())
          .also {
            holder.backingImageItem = null
          }
      }
      TYPE_LOADING_MORE -> (holder as LoadingMoreHolder).progress.visibility = if (position > 0) View.VISIBLE else View.INVISIBLE
    }
  }

  @SuppressLint("NewApi")
  override fun onViewRecycled(holder: ViewHolder) {
    super.onViewRecycled(holder)
    if (holder is ImageHolder) {
      // TODO can coil do this?
//      GlideApp.with(holder.itemView).clear(holder.image)
      // reset the badge & ripple which are dynamically determined
      holder.image.setBadgeColor(
        INITIAL_GIF_BADGE_COLOR
      )
      holder.image.showBadge(false)
      holder.image.foreground = UiUtil.createColorSelector(0x40808080, null)
    }
  }

  override fun getItemCount() = dataItemCount + if (showLoadingMore) 1 else 0

  private val dataItemCount: Int
    get() = data.size

  private val loadingMoreItemPosition: Int
    get() = if (showLoadingMore) itemCount - 1 else RecyclerView.NO_POSITION

  override fun getItemViewType(position: Int): Int {
    if (position < dataItemCount && dataItemCount > 0) {
      return TYPE_ITEM
    }
    return TYPE_LOADING_MORE
  }

  override fun dataStartedLoading() {
    if (showLoadingMore) {
      return
    }
    showLoadingMore = true
    notifyItemInserted(loadingMoreItemPosition)
  }

  override fun dataFinishedLoading() {
    if (!showLoadingMore) {
      return
    }
    val loadingPos = loadingMoreItemPosition
    showLoadingMore = false
    notifyItemRemoved(loadingPos)
  }

  internal class ImageHolder(
    itemView: View,
    private val loadingPlaceholders: Array<ColorDrawable>
  ) :
    ViewHolder(itemView), BindableCatchUpItemViewHolder, TemporaryScopeHolder by temporaryScope() {

    internal var backingImageItem: ImageItem? = null
    internal val image: BadgedFourThreeImageView = itemView as BadgedFourThreeImageView

    override fun itemView(): View = itemView

    private fun applyPalette(view: BadgedFourThreeImageView, palette: Palette) {
      view.foreground =
        UiUtil.createRipple(palette, 0.25f, 0.5f, 0x40808080, true)
    }

    @OptIn(ExperimentalCoilApi::class)
    override fun bind(
      item: CatchUpItem,
      itemClickHandler: OnClickListener?,
      markClickHandler: OnClickListener?,
      longClickHandler: OnLongClickListener?
    ) {
      backingImageItem?.let { imageItem ->
        image.load(imageItem.imageInfo.url) {
          memoryCacheKey(imageItem.imageInfo.cacheKey)
          placeholder(loadingPlaceholders[bindingAdapterPosition % loadingPlaceholders.size])
          if (!imageItem.hasFadedIn) {
            transition(SaturatingTransformation())
          } else {
            crossfade(0)
          }
          listener(
            onSuccess = { _, _ ->
              itemView().setOnClickListener(itemClickHandler)
              itemView().setOnLongClickListener(longClickHandler)
              val result = image.drawable
              val scope = newScope()
              if (result is BitmapDrawable) {
                scope.launch {
                  Palette.from(result.bitmap)
                    .clearFilters()
                    .generateAsync()?.let {
                      applyPalette(image, it)
                    }
                }
              } else if (result is MovieDrawable) {
                // TODO need to extract the first frame somehow
                // val image = result.firstFrame
                val bitmap: Bitmap? = null
                if (bitmap == null || bitmap.isRecycled) {
                  return@listener
                }
                scope.launch {
                  Palette.from(bitmap).clearFilters().generateAsync()?.let {
                    applyPalette(image, it)
                  }
                }

                // look at the corner to determine the gif badge color
                val cornerSize = (
                  56 * image.context.resources
                    .displayMetrics.scaledDensity
                  ).toInt()
                val corner = Bitmap.createBitmap(
                  bitmap,
                  bitmap.width - cornerSize,
                  bitmap.height - cornerSize,
                  cornerSize,
                  cornerSize
                )
                val isDark = ColorUtils.isDark(corner)
                corner.recycle()
                image.setBadgeColor(if (isDark) 0xb3ffffff.toInt() else 0x40000000)
              }
            }
          )
        }
        // need both placeholder & background to prevent seeing through image as it fades in
        image.background = loadingPlaceholders[bindingAdapterPosition % loadingPlaceholders.size]
        image.showBadge(imageItem.imageInfo.animatable)
      }
    }
  }
}

/** A [Transition] that saturates and fades in the new drawable on load */
@ExperimentalCoilApi
private class SaturatingTransformation(
  private val durationMillis: Long = SATURATION_ANIMATION_DURATION
) : Transition {
  init {
    require(durationMillis > 0) { "durationMillis must be > 0." }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  override suspend fun transition(target: TransitionTarget, result: ImageResult) {
    // Don't animate if the request was fulfilled by the memory cache.
    if (result is SuccessResult && result.metadata.dataSource == MEMORY_CACHE) {
      target.onSuccess(result.drawable)
      return
    }

    // Animate the drawable and suspend until the animation is completes.
    suspendCancellableCoroutine<Unit> { continuation ->
      when (result) {
        is SuccessResult -> {
          val animator = saturateDrawableAnimator(
            result.drawable,
            durationMillis,
            target.view
          )
          animator.doOnEnd {
            continuation.resume(Unit) { animator.cancel() }
          }
          animator.start()

          continuation.invokeOnCancellation { animator.cancel() }
          target.onSuccess(result.drawable)
        }
        is ErrorResult -> target.onError(result.drawable)
      }
    }
  }
}

private fun saturateDrawableAnimator(
  current: Drawable,
  duration: Long = SATURATION_ANIMATION_DURATION,
  view: View? = null
): Animator {
  current.mutate()
  view?.setHasTransientState(true)

  val cm = ImageLoadingColorMatrix()

  val satAnim = ObjectAnimator.ofFloat(cm, ImageLoadingColorMatrix.PROP_SATURATION, 0f, 1f)
  satAnim.duration = duration
  satAnim.addUpdateListener {
    current.colorFilter = ColorMatrixColorFilter(cm)
  }

  val alphaAnim = ObjectAnimator.ofFloat(cm, ImageLoadingColorMatrix.PROP_ALPHA, 0f, 1f)
  alphaAnim.duration = duration / 2

  val darkenAnim = ObjectAnimator.ofFloat(cm, ImageLoadingColorMatrix.PROP_BRIGHTNESS, 0.8f, 1f)
  darkenAnim.duration = (duration * 0.75f).roundToLong()

  return AnimatorSet().apply {
    playTogether(satAnim, alphaAnim, darkenAnim)
    interpolator = fastOutSlowInInterpolator
    doOnEnd {
      current.clearColorFilter()
      view?.setHasTransientState(false)
    }
  }
}

private const val SATURATION_ANIMATION_DURATION = 2000L
