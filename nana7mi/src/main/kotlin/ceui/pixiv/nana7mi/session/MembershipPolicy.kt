package ceui.pixiv.nana7mi.session

/**
 * Pure domain rules for attributing and merging premium membership signals from OAuth responses.
 *
 * Refresh responses sometimes omit membership flags or fail to identify the account. Treating an
 * omitted field as `false` would demote a paying account and pull it out of the lending pool.
 * Both borrowed-account renewal and host login-session refresh share this single rule.
 */
public object MembershipPolicy {

    /**
     * Returns the membership signal an OAuth response is allowed to speak for, or `null` when it
     * cannot speak for [expectedUid].
     *
     * Rules:
     * - [expectedUid] must be positive (`> 0`).
     * - [reportedUid] must equal [expectedUid] (rejects absent/zero/mismatched ids).
     * - [reportedIsPremium] `null` stays `null` ("provider said nothing").
     */
    @JvmStatic
    public fun attribute(
        reportedUid: Long?,
        expectedUid: Long,
        reportedIsPremium: Boolean?,
    ): Boolean? {
        if (expectedUid <= 0L) return null
        if (reportedUid == null || reportedUid != expectedUid) return null
        return reportedIsPremium
    }

    /**
     * Folds an [attributedPremium] signal into a [stored] membership value:
     * overwrites only when [attributedPremium] is non-null.
     */
    @JvmStatic
    public fun merge(stored: Boolean?, attributedPremium: Boolean?): Boolean? =
        attributedPremium ?: stored
}
