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
package io.sweers.catchup.ui.fragments

import android.animation.Animator
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import androidx.core.content.ContextCompat
import androidx.core.util.set
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.adapter.FragmentViewHolder
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.components.FragmentComponent
import dev.zacsweers.catchup.appconfig.AppConfig
import io.sweers.catchup.CatchUpPreferences
import io.sweers.catchup.R
import io.sweers.catchup.base.ui.InjectingBaseFragment
import io.sweers.catchup.base.ui.updateNavBarColor
import io.sweers.catchup.changes.ChangelogHelper
import io.sweers.catchup.databinding.FragmentPagerBinding
import io.sweers.catchup.injection.DaggerMap
import io.sweers.catchup.service.api.ServiceMeta
import io.sweers.catchup.ui.Scrollable
import io.sweers.catchup.ui.activity.SettingsActivity
import io.sweers.catchup.ui.fragments.service.v2.ServiceFragment2
import io.sweers.catchup.util.clearLightStatusBar
import io.sweers.catchup.util.isInNightMode
import io.sweers.catchup.util.resolveAttributeColor
import io.sweers.catchup.util.setLightStatusBar
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ru.ldralighieri.corbind.material.offsetChanges
import javax.inject.Inject
import kotlin.math.abs

fun ServiceMeta.toServiceHandler() = ServiceHandler(
  name,
  icon,
  themeColor
) { ServiceFragment2.newInstance(id) }

data class ServiceHandler(
  @StringRes val name: Int,
  @DrawableRes val icon: Int,
  @ColorRes val accent: Int,
  val instantiator: () -> Fragment
)

@AndroidEntryPoint
class PagerFragment : InjectingBaseFragment() {

  companion object {
    private const val SETTINGS_ACTIVITY_REQUEST = 100
  }

  @Inject
  lateinit var resolvedColorCache: ColorCache

  @Inject
  lateinit var serviceHandlers: Array<ServiceHandler>

  @Inject
  lateinit var changelogHelper: ChangelogHelper

  @Inject
  lateinit var catchUpPreferences: CatchUpPreferences

  @Inject
  lateinit var appConfig: AppConfig

  private val binding by viewBinding(FragmentPagerBinding::inflate)
  private val rootLayout get() = binding.pagerFragmentRoot
  private val tabLayout get() = binding.tabLayout
  private val viewPager get() = binding.viewPager
  private val toolbar get() = binding.toolbar
  val appBarLayout get() = binding.appbarlayout

  private val argbEvaluator = ArgbEvaluator()
  private var statusBarColorAnimator: ValueAnimator? = null
  private var tabLayoutColorAnimator: Animator? = null
  private var tabLayoutIsPinned = false
  private var canAnimateColor = true
  private var lastPosition = 0

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    (appBarLayout.layoutParams as CoordinatorLayout.LayoutParams).behavior?.let { behavior ->
      outState.run {
        putParcelable(
          "collapsingToolbarState",
          behavior.onSaveInstanceState(rootLayout, appBarLayout)
        )
      }
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    viewPager.offscreenPageLimit = 1
    val pagerAdapter = object : FragmentStateAdapter(childFragmentManager, lifecycle) {
      private val registeredFragments = SparseArray<Fragment>()

      override fun getItemCount(): Int = serviceHandlers.size

      override fun createFragment(position: Int): Fragment {
        return serviceHandlers[position].instantiator().also {
          registeredFragments[position] = it
        }
      }

      // TODO not sure this is right, may need to listen to Fragment's onDestroy(View?) directly
      override fun onViewDetachedFromWindow(holder: FragmentViewHolder) {
        super.onViewDetachedFromWindow(holder)
        registeredFragments.remove(holder.adapterPosition)
      }

      fun getRegisteredFragment(position: Int) = registeredFragments[position]
    }

    @ColorInt val colorPrimaryDark = view.context.resolveAttributeColor(R.attr.colorPrimaryDark)
    val isInNightMode = view.context.isInNightMode()
    if (!isInNightMode) {
      // Start with a light status bar in normal mode
      appBarLayout.setLightStatusBar(appConfig)
    }
    viewLifecycleOwner.lifecycleScope.launch {
      appBarLayout.offsetChanges()
        .distinctUntilChanged()
        .collect { offset ->
          if (offset == -toolbar.height) {
            statusBarColorAnimator?.cancel()
            tabLayoutIsPinned = true
            val newStatusColor = this@PagerFragment.getAndSaveColor(
              tabLayout.selectedTabPosition
            )
            statusBarColorAnimator = ValueAnimator.ofArgb(colorPrimaryDark, newStatusColor)
              .apply {
                addUpdateListener { animation ->
                  this@PagerFragment.requireActivity()
                    .window.statusBarColor = animation.animatedValue as Int
                }
                duration = 200
                interpolator = LinearOutSlowInInterpolator()
                start()
              }
            appBarLayout.clearLightStatusBar(appConfig)
          } else {
            val wasPinned = tabLayoutIsPinned
            tabLayoutIsPinned = false
            if (wasPinned) {
              statusBarColorAnimator?.cancel()
              statusBarColorAnimator = ValueAnimator.ofArgb(
                this@PagerFragment.requireActivity()
                  .window
                  .statusBarColor,
                colorPrimaryDark
              ).apply {
                addUpdateListener { animation ->
                  this@PagerFragment.requireActivity()
                    .window.statusBarColor = animation.animatedValue as Int
                }
                duration = 200
                interpolator = DecelerateInterpolator()
                if (!isInNightMode) {
                  appBarLayout.setLightStatusBar(appConfig)
                }
                start()
              }
            }
          }
        }
    }
    toolbar.inflateMenu(R.menu.main)
    toolbar.setOnMenuItemClickListener { item ->
      when (item.itemId) {
        R.id.settings -> {
          startActivityForResult(
            Intent(activity, SettingsActivity::class.java),
            SETTINGS_ACTIVITY_REQUEST
          )
          return@setOnMenuItemClickListener true
        }
      }
      false
    }

    // Initial title
    toolbar.title = resources.getString(serviceHandlers[0].name)

    // Set the initial color
    @ColorInt val initialColor = getAndSaveColor(0)
    tabLayout.setBackgroundColor(initialColor)
    viewPager.adapter = pagerAdapter
    TabLayoutMediator(tabLayout, viewPager) { tab, _ ->
      viewPager.setCurrentItem(tab.position, true)
    }.attach()
    changelogHelper.bindWith(toolbar, initialColor) {
      getAndSaveColor(tabLayout.selectedTabPosition)
    }

    // Set icons
    serviceHandlers.forEachIndexed { index, serviceHandler ->
      tabLayout.getTabAt(index)?.icon = VectorDrawableCompat.create(
        resources,
        serviceHandler.icon,
        null
      )
    }

    // Animate color changes
    // adapted from http://kubaspatny.github.io/2014/09/18/viewpager-background-transition/
    viewPager.registerOnPageChangeCallback(
      object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(
          position: Int,
          positionOffset: Float,
          positionOffsetPixels: Int
        ) {
          if (canAnimateColor) {
            val color: Int = if (position < pagerAdapter.itemCount - 1 && position < serviceHandlers.size - 1) {
              argbEvaluator.evaluate(
                positionOffset,
                getAndSaveColor(position),
                getAndSaveColor(position + 1)
              ) as Int
            } else {
              getAndSaveColor(serviceHandlers.size - 1)
            }
            tabLayout.setBackgroundColor(color)
            if (tabLayoutIsPinned) {
              activity?.window?.statusBarColor = color
            }
            activity?.updateNavBarColor(
              color,
              context = view.context,
              uiPreferences = catchUpPreferences,
              appConfig = appConfig
            )
          }
        }

        override fun onPageSelected(position: Int) {}

        override fun onPageScrollStateChanged(state: Int) {
          if (state == ViewPager2.SCROLL_STATE_IDLE) {
            canAnimateColor = true
          }
        }
      }
    )

    tabLayout.addOnTabSelectedListener(
      object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
          val position = tab.position
          toolbar.setTitle(serviceHandlers[position].name)

          // If we're switching between more than one page, we just want to manually set the color
          // once rather than let the usual page scroll logic cycle through all the colors in a weird
          // flashy way.
          if (abs(lastPosition - position) > 1) {
            canAnimateColor = false
            // Start with the current tablayout color to feel more natural if we're in between
            @ColorInt val startColor = (tabLayout.background as ColorDrawable).color
            @ColorInt val endColor = getAndSaveColor(position)
            tabLayoutColorAnimator?.cancel()
            ValueAnimator.ofFloat(0f, 1f)
              .run {
                interpolator = FastOutSlowInInterpolator() // TODO Use singleton
                duration = 400
                doOnStart {
                  tabLayoutColorAnimator = it
                }
                doOnEnd {
                  removeAllUpdateListeners()
                  tabLayoutColorAnimator = null
                }
                addUpdateListener { animator ->
                  @ColorInt val color = argbEvaluator.evaluate(
                    animator.animatedValue as Float,
                    startColor,
                    endColor
                  ) as Int
                  tabLayout.setBackgroundColor(color)
                  if (tabLayoutIsPinned) {
                    activity?.window?.statusBarColor = color
                  }
                  activity?.updateNavBarColor(
                    color,
                    context = view.context,
                    uiPreferences = catchUpPreferences,
                    appConfig = appConfig
                  )
                }
                start()
              }
          }
          lastPosition = position
        }

        override fun onTabUnselected(tab: TabLayout.Tab) {}

        override fun onTabReselected(tab: TabLayout.Tab) {
          pagerAdapter.getRegisteredFragment(tab.position).let {
            if (it is Scrollable) {
              it.onRequestScrollToTop()
              appBarLayout.setExpanded(true, true)
            }
          }
        }
      }
    )

    savedInstanceState?.run {
      getParcelable<Parcelable>("collapsingToolbarState")?.let {
        (appBarLayout.layoutParams as CoordinatorLayout.LayoutParams).behavior
          ?.onRestoreInstanceState(rootLayout, appBarLayout, it)
      }
    }
  }

  @ColorInt
  private fun getAndSaveColor(position: Int): Int {
    dayOnlyContext?.let {
      if (resolvedColorCache[position] == R.color.no_color) {
        resolvedColorCache[position] = ContextCompat.getColor(
          it,
          serviceHandlers[position].accent
        )
      }
    }
    return resolvedColorCache[position]
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == SETTINGS_ACTIVITY_REQUEST) {
      if (resultCode == SettingsActivity.SETTINGS_RESULT_DATA && data != null) {
        data.extras?.let { extras ->
          if (extras.getBoolean(SettingsActivity.NIGHT_MODE_UPDATED, false) ||
            extras.getBoolean(SettingsActivity.SERVICE_ORDER_UPDATED, false)
          ) {
            activity?.recreate()
          }
          if (extras.getBoolean(SettingsActivity.NAV_COLOR_UPDATED, false)) {
            activity?.updateNavBarColor(
              color = (tabLayout.background as ColorDrawable).color,
              context = requireView().context,
              recreate = true,
              uiPreferences = catchUpPreferences,
              appConfig = appConfig
            )
          }
        }
      }
    }
  }

  @InstallIn(FragmentComponent::class)
  @dagger.Module
  object Module {

    @Provides
    fun provideServiceHandlers(
      sharedPrefs: SharedPreferences,
      serviceMetas: DaggerMap<String, ServiceMeta>,
      catchUpPreferences: CatchUpPreferences
    ): Array<ServiceHandler> {
      check(serviceMetas.isNotEmpty()) {
        "No services found!"
      }
      val currentOrder = catchUpPreferences.servicesOrder?.split(",") ?: emptyList()
      return (
        serviceMetas.values
          .filter(ServiceMeta::enabled)
          .filter { sharedPrefs.getBoolean(it.enabledPreferenceKey, true) }
          .sortedBy { currentOrder.indexOf(it.id) }
          .map(ServiceMeta::toServiceHandler)
        )
        .toTypedArray()
    }

    @Provides
    fun provideColorCache(serviceHandlers: Array<ServiceHandler>) = ColorCache(
      IntArray(
        serviceHandlers.size
      ).apply { fill(R.color.no_color) }
    )
  }
}

// TODO This is cover for https://github.com/google/dagger/issues/1593
class ColorCache(val cache: IntArray) {
  operator fun get(index: Int) = cache[index]
  operator fun set(index: Int, value: Int) {
    cache[index] = value
  }
}
