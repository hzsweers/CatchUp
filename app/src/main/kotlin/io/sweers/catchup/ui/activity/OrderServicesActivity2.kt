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
package io.sweers.catchup.ui.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.expandIn
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.InteractionState
import androidx.compose.foundation.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ConstrainedLayoutReference
import androidx.compose.foundation.layout.ConstraintLayout
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumnFor
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.TopAppBar
import androidx.compose.material.ripple.RippleIndication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.globalPosition
import androidx.compose.ui.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.loadVectorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.chibatching.kotpref.bulk
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.components.FragmentComponent
import dagger.multibindings.Multibinds
import dev.zacsweers.catchup.appconfig.AppConfig
import dev.zacsweers.catchup.compose.CatchUpTheme
import dev.zacsweers.catchup.compose.accessibilityLabel
import io.sweers.catchup.CatchUpPreferences
import io.sweers.catchup.R
import io.sweers.catchup.base.ui.InjectableBaseFragment
import io.sweers.catchup.edu.Syllabus
import io.sweers.catchup.flowFor
import io.sweers.catchup.injection.DaggerMap
import io.sweers.catchup.service.api.ServiceMeta
import io.sweers.catchup.ui.FontHelper
import io.sweers.catchup.util.setLightStatusBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/*
 * This is a WIP implementation of OrderServices view with Compose.
 *
 * TODO:
 *  * Syllabus handling - requires integrating tap target on the location
 *  * Drag-and-drop - compose doesn't support this out of the box.
 *    * An intermediate solution might be to add up/down arrows
 */

private class OrderServicesViewModel(
  serviceMetasMap: DaggerMap<String, ServiceMeta>,
  private val catchUpPreferences: CatchUpPreferences
) : ViewModel() {

  private val storedOrder: StateFlow<List<String>>
  private val _serviceMetas: MutableStateFlow<List<ServiceMeta>>
  val serviceMetas: StateFlow<List<ServiceMeta>> get() = _serviceMetas
  val canSave: StateFlow<Boolean>

  init {
    val initialOrder = catchUpPreferences.servicesOrder?.split(",") ?: emptyList()
    val initialOrderedServices = serviceMetasMap.values.sortedBy { initialOrder.indexOf(it.id) }
    storedOrder = catchUpPreferences.flowFor { ::servicesOrder }
      .drop(1) // Ignore the initial value we read synchronously
      .map { it?.split(",") ?: emptyList() }
      .stateIn(viewModelScope, SharingStarted.Lazily, initialOrder)
    _serviceMetas = MutableStateFlow(initialOrderedServices)

    viewModelScope.launch {
      storedOrder
        .map { newOrder ->
          serviceMetasMap.values.sortedBy { newOrder.indexOf(it.id) }
        }
        .collect {
          _serviceMetas.value = it
        }
    }

    canSave = serviceMetas
      .map { it != initialOrderedServices }
      .stateIn(viewModelScope, SharingStarted.Lazily, false)
  }

  fun shuffle() {
    val items = serviceMetas.value.toMutableList()
    items.shuffle()
    _serviceMetas.value = items
  }

  fun save() {
    catchUpPreferences.bulk {
      servicesOrder = serviceMetas.value.joinToString(",", transform = ServiceMeta::id)
    }
  }

  fun shouldShowSyllabus(): Boolean {
    // TODO implement this
    return false
  }
}

@AndroidEntryPoint
class OrderServicesFragment2 : InjectableBaseFragment() {

  @Inject
  lateinit var serviceMetas: DaggerMap<String, ServiceMeta>

  @Inject
  lateinit var catchUpPreferences: CatchUpPreferences

  @Inject
  internal lateinit var syllabus: Syllabus

  @Inject
  internal lateinit var fontHelper: FontHelper

  @Inject
  internal lateinit var appConfig: AppConfig

  // Have to do this here because we can't set a factory separately
  @Suppress("UNCHECKED_CAST")
  private val viewModel by viewModels<OrderServicesViewModel> {
    object : ViewModelProvider.Factory {
      override fun <T : ViewModel?> create(modelClass: Class<T>): T {
        return OrderServicesViewModel(serviceMetas, catchUpPreferences) as T
      }
    }
  }

  override fun initView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    return ComposeView(inflater.context).apply {
      setLightStatusBar(appConfig)
      setContent {
        CatchUpTheme {
          // Have to pass the viewmodel here rather than use compose' viewModel() because otherwise it
          // will try the reflective instantiation since the factory isn't initialized because lol
          prepareContent(viewModel)
        }
      }
    }
  }

  @OptIn(ExperimentalAnimationApi::class)
  @Composable
  private fun prepareContent(viewModel: OrderServicesViewModel) {
    val canSave: Boolean by viewModel.canSave.collectAsState()
    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(stringResource(id = R.string.pref_reorder_services))
          },
          navigationIcon = {
            IconButton(
              onClick = ::onBackPressed,
              icon = {
                Image(vectorResource(id = R.drawable.ic_arrow_back_black_24dp))
              }
            )
          },
          actions = {
            IconButton(
              onClick = viewModel::shuffle,
              icon = {
                Image(vectorResource(id = R.drawable.ic_shuffle_black_24dp))
              },
              modifier = Modifier.accessibilityLabel(resId = R.string.shuffle)
            )
          }
        )
      },
      bodyContent = { prepareBody(viewModel) },
      floatingActionButton = {
        AnimatedVisibility(
          visible = canSave,
          enter = expandIn(expandFrom = Alignment.Center),
          exit = shrinkOut(shrinkTowards = Alignment.Center)
        ) {
          // TODO ripple color?
          FloatingActionButton(
            modifier = Modifier
              .accessibilityLabel(resId = R.string.save)
              .indication(
                InteractionState(),
                indication = RippleIndication(
                  color = Color.White
                )
              )
              .onGloballyPositioned { coordinates ->
                val (x, y) = coordinates.globalPosition
                // TODO show syllabus on fab
              },
            backgroundColor = colorResource(R.color.colorAccent),
            onClick = {
              viewModel.save()
              activity?.finish()
            },
            icon = {
              Image(
                vectorResource(R.drawable.ic_save_black_24dp),
                colorFilter = ColorFilter.tint(Color.White)
              )
            }
          )
        }
      }
    )
  }

  @Composable
  private fun prepareBody(viewModel: OrderServicesViewModel) {
    val currentItemsSorted by viewModel.serviceMetas.collectAsState()
    Surface(Modifier.fillMaxSize()) {
      LazyColumnFor(
        items = currentItemsSorted,
        modifier = Modifier,
        itemContent = { item ->
          Box(
            Modifier.background(colorResource(id = item.themeColor))
              .padding(16.dp)
          ) {
            ConstraintLayout(
              modifier = Modifier
                .fillMaxWidth() // TODO remove this...?
                .wrapContentHeight()
                .wrapContentWidth(Alignment.Start)
            ) {
              val (icon, spacer, text) = createRefs()
              val image = loadVectorResource(id = item.icon)
              var startRef: ConstrainedLayoutReference? = null
              image.resource.resource?.let {
                Image(
                  asset = it,
                  modifier = Modifier
                    .width(40.dp)
                    .height(40.dp)
                    .accessibilityLabel(R.string.service_icon)
                    .constrainAs(icon) {
                      bottom.linkTo(parent.bottom)
                      end.linkTo(spacer.start)
                      start.linkTo(parent.start)
                      top.linkTo(parent.top)
                    }
                )
                Spacer(
                  modifier = Modifier.width(8.dp)
                    .height(40.dp)
                    .constrainAs(spacer) {
                      bottom.linkTo(parent.bottom)
                      end.linkTo(text.start)
                      start.linkTo(icon.end)
                      top.linkTo(parent.top)
                    }
                )
                startRef = spacer
              }
              Text(
                text = stringResource(id = item.name),
                modifier = Modifier.constrainAs(text) {
                  val actualStartRef = startRef ?: parent
                  bottom.linkTo(parent.bottom)
                  end.linkTo(parent.end)
                  start.linkTo(actualStartRef.end)
                  top.linkTo(parent.top)
                },
                fontStyle = FontStyle.Normal,
                // TODO what about Subhead?
                style = MaterialTheme.typography.subtitle1,
                color = Color.White
              )
            }
          }
        }
      )
    }
  }

  override fun onBackPressed(): Boolean {
    if (viewModel.canSave.value) {
      AlertDialog.Builder(requireContext())
        .setTitle(R.string.pending_changes_title)
        .setMessage(R.string.pending_changes_message)
        .setNeutralButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
        .setPositiveButton(R.string.dontsave) { dialog, _ ->
          dialog.dismiss()
          activity?.finish()
        }
        .setNegativeButton(R.string.save) { dialog, _ ->
          dialog.dismiss()
          viewModel.save()
          activity?.finish()
        }
        .show()
      return true
    }
    activity?.finish()
    return false
  }
}

@InstallIn(FragmentComponent::class)
@Module
abstract class OrderServicesModule2 {

  @Multibinds
  abstract fun serviceMetas(): Map<String, ServiceMeta>
}
