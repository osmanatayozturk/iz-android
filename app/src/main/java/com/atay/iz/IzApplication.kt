package com.atay.iz

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class IzApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val osmReady by lazy { applicationScope.async {
        com.atay.iz.data.DiaryRepository(this@IzApplication).run {
            recoverInterruptedContributions()
            recoverInterruptedMapEdits()
        }
    } }
    internal val wearBridge by lazy { com.atay.iz.wear.WearPhoneBridge(this) }
    internal val community by lazy { com.atay.iz.osmcommunity.OsmCommunityRepository(this,
        com.atay.iz.integration.osm.OsmAuthManager.get(this).communitySessionSource) }
    internal val healthManager by lazy { com.atay.iz.health.HealthConnectManager(this, com.atay.iz.data.DiaryRepository(this)) }
    val navigation by lazy { com.atay.iz.navigation.JourneyNavigationCoordinator(this) }
    val groups by lazy { com.atay.iz.group.GroupCoordinator(this, navigation) }
    val weatherManager by lazy { com.atay.iz.weather.RideWeatherManager(this, navigation = navigation) }
    override fun onCreate() {
        super.onCreate()
        osmReady
        community.initialize()
        healthManager.initialize()
        wearBridge.initialize(this)
    }
}
