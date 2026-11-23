package dev.dsh.mobile.mesh.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types of the optional `forgePanel` Remote (the "Change requests & worktrees" plugin).
 *
 * Every value is decoded leniently and every optional member carries a default: the plugin is an
 * optional Host capability, and an older plugin revision must not turn the whole panel stream into
 * a decode failure. Only fields the App renders are declared; unknown fields are ignored.
 */

/** Facts about the checkout that contains the Session's current directory. */
@Serializable
data class ForgeCheckout(
    val root: String = "",
    val branch: String? = null,
    val linked: Boolean = false,
    val commit: String? = null,
)

/** Result of the panel's checkout inspection. `state` is `pending`, `none`, `failed`, or `ready`. */
@Serializable
data class ForgeCheckoutState(
    val state: String = "pending",
    val message: String? = null,
    val checkout: ForgeCheckout? = null,
)

/** Code-host repository that serves the checkout's remotes. */
@Serializable
data class ForgeRepository(
    val forge: String = "",
    val host: String = "",
    val owner: String = "",
    val name: String = "",
) {
    val fullName: String get() = if (owner.isEmpty()) name else "$owner/$name"
}

/** Sign-in state of the code host, or null before the first probe. */
@Serializable
data class ForgeReadiness(
    val state: String? = null,
    val message: String? = null,
)

/** A worktree the panel created for a Session. */
@Serializable
data class ForgeSessionWorktree(
    val path: String = "",
    val branch: String = "",
    val from: String? = null,
    @SerialName("baseCommit") val baseCommit: String = "",
    @SerialName("repositoryRoot") val repositoryRoot: String = "",
)

/** One open worktree request: the base branch, or null for HEAD. */
@Serializable
data class ForgeWorktreeRequest(
    val from: String? = null,
)

/** One running worktree creation. `phase` is `prepare` or `checkout`. */
@Serializable
data class ForgeWorktreeCreation(
    val phase: String = "prepare",
    val branch: String = "",
    val from: String? = null,
    val held: Boolean = false,
)

/** The last failed attempt of the worktree a waiting message requested. */
@Serializable
data class ForgeWorktreeFailure(
    val branch: String? = null,
    val from: String? = null,
    val phase: String = "prepare",
    val message: String = "",
)

/** The created worktree whose directory is missing; null `branchExists` means unknown. */
@Serializable
data class ForgeMissingWorktree(
    val branchExists: Boolean? = null,
)

/** A running restoration of a removed worktree. */
@Serializable
data class ForgeWorktreeRestoration(
    val phase: String = "prepare",
)

/** Worktree facts of one Session in the panel state. */
@Serializable
data class ForgePanelWorktree(
    val editable: Boolean = false,
    val blocker: String? = null,
    val request: ForgeWorktreeRequest? = null,
    val created: ForgeSessionWorktree? = null,
    val creation: ForgeWorktreeCreation? = null,
    val failure: ForgeWorktreeFailure? = null,
    val missing: ForgeMissingWorktree? = null,
    val restoration: ForgeWorktreeRestoration? = null,
    /** `creation` or `restoration` while a message waits before its turn starts. */
    val waiting: String? = null,
)

/** One changed file in the compact local-changes summary of the panel state. */
@Serializable
data class ForgeChangedFile(
    val path: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val binary: Boolean = false,
)

/** Size of the `branch` scope, used by the panel's local changes card. */
@Serializable
data class ForgeLocalChanges(
    val base: String = "",
    val files: List<ForgeChangedFile> = emptyList(),
)

/** Provider-neutral change request reference. */
@Serializable
data class ForgeChangeRequestRef(
    val repository: ForgeRepository? = null,
    val number: Int = 0,
)

/** One status check as the panel snapshot reports it. */
@Serializable
data class ForgeChangeRequestCheck(
    val name: String = "",
    /** `pending`, `success`, `failure`, `neutral`, `skipped`, or `cancelled`. */
    val state: String = "neutral",
    val url: String? = null,
    val workflow: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
    /** Host identity a rerun names, or null when the check cannot be rerun. */
    val rerun: String? = null,
)

/**
 * Last polled state of one change request.
 *
 * The real snapshot carries many more members (reviewers, merge methods, issues, ...); they are
 * deliberately not declared because the App's compact overview only needs the fields below.
 */
@Serializable
data class ForgeChangeRequestSnapshot(
    val ref: ForgeChangeRequestRef? = null,
    val url: String = "",
    val title: String = "",
    /** `open`, `closed`, or `merged`. */
    val state: String = "open",
    val draft: Boolean = false,
    val author: String? = null,
    val headBranch: String = "",
    val headSha: String = "",
    val baseBranch: String = "",
    val baseSha: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    /** `mergeable`, `conflicting`, or `unknown`. */
    val mergeability: String = "unknown",
    val mergeReady: Boolean = false,
    val mergeMethods: List<String> = emptyList(),
    val branchUpdates: List<String> = emptyList(),
    /** `approved`, `changes-requested`, `review-required`, or `none`. */
    val reviewDecision: String = "none",
    val checks: List<ForgeChangeRequestCheck> = emptyList(),
    /** `none`, `pending`, `success`, or `failure`. */
    val checksRollup: String = "none",
    val checksTruncated: Boolean = false,
    val canRerunChecks: Boolean = false,
    /** Whether the signed-in user may request reviews through the provider. */
    val canRequestReviews: Boolean = false,
    val unresolvedThreads: Int = 0,
    val threadsTruncated: Boolean = false,
    val updatedAt: String = "",
    val closedAt: String? = null,
)

/** One author of a review comment. */
@Serializable
data class ForgeCommentAuthor(
    val login: String = "",
    val avatarUrl: String? = null,
    val bot: Boolean = false,
)

/** One review comment. */
@Serializable
data class ForgeReviewComment(
    val id: String = "",
    val author: ForgeCommentAuthor? = null,
    /** Markdown source as the author wrote it. */
    val body: String = "",
    val createdAt: String = "",
    /** Part of the signed-in user's unsubmitted review. */
    val pending: Boolean = false,
    val url: String? = null,
    val viewerCanEdit: Boolean = false,
    val viewerCanDelete: Boolean = false,
)

/** One line range on one side of a diff at a named commit. */
@Serializable
data class ForgeAnchor(
    val commit: String = "",
    val startLine: Int = 0,
    val endLine: Int = 0,
)

/** One review thread on a file of a change request. */
@Serializable
data class ForgeReviewThread(
    val id: String = "",
    val path: String = "",
    /** Old (`L`) or new (`R`) side. */
    val side: String = "R",
    /** `line` or `file`. */
    val subject: String = "line",
    /** Position on the head commit of the read, or null when outdated. */
    val current: ForgeAnchor? = null,
    /** Position on the commit the thread was written on. */
    val original: ForgeAnchor? = null,
    /** Patch the host recorded when the thread was written. */
    val diffHunk: String? = null,
    val resolved: Boolean = false,
    val viewerCanReply: Boolean = false,
    val viewerCanResolve: Boolean = false,
    /** Oldest first; the first comment starts the thread. */
    val comments: List<ForgeReviewComment> = emptyList(),
)

/** The signed-in user's unsubmitted review. */
@Serializable
data class ForgePendingReview(
    val id: String = "",
    val comments: Int = 0,
)

/** Review threads of a change request read at one head commit. */
@Serializable
data class ForgeReviewData(
    val headSha: String = "",
    val viewer: String? = null,
    val threads: List<ForgeReviewThread> = emptyList(),
    val truncated: Boolean = false,
    val pendingReview: ForgePendingReview? = null,
    val viewerCanReview: Boolean = false,
)

/** Result of a review-thread write. */
@Serializable
data class ForgeReviewWriteResult(
    val ok: Boolean = false,
    /** `unsupported`, `stale`, `failed`, or `unknown` when [ok] is false. */
    val code: String? = null,
    val message: String? = null,
)

/** Result of publishing one comment or a completed review. */
@Serializable
data class ForgeReviewResult(
    val ok: Boolean = false,
    val url: String? = null,
    /** `unsupported`, `stale`, `failed`, or `unknown` when [ok] is false. */
    val code: String? = null,
    val message: String? = null,
)

/** Signed-in user's viewed marks on the files of a change request. */
@Serializable
data class ForgeViewedFiles(
    val headSha: String = "",
    /** `viewed`, `unviewed`, or `dismissed` per repository-relative path. */
    val files: Map<String, String> = emptyMap(),
    val truncated: Boolean = false,
)

/** One account a review can be requested from. */
@Serializable
data class ForgeReviewerCandidate(
    val login: String = "",
    val name: String? = null,
    val avatarUrl: String? = null,
    val suggested: Boolean = false,
)

/** One reviewer named by a review request. */
@Serializable
data class ForgeReviewerRef(
    /** `user`, `bot`, or `team`. */
    val kind: String = "user",
    val login: String = "",
)

/** One change request state in the host-wide session-list stream. */
@Serializable
data class ForgeChangeRequestListState(
    val url: String = "",
    val title: String = "",
    val state: String = "open",
    val draft: Boolean = false,
    val checksRollup: String = "none",
    val reviewDecision: String = "none",
    val mergeability: String = "unknown",
    val number: Int = 0,
)

/** One frame of `forgePanel/changeRequestStates`: a baseline, then changed states. */
@Serializable
data class ForgeChangeRequestStatesFrame(
    val type: String = "baseline",
    val states: List<ForgeChangeRequestListState> = emptyList(),
)

/** One polling result of a change request binding, as older panel revisions publish it. */
@Serializable
data class ForgeChangeRequestUpdate(
    val snapshot: ForgeChangeRequestSnapshot? = null,
)

/** One binding of a change request to a Session, with its latest polled snapshot. */
@Serializable
data class ForgePanelChangeRequest(
    val url: String = "",
    val origin: String? = null,
    val ref: ForgeChangeRequestRef? = null,
    /** Latest snapshot a poll returned, kept while later polls fail, or null before the first poll. */
    val snapshot: ForgeChangeRequestSnapshot? = null,
    /** Older panel revisions nested the snapshot here; prefer [snapshot] and fall back to this. */
    val update: ForgeChangeRequestUpdate? = null,
)

/** One frame of the per-Session `forgePanel/follow` stream. */
@Serializable
data class ForgePanelState(
    val directory: String = "",
    val checkout: ForgeCheckoutState = ForgeCheckoutState(),
    val repository: ForgeRepository? = null,
    val readiness: ForgeReadiness? = null,
    val changeRequests: List<ForgePanelChangeRequest> = emptyList(),
    val worktree: ForgePanelWorktree = ForgePanelWorktree(),
    val local: ForgeLocalChanges? = null,
)

/** Local branches offered as the base of a new worktree. */
@Serializable
data class ForgeBranches(
    val current: String? = null,
    val branches: List<String> = emptyList(),
)

/** One branch the `branch` scope can compare with. */
@Serializable
data class ForgeBaseBranch(
    val ref: String = "",
    val name: String = "",
)

/** What the `branch` scope compares with while no base branch is chosen. */
@Serializable
data class ForgeAutomaticBase(
    val name: String = "",
    val worktree: Boolean = false,
)

/** Branches the `branch` scope can compare with. */
@Serializable
data class ForgeBaseBranches(
    val automatic: ForgeAutomaticBase? = null,
    val remote: List<ForgeBaseBranch> = emptyList(),
    val local: List<ForgeBaseBranch> = emptyList(),
)

/** One hunk of a file diff; every line starts with ` `, `+`, `-`, or `\`. */
@Serializable
data class ForgeDiffHunk(
    @SerialName("oldStart") val oldStart: Int = 0,
    @SerialName("oldLines") val oldLines: Int = 0,
    @SerialName("newStart") val newStart: Int = 0,
    @SerialName("newLines") val newLines: Int = 0,
    val lines: List<String> = emptyList(),
)

/** One changed file of a diff. */
@Serializable
data class ForgeDiffFile(
    /** Repository-relative path after the change, or before it for a deleted file. */
    val path: String = "",
    val previousPath: String? = null,
    /** `added`, `deleted`, `modified`, or `renamed`. */
    val status: String = "modified",
    val binary: Boolean = false,
    val oversized: Boolean = false,
    val untracked: Boolean = false,
    val additions: Int = 0,
    val deletions: Int = 0,
    val hunks: List<ForgeDiffHunk> = emptyList(),
    /** `interface`, `implementation`, or `test`. */
    val layer: String = "implementation",
    val digest: String = "",
)

/** Local changes of the Session's checkout in one scope. */
@Serializable
data class ForgeLocalDiff(
    /** Ref or abbreviated commit the `branch` scope compares with; null for other scopes. */
    val base: String? = null,
    val files: List<ForgeDiffFile> = emptyList(),
    /** Untracked files left out beyond the Host's untracked-file cap. */
    val omitted: Int = 0,
)

/** Changes of a change request, or of consecutive commits of it. */
@Serializable
data class ForgeChangeRequestDiff(
    val headSha: String = "",
    val files: List<ForgeDiffFile> = emptyList(),
    /** Commits the local branch has beyond the head, when that branch is the head branch. */
    val localAhead: Int? = null,
    val fileUrls: Map<String, String> = emptyMap(),
)

/** Consecutive commits a changes view shows instead of the whole comparison. */
@Serializable
data class ForgeCommitRange(
    val from: String,
    val to: String,
) {
    val single: Boolean get() = from == to
}

/** One commit. */
@Serializable
data class ForgeCommit(
    val sha: String = "",
    val parent: String? = null,
    val title: String = "",
    val author: String? = null,
    val authoredAt: String = "",
)

/** Commits of the local `branch` scope at one HEAD commit, oldest first. */
@Serializable
data class ForgeLocalCommits(
    val headSha: String = "",
    val commits: List<ForgeCommit> = emptyList(),
    val truncated: Boolean = false,
)

/** Commits of a change request at one head commit, oldest first. */
@Serializable
data class ForgeCommitList(
    val headSha: String = "",
    val commits: List<ForgeCommit> = emptyList(),
    val truncated: Boolean = false,
)

/**
 * One local comparison. `branch` compares the working tree and untracked files against the merge
 * base with `base` (or the automatic fork point when null); the other kinds compare the working
 * tree, the index, or HEAD.
 */
data class ForgeLocalScope(
    val kind: String,
    val base: String? = null,
    val commits: ForgeCommitRange? = null,
) {
    init {
        require(kind in KINDS) { "unknown forge local scope: $kind" }
    }

    companion object {
        const val BRANCH: String = "branch"
        const val UNCOMMITTED: String = "uncommitted"
        const val UNSTAGED: String = "unstaged"
        const val STAGED: String = "staged"

        val KINDS: Set<String> = setOf(BRANCH, UNCOMMITTED, UNSTAGED, STAGED)

        fun branch(base: String? = null, commits: ForgeCommitRange? = null): ForgeLocalScope =
            ForgeLocalScope(BRANCH, base, commits)

        fun uncommitted(): ForgeLocalScope = ForgeLocalScope(UNCOMMITTED)

        fun unstaged(): ForgeLocalScope = ForgeLocalScope(UNSTAGED)

        fun staged(): ForgeLocalScope = ForgeLocalScope(STAGED)
    }
}

/** `true` when the Host says this URL is bound to the Session and served by a provider. */
fun ForgePanelChangeRequest.isServable(): Boolean = ref != null && latestSnapshot() != null

/** The latest snapshot, whether it arrived at the top level or only inside the poll update. */
fun ForgePanelChangeRequest.latestSnapshot(): ForgeChangeRequestSnapshot? = snapshot ?: update?.snapshot
