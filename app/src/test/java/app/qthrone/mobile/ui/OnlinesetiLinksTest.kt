package app.qthrone.mobile.ui

import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class OnlinesetiLinksTest {
    private fun link(url: String) = "onlineseti://import?url=" + URLEncoder.encode(url, "UTF-8")

    @Test fun acceptsBothSubscriptionHosts() {
        for (host in listOf("openseti.ru", "onlineseti.ru")) {
            val url = "https://$host/subscription/example-token-123"
            assertEquals(url, OnlinesetiLinks.subscription(link(url)))
        }
    }

    @Test fun rejectsOtherDestinationsAndAmbiguousInput() {
        for (url in listOf(
            "http://openseti.ru/subscription/example-token-123",
            "https://openseti.ru.attacker.test/subscription/example-token-123",
            "https://attacker.test/subscription/example-token-123",
            "https://user@openseti.ru/subscription/example-token-123",
            "https://openseti.ru:8443/subscription/example-token-123",
            "https://openseti.ru/subscription/../admin",
            "https://openseti.ru/subscription/example-token-123?redirect=test",
            "https://openseti.ru/subscription/example-token-123#fragment"
        )) assertNull(OnlinesetiLinks.subscription(link(url)))
        assertNull(OnlinesetiLinks.subscription(link("https://openseti.ru/subscription/example-token-123") + "&url=other"))
        assertNull(OnlinesetiLinks.subscription("onlineseti://import?url=%ZZ"))
    }
}
