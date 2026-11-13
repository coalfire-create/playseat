package dev.semicolon.playseat.car.voice

/**
 * 운전자 음성 명령 해석.
 *
 * 주행 중 운전자에게 열려 있는 유일한 조작 경로입니다. 화면은 잠겨 있고
 * 선곡 권한은 동승자 폰으로 넘어가 있으므로, 여기서 알아듣는 말이 곧
 * 운전자가 할 수 있는 전부입니다. 순수 함수로 두어 단위 테스트합니다.
 */
object VoiceCommands {

    enum class Command { NEXT_TRACK, LIKE, UNKNOWN }

    fun parse(raw: String?): Command {
        val t = raw?.replace(" ", "")?.lowercase() ?: return Command.UNKNOWN
        if (t.isEmpty()) return Command.UNKNOWN

        if (t.contains("다음") || t.contains("스킵") || t.contains("넘겨") || t.contains("넘어가"))
            return Command.NEXT_TRACK
        if (t.contains("좋아") || t.contains("저장") || t.contains("찜"))
            return Command.LIKE

        return Command.UNKNOWN
    }
}
