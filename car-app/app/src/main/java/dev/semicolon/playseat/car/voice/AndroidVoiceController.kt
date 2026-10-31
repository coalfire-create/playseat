package dev.semicolon.playseat.car.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Android 표준 TTS/STT 구현.
 *
 * Pleos TextToSpeech·SpeechToText SDK 가 붙기 전까지 이것으로 동작합니다.
 * 플랫폼 표준 API 라 Pleos 에뮬레이터와 일반 AVD 양쪽에서 같은 코드로 돌아갑니다.
 * Pleos SDK 로 교체할 때는 PleosVoiceController 를 쓰도록 팩토리만 바꿉니다.
 */
class AndroidVoiceController(private val context: Context) : VoiceController {

    override val isReal = true

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.KOREAN
            } else {
                Log.w(TAG, "TTS 초기화 실패")
            }
        }
    }

    override val canListen: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun speak(text: String) {
        if (!ttsReady) return
        // 안내는 짧게, 그리고 이전 안내를 덮어씁니다. 주행 중에 말이 쌓이면 방해가 됩니다.
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "playseat")
    }

    override fun listen(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!canListen) {
            onError("마이크 권한이 필요합니다")
            return
        }
        if (listening) return

        // SpeechRecognizer 는 메인 스레드에서 만들어야 합니다.
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (text.isNullOrBlank()) onError("알아듣지 못했습니다") else onResult(text)
            }

            override fun onError(error: Int) {
                listening = false
                onError(describe(error))
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        listening = true
        runCatching { r.startListening(intent) }.onFailure {
            listening = false
            onError("음성 인식을 시작할 수 없습니다")
        }
    }

    override fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    private fun describe(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "알아듣지 못했습니다"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말씀이 없었습니다"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요합니다"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 문제입니다"
        else -> "음성 인식 오류 ($error)"
    }

    companion object { private const val TAG = "AndroidVoice" }
}
