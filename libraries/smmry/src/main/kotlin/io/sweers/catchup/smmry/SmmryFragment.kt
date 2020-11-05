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
package io.sweers.catchup.smmry

import android.annotation.SuppressLint
import android.graphics.ColorFilter
import android.os.Bundle
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.SimpleColorFilter
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import dagger.hilt.android.AndroidEntryPoint
import io.sweers.catchup.base.ui.InjectableBaseFragment
import io.sweers.catchup.service.api.ScrollableContent
import io.sweers.catchup.service.api.SummarizationInfo
import io.sweers.catchup.service.api.SummarizationType
import io.sweers.catchup.service.api.SummarizationType.NONE
import io.sweers.catchup.service.api.SummarizationType.TEXT
import io.sweers.catchup.service.api.SummarizationType.URL
import io.sweers.catchup.smmry.SmmryModule.ForSmmry
import io.sweers.catchup.smmry.databinding.FragmentSmmryBinding
import io.sweers.catchup.smmry.model.SmmryDao
import io.sweers.catchup.smmry.model.SmmryRequestBuilder
import io.sweers.catchup.smmry.model.SmmryResponse
import io.sweers.catchup.smmry.model.SmmryStorageEntry
import io.sweers.catchup.util.hide
import io.sweers.catchup.util.show
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Overlay fragment for displaying Smmry API results.
 */
@AndroidEntryPoint
class SmmryFragment : InjectableBaseFragment(), ScrollableContent {

  companion object {
    private const val ID_TITLE = "smmryfragment.title"
    private const val ID_ID = "smmryfragment.id"
    private const val ID_VALUE = "smmryfragment.value"
    private const val ID_TYPE = "smmryfragment.type"
    private const val ID_ACCENT = "smmryfragment.accent"
    private const val ID_LOADED = "smmryfragment.loaded"

    fun newInstance(
      id: String,
      @ColorInt accentColor: Int,
      inputTitle: String,
      info: SummarizationInfo
    ): SmmryFragment {
      return SmmryFragment().apply {
        arguments = bundleOf(
          ID_ID to id,
          ID_ACCENT to accentColor,
          ID_TITLE to inputTitle,
          ID_VALUE to info.value,
          ID_TYPE to info.type.name
        )
      }
    }
  }

  @Inject
  lateinit var smmryService: SmmryService
  @ForSmmry
  @Inject
  lateinit var moshi: Moshi
  @Inject
  lateinit var smmryDao: SmmryDao

  private val binding by viewBinding(FragmentSmmryBinding::inflate)
  private val loadingView get() = binding.loadingView
  private val lottieView get() = binding.smmryLoading
  private val content get() = binding.contentContainer
  private val tags get() = binding.tags
  private val title get() = binding.title
  private val summary get() = binding.summary

  private lateinit var id: String
  private lateinit var info: SummarizationInfo
  @ColorInt
  private var accentColor: Int = 0
  private lateinit var inputTitle: String
  private var alreadyLoaded = false

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.run {
      putString(ID_TITLE, inputTitle)
      putString(ID_ID, id)
      putString(ID_VALUE, info.value)
      putString(ID_TYPE, info.type.name)
      putInt(ID_ACCENT, accentColor)
      putBoolean(ID_LOADED, alreadyLoaded)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    (savedInstanceState ?: arguments)?.run {
      inputTitle = getString(ID_TITLE)!!
      id = getString(ID_ID)!!
      val value = getString(ID_VALUE)!!
      val type = SummarizationType.valueOf(getString(ID_TYPE)!!)
      info = SummarizationInfo(value, type)
      accentColor = getInt(ID_ACCENT)
      alreadyLoaded = getBoolean(ID_LOADED, false)
    }
  }

  @SuppressLint("RestrictedApi") // False positive
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    content.isNestedScrollingEnabled = true
    title.text = inputTitle
    if (!alreadyLoaded) {
      loadingView.show()
      lottieView.addValueCallback(
        KeyPath("**"),
        LottieProperty.COLOR_FILTER,
        LottieValueCallback<ColorFilter>(SimpleColorFilter(accentColor))
      )
    } else {
      loadingView.hide()
    }
    viewLifecycleOwner.lifecycleScope.launch {
      val response = tryRequestFromStorage() ?: fetchFromNetwork()
      if (!isActive) {
        // TODO why is this necessary? One of the above suspends are not cooperating with isActive()
        return@launch
      }
      alreadyLoaded = true
      loadingView.hide(animate = true)
      when (response) {
        is SmmryResponse.Success -> showSummary(response)
        is SmmryResponse.Failure -> summary.text = response.normalizedMessage
      }
    }
  }

  override fun canScrollVertically(directionInt: Int): Boolean {
    return content.canScrollVertically(directionInt)
  }

  private suspend fun tryRequestFromStorage() = smmryDao.getItem(id)?.let {
    moshi.adapter(SmmryResponse::class.java).fromJson(it.json) ?: throw JsonDataException(
      "Could not parse entry"
    )
  }

  private suspend fun fetchFromNetwork(): SmmryResponse {
    val response: SmmryResponse = try {
      when (info.type) {
        TEXT -> smmryService.summarizeText(
          SmmryRequestBuilder.forText()
            .withBreak(true)
            .keywordCount(5)
            .sentenceCount(5)
            .build(),
          info.value
        )
        URL -> smmryService.summarizeUrl(
          SmmryRequestBuilder.forUrl(info.value)
            .withBreak(true)
            .keywordCount(5)
            .sentenceCount(5)
            .build()
        )
        NONE -> SmmryResponse.Success.just(inputTitle, info.value)
      }
    } catch (error: Exception) {
      // We should indicate when something's network related
      SmmryResponse.Failure.UnknownErrorCode
    }
    if (response != SmmryResponse.Failure.UnknownErrorCode) {
      smmryDao.putItem(
        SmmryStorageEntry(
          url = id,
          json = moshi.adapter(SmmryResponse::class.java).toJson(response)
        )
      )
    }
    return response
  }

  @SuppressLint("SetTextI18n")
  private fun showSummary(smmry: SmmryResponse.Success) {
    if (smmry.keywords != null) {
      tags.setTextColor(accentColor)
      tags.text = smmry.keywords.joinToString("  —  ") { s ->
        s.trim { it <= ' ' }.toUpperCase(Locale.getDefault())
      }
      tags.show()
    } else {
      tags.hide()
    }
    summary.text = smmry.title.takeIf { it.isNotEmpty() }?.let { "$it\n\n" }.orEmpty() + smmry.content
      .replace("[BREAK]", "\n\n")
    if (content.isGone) {
      content.show(true)
    }
  }
}
