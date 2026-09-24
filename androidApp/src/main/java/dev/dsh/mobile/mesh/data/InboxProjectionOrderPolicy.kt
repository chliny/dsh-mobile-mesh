package dev.dsh.mobile.mesh.data

/** An older independently-produced baseline must not overwrite a newer live inbox projection. */
internal fun shouldApplyInboxProjection(previousSeq: Int?, incomingSeq: Int?): Boolean =
    incomingSeq == null || previousSeq == null || incomingSeq >= previousSeq
