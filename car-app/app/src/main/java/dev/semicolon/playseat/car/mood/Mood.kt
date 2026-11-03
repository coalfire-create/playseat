package dev.semicolon.playseat.car.mood

import dev.semicolon.playseat.car.session.SessionMode
import dev.semicolon.playseat.car.session.SessionState
import dev.semicolon.playseat.car.vehicle.CabinEnvironment
import dev.semicolon.playseat.car.vehicle.DriveState
import dev.semicolon.playseat.car.vehicle.VehicleSnapshot
import dev.semicolon.playseat.car.vehicle.WiperLevel

/**
 * 지금 차 안의 분위기.
 *
 * 왜 이 축으로 나누는가
 *   장르나 취향으로 나누면 차가 모르는 것을 아는 척하게 됩니다. 차가 실제로
 *   아는 것은 상황입니다 — 비가 오는지, 밤인지, 굽잇길인지, 충전 중인지,
 *   곧 내리는지. 그 상황에 맞는 곡은 사람도 같은 기준으로 고릅니다.
 *
 * [argb] 는 실내 앰비언트 조명과 화면 배경에 같이 씁니다. 화면과 조명이
 * 따로 놀면 "앱이 켜졌다" 가 아니라 "둘 중 하나가 고장났다" 로 보입니다.
 */
enum class Mood(
    val id: String,
    val label: String,
    val argb: Int,
) {
    /** 와이퍼가 움직이는 동안. */
    RAIN("RAIN", "비 오는 날", 0xFF4C6E8F.toInt()),

    /** 야간 주행. */
    NIGHT("NIGHT", "밤 드라이브", 0xFF3B4B7A.toInt()),

    /** 밝을 때의 주행. */
    SUNNY("SUNNY", "맑은 날 드라이브", 0xFFE0A33C.toInt()),

    /** 조향이 계속 바뀌는 구간 — 산길·해안도로. */
    WINDING("WINDING", "굽잇길", 0xFF2F8F6B.toInt()),

    /** 충전 중 정차. */
    LOUNGE("LOUNGE", "충전 라운지", 0xFF7A5CA8.toInt()),

    /** 도착 5분 이내. */
    ARRIVING("ARRIVING", "도착 전", 0xFFBF6B4A.toInt()),

    /** 무엇으로도 특정되지 않을 때. */
    CALM("CALM", "조용한 시간", 0xFF6E7B86.toInt());

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** 판정 결과. [reason] 은 사람에게 보여 줄 근거입니다. */
data class MoodReading(
    val mood: Mood,
    val reason: String,
) {
    val id get() = mood.id
    val label get() = mood.label
}

/**
 * 신호 → 분위기.
 *
 * 순수 함수로 둡니다. 차량 신호도 세션 상태도 값으로 받기 때문에,
 * 에뮬레이터 없이 테스트에서 모든 조합을 돌릴 수 있습니다.
 *
 * 왜 근거 문장을 같이 돌려주는가
 *   추천이 틀렸을 때 사람이 납득하려면 "왜 이걸 골랐는지" 가 보여야 합니다.
 *   "비 오는 날" 만 뜨면 틀렸을 때 그냥 멍청한 기능이지만,
 *   "와이퍼가 움직이고 있어요" 가 붙으면 사람이 직접 고쳐 줍니다.
 */
object MoodEngine {

    /** 이보다 크게 조향이 흔들리면 굽잇길로 봅니다. */
    const val WINDING_SWING_DEG = 55f

    fun read(vehicle: VehicleSnapshot, state: SessionState): MoodReading {
        val env = vehicle.env
        val driving = vehicle.driveState == DriveState.DRIVING

        // 순서가 곧 우선순위입니다. 위에서 걸리면 아래는 보지 않습니다.
        // 비는 밤보다, 도착은 굽잇길보다 먼저입니다 — 더 구체적인 상황이
        // 곡 선택을 더 많이 바꾸기 때문입니다.
        return when {
            env.wiper != WiperLevel.OFF -> MoodReading(
                Mood.RAIN,
                if (env.wiper == WiperLevel.HIGH) "와이퍼가 빠르게 움직이고 있어요"
                else "와이퍼가 움직이고 있어요",
            )

            state.mode == SessionMode.CHARGING_LOUNGE -> MoodReading(
                Mood.LOUNGE, "충전 중이라 시간이 있어요",
            )

            driving && state.landing -> MoodReading(
                Mood.ARRIVING, "곧 도착해요",
            )

            driving && env.steeringSwingDeg >= WINDING_SWING_DEG -> MoodReading(
                Mood.WINDING, "굽은 길을 달리고 있어요",
            )

            driving && (env.night || env.lowBeamOn) -> MoodReading(
                Mood.NIGHT,
                if (env.lowBeamOn && !env.night) "전조등이 켜져 있어요" else "밤에 달리고 있어요",
            )

            driving -> MoodReading(Mood.SUNNY, "밝을 때 달리고 있어요")

            else -> MoodReading(
                Mood.CALM,
                if (env.night) "정차 중이고 밖이 어두워요" else "정차 중이에요",
            )
        }
    }
}
