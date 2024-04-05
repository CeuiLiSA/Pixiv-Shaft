package ceui.lisa.processor

import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element
import javax.tools.Diagnostic

class ProcessorLogger(private val env: ProcessingEnvironment) {

    fun n(message: String, element: Element? = null) {
        print(Diagnostic.Kind.NOTE, message, element) // 1
    }

    fun w(message: String, element: Element? = null) {
        print(Diagnostic.Kind.WARNING, message, element) // 1
    }

    fun e(message: String, element: Element? = null) {
        print(Diagnostic.Kind.ERROR, message, element) // 1
    }

    private fun print(kind: Diagnostic.Kind, message: String, element: Element?) {
        print("\n")
        env.messager.printMessage(kind, message, element)  // 2
    }
}
