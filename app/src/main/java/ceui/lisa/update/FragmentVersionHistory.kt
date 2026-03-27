package ceui.lisa.update

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentVersionHistoryBinding
import ceui.lisa.fragments.SwipeFragment
import com.scwang.smart.refresh.layout.SmartRefreshLayout
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers

class FragmentVersionHistory : SwipeFragment<FragmentVersionHistoryBinding>() {

    private var disposable: Disposable? = null

    override fun initLayout() {
        mLayoutID = R.layout.fragment_version_history
    }

    override fun getSmartRefreshLayout(): SmartRefreshLayout {
        return baseBind.refreshLayout
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

        disposable?.dispose()
        disposable = AppUpdateChecker.fetchAllReleases()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ releases ->
                baseBind.loadingView.visibility = View.GONE
                if (releases.isEmpty()) {
                    baseBind.errorText.setText(R.string.version_history_empty)
                    baseBind.errorText.visibility = View.VISIBLE
                } else {
                    baseBind.recyclerView.visibility = View.VISIBLE
                    baseBind.recyclerView.adapter = ReleaseHistoryAdapter(releases, mContext)
                }
            }, { _ ->
                baseBind.loadingView.visibility = View.GONE
                baseBind.errorText.setText(R.string.update_check_failed)
                baseBind.errorText.visibility = View.VISIBLE
            })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable?.dispose()
    }
}
