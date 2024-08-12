package ceui.pixiv.ui.works


fun buildPixivWorksFileName(illustId: Long, index: Int = 0): String {
    return "pixiv_works_${illustId}_p${index}.png"
}