package ceui.pixiv.ui.common.repo

import timber.log.Timber


/**
 * Base abstract class for data repositories.
 * Defines the contract for loading data from either local or remote sources.
 */
abstract class Repository<ValueT> {

    /**
     * Suspended function to load data.
     * Subclasses must override this method to provide their data-loading logic.
     */
    abstract suspend fun load(): LoadResult<ValueT>?

    init {
        // Log when a repository instance is created
        Timber.d("RepositoryCreated ${this.javaClass.simpleName}")
    }
}
