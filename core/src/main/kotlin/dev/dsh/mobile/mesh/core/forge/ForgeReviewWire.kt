package dev.dsh.mobile.mesh.core.forge

import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewerRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One inline comment addressed to the old or new side of a change request diff. */
data class ForgeLineComment(
    val path: String,
    val side: String,
    val startLine: Int,
    val endLine: Int,
    val body: String,
)

/** The line target of a captured review comment, without its frozen hunk. */
fun ForgeReviewComment.asLineComment(body: String = this.body): ForgeLineComment = ForgeLineComment(
    path = path,
    side = side,
    startLine = startLine,
    endLine = endLine,
    body = body,
)

private fun ForgeLineComment.toJson(): JsonObject = buildJsonObject {
    put("path", JsonPrimitive(path))
    put("side", JsonPrimitive(side))
    put("startLine", JsonPrimitive(startLine))
    put("endLine", JsonPrimitive(endLine))
    put("body", JsonPrimitive(body))
}

/** A reply to one review thread, published now or added to the pending review. */
fun forgeReviewReplyWrite(headSha: String, threadId: String, body: String, pending: Boolean): JsonObject =
    buildJsonObject {
        put("headSha", JsonPrimitive(headSha))
        put("kind", JsonPrimitive("reply"))
        put("thread", JsonPrimitive(threadId))
        put("body", JsonPrimitive(body))
        put("pending", JsonPrimitive(pending))
    }

/** Mark or unmark one review thread resolved. */
fun forgeReviewResolveWrite(headSha: String, threadId: String, resolved: Boolean): JsonObject =
    buildJsonObject {
        put("headSha", JsonPrimitive(headSha))
        put("kind", JsonPrimitive("resolve"))
        put("thread", JsonPrimitive(threadId))
        put("resolved", JsonPrimitive(resolved))
    }

/** Replace the text of the signed-in user's own comment. */
fun forgeReviewEditCommentWrite(headSha: String, commentId: String, body: String): JsonObject =
    buildJsonObject {
        put("headSha", JsonPrimitive(headSha))
        put("kind", JsonPrimitive("edit-comment"))
        put("comment", JsonPrimitive(commentId))
        put("body", JsonPrimitive(body))
    }

/** Delete the signed-in user's own comment. */
fun forgeReviewDeleteCommentWrite(headSha: String, commentId: String): JsonObject =
    buildJsonObject {
        put("headSha", JsonPrimitive(headSha))
        put("kind", JsonPrimitive("delete-comment"))
        put("comment", JsonPrimitive(commentId))
    }

/** Add one line comment to the signed-in user's pending review. */
fun forgeReviewAddPendingWrite(headSha: String, comment: ForgeLineComment): JsonObject =
    buildJsonObject {
        put("headSha", JsonPrimitive(headSha))
        put("kind", JsonPrimitive("add-pending"))
        put("comment", comment.toJson())
    }

/** Submit the pending review with an explicit decision and summary. */
fun forgeReviewSubmitPendingWrite(
    headSha: String,
    reviewId: String,
    event: String,
    body: String,
): JsonObject = buildJsonObject {
    put("headSha", JsonPrimitive(headSha))
    put("kind", JsonPrimitive("submit-pending"))
    put("review", JsonPrimitive(reviewId))
    put("event", JsonPrimitive(event))
    put("body", JsonPrimitive(body))
}

/** Delete a pending review and every comment it holds. */
fun forgeReviewDiscardPendingWrite(headSha: String, reviewId: String): JsonObject =
    buildJsonObject {
        put("headSha", JsonPrimitive(headSha))
        put("kind", JsonPrimitive("discard-pending"))
        put("review", JsonPrimitive(reviewId))
    }

/** Publish one line comment immediately. */
fun forgeSubmitCommentRequest(headSha: String, comment: ForgeLineComment): JsonObject =
    buildJsonObject {
        put("headSha", JsonPrimitive(headSha))
        put("kind", JsonPrimitive("comment"))
        put("comment", comment.toJson())
    }

/** Publish a completed review with all of its comments. */
fun forgeSubmitReviewRequest(
    headSha: String,
    event: String,
    body: String,
    comments: List<ForgeLineComment>,
): JsonObject = buildJsonObject {
    put("headSha", JsonPrimitive(headSha))
    put("kind", JsonPrimitive("review"))
    put("event", JsonPrimitive(event))
    put("body", JsonPrimitive(body))
    put("comments", JsonArray(comments.map { it.toJson() }))
}

/** The wire array of reviewers a review request names. */
fun forgeReviewersJson(reviewers: List<ForgeReviewerRef>): JsonArray = JsonArray(
    reviewers.map { reviewer ->
        buildJsonObject {
            put("kind", JsonPrimitive(reviewer.kind))
            put("login", JsonPrimitive(reviewer.login))
        }
    },
)
