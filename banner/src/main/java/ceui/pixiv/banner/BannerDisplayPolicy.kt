package ceui.pixiv.banner

enum class BannerDisplayPolicy {

    /** Append to the priority queue and wait the caller's turn. */
    Enqueue,

    /**
     * Replace any pending entry **and** the currently presenting banner if
     * its `dedupKey` matches.
     */
    Replace,

    /**
     * Drop the new request silently if a banner of the same
     * [BannerCategory] is already on screen.
     */
    DropIfShowing,

    /**
     * Interrupt the currently presenting banner and push it back to the
     * front of the queue so it resumes after the new entry finishes.
     */
    Preempt,
}
