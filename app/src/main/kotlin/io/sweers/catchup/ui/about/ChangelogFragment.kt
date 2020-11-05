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
package io.sweers.catchup.ui.about

import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView.Adapter
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.rx2.Rx2Apollo
import com.google.android.material.snackbar.Snackbar
import com.uber.autodispose.autoDispose
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import io.sweers.catchup.R
import io.sweers.catchup.base.ui.InjectableBaseFragment
import io.sweers.catchup.data.LinkManager
import io.sweers.catchup.data.github.RepoReleasesQuery
import io.sweers.catchup.databinding.FragmentChangelogBinding
import io.sweers.catchup.gemoji.EmojiMarkdownConverter
import io.sweers.catchup.gemoji.replaceMarkdownEmojisIn
import io.sweers.catchup.service.api.UrlMeta
import io.sweers.catchup.ui.Scrollable
import io.sweers.catchup.ui.base.CatchUpItemViewHolder
import io.sweers.catchup.util.e
import io.sweers.catchup.util.hide
import io.sweers.catchup.util.w
import jp.wasabeef.recyclerview.animators.FadeInUpAnimator
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import java.io.IOException
import javax.inject.Inject

@AndroidEntryPoint
class ChangelogFragment : InjectableBaseFragment(), Scrollable {

  @Inject
  lateinit var apolloClient: ApolloClient
  @Inject
  internal lateinit var linkManager: LinkManager
  @Inject
  internal lateinit var markdownConverter: EmojiMarkdownConverter

  private val binding by viewBinding(FragmentChangelogBinding::inflate)
  private val progressBar get() = binding.progress
  private val recyclerView get() = binding.list

  private lateinit var layoutManager: LinearLayoutManager
  private lateinit var adapter: ChangelogAdapter

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    adapter = ChangelogAdapter()
    recyclerView.adapter = adapter
    layoutManager = LinearLayoutManager(view.context)
    recyclerView.layoutManager = layoutManager
    var pendingRvState: Parcelable? = null
    if (savedInstanceState == null) {
      recyclerView.itemAnimator = FadeInUpAnimator(OvershootInterpolator(1f)).apply {
        addDuration = 300
        removeDuration = 300
      }
    } else {
      pendingRvState = savedInstanceState.getParcelable("changeloglm")
    }

    requestItems()
      .subscribeOn(Schedulers.io())
      .observeOn(AndroidSchedulers.mainThread())
      .doFinally {
        progressBar.hide()
      }
      .autoDispose(this)
      .subscribe { data, error ->
        if (data != null) {
          adapter.setItems(data)
          pendingRvState?.let(layoutManager::onRestoreInstanceState)
        } else {
          // TODO Show a better error
          if (error is IOException) {
            w(error) { "Could not load changelog." }
          } else {
            e(error) { "Could not load changelog." }
          }
          Snackbar.make(
            recyclerView,
            R.string.changelog_error,
            Snackbar.LENGTH_SHORT
          )
            .show()
        }
      }
  }

  private fun requestItems(): Single<List<ChangeLogItem>> {
    return Rx2Apollo.from(apolloClient.query(RepoReleasesQuery()))
      .flatMapIterable { it.data!!.repository!!.releases.nodes }
      .map {
        with(it) {
          ChangeLogItem(
            name = name!!,
            timestamp = publishedAt!!,
            tag = tag!!.name,
            sha = tag.target.abbreviatedOid,
            url = url.toString(),
            description = description!!
          )
        }
      }
      .map {
        it.copy(
          name = markdownConverter.replaceMarkdownEmojisIn(it.name),
          description = markdownConverter.replaceMarkdownEmojisIn(it.description)
        )
      }
      .toList()
  }

  override fun onRequestScrollToTop() {
    if (layoutManager.findFirstVisibleItemPosition() > 50) {
      recyclerView.scrollToPosition(0)
    } else {
      recyclerView.smoothScrollToPosition(0)
    }
  }

  private inner class ChangelogAdapter : Adapter<CatchUpItemViewHolder>() {

    private val items = mutableListOf<ChangeLogItem>()

    fun setItems(newItems: List<ChangeLogItem>) {
      items.addAll(newItems)
      notifyItemRangeInserted(0, newItems.size)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CatchUpItemViewHolder {
      return CatchUpItemViewHolder.create(parent)
    }

    override fun onBindViewHolder(holder: CatchUpItemViewHolder, position: Int) {
      val item = items[position]
      holder.apply {
        title(item.name)
        tag(item.tag)
        timestamp(item.timestamp)
        source(item.sha)
        author(null)
        hideMark()
        holder.container.setOnClickListener {
          viewLifecycleOwner.lifecycleScope.launch {
            linkManager.openUrl(UrlMeta(item.url, 0, itemView.context))
          }
        }
      }
    }
  }
}

private data class ChangeLogItem(
  val name: String,
  val timestamp: Instant,
  val tag: String,
  val sha: String,
  val url: String,
  val description: String
)
