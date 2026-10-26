# JoyBrush synchronization and cleanup contract

Owner policy, October 26, 2026. Local source and its published branch must have an
honest, inspectable relationship. Synchronization does not mean merging unverified
feature branches into `joy-creator`, or deleting preserved work to obtain a clean status.

## Each checkpoint

1. Read the current roadmap and inspect branch, status, worktree and remote refs.
   Fetch before assessing divergence. Use an isolated branch; preserve unrelated work.
2. Commit explicit owned source, tests and relevant roadmap row/handoff changes.
   Review the staged diff. Exclude caches, APKs/build output, credentials and owner media.
3. Push that branch without force and compare `git rev-parse HEAD` with
   `git ls-remote origin refs/heads/<branch>`. Record equality or the exact failure.
4. Handoff: source/base, branch+remote status, unique dirty/untracked paths, evidence,
   running jobs/locks, owner and next gate. Another agent must not need chat history
   to distinguish source-only from tested, integrated and activated work.
5. Root reconciles small milestone updates into the canonical roadmap; specialists
   must not copy their whole older board over it. Handoffs retain detailed substeps.

## Reconciliation and cleanup

Fetch and inspect ahead/behind and ancestry; never interpret matching filenames as
matching source. Do not automatically pull/reset the stale primary. Conflicting
cherry-picks/merges are aborted and changes selectively reapplied on current source.

Before deleting any artifact or worktree, inventory its unique commits, dirty/staged
files, raw evidence and external dependencies. Preserve a recoverable source/evidence
checkpoint, then clean only proven disposable artifacts within the owned path.
Original/helper worktrees remain preserved under the owner's existing instructions.
No shared-branch force-push, destructive blanket cleanup or unrelated process killing.

One canonical roadmap is live; older boards/plans are explicitly historical redirects.
Missing contracts or unbound test/APK receipts are audit tasks. A green isolated
branch is not production activation; merging must retain its outstanding gates.

## Current source layout

- Canonical coordinator: `C:/Temp/jb-select-reconcile-20261024`, published branch
  `lead/select-reconcile-20261024`; current code checkpoint `4b9ed850c`.
- Recorded production upstream `origin/joy-creator@f79efc378`, freshly fetched
  October26. Feature integration continues separately until its gates pass.
- Primary `C:/+Projects/Screenrecorder/FadCam` and original helper worktrees are
  preserved historical/working state, not sources to overwrite current integration.
- Domain branches keep their own reviewed checkpoints; reconcile by explicit small
  patches. See roadmap for current owners and remaining integration gates.

The audit report is a dated snapshot, not permission to delete. Recheck before action.
