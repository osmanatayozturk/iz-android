package org.iz.navigation

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class IzApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val osmReady by lazy { applicationScope.async {
        org.iz.navigation.data.DiaryRepository(this@IzApplication).run {
            recoverInterruptedContributions()
            recoverInterruptedMapEdits()
        }
    } }
    internal val wearBridge by lazy { org.iz.navigation.wear.WearPhoneBridge(this) }
    internal val community by lazy { org.iz.navigation.osmcommunity.OsmCommunityRepository(this,
        org.iz.navigation.integration.osm.OsmAuthManager.get(this).communitySessionSource) }
    internal val healthManager by lazy { org.iz.navigation.health.HealthConnectManager(this, org.iz.navigation.data.DiaryRepository(this)) }
    val navigation by lazy { org.iz.navigation.navigation.JourneyNavigationCoordinator(this) }
    val groups by lazy { org.iz.navigation.group.GroupCoordinator(this, navigation) }
    val weatherManager by lazy { org.iz.navigation.weather.RideWeatherManager(this, navigation = navigation) }
    override fun onCreate() {
        super.onCreate()
        osmReady
        community.initialize()
        healthManager.initialize()
        wearBridge.initialize(this)
    }
}
