package ceui.pixiv.download.template

/**
 * Recursive-descent parser for the download path template DSL.
 *
 * Grammar (EBNF-ish):
 *   template   := node*
 *   node       := variable | conditional | literal
 *   variable   := '{' name (':' format)? '}'
 *   conditional:= '[?' condition ':' node* ']'
 *   condition  := flagName | '!' flagName | 'p>' integer
 *   literal    := any char not starting a variable or conditional
 *
 * Escape any special character with a backslash.
 */
internal class TemplateParser(private val src: String) {

    private var pos = 0

    companion object {
        private val NAME_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }

    fun parseAll(): List<TemplateNode> = parseNodes(terminator = null)

    private fun parseNodes(terminator: Char?): List<TemplateNode> {
        val nodes = mutableListOf<TemplateNode>()
        val literal = StringBuilder()

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                nodes += TemplateNode.Literal(literal.toString())
                literal.clear()
            }
        }

        while (pos < src.length) {
            val c = src[pos]
            if (terminator != null && c == terminator) {
                flushLiteral()
                return nodes
            }
            when {
                c == '\\' && pos + 1 < src.length -> {
                    literal.append(src[pos + 1]); pos += 2
                }
                c == '{' -> { flushLiteral(); nodes += parseVariable() }
                c == '[' && pos + 1 < src.length && src[pos + 1] == '?' -> {
                    flushLiteral(); nodes += parseConditional()
                }
                else -> { literal.append(c); pos++ }
            }
        }

        if (terminator != null) {
            error("Unterminated block: expected '$terminator' at end of template")
        }
        flushLiteral()
        return nodes
    }

    private fun parseVariable(): TemplateNode.Variable {
        expect('{')
        val end = src.indexOf('}', pos).also { require(it >= 0) { "Unterminated '{' at $pos" } }
        val body = src.substring(pos, end)
        pos = end + 1
        val colon = body.indexOf(':')
        val name = if (colon < 0) body.trim() else body.substring(0, colon).trim()
        require(name.matches(NAME_REGEX)) {
            "Invalid variable name '$name' — expected identifier (letters, digits, underscore)"
        }
        val format = if (colon < 0) null else body.substring(colon + 1)
        return TemplateNode.Variable(name, format)
    }

    private fun parseConditional(): TemplateNode.Conditional {
        expect('['); expect('?')
        val colon = src.indexOf(':', pos).also { require(it >= 0) { "Missing ':' in conditional at $pos" } }
        val condRaw = src.substring(pos, colon)
        pos = colon + 1
        val body = parseNodes(terminator = ']')
        expect(']')
        return TemplateNode.Conditional(Condition.parse(condRaw), body)
    }

    private fun expect(c: Char) {
        require(pos < src.length && src[pos] == c) {
            "Expected '$c' at position $pos in template '$src'"
        }
        pos++
    }
}
