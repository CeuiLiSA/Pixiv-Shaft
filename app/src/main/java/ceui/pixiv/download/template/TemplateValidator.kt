package ceui.pixiv.download.template

import ceui.pixiv.download.model.Bucket

/**
 * Static analysis of a template source. Intended for the settings UI — returns
 * a list of findings so users can fix mistakes before saving.
 *
 * Specification pattern: each rule is an independent check against a parsed
 * template; the validator aggregates findings from all applicable rules.
 */
object TemplateValidator {

    enum class Severity { Error, Warning }

    data class Issue(val severity: Severity, val message: String)

    data class Result(
        val issues: List<Issue>,
        val compiled: Template? = null,
    ) {
        val ok: Boolean get() = issues.none { it.severity == Severity.Error }
        val errors: List<Issue> get() = issues.filter { it.severity == Severity.Error }
        val warnings: List<Issue> get() = issues.filter { it.severity == Severity.Warning }
    }

    fun validate(source: String, bucket: Bucket? = null): Result {
        val issues = mutableListOf<Issue>()

        val compiled = try {
            Template.compile(source)
        } catch (e: Exception) {
            issues += Issue(Severity.Error, "Syntax: ${e.message ?: "invalid template"}")
            return Result(issues, null)
        }

        if (source.isBlank()) {
            issues += Issue(Severity.Error, "Template is empty")
        }

        if (bucket != null) {
            // Compiling is not enough: unknown flags/variables and bad date
            // formats only blow up at *render* time (TemplateContext), which
            // used to slip past Save and crash the actual download. Render a
            // sample so those errors are caught before the template is persisted.
            when (val preview = TemplateSamples.preview(source, bucket)) {
                is TemplateSamples.Preview.Failure ->
                    issues += Issue(Severity.Error, "Render: ${preview.message}")
                is TemplateSamples.Preview.Ok -> Unit
            }
            applyBucketRules(source, bucket, issues)
        }

        checkPageMasks(source, issues)

        if (source.endsWith("/") || source.endsWith("\\")) {
            issues += Issue(Severity.Error, "Template must end with a filename, not a directory separator")
        }

        return Result(issues, compiled)
    }

    private val PAGE_MASK_REGEX = Regex("\\{(page1?|series_order):([^}]*)\\}")

    /**
     * `{page:000}` pins a zero-pad width. Rendering ignores a malformed mask so
     * that pre-existing templates keep working (see
     * [TemplateContext.explicitWidth]), which means a typo would otherwise be
     * invisible — the download would just silently come out unpadded. Catch it
     * here, while the user is still looking at the editor.
     */
    private fun checkPageMasks(source: String, issues: MutableList<Issue>) {
        PAGE_MASK_REGEX.findAll(source).forEach { match ->
            val (name, mask) = match.destructured
            if (!TemplateContext.isPageMask(mask)) {
                issues += Issue(
                    Severity.Error,
                    "Invalid width in '{$name:$mask}' — expected 1-${TemplateContext.MAX_PAGE_WIDTH} " +
                        "zeros, e.g. {$name:000}",
                )
            }
        }
    }

    private fun applyBucketRules(source: String, bucket: Bucket, issues: MutableList<Issue>) {
        if (bucket == Bucket.Backup) {
            if (source.contains("{ext}")) {
                issues += Issue(
                    Severity.Error,
                    "Backup template must not contain {ext} — backup files are always .json",
                )
            }
            if (!source.endsWith(".json")) {
                issues += Issue(
                    Severity.Error,
                    "Backup template must end with .json",
                )
            }
            return
        }
        if (bucket == Bucket.Log) {
            if (source.contains("{ext}")) {
                issues += Issue(
                    Severity.Error,
                    "Log template must not contain {ext} — log files are always .txt",
                )
            }
            if (!source.endsWith(".txt")) {
                issues += Issue(
                    Severity.Error,
                    "Log template must end with .txt",
                )
            }
            return
        }
        val needsExtension = bucket != Bucket.Novel
        if (needsExtension && !source.contains("{ext}") && !source.matches(Regex(".*\\.[A-Za-z0-9]+$"))) {
            issues += Issue(
                Severity.Warning,
                "Template has no {ext} variable and no literal file extension — downloads may lose their extension",
            )
        }
        if (bucket == Bucket.Illust && !source.contains("{id}")) {
            issues += Issue(
                Severity.Warning,
                "Illust template without {id} will cause filename collisions across works with the same title",
            )
        }
    }
}
