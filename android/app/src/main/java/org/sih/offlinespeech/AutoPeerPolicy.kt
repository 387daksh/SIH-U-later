package org.sih.offlinespeech

internal data class AutoPeer(val address: String, val node: String, val role: String, val seenAt: Long)

/** Join an existing owner first; only the lowest visible idle node initiates a new group.
 *  [idleSince] is when this phone last had no group: once it has waited well past the
 *  point where the lowest node should have formed one, the next node takes over so a
 *  phone stuck on a busy Wi-Fi stack cannot block the whole team from connecting. */
internal fun chooseAutoPeer(localNode: String, peers: Collection<AutoPeer>, now: Long,
    idleSince: Long = now): AutoPeer? {
    val fresh = peers.filter { it.node != localNode && now - it.seenAt in 0..45_000 }
    fresh.filter { it.role == "owner" }.minByOrNull { it.node }?.let { return it }
    val idle = fresh.filter { it.role == "idle" }
    if (now - idleSince < 60_000 && idle.any { it.node < localNode }) return null
    return idle.minByOrNull { it.node }
}
