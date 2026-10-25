package app.qthrone.mobile.bg.carrier

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class VkRelayOAuthTest {
    @Test fun acceptsOnlyMatchingVkCallback() {
        assertEquals("test-token" to 3600L,VkRelayOAuth.tokenFields("https://oauth.vk.com/blank.html#access_token=test-token&expires_in=3600&state=expected","expected"))
        assertNull(VkRelayOAuth.tokenFields("https://oauth.vk.com/blank.html#access_token=test-token&state=wrong","expected"))
        assertNull(VkRelayOAuth.tokenFields("https://example.com/blank.html#access_token=test-token&state=expected","expected"))
    }
    @Test fun rejectsLookalikesAndInsecureRedirects() {
        assertTrue(VkRelayOAuth.trustedHttps(URI("https://login.vk.com/?act=grant_access")))
        for(url in listOf("http://vk.com/","https://vk.com.example.com/","https://vk.com@evil.test/","https://vk.com:8443/")) {
            assertFalse(VkRelayOAuth.trustedHttps(URI(url)))
        }
    }
    @Test fun extractsHtmlAuthorizationRedirect() {
        assertEquals("https://login.vk.com/?act=grant_access&x=1",VkRelayOAuth.nextAuthorizeUrl("<a href=\"https://login.vk.com/?act=grant_access&amp;x=1\">"))
    }
}
