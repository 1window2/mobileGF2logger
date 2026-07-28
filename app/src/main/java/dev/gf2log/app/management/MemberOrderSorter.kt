package dev.gf2log.app.management

enum class MemberSortField {
    JOIN_DATE,
    WEEKLY_MERIT,
    TOTAL_MERIT,
}

enum class MemberSortDirection {
    ASCENDING,
    DESCENDING,
}

object MemberOrderSorter {
    fun sort(
        members: List<MemberStatus>,
        latestMembers: Map<Long, SnapshotMember>,
        field: MemberSortField,
        direction: MemberSortDirection,
    ): List<MemberStatus> = members.sortedWith { first, second ->
        val primary = when (field) {
            MemberSortField.JOIN_DATE -> compareKnown(
                first.currentJoinDate(),
                second.currentJoinDate(),
                direction,
            )
            MemberSortField.WEEKLY_MERIT -> compareOptional(
                latestMembers[first.uid]?.weeklyMerit,
                latestMembers[second.uid]?.weeklyMerit,
                direction,
            )
            MemberSortField.TOTAL_MERIT -> compareOptional(
                latestMembers[first.uid]?.totalMerit,
                latestMembers[second.uid]?.totalMerit,
                direction,
            )
        }
        if (primary != 0) {
            primary
        } else {
            val name = String.CASE_INSENSITIVE_ORDER.compare(first.name, second.name)
            if (name != 0) name else first.uid.compareTo(second.uid)
        }
    }

    private fun MemberStatus.currentJoinDate() =
        tenures.lastOrNull { it.leftAt == null }?.joinedAt ?: firstSeenAt

    private fun <T : Comparable<T>> compareKnown(
        first: T,
        second: T,
        direction: MemberSortDirection,
    ): Int = direction.apply(first.compareTo(second))

    private fun <T : Comparable<T>> compareOptional(
        first: T?,
        second: T?,
        direction: MemberSortDirection,
    ): Int = when {
        first == null && second == null -> 0
        first == null -> 1
        second == null -> -1
        else -> direction.apply(first.compareTo(second))
    }

    private fun MemberSortDirection.apply(value: Int): Int =
        if (this == MemberSortDirection.ASCENDING) value else -value
}
