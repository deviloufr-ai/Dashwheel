package com.openauto.dash

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The GitHub build: free software with everything open, updated in place
 * from GitHub Releases, and a coffee on Ko-fi for whoever wants to say thanks.
 */
internal object Edition : StoreEdition {
    override val playStore: Boolean = false
    override val unlocked: StateFlow<Boolean> = MutableStateFlow(true)
    override val price: StateFlow<String?> = MutableStateFlow(null)
    override val purchase: StateFlow<PurchaseState> = MutableStateFlow(PurchaseState.IDLE)
    override fun start(context: Context) = Unit
    override fun buy(activity: Activity) = Unit
    override val donationUrl: String = "https://ko-fi.com/deviloufr"
    /** The companion's asset in the latest release (see .github/workflows/build.yml). */
    override val companionUrl: String =
        "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest/download/${UpdateManager.COMPANION_APK_NAME}"
    override val updatesFromGitHub: Boolean = true
}
