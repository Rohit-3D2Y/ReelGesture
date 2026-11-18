package com.inhouse.reelgesture

/**
 * Fires once when a hand pose is seen, then waits for the pose to go away before it can
 * fire again (so holding ☝ up scrolls exactly one reel).
 *
 * A clear-cut ("strong") pose fires on the very first frame; a borderline one must be
 * seen on two consecutive frames so flicker can't trigger a scroll.
 */
class PoseTrigger(private val onFire: (Pose, Long) -> Unit) {

    enum class Pose { ONE_FINGER, TWO_FINGERS }

    private val confirmFrames = 2 // consecutive frames before firing a borderline pose
    private val releaseFrames = 2 // consecutive frames without the pose before re-arming
    private val cooldownMs = 500L

    private var candidate: Pose? = null
    private var candidateFrames = 0
    private var releasedFrames = 0
    private var armed = true
    private var lastFireMs = 0L

    /** True while the pose is still being held after firing. */
    val waitingForRelease get() = !armed

    /** [timeMs] is the frame's timestamp, passed back to [onFire] for latency measurement. */
    fun update(timeMs: Long, pose: Pose?, strong: Boolean = false) {
        if (pose == null) {
            candidate = null
            candidateFrames = 0
            if (++releasedFrames >= releaseFrames) armed = true
            return
        }
        releasedFrames = 0
        if (pose != candidate) {
            candidate = pose
            candidateFrames = 0
        }
        candidateFrames++
        if (armed && (strong || candidateFrames >= confirmFrames) && timeMs - lastFireMs >= cooldownMs) {
            armed = false
            lastFireMs = timeMs
            onFire(pose, timeMs)
        }
    }
}
