package com.inhouse.reelgesture

/**
 * Fires once when a hand pose is held for a couple of frames, then waits for the pose
 * to go away before it can fire again (so holding ☝ up scrolls exactly one reel).
 */
class PoseTrigger(private val onFire: (Pose) -> Unit) {

    enum class Pose { ONE_FINGER, TWO_FINGERS }

    private val confirmFrames = 2 // consecutive frames before firing, filters flicker
    private val releaseFrames = 2 // consecutive frames without the pose before re-arming
    private val cooldownMs = 700L

    private var candidate: Pose? = null
    private var candidateFrames = 0
    private var releasedFrames = 0
    private var armed = true
    private var lastFireMs = 0L

    /** True while the pose is still being held after firing. */
    val waitingForRelease get() = !armed

    fun update(timeMs: Long, pose: Pose?) {
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
        if (armed && candidateFrames >= confirmFrames && timeMs - lastFireMs >= cooldownMs) {
            armed = false
            lastFireMs = timeMs
            onFire(pose)
        }
    }
}
