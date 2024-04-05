package ceui.lisa.processor

import ceui.lisa.annotations.ItemHolder
import com.google.auto.service.AutoService
import java.io.File
import java.lang.Exception
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedOptions
import javax.annotation.processing.SupportedSourceVersion
import javax.lang.model.SourceVersion
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement
import javax.lang.model.type.MirroredTypeException
import kotlin.reflect.KClass

fun getClassName(clsProvider: ()-> KClass<*>): String {
    return try {
        clsProvider().toString()
    } catch (mte: MirroredTypeException) {
        mte.typeMirror.toString()
    } catch (ex: Exception) {
        throw ex
    }
}

@AutoService(Processor::class) // For registering the service
@SupportedSourceVersion(SourceVersion.RELEASE_17) // to support Java 8
@SupportedOptions(FileGenerator.KAPT_KOTLIN_GENERATED_OPTION_NAME)
class FileGenerator : AbstractProcessor() {

    private val logger by lazy { ProcessorLogger(processingEnv) }

    companion object {
        const val KAPT_KOTLIN_GENERATED_OPTION_NAME = "kapt.kotlin.generated"
    }

    override fun getSupportedAnnotationTypes(): MutableSet<String> {
        return mutableSetOf(ItemHolder::class.java.name)
    }

    data class HolderEntry(
        val existingPackage: String,
        val itemHolder: String,
        val binding: String,
        val bindingFullname: String,
        val viewHolder: String,
    )

    override fun process(set: MutableSet<out TypeElement>?, roundEnvironment: RoundEnvironment?): Boolean {
        if (roundEnvironment == null) {
            return true
        }

        val holderElements = (roundEnvironment.getElementsAnnotatedWith(ItemHolder::class.java) ?: setOf()).toList()
        if (holderElements.isEmpty()) {
            return true
        }

        val kaptKotlinGeneratedDir = processingEnv.options[KAPT_KOTLIN_GENERATED_OPTION_NAME] ?: return true

        val file = File(kaptKotlinGeneratedDir, "ViewHolderMap.kt")

        val content = StringBuilder()

        val packages = (holderElements).map {
            processingEnv.elementUtils.getPackageOf(it).toString() + ".*"
        }

        val packageName = (packages.first().split(".").subList(0, 3) + listOf("viewholdermap")).joinToString(".")

        val holderEntries = holderElements.map {
            val itemHolderFullName = getClassName {
                it.getAnnotation(ItemHolder::class.java).itemHolderCls
            }
            val itemHolderName = itemHolderFullName.split(".").last()

            val holderPackage = itemHolderFullName.replace(itemHolderName, "")

            val ctor = it.enclosedElements.first { it.kind == ElementKind.CONSTRUCTOR } as ExecutableElement

            val bindingType = ctor.parameters[0].asType()
            val bindingClsName = bindingType.toString().split(".").last()
            val viewHolderName = it.simpleName

            //print("FUCK Processing ${ctor.parameters.size} \n\n")
            HolderEntry(holderPackage, itemHolderName, bindingClsName, bindingType.toString(), viewHolderName.toString())
        }

        val buildMapEntries = holderEntries.map {
            "    ${it.itemHolder}::class.java.hashCode() to ViewHolderFactory::${it.viewHolder}Builder"
        }

        content.append("package ").append(packageName)
        content.append("\n")
        content.append("\n")
        content.append("import android.view.LayoutInflater\n")
        content.append("import android.view.ViewGroup\n")
        content.append("import android.view.View\n")
        content.append("import androidx.viewbinding.ViewBinding\n")
        content.append("import ceui.refactor.ListItemHolder\n")
        content.append("import ceui.refactor.ListItemViewHolder\n")
        holderEntries.forEach {
            content.append("import ${ it.existingPackage }${it.viewHolder}\n")
        }
        holderEntries.forEach {
            content.append("import ${ it.existingPackage }${it.itemHolder}\n")
        }
        content.append(holderEntries.filter { it.binding.endsWith("Binding") }.map { "import ${it.bindingFullname}" }.distinct().joinToString("\n"))
        content.append("\n")
        content.append("\n")
        content.append("object ViewHolderFactory {\n")
        holderEntries.forEach {
            content.append("\n")
            logger.n("${it}")
            content.append("    private fun ${it.viewHolder}Builder(parent: ViewGroup): ListItemViewHolder<out ViewBinding, out ListItemHolder> {")
            content.append("\n")
            content.append("        val binding = ${it.binding}.inflate(\n" +
                    "            LayoutInflater.from(parent.context),\n" +
                    "            parent,\n" +
                    "            false\n" +
                    "        )")
            content.append("\n")
            content.append("        return ${it.viewHolder}(binding)")
            content.append("\n")
            content.append("    }")
            content.append("\n")
        }

        content.append("\n")
        content.append("    val VIEW_HOLDER_MAP = mapOf(\n    " + buildMapEntries.joinToString(",\n    ") + "     \n    )\n\n\n")


        content.append("}")
        content.append("\n")
        content.append("\n")


        file.writeText(content.toString())

        return true
    }
}