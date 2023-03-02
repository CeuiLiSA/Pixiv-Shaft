package ceui.loxia

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch


data class Tuple4<T1, T2, T3, T4>(val first: T1, val second: T2, val third: T3, val forth: T4)
data class Tuple5<T1, T2, T3, T4, T5>(val first: T1, val second: T2, val third: T3, val forth: T4, val fifth: T5)
data class Tuple6<T1, T2, T3, T4, T5, T6>(val first: T1, val second: T2, val third: T3, val forth: T4, val fifth: T5, val sixth: T6)


fun <T1, T2, S> combineLatest2(
    source1: LiveData<T1>,
    source2: LiveData<T2>,
    combine: (data1: T1?, data2: T2?) -> S,
) : LiveData<S> {
    val finalLiveData: MediatorLiveData<S> = MediatorLiveData()

    var data1: T1? = source1.value
    var data2: T2? = source2.value

    finalLiveData.addSource(source1) {
        data1 = it
        finalLiveData.value = combine(data1, data2)
    }
    finalLiveData.addSource(source2) {
        data2 = it
        finalLiveData.value = combine(data1, data2)
    }

    return finalLiveData
}

fun <T1, T2> combineLatest(
    source1: LiveData<T1>,
    source2: LiveData<T2>,
) : LiveData<Pair<T1?, T2?>> {
    return combineLatest2(source1, source2, ::Pair)
}

fun <T1, T2, T3, S> combineLatest3(
    source1: LiveData<T1>,
    source2: LiveData<T2>,
    source3: LiveData<T3>,
    combine: (data1: T1?, data2: T2?, data3: T3?) -> S,
) : LiveData<S> {
    val finalLiveData: MediatorLiveData<S> = MediatorLiveData()

    var data1: T1? = source1.value
    var data2: T2? = source2.value
    var data3: T3? = source3.value

    finalLiveData.addSource(source1) {
        data1 = it
        finalLiveData.value = combine(data1, data2, data3)
    }
    finalLiveData.addSource(source2) {
        data2 = it
        finalLiveData.value = combine(data1, data2, data3)
    }
    finalLiveData.addSource(source3) {
        data3 = it
        finalLiveData.value = combine(data1, data2, data3)
    }

    return finalLiveData
}

fun <T1, T2, T3> combineLatest(
    source1: LiveData<T1>,
    source2: LiveData<T2>,
    source3: LiveData<T3>,
) : LiveData<Triple<T1?, T2?, T3?>> {
    return combineLatest3(source1, source2, source3, ::Triple)
}

fun <T1, T2, T3, T4, S> combineLatest4(
    source1: LiveData<T1>,
    source2: LiveData<T2>,
    source3: LiveData<T3>,
    source4: LiveData<T4>,
    combine: (data1: T1?, data2: T2?, data3: T3?, data4: T4?) -> S,
) : LiveData<S> {
    val finalLiveData: MediatorLiveData<S> = MediatorLiveData()

    var data1: T1? = source1.value
    var data2: T2? = source2.value
    var data3: T3? = source3.value
    var data4: T4? = source4.value

    finalLiveData.addSource(source1) {
        data1 = it
        finalLiveData.value = combine(data1, data2, data3, data4)
    }
    finalLiveData.addSource(source2) {
        data2 = it
        finalLiveData.value = combine(data1, data2, data3, data4)
    }
    finalLiveData.addSource(source3) {
        data3 = it
        finalLiveData.value = combine(data1, data2, data3, data4)
    }

    finalLiveData.addSource(source4) {
        data4 = it
        finalLiveData.value = combine(data1, data2, data3, data4)
    }

    return finalLiveData
}


fun <T1, T2, T3, T4> combineLatest(
    source1: LiveData<T1>,
    source2: LiveData<T2>,
    source3: LiveData<T3>,
    source4: LiveData<T4>,
) : LiveData<Tuple4<T1?, T2?, T3?, T4?>> {
    return combineLatest4(source1, source2, source3, source4, ::Tuple4)
}


fun <T1, T2, T3, T4, T5, S> combineLatest5(
    source1: LiveData<T1>,
    source2: LiveData<T2>,
    source3: LiveData<T3>,
    source4: LiveData<T4>,
    source5: LiveData<T5>,
    combine: (data1: T1?, data2: T2?, data3: T3?, data4: T4?, data5: T5?) -> S,
) : LiveData<S> {
    val finalLiveData: MediatorLiveData<S> = MediatorLiveData()

    var data1: T1? = source1.value
    var data2: T2? = source2.value
    var data3: T3? = source3.value
    var data4: T4? = source4.value
    var data5: T5? = source5.value

    finalLiveData.addSource(source1) {
        data1 = it
        finalLiveData.value = combine(data1, data2, data3, data4, data5)
    }
    finalLiveData.addSource(source2) {
        data2 = it
        finalLiveData.value = combine(data1, data2, data3, data4, data5)
    }
    finalLiveData.addSource(source3) {
        data3 = it
        finalLiveData.value = combine(data1, data2, data3, data4, data5)
    }

    finalLiveData.addSource(source4) {
        data4 = it
        finalLiveData.value = combine(data1, data2, data3, data4, data5)
    }

    finalLiveData.addSource(source5) {
        data5 = it
        finalLiveData.value = combine(data1, data2, data3, data4, data5)
    }

    return finalLiveData
}

fun <T1, T2, T3, T4, T5, T6, S> combineLatest6(
    source1: LiveData<T1>,
    source2: LiveData<T2>,
    source3: LiveData<T3>,
    source4: LiveData<T4>,
    source5: LiveData<T5>,
    source6: LiveData<T6>,
    combine: (data1: T1?, data2: T2?, data3: T3?, data4: T4?, data5: T5?, data6: T6?) -> S,
) : LiveData<S> {
    val finalLiveData: MediatorLiveData<S> = MediatorLiveData()

    var data1: T1? = source1.value
    var data2: T2? = source2.value
    var data3: T3? = source3.value
    var data4: T4? = source4.value
    var data5: T5? = source5.value
    var data6: T6? = source6.value

    finalLiveData.addSource(source1) {
        data1 = it
        finalLiveData.value = combine(data1, data2, data3, data4, data5, data6)
    }
    finalLiveData.addSource(source2) {
        data2 = it
        finalLiveData.value = combine(data1, data2, data3, data4, data5, data6)
    }
    finalLiveData.addSource(source3) {
        data3 = it
        finalLiveData.value = combine(data1, data2, data3, data4, data5, data6)
    }

    finalLiveData.addSource(source4) {
        data4 = it
        finalLiveData.value = combine(data1, data2, data3, data4, data5, data6)
    }

    finalLiveData.addSource(source5) {
        data5 = it
        finalLiveData.value = combine(data1, data2, data3, data4, data5, data6)
    }

    finalLiveData.addSource(source6) {
        data6 = it
        finalLiveData.value = combine(data1, data2, data3, data4, data5, data6)
    }

    return finalLiveData
}


fun <T1, T2, T3, T4, T5> combineLatest(
    source1: LiveData<T1>,
    source2: LiveData<T2>,
    source3: LiveData<T3>,
    source4: LiveData<T4>,
    source5: LiveData<T5>,
) : LiveData<Tuple5<T1?, T2?, T3?, T4?, T5?>> {
    return combineLatest5(source1, source2, source3, source4, source5, ::Tuple5)
}

fun <T1, T2, T3, T4, T5, T6> combineLatest(
    source1: LiveData<T1>,
    source2: LiveData<T2>,
    source3: LiveData<T3>,
    source4: LiveData<T4>,
    source5: LiveData<T5>,
    source6: LiveData<T6>,
) : LiveData<Tuple6<T1?, T2?, T3?, T4?, T5?, T6?>> {
    return combineLatest6(source1, source2, source3, source4, source5, source6, ::Tuple6)
}



fun <T> MutableLiveData<T>.asLiveData() = this as LiveData<T>


fun <T> LiveData<T>.delay(mills: Long): LiveData<T?> {
    val result = MediatorLiveData<T>()
    result.addSource(this) { v ->
        MainScope().launch {
            delay(mills)
            result.value = v
        }
    }

    return result
}


fun <T> LiveData<T>.filter(func: (T) -> Boolean): LiveData<T> {
    val result = MediatorLiveData<T>()
    result.addSource(this) { if (func(it)) result.value = it }
    return result
}


// deprecated
@Deprecated("Deprecated function", replaceWith = ReplaceWith("LiveData<T>.once"))
fun <T> LiveData<T>.onceDeprecated(): LiveData<T> {
    val result = MediatorLiveData<T>()
    var hasEmitted = false
    result.addSource(this) {
        if (!hasEmitted) {
            result.value = it
            hasEmitted = true
        }
    }

    return result
}

fun <T> LiveData<T>.once(): LiveData<T> {
    val result = MediatorLiveData<T>()
    result.addSource(this) {
        result.value = it
        result.removeSource(this)
    }

    return result
}


fun <T> LiveData<T?>.notNull(): LiveData<T> {
    val result = MediatorLiveData<T>()
    result.addSource(this) {
        if (it != null) {
            result.value = it
            result.removeSource(this)
        }
    }

    return result
}

fun <T> LiveData<T?>.filterNull(): LiveData<T> {
    val result = MediatorLiveData<T>()
    result.addSource(this) {
        if (it != null) {
            result.value = it
        }
    }

    return result
}

fun <T> LiveData<T>.toDiff(): LiveData<Pair<T?, T>> {
    val result = MediatorLiveData<Pair<T?, T>>()
    var oldValue = value
    result.addSource(this) {
        result.value = Pair(oldValue, it)
        oldValue = it
    }

    return result
}


fun <T> LiveData<T>.debounce(duration: Long) = MediatorLiveData<T>().also { mld ->
    val source = this
    val handler = Handler(Looper.getMainLooper())

    val runnable = Runnable {
        mld.value = source.value
    }

    mld.addSource(source) {
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, duration)
    }
}

fun <T> LiveData<T>.throttle(duration: Long) = MediatorLiveData<T>().also { mld ->
    val source = this
    val handler = Handler(Looper.getMainLooper())

    var posted = false

    val runnable = object: Runnable {
        override fun run() {
            mld.value = source.value
            handler.removeCallbacks(this)
            posted = false
        }
    }

    mld.addSource(source) {
        if (!posted) {
            handler.postDelayed(runnable, duration)
        }
    }
}


suspend fun LiveData<Boolean>.waitForTrue(lifecycleOwner: LifecycleOwner) {
    val task = CompletableDeferred<Unit>()
    if (value == true) {
        task.complete(Unit)
        return task.await()
    }

    observe(lifecycleOwner) {
        if (it) {
            if (!task.isCompleted) {
                task.complete(Unit)
            }
        }
    }

    return task.await()
}

suspend fun<T> LiveData<T>.waitForValue(lifecycleOwner: LifecycleOwner): T {
    val task = CompletableDeferred<T>()

    observe(lifecycleOwner) {
        if (!task.isCompleted) {
            task.complete(it)
        }
    }

    return task.await()
}


data class Observation<T>(
    private val liveData: LiveData<T>,
    private val observer: Observer<T>,
) {
    fun unobserve() {
        liveData.removeObserver(observer)
    }
}

fun<T> LiveData<T>.trackedObserve(lifecycleOwner: LifecycleOwner, observer: Observer<T>): Observation<T> {
    observe(lifecycleOwner, observer)
    return Observation(this, observer)
}

inline fun<T> LiveData<T>.trackedObserveForEver(crossinline onChanged: (T) -> Unit): Observation<T> {
    val observer = Observer<T> { t -> onChanged(t) }
    observeForever(observer)
    return Observation(this, observer)
}


fun MutableLiveData<Boolean>.flipBoolean() {
    value = !(this.value ?: false)
}

fun<T> LiveData<T>.ensureNotNull(listener: (T)->Unit) {
    val v = value
    if (v != null) {
        listener(v)
    } else {
        observeForever(object : Observer<T> {
            var hasTriggered = false
            override fun onChanged(t: T) {
                if (t != null) {
                    if (!hasTriggered) {
                        hasTriggered = true
                        listener(t)
                    }
                }
            }
        })
    }
}

fun<T> LiveData<T>.requireValue(): T {
    return value!!
}


infix fun <A, B, C> Pair<A, B>.to(that: C) = Triple(this.first, this.second, that)

class UpwardLiveData : MutableLiveData<Int>() {
    override fun setValue(value: Int?) {
        val local = this.value
        if (value != null && (local == null || value > local)) {
            super.setValue(value)
        }
    }
}

fun <T> LiveData<T>.safeObserver(lifecycleOwner: LifecycleOwner, observer: Observer<T>) {
    observe(lifecycleOwner, observer)
}