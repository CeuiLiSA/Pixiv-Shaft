package ceui.lisa.annotations

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
annotation class ItemHolder(val itemHolderCls: KClass<*>)