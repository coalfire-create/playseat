package dev.semicolon.playseat.car

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.semicolon.playseat.car.ui.CarScreen
import dev.semicolon.playseat.car.ui.DebugActions
import dev.semicolon.playseat.car.ui.PlaySeatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PlaySeatTheme {
                val vm: CarViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                CarScreen(
                    state = state,
                    joinUrl = vm.joinUrl,
                    usingRealSignals = vm.usingRealVehicleSignals,
                    onDriverSkip = vm::driverSkip,
                    onDriverPassTurn = vm::driverPassTurn,
                    debug = vm.fake?.let { fake ->
                        DebugActions(
                            onToggleDrive = fake::toggleDriveState,
                            onToggleSeat = fake::toggleSeat,
                            onToggleCharging = fake::toggleCharging,
                        )
                    },
                )
            }
        }
    }
}
