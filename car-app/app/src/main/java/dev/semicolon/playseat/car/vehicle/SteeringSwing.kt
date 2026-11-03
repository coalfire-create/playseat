package dev.semicolon.playseat.car.vehicle

/**
 * 조향 변동폭 측정기.
 *
 * 각도 하나로는 굽잇길인지 알 수 없습니다. 주차장에서 핸들을 끝까지 꺾어도
 * 각도는 큽니다. 최근 몇 초 동안 **얼마나 바뀌었는지**가 굽잇길의 표식입니다.
 *
 * 창(window)으로 들고 있다가 최대·최소 차이를 봅니다. 평균이나 분산보다
 * 읽기 쉽고, 한 번 크게 꺾은 것과 계속 흔들린 것을 같은 값으로 보지 않습니다.
 */
class SteeringSwing(private val windowMs: Long = 12_000) {

    private val samples = ArrayDeque<Pair<Long, Float>>()

    fun add(angleDeg: Float, at: Long): Float {
        samples.addLast(at to angleDeg)
        while (samples.isNotEmpty() && at - samples.first().first > windowMs) samples.removeFirst()
        if (samples.size < 2) return 0f
        val values = samples.map { it.second }
        return values.max() - values.min()
    }

    fun clear() = samples.clear()
}
