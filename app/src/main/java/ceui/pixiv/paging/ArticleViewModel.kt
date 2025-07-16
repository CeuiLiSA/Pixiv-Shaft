package ceui.pixiv.paging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn

class ArticleViewModel : ViewModel() {
    private val repository = ArticleRepository()

    val pager = Pager(
        config = PagingConfig(pageSize = 20),
        pagingSourceFactory = { ArticlePagingSource(repository) }
    ).flow.cachedIn(viewModelScope)
}
