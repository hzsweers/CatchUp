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

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import androidx.palette.graphics.Palette.Swatch
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import coil.load
import coil.transform.CircleCropTransformation
import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.ApolloCall.StatusEvent
import com.apollographql.apollo.ApolloCall.StatusEvent.COMPLETED
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Response
import com.apollographql.apollo.api.cache.http.HttpCachePolicy
import com.apollographql.apollo.exception.ApolloException
import com.google.android.material.snackbar.Snackbar
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import dagger.hilt.android.AndroidEntryPoint
import io.sweers.catchup.R
import io.sweers.catchup.R.layout
import io.sweers.catchup.base.ui.InjectableBaseFragment
import io.sweers.catchup.base.ui.findSwatch
import io.sweers.catchup.base.ui.generateAsync
import io.sweers.catchup.base.ui.luminosity
import io.sweers.catchup.data.LinkManager
import io.sweers.catchup.data.github.ProjectOwnersByIdsQuery
import io.sweers.catchup.data.github.RepositoriesByIdsQuery
import io.sweers.catchup.data.github.RepositoryByNameAndOwnerQuery
import io.sweers.catchup.databinding.AboutHeaderItemBinding
import io.sweers.catchup.databinding.FragmentLicensesBinding
import io.sweers.catchup.flowbinding.safeOffer
import io.sweers.catchup.gemoji.EmojiMarkdownConverter
import io.sweers.catchup.gemoji.replaceMarkdownEmojisIn
import io.sweers.catchup.service.api.TemporaryScopeHolder
import io.sweers.catchup.service.api.UrlMeta
import io.sweers.catchup.service.api.temporaryScope
import io.sweers.catchup.ui.Scrollable
import io.sweers.catchup.ui.StickyHeaders
import io.sweers.catchup.ui.StickyHeadersLinearLayoutManager
import io.sweers.catchup.ui.base.CatchUpItemViewHolder
import io.sweers.catchup.util.UiUtil
import io.sweers.catchup.util.dp2px
import io.sweers.catchup.util.hide
import io.sweers.catchup.util.isInNightMode
import io.sweers.catchup.util.kotlin.distinct
import io.sweers.catchup.util.kotlin.groupBy
import io.sweers.catchup.util.kotlin.sortBy
import io.sweers.catchup.util.w
import jp.wasabeef.recyclerview.animators.FadeInUpAnimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flattenConcat
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import javax.inject.Inject
import kotlin.LazyThreadSafetyMode.NONE

/**
 * A fragment that displays oss licenses.
 */
@AndroidEntryPoint
class LicensesFragment : InjectableBaseFragment(), Scrollable {

  @Inject
  lateinit var apolloClient: ApolloClient

  @Inject
  lateinit var moshi: Moshi

  @Inject
  internal lateinit var linkManager: LinkManager

  @Inject
  internal lateinit var markdownConverter: EmojiMarkdownConverter

  private val dimenSize by lazy(NONE) {
    resources.getDimensionPixelSize(R.dimen.avatar)
  }

  private val binding by viewBinding(FragmentLicensesBinding::inflate)
  private val progressBar get() = binding.progress
  private val recyclerView get() = binding.list

  private lateinit var adapter: Adapter
  private lateinit var layoutManager: StickyHeadersLinearLayoutManager<Adapter>

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putParcelable("changelogState", layoutManager.onSaveInstanceState())
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    adapter = Adapter()
    recyclerView.adapter = adapter
    layoutManager = StickyHeadersLinearLayoutManager(view.context)
    recyclerView.layoutManager = layoutManager
    var pendingRvState: Parcelable? = null
    if (savedInstanceState == null) {
      recyclerView.itemAnimator = FadeInUpAnimator(OvershootInterpolator(1f)).apply {
        addDuration = 300
        removeDuration = 300
      }
    } else {
      pendingRvState = savedInstanceState.getParcelable("changelogState")
    }

    viewLifecycleOwner.lifecycleScope.launch {
      try {
        val data = requestItems()
        adapter.setItems(data)
        pendingRvState?.let(layoutManager::onRestoreInstanceState)
      } catch (error: Exception) {
        // TODO Show a better error
        w(error) { "Could not load open source licenses." }
        Snackbar.make(recyclerView, R.string.licenses_error, Snackbar.LENGTH_SHORT).show()
      } finally {
        progressBar.hide()
      }
    }
  }

  /**
   * I give you: the most over-engineered OSS licenses section ever.
   */
  private suspend fun requestItems(): List<OssBaseItem> {
    // Start with a fetch of our github entries from assets
    val githubEntries = withContext(Dispatchers.Default) {
      val adapter = moshi.adapter<List<OssGitHubEntry>>()
      val regular = adapter.fromJson(resources.assets.open("licenses_github.json").source().buffer())!!
      val generated = adapter.fromJson(resources.assets.open("generated_licenses.json").source().buffer())!!
      return@withContext regular + generated
    }
    // Fetch repos, send down a map of the ids to owner ids
    val idsToOwnerIds = githubEntries.asFlow()
      .map { RepositoryByNameAndOwnerQuery(it.owner, it.name) }
      .flatMapMerge {
        apolloClient.query(it)
          .toBuilder()
          .httpCachePolicy(HttpCachePolicy.CACHE_FIRST)
          .build()
          .toFlow()
          .map {
            with(it.data!!.repository!!) {
              id to owner.id
            }
          }
          .flowOn(Dispatchers.IO)
      }
      .distinct()
      .fold(mutableMapOf()) { map: MutableMap<String, String>, (first, second) ->
        map.apply {
          put(first, second)
        }
      }

    // Fetch the users by their IDs
    val userIdToNameMap = withContext(Dispatchers.IO) {
      apolloClient.query(ProjectOwnersByIdsQuery(idsToOwnerIds.values.distinct()))
        .toBuilder()
        .httpCachePolicy(HttpCachePolicy.CACHE_FIRST)
        .build()
        .toFlow()
        .map { it.data!!.nodes.filterNotNull().asFlow() }
        .flattenConcat()
        // Reduce into a map of the owner ID -> display name
        .fold(mutableMapOf<String, String>()) { map, node ->
          map.apply {
            node.asOrganization?.run { map[id] = (name ?: login) }
            node.asUser?.run { map[id] = (name ?: login) }
          }
        }
    }
    // Fetch the repositories by their IDs, map down to its
    return apolloClient.query(RepositoriesByIdsQuery(idsToOwnerIds.keys.toList()))
      .toBuilder()
      .httpCachePolicy(HttpCachePolicy.CACHE_FIRST)
      .build()
      .toFlow()
      .map {
        it.data!!.nodes.asSequence()
          .mapNotNull { it?.asRepository }
          .asFlow()
      }
      .flattenConcat()
      .map { it to userIdToNameMap.getValue(it.owner.id) }
      .map { (repo, ownerName) ->
        OssItem(
          avatarUrl = repo.owner.avatarUrl.toString(),
          author = ownerName,
          name = repo.name,
          clickUrl = repo.url.toString(),
          license = repo.licenseInfo?.name,
          description = repo.description
        )
      }
      .onStart {
        moshi.adapter<List<OssItem>>()
          .fromJson(resources.assets.open("licenses_mixins.json").source().buffer())!!
          .forEach { emit(it) }
      }
      .flowOn(Dispatchers.IO)
      .groupBy { it.author }
      .sortBy { it.first }
      .flatMapConcat { it.second.asFlow().sortBy { it.name } }
      .map {
        // TODO use CopyDynamic when 0.3.0 is out
        it.copy(
          author = markdownConverter.replaceMarkdownEmojisIn(it.author),
          name = markdownConverter.replaceMarkdownEmojisIn(it.name),
          description = it.description?.let {
            markdownConverter.replaceMarkdownEmojisIn(it)
          } ?: it.description
        )
      }
      .flowOn(Dispatchers.IO)
      .toList()
      .let {
        val collector = mutableListOf<OssBaseItem>()
        with(it[0]) {
          collector += OssItemHeader(
            name = author,
            avatarUrl = avatarUrl
          )
        }
        it.fold(it[0].author) { lastAuthor, currentItem ->
          if (currentItem.author != lastAuthor) {
            collector += OssItemHeader(
              name = currentItem.author,
              avatarUrl = currentItem.avatarUrl
            )
          }
          collector += currentItem
          currentItem.author
        }
        collector
      }
  }

  override fun onRequestScrollToTop() {
    if (layoutManager.findFirstVisibleItemPosition() > 50) {
      recyclerView.scrollToPosition(0)
    } else {
      recyclerView.smoothScrollToPosition(0)
    }
  }

  private inner class Adapter :
    RecyclerView.Adapter<ViewHolder>(),
    StickyHeaders,
    StickyHeaders.ViewSetup {

    private val items = mutableListOf<OssBaseItem>()
    private val headerColorThresholdFun = { swatch: Swatch ->
      if (activity?.isInNightMode() == true) {
        swatch.luminosity > 0.6F
      } else {
        swatch.luminosity < 0.5F
      }
    }

    private val defaultHeaderTextColor by lazy {
      if (activity?.isInNightMode() == true) Color.WHITE else Color.BLACK
    }

    override fun isStickyHeader(position: Int) = items[position] is OssItemHeader

    override fun setupStickyHeaderView(stickyHeader: View) {
      stickyHeader.animate()
        .z(stickyHeader.resources.dp2px(4f))
        .setInterpolator(UiUtil.fastOutSlowInInterpolator)
        .setDuration(200)
        .start()
    }

    override fun teardownStickyHeaderView(stickyHeader: View) {
      with(stickyHeader) {
        findViewById<TextView>(R.id.title).setTextColor(defaultHeaderTextColor)
        animate()
          .z(0f)
          .setInterpolator(UiUtil.fastOutSlowInInterpolator)
          .setDuration(200)
          .start()
      }
    }

    fun setItems(newItems: List<OssBaseItem>) {
      items.addAll(newItems)
      notifyItemRangeInserted(0, newItems.size)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
      when (val item = items[position]) {
        is OssItemHeader -> (holder as HeaderHolder).run {
          icon.load(item.avatarUrl) {
            transformations(CircleCropTransformation())
            size(dimenSize, dimenSize)
            listener(
              onSuccess = { _, _ ->
                val bitmap: Bitmap? = when (val data = icon.drawable) {
                  is BitmapDrawable -> data.bitmap
                  else -> null
                }
                bitmap?.let {
                  newScope().launch {
                    val color = Palette.from(it)
                      .clearFilters()
                      .generateAsync()
                      ?.findSwatch(headerColorThresholdFun)
                      ?.rgb
                      ?: defaultHeaderTextColor
                    holder.title.setTextColor(color)
                  }
                }
              }
            )
          }
          title.text = item.name
        }
        is OssItem -> (holder as CatchUpItemViewHolder).apply {
          // Set the title top margin to 0dp as layout_goneMarginTop doesn't work.
          // See https://issuetracker.google.com/issues/68768935
          setTitleTopMargin(holder)
          title(
            "${item.name}${if (!item.description.isNullOrEmpty()) " — ${item.description}" else ""}"
          )
          score(null)
          timestamp(null)
          author(item.license)
          source(null)
          tag(null)
          hideMark()
          holder.container.setOnClickListener {
            // Search up to the first sticky header position
            // Maybe someday we should just return the groups rather than flattening
            // but this was neat to write in kotlin
            val accentColor = (holder.bindingAdapterPosition downTo 0)
              .find(::isStickyHeader)
              ?.let {
                (
                  recyclerView.findViewHolderForAdapterPosition(it)
                    as HeaderHolder
                  )
                  .title.textColors.defaultColor
              } ?: 0
            val context = itemView.context
            viewLifecycleOwner.lifecycleScope.launch {
              linkManager.openUrl(
                UrlMeta(
                  item.clickUrl,
                  accentColor,
                  context
                )
              )
            }
          }
        }
      }
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
      return if (viewType == 0) {
        HeaderHolder(
          LayoutInflater.from(parent.context)
            .inflate(layout.about_header_item, parent, false)
        )
      } else {
        CatchUpItemViewHolder.create(parent)
      }
    }

    override fun getItemViewType(position: Int) = items[position].itemType()
  }

  private fun setTitleTopMargin(holder: CatchUpItemViewHolder) = with(holder) {
    with(title) {
      val lp = layoutParams as ViewGroup.MarginLayoutParams
      if (lp.topMargin != 0) {
        layoutParams = lp.apply { this.topMargin = 0 }
      }
    }
  }
}

@JsonClass(generateAdapter = true)
internal data class OssGitHubEntry(val owner: String, val name: String)

private class HeaderHolder(view: View) :
  ViewHolder(
    view
  ),
  TemporaryScopeHolder by temporaryScope() {
  val binding = AboutHeaderItemBinding.bind(view)
  val icon = binding.icon
  val title = binding.title
}

internal sealed class OssBaseItem {
  abstract fun itemType(): Int
}

internal data class OssItemHeader(
  val avatarUrl: String,
  val name: String
) : OssBaseItem() {
  override fun itemType() = 0
}

@JsonClass(generateAdapter = true)
internal data class OssItem(
  val avatarUrl: String,
  val author: String,
  val name: String,
  val license: String?,
  val clickUrl: String,
  val description: String?,
  val authorUrl: String? = null
) : OssBaseItem() {
  override fun itemType() = 1
}

/**
 * Converts an [ApolloCall] to a [Flow].
 */
suspend fun <T> ApolloCall<T>.toFlow(): Flow<Response<T>> = callbackFlow<Response<T>> {
  enqueue(
    object : ApolloCall.Callback<T>() {
      override fun onResponse(response: Response<T>) {
        safeOffer(response)
      }

      override fun onFailure(e: ApolloException) {
        throw e
      }

      override fun onStatusEvent(event: StatusEvent) {
        if (event == COMPLETED && !isClosedForSend) {
          close()
        }
      }
    }
  )
  awaitClose {
    if (!isCanceled) {
      cancel()
    }
  }
}.conflate()
