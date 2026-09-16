package ceui.pixiv.ui.referral

import android.os.Bundle
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

internal enum class ReferralTab { TASKS, WALLET }
internal enum class ReferralFilter { ALL, PROGRESS, READY }
internal data class ReferralUiState(
    val snapshot: ReferralSnapshot,
    val tab: ReferralTab,
    val filter: ReferralFilter,
    val darkOverride: Boolean?,
    val accentOverride: Int?,
)

/** Retains the local demo across view recreation/process restoration, isolated from member APIs. */
internal class ReferralPlanViewModel(private val saved: SavedStateHandle) : ViewModel() {
    private val repository = ReferralDemoRepository(restore(saved[KEY]))
    private val mutable = MutableLiveData(current())
    val state: LiveData<ReferralUiState> = mutable
    val value: ReferralUiState get() = mutable.value!!

    private fun current() = ReferralUiState(
        repository.snapshot,
        enumValueOr(saved["tab"], ReferralTab.TASKS),
        enumValueOr(saved["filter"], ReferralFilter.ALL),
        saved["dark"], saved["accent"],
    )

    fun tab(tab: ReferralTab) { saved["tab"] = tab.name; publish() }
    fun filter(filter: ReferralFilter) { saved["filter"] = filter.name; publish() }
    fun appearance(dark: Boolean?, accent: Int?) { saved["dark"] = dark; saved["accent"] = accent; publish() }
    fun submit(task: ReferralTask, url: String, description: String, confirmed: Boolean): Boolean =
        repository.submit(task, url, description, confirmed).also { if (it) publish() }
    fun review(task: ReferralTask, approved: Boolean) { if (repository.review(task, approved)) publish() }
    fun claim(task: ReferralTask): Boolean = repository.claim(task, System.currentTimeMillis()).also { if (it) publish() }
    fun activate(task: ReferralTask): Boolean = repository.activate(task, System.currentTimeMillis()).also { if (it) publish() }
    fun addInvite() { repository.addInvite(); publish() }
    fun addRetained() { repository.addRetained(); publish() }
    fun reset() { repository.reset(); saved["tab"] = ReferralTab.TASKS.name; saved["filter"] = ReferralFilter.ALL.name; publish() }
    fun refreshTime() { mutable.value = current() }

    private fun publish() {
        val snapshot = repository.snapshot
        saved[KEY] = Bundle().apply {
            putStringArrayList("statuses", ArrayList(ReferralTask.entries.map { snapshot.status(it).name }))
            putInt("effective", snapshot.effective)
            putInt("retained", snapshot.retained)
            putLong("activeUntil", snapshot.activeUntil)
            putParcelableArrayList("cards", ArrayList(snapshot.cards.map { card -> Bundle().apply {
                putString("task", card.task.name); putLong("claimed", card.claimedAt)
                putLong("expires", card.expiresAt); putLong("activated", card.activatedAt)
            } }))
            putBundle("submissions", Bundle().apply { snapshot.submissions.forEach { (task, data) ->
                putBundle(task.name, Bundle().apply { putString("url", data.url); putString("description", data.description) })
            } })
        }
        mutable.value = current()
    }

    companion object {
        private const val KEY = "referral_preview_v1"
        private inline fun <reified T : Enum<T>> enumValueOr(name: String?, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: fallback

        private fun restore(bundle: Bundle?): ReferralSnapshot {
            if (bundle == null) return ReferralSnapshot()
            return runCatching {
                val statuses = bundle.getStringArrayList("statuses") ?: return ReferralSnapshot()
                require(statuses.size == ReferralTask.entries.size)
                @Suppress("DEPRECATION")
                val cards = bundle.getParcelableArrayList<Bundle>("cards").orEmpty().map { card ->
                    ReferralCard(ReferralTask.valueOf(card.getString("task")!!), card.getLong("claimed"),
                        card.getLong("expires"), card.getLong("activated"))
                }
                val submissions = bundle.getBundle("submissions")
                ReferralSnapshot(
                    statuses = ReferralTask.entries.associateWith { ReferralStatus.valueOf(statuses[it.ordinal]) },
                    effective = bundle.getInt("effective", 1).coerceIn(1, 99),
                    retained = bundle.getInt("retained").coerceIn(0, minOf(3, bundle.getInt("effective", 1))),
                    cards = cards,
                    submissions = ReferralTask.entries.mapNotNull { task -> submissions?.getBundle(task.name)?.let {
                        task to ReferralSubmission(it.getString("url").orEmpty(), it.getString("description").orEmpty())
                    } }.toMap(),
                    activeUntil = bundle.getLong("activeUntil"),
                )
            }.getOrDefault(ReferralSnapshot())
        }
    }
}
