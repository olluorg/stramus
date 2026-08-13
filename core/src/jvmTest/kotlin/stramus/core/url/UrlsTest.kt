package stramus.core.url

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlsTest {

    private val id = "dQw4w9WgXcQ"

    @Test
    fun `watch addresses name their video`() {
        assertEquals(id, youtubeVideoId("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, youtubeVideoId("https://youtube.com/watch?v=$id"))
        assertEquals(id, youtubeVideoId("https://m.youtube.com/watch?v=$id"))
        assertEquals(id, youtubeVideoId("https://music.youtube.com/watch?v=$id"))
    }

    @Test
    fun `the parameters around v do not hide it`() {
        assertEquals(id, youtubeVideoId("https://www.youtube.com/watch?list=PL1&v=$id&t=42s"))
        assertEquals(id, youtubeVideoId("https://www.youtube.com/watch?v=$id&t=42s"))
    }

    @Test
    fun `a fragment is not part of the id`() {
        assertEquals(id, youtubeVideoId("https://youtu.be/$id#t=10"))
        assertEquals(id, youtubeVideoId("https://www.youtube.com/watch?v=$id#comments"))
    }

    @Test
    fun `the short form is the id itself`() {
        assertEquals(id, youtubeVideoId("https://youtu.be/$id"))
        assertEquals(id, youtubeVideoId("https://youtu.be/$id?t=30"))
    }

    @Test
    fun `the path forms the site hands out`() {
        assertEquals(id, youtubeVideoId("https://www.youtube.com/shorts/$id"))
        assertEquals(id, youtubeVideoId("https://www.youtube.com/embed/$id?rel=0"))
        assertEquals(id, youtubeVideoId("https://www.youtube-nocookie.com/embed/$id"))
        assertEquals(id, youtubeVideoId("https://www.youtube.com/live/$id"))
        assertEquals(id, youtubeVideoId("https://www.youtube.com/v/$id"))
    }

    @Test
    fun `pages that are not a video have none`() {
        assertNull(youtubeVideoId("https://www.youtube.com/"))
        assertNull(youtubeVideoId("https://www.youtube.com/feed/subscriptions"))
        assertNull(youtubeVideoId("https://www.youtube.com/@kotlin"))
        assertNull(youtubeVideoId("https://www.youtube.com/playlist?list=PL1234567890"))
    }

    @Test
    fun `an id of the wrong shape is not one`() {
        assertNull(youtubeVideoId("https://www.youtube.com/watch?v=short"))
        assertNull(youtubeVideoId("https://youtu.be/way-too-long-for-an-id"))
        assertNull(youtubeVideoId("https://www.youtube.com/watch?v="))
    }

    @Test
    fun `other sites are left alone`() {
        assertNull(youtubeVideoId("https://vimeo.com/watch?v=$id"))
        assertNull(youtubeVideoId("https://kotlinlang.org"))
        assertNull(youtubeVideoId("https://notyoutube.com/watch?v=$id"))
    }
}
