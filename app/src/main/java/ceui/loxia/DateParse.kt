package ceui.loxia

object DateParse {

    private const val FAKE_DATE = "2022-03-21 08:24"

    fun displayCreateDate(create_date: String?): String {
        if (create_date?.isNotEmpty() == true) {
            return if (create_date.contains("T") && create_date.length == 25) {
                val str = create_date.substring(0, 16)
                str.replace("T", "  ")
            } else {
                FAKE_DATE
            }
        }
        return FAKE_DATE
    }
}