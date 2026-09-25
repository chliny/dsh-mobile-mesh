package dev.dsh.mobile.mesh.data

/** A completed session must not retain its previous turn's clock across a foreground refresh. */
internal fun resolvedTurnStartMillis(
    running: Boolean,
    eventStartMillis: Long?,
    rememberedStartMillis: Long?,
    nowMillis: Long,
): Long? = when {
    !running -> null
    eventStartMillis != null -> eventStartMillis
    rememberedStartMillis != null -> rememberedStartMillis
    else -> nowMillis
}
