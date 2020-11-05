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

import android.os.Bundle
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import io.sweers.catchup.base.ui.InjectingBaseFragment
import io.sweers.catchup.databinding.FragmentService2Binding
import io.sweers.catchup.ui.Scrollable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

@AndroidEntryPoint
class ServiceFragment2 : InjectingBaseFragment(), Scrollable {

  companion object {
    private const val ARG_SERVICE_KEY = "serviceKey"
    fun newInstance(serviceKey: String) =
      ServiceFragment2().apply {
        arguments = bundleOf(ARG_SERVICE_KEY to serviceKey)
      }
  }

  @Inject
  lateinit var catchUpItemRepository: CatchUpItemRepository

  private val binding by viewBinding(FragmentService2Binding::inflate)
  private val layoutManager: LinearLayoutManager
    get() = binding.list.layoutManager as LinearLayoutManager
  private lateinit var adapter: ItemsAdapter

  private val model: ServiceViewModel by viewModels {
    object : AbstractSavedStateViewModelFactory(this, null) {
      override fun <T : ViewModel?> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle
      ): T {
        @Suppress("UNCHECKED_CAST")
        return ServiceViewModel(
          catchUpItemRepository,
          handle,
          requireArguments().getString(ARG_SERVICE_KEY)!!
        ) as T
      }
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    // TODO should we show this separately for empty states?
//    binding.loadStatusContainer.retryButton.setOnClickListener {
//      onRetry()
//    }
//    binding.loadStatusContainer.errorImage.setOnClickListener {
//      onErrorClick(binding.loadStatusContainer.errorImage)
//    }
    @ColorInt val accentColor = ContextCompat.getColor(view.context, model.serviceMeta.themeColor)
    @ColorInt val dayAccentColor = ContextCompat.getColor(
      dayOnlyContext!!,
      model.serviceMeta.themeColor
    )
    binding.refresh.run {
      setColorSchemeColors(dayAccentColor)
      setOnRefreshListener { adapter.refresh() }
    }
//    binding.loadStatusContainer.progress.indeterminateTintList = ColorStateList.valueOf(accentColor)

    adapter = ItemsAdapter()
    // TODO handle grids and image views
    // TODO click handling
    binding.list.layoutManager = LinearLayoutManager(view.context, RecyclerView.VERTICAL, false)
    binding.list.adapter = adapter.withLoadStateHeaderAndFooter(
      header = ItemsLoadStateAdapter(adapter),
      footer = ItemsLoadStateAdapter(adapter)
    )

    lifecycleScope.launchWhenCreated {
      adapter.loadStateFlow.collectLatest { loadStates ->
        binding.refresh.isRefreshing = loadStates.refresh is LoadState.Loading
      }
    }

    lifecycleScope.launchWhenCreated {
      model.posts
        .flowOn(Dispatchers.IO)
        .collectLatest {
          adapter.submitData(it)
        }
    }

    lifecycleScope.launchWhenCreated {
      adapter.loadStateFlow
        // Only emit when REFRESH LoadState for RemoteMediator changes.
        .distinctUntilChangedBy { it.refresh }
        // Only react to cases where Remote REFRESH completes i.e., NotLoading.
        .filter { it.refresh is LoadState.NotLoading }
        .collect { binding.list.scrollToPosition(0) }
    }
  }

  override fun onRequestScrollToTop() {
    if (layoutManager.findFirstVisibleItemPosition() > 50) {
      binding.list.scrollToPosition(0)
    } else {
      binding.list.smoothScrollToPosition(0)
    }
  }
}
