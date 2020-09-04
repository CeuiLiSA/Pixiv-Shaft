package ceui.lisa.fragments

import android.os.Bundle
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.base.BaseFragment
import ceui.lisa.databinding.FragmentMangaSeriesBinding
import ceui.lisa.http.ErrorCtrl
import ceui.lisa.http.Retro
import ceui.lisa.model.IllustSeries
import ceui.lisa.utils.Params
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers

class FragmentMangaSeries : BaseFragment<FragmentMangaSeriesBinding>() {

    var seriesID: Int = 0

    override fun initLayout() {
        mLayoutID = R.layout.fragment_manga_series
    }

    override fun initBundle(bundle: Bundle) {
        seriesID = bundle.getInt(Params.ID)
    }

    override fun initView() {
        baseBind.toolbarTitle.text = getString(R.string.string_230)
    }

    override fun initData() {
        Retro.getAppApi().getIllustSeries(Shaft.sUserModel.response.access_token, seriesID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(object :ErrorCtrl<IllustSeries>(){
                    override fun onNext(t: IllustSeries) {
                        baseBind.toolbarTitle.text = t.illust_series_detail.title
                    }
                })
    }

    companion object {
        @JvmStatic
        fun newInstance(seriesID: Int): FragmentMangaSeries {
            return FragmentMangaSeries().apply {
                arguments = Bundle().apply {
                    putInt(Params.ID, seriesID)
                }
            }
        }
    }
}