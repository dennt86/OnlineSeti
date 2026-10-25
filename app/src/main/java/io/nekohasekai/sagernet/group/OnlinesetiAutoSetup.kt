package app.qthrone.mobile.group

import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.GroupRepo
import app.qthrone.mobile.database.ProfileManager
import app.qthrone.mobile.outbound.types.AutoSelector
import app.qthrone.mobile.ui.OnlinesetiLinks
import java.net.URLEncoder

/** Keep the selector outside the subscription, so refreshing it cannot delete Auto mode. */
object OnlinesetiAutoSetup {
    fun owns(url: String): Boolean = OnlinesetiLinks.subscription(
        "onlineseti://import?url=" + URLEncoder.encode(url, "UTF-8")
    ) != null

    fun policy(groupId: Long) = AutoSelector().apply {
        name = "Автоматически · Onlineseti"
        gid = groupId
        watchIntervalSec = 10
        intervalSec = 60
        benchIntervalSec = 300
        resultValidityMins = 5
        expected = 3
        dialRetries = 2
        toleranceMs = 300
        interruptOnSwitch = true
        balance = false
    }

    /** Activate automatic mode when the user currently has a server from this subscription selected. */
    fun shouldActivateSelector(selectedGroupId: Long?, subscriptionId: Long): Boolean =
        selectedGroupId == null || selectedGroupId == subscriptionId

    suspend fun ensure(groupId: Long) {
        val localGroup = GroupRepo.serverListId()
        val existing = ProfileManager.members(localGroup).firstOrNull {
            (it.outbound as? AutoSelector)?.gid == groupId
        }
        val selector = existing ?: ProfileManager.addProfile(policy(groupId), localGroup)
        val selectedGroupId = ProfileManager.getProfile(DataStore.selectedProxy)?.groupId
        if (shouldActivateSelector(selectedGroupId, groupId)) DataStore.selectedProxy = selector.id
    }
}
