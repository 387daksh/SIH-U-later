package org.sih.offlinespeech

internal data class AutoPeer(val address: String, val node: String, val role: String, val seenAt: Long)

/** Join an existing owner first; only the lowest visible idle node initiates a new group. */
internal fun chooseAutoPeer(localNode: String, peers: Collection<AutoPeer>, now: Long): AutoPeer? {
    val fresh = peers.filter { it.node != localNode && now - it.seenAt in 0..45_000 }
    fresh.filter { it.role == "owner" }.minByOrNull { it.node }?.let { return it }
    val idle = fresh.filter { it.role == "idle" }
    if (idle.any { it.node < localNode }) return null
    return idle.minByOrNull { it.node }
}
