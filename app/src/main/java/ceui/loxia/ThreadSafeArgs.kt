package ceui.loxia

import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.navigation.NavArgs

import android.os.Bundle
import androidx.collection.ArrayMap
import java.lang.reflect.Method
import kotlin.reflect.KClass

internal val methodSignature = arrayOf(Bundle::class.java)
internal val methodMap = ArrayMap<KClass<out NavArgs>, Method>()


class ThreadSafeArgsLazy<Args : NavArgs>(
    private val navArgsClass: KClass<Args>,
    private val argumentProducer: () -> Bundle
) : Lazy<Args> {
    private var cached: Args? = null

    override val value: Args
        @Synchronized
        get() {
            var args = cached
            if (args == null) {
                val arguments = argumentProducer()
                val method: Method = synchronized(methodMap) {
                    methodMap[navArgsClass]
                        ?: navArgsClass.java.getMethod("fromBundle", *methodSignature).also { method ->
                            // Save a reference to the method
                            methodMap[navArgsClass] = method
                        }
                }

                @Suppress("UNCHECKED_CAST")
                args = method.invoke(null, arguments) as Args
                cached = args
            }
            return args
        }

    override fun isInitialized() = cached != null
}


// Thread safe NavArgs
@MainThread
inline fun <reified Args : NavArgs> Fragment.threadSafeArgs() = ThreadSafeArgsLazy(Args::class) {
    arguments ?: Bundle()
}
