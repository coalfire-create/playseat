package dev.semicolon.playseat.car.voice

/** 운전자 음성 입출력. STT/TTS 구현을 교체할 수 있게 분리합니다. */
interface VoiceController {
    val isReal: Boolean

    /** 음성 인식을 쓸 수 있는지. 권한이 없거나 엔진이 없으면 false. */
    val canListen: Boolean

    fun speak(text: String)
    fun listen(onResult: (String) -> Unit, onError: (String) -> Unit)
    fun release()
}

/** 아무것도 하지 않는 구현. 음성 엔진이 없는 환경에서 앱이 죽지 않게 합니다. */
object NoopVoiceController : VoiceController {
    override val isReal = false
    override val canListen = false
    override fun speak(text: String) = Unit
    override fun listen(onResult: (String) -> Unit, onError: (String) -> Unit) =
        onError("음성 엔진을 사용할 수 없습니다")
    override fun release() = Unit
}
