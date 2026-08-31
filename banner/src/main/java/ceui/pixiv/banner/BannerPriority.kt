package ceui.pixiv.banner

/**
 * Ranks competing banner requests when more than one is queued at the same time.
 *
 * The presenter pulls the highest-priority entry next; ties are broken by
 * arrival order. Use [CRITICAL] sparingly — it allows preemption of an
 * already-presenting banner under [BannerDisplayPolicy.Preempt].
 */
enum class BannerPriority { LOW, NORMAL, HIGH, CRITICAL }
