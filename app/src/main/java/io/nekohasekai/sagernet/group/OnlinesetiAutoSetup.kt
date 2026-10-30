package app.qthrone.mobile.group

import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.GroupRepo
import app.qthrone.mobile.database.ProfileManager
import app.qthrone.mobile.database.ProxyEntity
import app.qthrone.mobile.database.SagerDatabase
import app.qthrone.mobile.outbound.types.AutoSelector
import app.qthrone.mobile.ui.OnlinesetiLinks
import java.net.URLEncoder

/** Keep the selector outside the subscription, so refreshing it cannot delete Auto mode. */
object OnlinesetiAutoSetup {
    const val AUTOMATIC_NOTIFICATION_NAME = "Onlineseti — A"
    const val MANUAL_NOTIFICATION_NAME = "Onlineseti — M"
    private const val SELECTOR_NAME = AUTOMATIC_NOTIFICATION_NAME
    private const val LEGACY_SELECTOR_NAME = "Автоматически · Onlineseti"

    fun isManagedSelectorName(name: String?): Boolean =
        name == SELECTOR_NAME || name == LEGACY_SELECTOR_NAME

    private fun isManaged(selector: AutoSelector): Boolean = isManagedSelectorName(selector.name)

    fun owns(url: String): Boolean = OnlinesetiLinks.subscription(
        "onlineseti://import?url=" + URLEncoder.encode(url, "UTF-8")
    ) != null

    fun policy(groupId: Long) = AutoSelector().apply {
        name = SELECTOR_NAME
        gid = groupId
        watchIntervalSec = 10
        intervalSec = 60
        benchIntervalSec = 300
        resultValidityMins = 5
        expected = 3
        // A single transient reset on the SOCKS hop should not immediately mark a member unusable.
        dialRetries = 3
        // Keep the current healthy route; other routes remain standby until the core detects an outage.
        toleranceMs = AutoSelector.STICKY_TOLERANCE_MS
        // Preserve in-flight sessions when a member briefly resets; new connections still fail over.
        interruptOnSwitch = false
        balance = false
    }

    /** Activate when this selector (including a stale instance being repaired) is already selected. */
    fun shouldActivateSelector(
        selectedProfileId: Long?, selectorId: Long,
        selectedGroupId: Long?, subscriptionId: Long,
    ): Boolean = selectedProfileId == selectorId || selectedGroupId == null || selectedGroupId == subscriptionId

    /** Rebind only app-managed selectors and discard ranks tied to the previous subscription group's profile IDs. */
    fun rebind(selector: AutoSelector, groupId: Long): Boolean {
        if (selector.gid == groupId) return false
        selector.gid = groupId
        selector.pool.clear()
        selector.poolRankedAt = 0
        selector.lastBuilt.clear()
        selector.lastBuiltAt = 0
        selector.pinnedID = -1
        return true
    }

    /** Migrate the old managed default once while preserving any user-customized tolerance. */
    fun migrateSmoothingPolicy(selector: AutoSelector): Boolean {
        if (selector.toleranceMs != 300 && selector.toleranceMs != 1000 && selector.toleranceMs != 5000) return false
        selector.toleranceMs = AutoSelector.STICKY_TOLERANCE_MS
        return true
    }

    fun needsGroupRepair(boundGroupExists: Boolean, boundMemberCount: Int): Boolean =
        !boundGroupExists || boundMemberCount <= 0

    /** Repair an app-managed selector from its current Onlineseti subscription before attempting to connect. */
    fun repairOrphan(profile: ProxyEntity): Boolean {
        val selector = profile.outbound as? AutoSelector ?: return false
        if (!isManaged(selector)) return false
        val boundGroup = GroupRepo.get(selector.gid)
        // A subscription refresh can preserve an empty group after receiving an unusable response. If this device
        // already has another populated first-party subscription (for example, the old group before recreation),
        // do not leave Auto mode bound to the empty one.
        if (!needsGroupRepair(boundGroup != null, boundGroup?.let { ProfileManager.memberIds(it.id).size } ?: 0)) return false
        val group = GroupRepo.all().firstOrNull {
            it.isSubscription && owns(it.url) && ProfileManager.memberIds(it.id).isNotEmpty()
        } ?: return false
        if (!rebind(selector, group.id)) return false
        profile.putOutbound(selector)
        SagerDatabase.proxyDao.updateProxy(profile)
        return true
    }

    suspend fun ensure(groupId: Long) {
        val localGroup = GroupRepo.serverListId()
        val selectors = ProfileManager.members(localGroup).mapNotNull { profile ->
            (profile.outbound as? AutoSelector)?.let { profile to it }
        }
        val selectedId = DataStore.selectedProxy
        val selected = selectors.firstOrNull { it.first.id == selectedId && isManaged(it.second) }
        val existing = selected
            ?: selectors.firstOrNull { it.second.gid == groupId && isManaged(it.second) }
            ?: selectors.firstOrNull { isManaged(it.second) }
        val selector = if (existing == null) {
            ProfileManager.addProfile(policy(groupId), localGroup)
        } else {
            val (profile, outbound) = existing
            val groupChanged = rebind(outbound, groupId)
            val smoothingChanged = migrateSmoothingPolicy(outbound)
            val nameChanged = outbound.name != SELECTOR_NAME
            outbound.name = SELECTOR_NAME
            if (groupChanged || smoothingChanged || nameChanged) {
                profile.putOutbound(outbound)
                ProfileManager.updateProfile(profile)
            }
            profile
        }
        val selectedProfile = ProfileManager.getProfile(selectedId)
        if (shouldActivateSelector(
                selectedProfileId = selectedId.takeIf { selected?.first?.id == it },
                selectorId = selector.id,
                selectedGroupId = selectedProfile?.groupId,
                subscriptionId = groupId,
            )
        ) DataStore.selectedProxy = selector.id
    }
}
