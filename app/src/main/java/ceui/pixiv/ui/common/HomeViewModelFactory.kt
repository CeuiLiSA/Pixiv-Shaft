package ceui.pixiv.ui.common

import android.content.res.AssetManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class HomeViewModelFactory(private val assets: AssetManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HomeViewModel(assets) as T
    }
}
