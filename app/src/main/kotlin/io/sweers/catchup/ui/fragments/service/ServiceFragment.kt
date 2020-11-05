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

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DiffUtil.DiffResult
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.apollographql.apollo.exception.ApolloException
import com.uber.autodispose.autoDispose
import dagger.hilt.android.AndroidEntryPoint
import dev.zacsweers.catchup.appconfig.AppConfig
import io.reactivex.android.schedulers.AndroidSchedulers
import io.sweers.catchup.R
import io.sweers.catchup.base.ui.InjectingBaseFragment
import io.sweers.catchup.data.LinkManager
import io.sweers.catchup.databinding.FragmentServiceBinding
import io.sweers.catchup.injection.DaggerMap
import io.sweers.catchup.service.api.CatchUpItem
import io.sweers.catchup.service.api.DataRequest
import io.sweers.catchup.service.api.DisplayableItem
import io.sweers.catchup.service.api.Service
import io.sweers.catchup.service.api.ServiceException
import io.sweers.catchup.service.api.UrlMeta
import io.sweers.catchup.service.api.VisualService
import io.sweers.catchup.ui.DetailDisplayer
import io.sweers.catchup.ui.InfiniteScrollListener
import io.sweers.catchup.ui.Scrollable
import io.sweers.catchup.ui.activity.FinalServices
import io.sweers.catchup.ui.activity.TextViewPool
import io.sweers.catchup.ui.activity.VisualViewPool
import io.sweers.catchup.ui.base.CatchUpItemViewHolder
import io.sweers.catchup.ui.base.DataLoadingSubject
import io.sweers.catchup.ui.base.DataLoadingSubject.DataLoadingCallbacks
import io.sweers.catchup.ui.fragments.service.LoadResult.DiffResultData
import io.sweers.catchup.ui.fragments.service.LoadResult.NewData
import io.sweers.catchup.ui.widget.BaseCatchupAdapter
import io.sweers.catchup.util.e
import io.sweers.catchup.util.hide
import io.sweers.catchup.util.kotlin.applyOn
import io.sweers.catchup.util.show
import io.sweers.catchup.util.w
import jp.wasabeef.recyclerview.animators.FadeInUpAnimator
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.saket.inboxrecyclerview.page.SimplePageStateChangeCallbacks
import retrofit2.HttpException
import java.io.IOException
import java.security.InvalidParameterException
import javax.inject.Inject
import javax.inject.Provider

abstract class DisplayableItemAdapter<T : DisplayableItem, VH : ViewHolder>(
  val columnCount: Int = 1
) : BaseCatchupAdapter<VH>(), DataLoadingCallbacks {

  companion object {
    const val TYPE_ITEM = 0
    const val TYPE_LOADING_MORE = -1
  }

  protected val data = mutableListOf<T>()
  private val clicksChannel = BroadcastChannel<UrlMeta>(Channel.BUFFERED)

  internal fun update(loadResult: LoadResult<T>) {
    when (loadResult) {
      is NewData -> {
        data.addAll(loadResult.newData)
        notifyItemRangeInserted(data.size - loadResult.newData.size, loadResult.newData.size)
      }
      is DiffResultData -> {
        data.clear()
        data.addAll(loadResult.data)
        loadResult.diffResult.dispatchUpdatesTo(this)
      }
    }
  }

  @FlowPreview
  fun clicksFlow() = clicksChannel.asFlow()

  protected fun clicksChannel(): SendChannel<UrlMeta> = clicksChannel

  fun getItems(): List<DisplayableItem> = data

  fun getItemColumnSpan(position: Int) = when (getItemViewType(position)) {
    TYPE_LOADING_MORE -> columnCount
    else -> 1
  }
}

@AndroidEntryPoint
class ServiceFragment :
  InjectingBaseFragment(),
  SwipeRefreshLayout.OnRefreshListener,
  Scrollable,
  DataLoadingSubject {

  companion object {
    const val ARG_SERVICE_KEY = "serviceKey"
    fun newInstance(serviceKey: String) =
      ServiceFragment().apply {
        arguments = bundleOf(ARG_SERVICE_KEY to serviceKey)
      }
  }

  private val binding by viewBinding(FragmentServiceBinding::inflate)
  private val errorView get() = binding.loadStatusContainer.errorContainer
  private val errorTextView get() = binding.loadStatusContainer.errorMessage
  private val errorImage get() = binding.loadStatusContainer.errorImage
  private val recyclerView get() = binding.list
  private val progress get() = binding.loadStatusContainer.progress
  private val swipeRefreshLayout get() = binding.refresh

  private lateinit var layoutManager: LinearLayoutManager
  private lateinit var adapter: DisplayableItemAdapter<out DisplayableItem, ViewHolder>
  private var currentPage: String? = null
  private var nextPage: String? = null
  private var isRestoring = false
  private var pageToRestoreTo: String? = null
  private var moreDataAvailable = true
  private var dataLoading = false
  private var pendingRVState: Parcelable? = null
  private var detailDisplayed: Boolean = false
  private val defaultItemAnimator = DefaultItemAnimator()

  @Inject
  internal lateinit var linkManager: LinkManager
  @TextViewPool
  @Inject
  lateinit var textViewPool: RecycledViewPool
  @VisualViewPool
  @Inject
  lateinit var visualViewPool: RecycledViewPool
  @FinalServices
  @Inject
  lateinit var services: DaggerMap<String, Provider<Service>>
  private lateinit var service: Service
  @Inject
  lateinit var fragmentCreators: DaggerMap<Class<out Fragment>, Provider<Fragment>>
  @Inject
  lateinit var detailDisplayer: DetailDisplayer
  @Inject
  lateinit var appConfig: AppConfig

  override fun toString() = "ServiceFragment: ${arguments?.get(ARG_SERVICE_KEY)}"

  override fun isDataLoading(): Boolean = dataLoading

  override fun onAttach(context: Context) {
    super.onAttach(context)
    service = requireArguments()[ARG_SERVICE_KEY].let {
      services[it]?.get() ?: throw IllegalArgumentException("No service provided for $it!")
    }
    nextPage = service.meta().firstPageKey
  }

  private fun createLayoutManager(
    context: Context,
    adapter: DisplayableItemAdapter<*, *>
  ): LinearLayoutManager {
    return if (service.meta().isVisual) {
      val spanConfig = (service.rootService() as VisualService).spanConfig()
      GridLayoutManager(context, spanConfig.spanCount).apply {
        val resolver = spanConfig.spanSizeResolver ?: { 1 }
        val loadingAwareResolver = { position: Int ->
          when (adapter.getItemViewType(position)) {
            DisplayableItemAdapter.TYPE_LOADING_MORE -> adapter.columnCount
            else -> resolver(position)
          }
        }
        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
          override fun getSpanSize(position: Int) = loadingAwareResolver(position)
        }
      }
    } else {
      LinearLayoutManager(activity, RecyclerView.VERTICAL, false)
    }
  }

  override fun onBackPressed(): Boolean {
    return if (detailDisplayer.isExpandedOrExpanding) {
      recyclerView.collapse()
      true
    } else {
      super.onBackPressed()
    }
  }

  private fun createAdapter(
    context: Context
  ): DisplayableItemAdapter<out DisplayableItem, ViewHolder> {
    if (service.meta().isVisual) {
      val spanConfig = (service.rootService() as VisualService).spanConfig()
      val adapter = ImageAdapter(context, spanConfig.spanCount) { item, holder, clicksChannel ->
        service.bindItemView(
          item.realItem(),
          holder,
          clicksChannel,
          clicksChannel,
          clicksChannel
        )
      }
      // TODO adapt this for Coil
//      val preloader = RecyclerViewPreloader(GlideApp.with(context),
//          adapter,
//          ViewPreloadSizeProvider<ImageItem>(),
//          ImageAdapter.PRELOAD_AHEAD_ITEMS)
//      recyclerView.addOnScrollListener(preloader)
      return adapter
    } else {
      return TextAdapter { item, holder, clicksChannel ->
        service.bindItemView(
          item,
          holder,
          clicksChannel,
          clicksChannel,
          clicksChannel
        )
        if (appConfig.isDebug) {
          item.detailKey?.let { key ->
            val args = bundleOf(
              "detailKey" to key,
              "detailTitle" to item.title
            )
            val targetProvider = fragmentCreators[service.meta().deeplinkFragment] ?: error("No deeplink for $key")
            holder.setLongClickHandler {
              detailDisplayer.showDetail { page, fragmentManager ->
                detailDisplayed = true
                val targetFragment = targetProvider.get().apply {
                  arguments = args
                }
                detailDisplayer.bind(recyclerView, targetFragment)
                page.addStateChangeCallbacks(
                  object : SimplePageStateChangeCallbacks() {
                    override fun onPageCollapsed() {
                      detailDisplayed = false
                      page.removeStateChangeCallbacks(this)
                      detailDisplayer.unbind(recyclerView)
                      fragmentManager.commitNow(allowStateLoss = true) {
                        remove(targetFragment)
                      }
                    }
                  }
                )
                fragmentManager.commitNow(allowStateLoss = true) {
                  add(page.id, targetFragment)
                }
                recyclerView.expandItem(item.id)
                recyclerView::collapse
              }
              true
            }
          }
        }
      }
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    savedInstanceState?.run {
      pageToRestoreTo = getString("currentPage", null)
      pendingRVState = getParcelable("layoutManagerState")
      isRestoring = pendingRVState != null
      if (isRestoring) {
        errorView.hide()
        recyclerView.show()
        recyclerView.itemAnimator = defaultItemAnimator
      }
      detailDisplayed = getBoolean("detailDisplayed")

      if (detailDisplayed) {
        // This is necessary to support state restoration in IRV, which expects the page to be
        // bound after rotation before restoration.
        detailDisplayer.bind(recyclerView, useExistingFragment = true)
      }
    }
    binding.loadStatusContainer.retryButton.setOnClickListener {
      onRetry()
    }
    binding.loadStatusContainer.errorImage.setOnClickListener {
      onErrorClick(binding.loadStatusContainer.errorImage)
    }
    @ColorInt val accentColor = ContextCompat.getColor(view.context, service.meta().themeColor)
    @ColorInt val dayAccentColor = ContextCompat.getColor(
      dayOnlyContext!!,
      service.meta().themeColor
    )
    swipeRefreshLayout.run {
      setColorSchemeColors(dayAccentColor)
      setOnRefreshListener(this@ServiceFragment)
    }
    progress.indeterminateTintList = ColorStateList.valueOf(accentColor)
    adapter = createAdapter(view.context)
    viewLifecycleOwner.lifecycleScope.launch {
      adapter.clicksFlow().collect {
        linkManager.openUrl(it)
      }
    }
    layoutManager = createLayoutManager(view.context, adapter)
    recyclerView.layoutManager = layoutManager
    recyclerView.addOnScrollListener(
      object : InfiniteScrollListener(layoutManager, this@ServiceFragment) {
        override fun onLoadMore() {
          loadData()
        }
      }
    )
    if (service.meta().isVisual) {
      recyclerView.setRecycledViewPool(visualViewPool)
    } else {
      recyclerView.setRecycledViewPool(textViewPool)
    }
    recyclerView.adapter = adapter
    if (!service.meta().isVisual) {
      recyclerView.itemAnimator = FadeInUpAnimator(OvershootInterpolator(1f)).apply {
        addDuration = 300
        removeDuration = 300
      }
    }
    swipeRefreshLayout.isEnabled = false
    loadData()
  }

  private fun onRetry() {
    errorView.hide()
    progress.show()
    onRefresh()
  }

  private fun onErrorClick(imageView: ImageView) {
    (imageView.drawable as AnimatedVectorDrawableCompat).start()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.run {
      putBoolean("detailDisplayed", detailDisplayed)
      if (currentPage != service.meta().firstPageKey) {
        putString("currentPage", currentPage)
      }
      putParcelable("layoutManagerState", recyclerView.layoutManager?.onSaveInstanceState())
    }
    super.onSaveInstanceState(outState)
  }

  private fun loadData(fromRefresh: Boolean = false) {
    if (!recyclerView.isVisible) {
      progress.show()
    }
    if (fromRefresh || adapter.itemCount == 0) {
      moreDataAvailable = true
      nextPage = service.meta().firstPageKey
    }
    if (nextPage == null) {
      moreDataAvailable = false
    }
    if (!moreDataAvailable) {
      return
    }
    val pageToRequest = pageToRestoreTo ?: nextPage!!
    dataLoading = true
    if (adapter.itemCount != 0) {
      recyclerView.post { adapter.dataStartedLoading() }
      recyclerView.itemAnimator = defaultItemAnimator
    }
    val multiPage = pageToRestoreTo != null
    if (multiPage) {
      recyclerView.itemAnimator = defaultItemAnimator
    }
    service.fetchPage(
      DataRequest(
        fromRefresh = fromRefresh && !isRestoring,
        multiPage = multiPage,
        pageId = pageToRequest
      )
        .also {
          isRestoring = false
          pageToRestoreTo = null
        }
    )
      .map { (data, nextPageToken, wasFresh) ->
        data.also {
          nextPage = nextPageToken
          currentPage = pageToRequest
          if (wasFresh) {
            // If it was fresh data but we thought we were restoring (i.e. stale cache or
            // something), don't restore to to some now-random position
            pendingRVState = null
          }
        }
      }
      .flattenAsObservable { it }
      .let {
        // If these are images, wrap in our visual item
        if (service.meta().isVisual) {
          it.map { catchupItem ->
            // If any already exist, we don't need to re-fade them in
            ImageItem(catchupItem)
              .apply {
                adapter.getItems()
                  .find { it.realItem().id == catchupItem.id }
                  ?.let {
                    hasFadedIn = (it as ImageItem).hasFadedIn
                  }
              }
          }
        } else it
      }
      .cast(DisplayableItem::class.java)
      .toList()
      .toMaybe()
      .map { newData ->
        if (fromRefresh) {
          DiffResultData(
            newData,
            DiffUtil.calculateDiff(
              ItemUpdateCallback(
                adapter.getItems(),
                newData
              )
            )
          )
        } else {
          NewData(newData)
        }
      }
      .observeOn(AndroidSchedulers.mainThread())
      .doOnEvent { _, _ ->
        swipeRefreshLayout.isEnabled = true
        swipeRefreshLayout.isRefreshing = false
      }
      .doFinally {
        dataLoading = false
        recyclerView.post {
          adapter.dataFinishedLoading()
        }
      }
      .doOnComplete { moreDataAvailable = false }
      .autoDispose(this)
      .subscribe(
        { loadResult ->
          applyOn(progress, errorView) { hide() }
          swipeRefreshLayout.show()
          recyclerView.post {
            @Suppress("UNCHECKED_CAST") // badpokerface.png
            when (val finalAdapter = adapter) {
              is TextAdapter -> {
                finalAdapter.update((loadResult as LoadResult<CatchUpItem>))
              }
              is ImageAdapter -> {
                finalAdapter.update((loadResult as LoadResult<ImageItem>))
              }
            }
            pendingRVState?.let {
              recyclerView.layoutManager?.onRestoreInstanceState(it)
              pendingRVState = null
            }
          }
        },
        { error: Throwable ->
          val activity = activity
          if (activity != null) {
            when (error) {
              is IOException -> {
                progress.hide()
                errorTextView.text = activity.getString(R.string.connection_issue)
                swipeRefreshLayout.hide()
                errorView.show()
                AnimatedVectorDrawableCompat.create(activity, R.drawable.avd_no_connection)
                  ?.let {
                    errorImage.setImageDrawable(it)
                    it.start()
                  }
                w(error) { "IOException" }
              }
              is ServiceException -> {
                progress.hide()
                errorTextView.text = error.message
                swipeRefreshLayout.hide()
                errorView.show()
                AnimatedVectorDrawableCompat.create(activity, R.drawable.avd_no_connection)
                  ?.let {
                    errorImage.setImageDrawable(it)
                    it.start()
                  }
                e(error) { "ServiceException: ${error.message}" }
              }
              is HttpException, is ApolloException -> {
                // TODO Show some sort of API error response.
                progress.hide()
                errorTextView.text = activity.getString(R.string.api_issue)
                swipeRefreshLayout.hide()
                errorView.show()
                AnimatedVectorDrawableCompat.create(activity, R.drawable.avd_no_connection)
                  ?.let {
                    errorImage.setImageDrawable(it)
                    it.start()
                  }
                e(error) { "HttpException" }
              }
              else -> {
                // TODO Show some sort of generic response error
                progress.hide()
                swipeRefreshLayout.hide()
                errorTextView.text = activity.getString(io.sweers.catchup.base.ui.R.string.unknown_issue)
                errorView.show()
                AnimatedVectorDrawableCompat.create(activity, R.drawable.avd_no_connection)
                  ?.let {
                    errorImage.setImageDrawable(it)
                    it.start()
                  }
                e(error) { "Unknown issue." }
              }
            }
          } else {
            pageToRestoreTo = pageToRequest
          }
        }
      )
  }

  override fun onRefresh() {
    loadData(true)
  }

  override fun onRequestScrollToTop() {
    if (layoutManager.findFirstVisibleItemPosition() > 50) {
      recyclerView.scrollToPosition(0)
    } else {
      recyclerView.smoothScrollToPosition(0)
    }
  }

  private class TextAdapter(
    private val bindDelegate: (CatchUpItem, CatchUpItemViewHolder, clicksChannel: SendChannel<UrlMeta>) -> Unit
  ) :
    DisplayableItemAdapter<CatchUpItem, ViewHolder>() {

    private var showLoadingMore = false

    init {
      setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
      if (getItemViewType(position) == TYPE_LOADING_MORE) {
        return RecyclerView.NO_ID
      }
      return data[position].stableId()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
      val layoutInflater = LayoutInflater.from(parent.context)
      when (viewType) {
        TYPE_ITEM -> return CatchUpItemViewHolder.create(parent)
        TYPE_LOADING_MORE -> return LoadingMoreHolder(
          layoutInflater.inflate(
            R.layout.infinite_loading,
            parent,
            false
          )
        )
      }
      throw InvalidParameterException("Unrecognized view type - $viewType")
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
      when (getItemViewType(position)) {
        TYPE_ITEM ->
          try {
            bindDelegate(data[position], holder as CatchUpItemViewHolder, clicksChannel())
          } catch (error: Exception) {
            e(error) { "Bind delegate failure!" }
          }

        TYPE_LOADING_MORE -> (holder as LoadingMoreHolder).progress.visibility = if (position > 0) View.VISIBLE else View.INVISIBLE
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
  }
}

class LoadingMoreHolder(itemView: View) : ViewHolder(itemView) {
  val progress: ProgressBar = itemView as ProgressBar
}

@Suppress("unused")
internal sealed class LoadResult<T : DisplayableItem> {
  data class DiffResultData<T : DisplayableItem>(
    val data: List<T>,
    val diffResult: DiffResult
  ) : LoadResult<T>()

  data class NewData<T : DisplayableItem>(val newData: List<T>) : LoadResult<T>()
}

internal class ItemUpdateCallback<T : DisplayableItem>(
  private val oldItems: List<T>,
  private val newItems: List<T>
) : DiffUtil.Callback() {
  override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
    oldItems[oldItemPosition].stableId() == newItems[newItemPosition].stableId()

  override fun getOldListSize() = oldItems.size

  override fun getNewListSize() = newItems.size

  override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
    oldItems[oldItemPosition] == newItems[newItemPosition]
}
