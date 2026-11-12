package ceui.pixiv.ui.artlist

import ceui.lisa.model.ListIllust
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ArtlistContractTest {
    @Test fun `work pages reuse ListIllust without losing their cursor or work identity`() {
        val json = """{"illusts":[{"id":123,"title":"夏天","visible":true,"x_restrict":0,"page_count":12}],"next_url":"https://pixshaft.com/v1/artlists/7/works?after=30&limit=30&r18=0"}"""
        val page = Gson().fromJson(json, ListIllust::class.java)
        assertEquals(123L, page.list.single().id)
        assertEquals(12, page.list.single().page_count)
        assertEquals(7L, ArtlistRepository.pageListId(page.nextUrl))
    }

    @Test fun `only exact first-party artwork page URLs enter the first-party pagination client`() {
        assertEquals(1L, ArtlistRepository.pageListId("https://pixshaft.com/v1/artlists/1/works?after=20"))
        listOf("https://pixshaft.com.evil.test/v1/artlists/1/works", "http://pixshaft.com/v1/artlists/1/works",
            "https://pixshaft.com:8443/v1/artlists/1/works", "https://user@pixshaft.com/v1/artlists/1/works",
            "https://pixshaft.com/v1/account/online", "https://pixshaft.com/v1/artlists/0/works",
            "https://pixshaft.com/v1/artlists/1/works#fragment").forEach { assertNull(it, ArtlistRepository.pageListId(it)) }
    }
}
