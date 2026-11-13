package dev.semicolon.playseat.car.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsTest {

    @Test
    fun `다음 곡 계열을 알아듣는다`() {
        listOf("다음 곡", "다음곡 틀어줘", "스킵", "넘겨줘", "그냥 넘어가").forEach {
            assertEquals(it, VoiceCommands.Command.NEXT_TRACK, VoiceCommands.parse(it))
        }
    }

    @Test
    fun `좋아요 계열을 알아듣는다`() {
        listOf("이 노래 좋아", "저장해줘", "찜").forEach {
            assertEquals(it, VoiceCommands.Command.LIKE, VoiceCommands.parse(it))
        }
    }

    @Test
    fun `모르는 말과 빈 입력은 UNKNOWN`() {
        listOf("", "   ", "오늘 날씨 어때", "에어컨 켜줘").forEach {
            assertEquals(it, VoiceCommands.Command.UNKNOWN, VoiceCommands.parse(it))
        }
        assertEquals(VoiceCommands.Command.UNKNOWN, VoiceCommands.parse(null))
    }
}
