package ceui.pixiv.nana7mi.session

/**
 * Marks builder receivers in the Nana7mi Session Kotlin DSL so nested DSL scopes cannot
 * accidentally reference properties from an enclosing builder.
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class Nana7miDsl
