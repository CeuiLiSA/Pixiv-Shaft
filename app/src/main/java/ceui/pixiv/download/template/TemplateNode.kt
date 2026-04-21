package ceui.pixiv.download.template

/**
 * AST for a compiled template. Interpreter pattern — each node knows how to render
 * itself against a [TemplateContext].
 */
sealed interface TemplateNode {

    fun render(ctx: TemplateContext, out: StringBuilder)

    data class Literal(val text: String) : TemplateNode {
        override fun render(ctx: TemplateContext, out: StringBuilder) {
            out.append(text)
        }
    }

    data class Variable(val name: String, val format: String? = null) : TemplateNode {
        override fun render(ctx: TemplateContext, out: StringBuilder) {
            out.append(ctx.resolveVariable(name, format))
        }
    }

    data class Conditional(val condition: Condition, val body: List<TemplateNode>) : TemplateNode {
        override fun render(ctx: TemplateContext, out: StringBuilder) {
            if (ctx.evaluate(condition)) {
                body.forEach { it.render(ctx, out) }
            }
        }
    }
}
