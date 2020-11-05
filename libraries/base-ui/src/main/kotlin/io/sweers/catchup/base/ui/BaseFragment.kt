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
package io.sweers.catchup.base.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import com.uber.autodispose.ScopeProvider
import com.uber.autodispose.android.lifecycle.scope
import io.reactivex.CompletableSource
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

abstract class BaseFragment : Fragment(), ScopeProvider, BackpressHandler {

  companion object {
    private val DAY_MODE_CONF = Configuration().apply {
      uiMode = Configuration.UI_MODE_NIGHT_NO
    }
  }

  @Suppress("LeakingThis")
  private lateinit var lifecycleProvider: ScopeProvider
  protected var dayOnlyContext: Context? = null

  private var viewBindingProperty: ViewBindingProperty<*>? = null
    set(value) {
      require(value != null)
      check(field == null)
      check(view == null) { "You cannot set the binding property after the view is created" }
      field = value
      viewLifecycleOwnerLiveData.observeForever {
        if (it == null) {
          value.destroy()
        }
      }
    }

  override fun onAttach(context: Context) {
    super.onAttach(context)
    dayOnlyContext = context.createConfigurationContext(DAY_MODE_CONF)
  }

  final override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View? {
    lifecycleProvider = viewLifecycleOwner.scope()
    return initView(inflater, container, savedInstanceState)
  }

  // TODO remove when compose fragment is separated
  protected open fun initView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View? {
    return viewBindingProperty?.inflate(inflater, container)
      ?: super.onCreateView(inflater, container, savedInstanceState)
  }

  protected fun <VB : ViewBinding> viewBinding(
    creator: (LayoutInflater, ViewGroup?, Boolean) -> VB
  ): ReadOnlyProperty<BaseFragment, VB> = ViewBindingProperty(creator)
    .also { viewBindingProperty = it }

  override fun onDestroyView() {
    dayOnlyContext = null
    super.onDestroyView()
  }

  override fun requestScope(): CompletableSource {
    return lifecycleProvider.requestScope()
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    if (item.itemId == android.R.id.home) {
      return onBackPressed()
    }
    return super.onOptionsItemSelected(item)
  }

  override fun onBackPressed(): Boolean {
    childFragmentManager.fragments.filterIsInstance<BackpressHandler>().forEach {
      if (it.onBackPressed()) {
        return true
      }
    }
    return false
  }
}

private class ViewBindingProperty<VB : ViewBinding>(
  private val creator: (LayoutInflater, ViewGroup?, Boolean) -> VB
) : ReadOnlyProperty<Fragment, VB> {
  private var binding: VB? = null

  fun inflate(inflater: LayoutInflater, root: ViewGroup?): View {
    check(binding == null)
    return creator(inflater, root, false)
      .also { binding = it }
      .root
  }

  fun destroy() {
    binding = null
  }

  override fun getValue(thisRef: Fragment, property: KProperty<*>): VB {
    if (thisRef.view == null) {
      error("View is not attached")
    }
    return binding ?: error("The binding and fragment view are not in sync. This shouldn't happen.")
  }
}

interface BackpressHandler {
  fun onBackPressed(): Boolean
}
