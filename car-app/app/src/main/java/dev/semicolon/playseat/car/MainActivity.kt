package dev.semicolon.playseat.car

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                val album by vm.lastAlbum.collectAsStateWithLifecycle()
                val voiceStatus by vm.voiceStatus.collectAsStateWithLifecycle()

                // 주야간이 바뀌면 분위기 판정에 다시 알려 줍니다. 차량 설정에서
                // 야간으로 바꾸면 액티비티만 다시 구성되고 ViewModel 은 살아남아서,
                // 시작할 때 한 번 읽은 값으로 그대로 머물러 있었습니다.
                val night = isSystemInDarkTheme()
                LaunchedEffect(night) { vm.pushNightMode() }

                // 운전자 음성 조작에 마이크 권한이 필요합니다. 거부해도 나머지는 그대로 동작합니다.
                var micGranted by remember { mutableStateOf(vm.canListen) }
                val askMic = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { micGranted = it }
                LaunchedEffect(Unit) {
                    if (!micGranted) askMic.launch(Manifest.permission.RECORD_AUDIO)
                }

                CarScreen(
                    state = state,
                    joinUrl = vm.joinUrl,
                    usingRealSignals = vm.usingRealVehicleSignals,
                    usingRealPlayback = vm.usingRealPlayback,
                    lastAlbum = album,
                    voiceStatus = voiceStatus,
                    canListen = micGranted && vm.canListen,
                    onDriverSkip = vm::driverSkip,
                    onDriverPassTurn = vm::driverPassTurn,
                    onListen = vm::startListening,
                    debug = vm.fake?.let { fake ->
                        DebugActions(
                            onToggleDrive = fake::toggleDriveState,
                            onToggleSeat = fake::toggleSeat,
                            onToggleCharging = fake::toggleCharging,
                            onSetEta = fake::setEta,
                            onCycleWiper = fake::cycleWiper,
                            onToggleLowBeam = fake::toggleLowBeam,
                            onToggleWinding = fake::toggleWinding,
                        )
                    },
                )
            }
        }
    }
}
