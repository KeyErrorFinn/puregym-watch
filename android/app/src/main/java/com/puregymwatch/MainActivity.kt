package com.puregymwatch

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {
  private var scanBrightnessRequested = false
  fun setScanBrightness(enabled: Boolean) {
    scanBrightnessRequested = enabled
    applyScanBrightness(enabled && hasWindowFocus())
  }
  private fun applyScanBrightness(enabled: Boolean) {
    window.attributes = window.attributes.apply {
      screenBrightness = if (enabled) android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
  }
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    val repo = PhoneRepository.get(this)
    applyScanBrightness(hasFocus && scanBrightnessRequested && repo.signedIn() && repo.cached() != null)
  }
  override fun onPause() { applyScanBrightness(false); super.onPause() }
  override fun onDestroy() { applyScanBrightness(false); super.onDestroy() }


  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "PureGymWatch"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)
}
