package com.marketsboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.marketsboard.ui.settings.LocationAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The location permission as the shared code sees it. The Activity calls [refresh] when it resumes (the user may have
 * changed it in system settings) and installs [requester], which shows the system dialog or, when Android will not ask
 * again, opens the app's settings page. Denied is a normal state: nothing here throws or logs.
 */
class AndroidLocationAccess(private val context: Context) : LocationAccess {
    private val _granted = MutableStateFlow(check())
    override val granted: StateFlow<Boolean> = _granted.asStateFlow()

    /** Set by the Activity while it exists. */
    @Volatile var requester: (() -> Unit)? = null

    fun refresh() { _granted.value = check() }

    override fun request() { requester?.invoke() }

    private fun check(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
