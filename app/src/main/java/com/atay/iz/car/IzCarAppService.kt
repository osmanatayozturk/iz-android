package com.atay.iz.car

import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.*
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.atay.iz.BuildConfig
import com.atay.iz.IzApplication
import com.atay.iz.data.DiaryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class IzCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = if (BuildConfig.DEBUG) {
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    } else {
        HostValidator.Builder(this).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
    }
    override fun onCreateSession(): Session = IzCarSession()
}

internal class IzCarSession : Session() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var surface: CarMapSurface
    private lateinit var home: CarHomeScreen
    private var hostNavigating = false

    override fun onCreateScreen(intent: Intent): Screen {
        val app = carContext.applicationContext as IzApplication
        val coordinator = app.navigation
        val manager = carContext.getCarService(NavigationManager::class.java)
        surface = CarMapSurface(carContext)
        home = CarHomeScreen(carContext, surface)
        displayUpdate { carContext.getCarService(AppManager::class.java).setSurfaceCallback(surface) }
        displayUpdate { manager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() { coordinator.stopGuidance() }
            override fun onAutoDriveEnabled() { coordinator.enableSimulation() }
        }) }
        scope.launch {
            coordinator.state.collect { state ->
                displayUpdate {
                    if (state.guidance && !hostNavigating) {
                        manager.navigationStarted()
                        hostNavigating = true
                    } else if (!state.guidance && hostNavigating) {
                        manager.navigationEnded()
                        hostNavigating = false
                    }
                    if (hostNavigating) manager.updateTrip(carTrip(state))
                }
                displayUpdate { home.refresh(state) }
            }
        }
        scope.launch {
            coordinator.state.map { it.journey?.id to it.simulation }.distinctUntilChanged().collectLatest { (id, simulation) ->
                displayUpdate { home.trail(emptyList()) }
                if (id != null) {
                    val flow = if (simulation) coordinator.simulationPoints else DiaryRepository(carContext).observeJourneyPoints(id)
                    flow.collect { points -> displayUpdate { home.trail(points) } }
                }
            }
        }
        scope.launch { app.weatherManager.state.collect { displayUpdate { home.safeInvalidate() } } }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { displayUpdate { surface.setActive(true); home.requestIdleLocation() } }
            override fun onStop(owner: LifecycleOwner) { displayUpdate { surface.setActive(false) } }
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
                displayUpdate { manager.clearNavigationManagerCallback() }
                if (hostNavigating) runCatching { manager.navigationEnded() }
                hostNavigating = false
                runCatching { carContext.getCarService(AppManager::class.java).setSurfaceCallback(null) }
                displayUpdate { surface.close() }
                coordinator.stopSimulation()
                // Real recording and guidance belong to the process/service, never the display session.
            }
        })
        NavigationIntents.parse(intent)?.let { target -> scope.launch { home.openTarget(target) } }
        return home
    }

    private inline fun displayUpdate(block: () -> Unit) {
        if (!safelyUpdateCarDisplay(block)) {
            com.atay.iz.tracking.LocationDiagnostics(java.io.File(carContext.noBackupFilesDir, "diagnostics"))
                .record(com.atay.iz.tracking.LocationDiagnostics.Event.DISPLAY_FAILED, null, null)
        }
    }

    override fun onNewIntent(intent: Intent) {
        NavigationIntents.parse(intent)?.let {
            displayUpdate {
                carContext.getCarService(ScreenManager::class.java).popToRoot()
                home.openTarget(it)
            }
        }
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        if (::surface.isInitialized) displayUpdate { surface.nightChanged() }
    }
}
