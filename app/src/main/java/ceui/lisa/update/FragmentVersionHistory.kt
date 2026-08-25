package ceui.lisa.update

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentVersionHistoryBinding
import ceui.lisa.fragments.BaseLazyFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class FragmentVersionHistory : BaseLazyFragment<FragmentVersionHistoryBinding>() {

    private var loadJob: Job? = null

    override fun initLayout() {
        mLayoutID = R.layout.fragment_version_history
    }


    override fun initData() {
        baseBind.toolbar.setNavigationOnClickListener { mActivity.finish() }
        baseBind.recyclerView.layoutManager = LinearLayoutManager(mContext)

        loadReleases()
    }

    private fun loadReleases() {
        baseBind.loadingView.visibility = View.VISIBLE
        baseBind.errorText.visibility = View.GONE
        baseBind.recyclerView.visibility = View.GONE

        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val releases = AppUpdateChecker.fetchAllReleases()
                baseBind.loadingView.visibility = View.GONE
                if (releases.isEmpty()) {
                    baseBind.errorText.setText(R.string.version_history_empty)
                    baseBind.errorText.visibility = View.VISIBLE
                } else {
                    baseBind.recyclerView.visibility = View.VISIBLE
                    baseBind.recyclerView.adapter = ReleaseHistoryAdapter(releases, mContext)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                baseBind.loadingView.visibility = View.GONE
                baseBind.errorText.setText(R.string.update_check_failed)
                baseBind.errorText.visibility = View.VISIBLE
            }
        }
    }
}
