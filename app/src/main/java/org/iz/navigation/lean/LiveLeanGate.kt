package org.iz.navigation.lean

import org.iz.navigation.BuildConfig

/** Field acceptance is mount-specific. This gate only controls the new foreground dial. */
internal object LiveLeanGate {
    fun allows(mount: LeanMount): Boolean =
        BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "org.iz.navigation.qaredesign20261119" &&
            (mount == LeanMount.BODY || mount == LeanMount.HANDLEBAR)
}
