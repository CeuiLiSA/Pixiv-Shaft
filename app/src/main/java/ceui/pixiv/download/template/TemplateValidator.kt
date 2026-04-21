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
            applyBucketRules(source, bucket, issues)
        }

        if (source.endsWith("/") || source.endsWith("\\")) {
            issues += Issue(Severity.Error, "Template must end with a filename, not a directory separator")
        }

        return Result(issues, compiled)
    }

    private fun applyBucketRules(source: String, bucket: Bucket, issues: MutableList<Issue>) {
        val needsExtension = bucket != Bucket.Novel && bucket != Bucket.Backup && bucket != Bucket.Log
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
