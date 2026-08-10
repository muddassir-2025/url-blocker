package com.muddassir.clearview.quran.model

/**
 * One dhikr phrase in the Dhikr Counter, together with its OWN counting
 * state — each dhikr keeps its count, target and last-completed session
 * independently, so switching between them never loses progress.
 *
 * @property id          Stable id (e.g. "subhanallah" for the built-ins,
 *                       "custom-<uuid>" for user-created ones). Never changes
 *                       on rename, so persisted selection stays valid.
 * @property name        Display name (e.g. "SubhanAllah").
 * @property arabic      The phrase in Arabic (may be empty for custom dhikr).
 * @property translation English translation (may be empty).
 * @property target      Repetition goal (33 / 99 / 100 / 1000 / custom).
 *                       0 = no target (the ring stays empty, "—" is shown).
 * @property visible     Hidden dhikr stay in management but never appear in
 *                       the swipeable selection (recoverable via settings).
 * @property order       Position in the selection (and the management list).
 * @property count       The CURRENT count (persisted, survives restarts).
 * @property lastCompletedCount The most recent completed session's count —
 *                       recorded when the count first reaches the target, kept
 *                       after a reset as the "Last: N" indicator.
 * @property lastCompletedAt   When that session was completed (epoch ms, 0 =
 *                       never completed).
 */
data class DhikrItem(
    val id: String,
    val name: String,
    val arabic: String = "",
    val translation: String = "",
    val target: Int = 33,
    val visible: Boolean = true,
    val order: Int = 0,
    val count: Int = 0,
    val lastCompletedCount: Int = 0,
    val lastCompletedAt: Long = 0L
)
