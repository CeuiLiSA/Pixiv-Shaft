package ceui.pixiv.ui.common

sealed class Either<out L, out R> {
    data class Left<out L>(val value: L) : Either<L, Nothing>()
    data class Right<out R>(val value: R) : Either<Nothing, R>()
}

sealed class ResultOrNoOp<ResultT> {
    class Done<ResultT>(val res: ResultT) : ResultOrNoOp<ResultT>()
    class NoOp<ResultT> : ResultOrNoOp<ResultT>()
}

fun <ResultT> ResultOrNoOp<ResultOrNoOp<ResultT>>.flatten(): ResultOrNoOp<ResultT> {
    return when (this) {
        is ResultOrNoOp.Done -> res
        else -> ResultOrNoOp.NoOp()
    }
}

fun <ResultT : Any> ResultOrNoOp<ResultT>.getOrNull(): ResultT? {
    return when (this) {
        is ResultOrNoOp.Done -> res
        else -> null
    }
}
