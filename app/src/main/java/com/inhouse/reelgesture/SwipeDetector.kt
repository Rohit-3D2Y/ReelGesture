package com.inhouse.reelgesture

/**
 * Turns a stream of fingertip vertical positions (normalized 0 = top, 1 = bottom),
 * fed only while the two-finger pose is held, into discrete UP / DOWN swipe events.
 */
class SwipeDetector(
    private val threshold: () -> Float,
    private val allowDown: () -> Boolean,
    private val onSwipe: (Direction) -> Unit,
) {
    enum class Direction { UP, DOWN }

    private val windowMs = 600L
    private val cooldownMs = 900L
    // After a swipe, ignore the opposite direction longer so the hand's return stroke isn't counted.
    private val reverseLockoutMs = 1500L

    private val samples = ArrayDeque<Pair<Long, Float>>()
    private var lastFireMs = 0L
    private var lastDirection: Direction? = null

    fun reset() = samples.clear()

    fun add(timeMs: Long, y: Float) {
        if (timeMs - lastFireMs < cooldownMs) {
            samples.clear()
            return
        }
        samples.addLast(timeMs to y)
        while (samples.isNotEmpty() && timeMs - samples.first().first > windowMs) samples.removeFirst()
        if (samples.size < 2) return

        val t = threshold()
        val rise = samples.maxOf { it.second } - y // fingers moved up
        val fall = y - samples.minOf { it.second } // fingers moved down

        val direction = when {
            rise > t -> Direction.UP
            fall > t && allowDown() -> Direction.DOWN
            else -> return
        }
        if (direction != lastDirection && lastDirection != null && timeMs - lastFireMs < reverseLockoutMs) {
            samples.clear()
            return
        }
        lastFireMs = timeMs
        lastDirection = direction
        samples.clear()
        onSwipe(direction)
    }
}
