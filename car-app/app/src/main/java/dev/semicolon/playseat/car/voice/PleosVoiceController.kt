package dev.semicolon.playseat.car.voice

import android.content.Context

/**
 * Pleos TextToSpeech·SpeechToText SDK 구현 자리 (미완).
 *
 * 퍼미션: TTS_SERVICE, STT_SERVICE
 * 연결 순서는 PleosVehicleSignalSource.kt 상단 주석과 같습니다.
 *
 * Pleos 음성 엔진을 쓰면 차량 마이크 빔포밍과 스티어링 휠 음성 버튼을 함께 쓸 수 있어,
 * 주행 중 운전자 경로의 인식률이 표준 API 보다 낫습니다. 붙이는 즉시 교체합니다.
 */
class PleosVoiceController(private val context: Context) : VoiceController {

    override val isReal = true
    override val canListen = false

    override fun speak(text: String) {
        // TODO(Pleos): TextToSpeech SDK 로 안내 음성 출력
    }

    override fun listen(onResult: (String) -> Unit, onError: (String) -> Unit) {
        // TODO(Pleos): SpeechToText SDK 로 운전자 명령 인식
        onError("Pleos 음성 SDK 미연동")
    }

    override fun release() {
        // TODO(Pleos): 엔진 해제
    }

    companion object {
        /** TODO(Pleos): SDK 연결 확인으로 바꿉니다. */
        fun isAvailable(context: Context): Boolean = false
    }
}

object VoiceControllerFactory {
    fun create(context: Context): VoiceController = when {
        PleosVoiceController.isAvailable(context) -> PleosVoiceController(context)
        // TTS 는 권한 없이도 되므로, 인식 엔진이 없어도 안내 음성은 살려 둡니다.
        else -> AndroidVoiceController(context)
    }
}
