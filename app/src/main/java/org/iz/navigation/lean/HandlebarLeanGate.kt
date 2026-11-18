package org.iz.navigation.lean

import org.iz.navigation.BuildConfig

internal object HandlebarLeanGate {
    val enabled: Boolean
        get() = BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "org.iz.navigation.qahandlebar20261118"
}
