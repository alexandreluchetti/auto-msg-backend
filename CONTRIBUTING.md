# Contributing

## Branches

| Branch | Purpose |
|---|---|
| `main` | Production. Every release is tagged `vX.Y.Z` (version from the root `pom.xml`). |
| `dev` | Integration branch, created from `main`. Changes arrive only through pull requests. |
| `feat/<name>`, `fix/<name>`, `refactor/<name>`, `chore/<name>` | Short-lived work branches, created from `dev`. |

Work branch names are lowercase kebab-case in English, e.g. `feat/inbound-event-worker`,
`fix/echo-window-check`, `chore/project-rules`.

| Prefix | Use for |
|---|---|
| `feat/` | New behavior visible to customers, tenants, attendants or API clients |
| `fix/` | Bug fixes |
| `refactor/` | Code changes without behavior changes |
| `chore/` | Build, tooling, infrastructure, dependencies, documentation |

## Flow

1. Update `dev` and branch from it:

   ```bash
   git switch dev
   git pull
   git switch -c feat/<name>
   ```

2. Commit (messages in English, imperative mood) and run the checks (unit and integration
   tests; Docker must be running):

   ```bash
   ./mvnw verify
   ```

3. Push and open a pull request into `dev`:

   ```bash
   git push -u origin feat/<name>
   gh pr create --base dev --title "<title>" --body "<description>"
   ```

4. After approval, merge the pull request and delete the work branch (on GitHub and locally):

   ```bash
   git switch dev
   git pull
   git branch -d feat/<name>
   ```

5. Release: raise `<version>` in the root `pom.xml` and the modules (through a work branch into
   `dev`), then open a pull request from `dev` into `main`. After it is merged, tag `main`:

   ```bash
   git switch main
   git pull
   git tag -a vX.Y.Z -m "Version X.Y.Z"
   git push origin vX.Y.Z
   ```

6. `dev` is synced with `main` automatically: after every merge into `main`, the
   `sync-dev-with-main` workflow (`.github/workflows/sync-dev-with-main.yml`) opens a pull
   request from `main` into `dev` and merges it (merge commit; no content changes). Afterwards
   `git log origin/dev..origin/main` must be empty; then update your local `dev`:

   ```bash
   git switch dev
   git pull
   ```

   If the workflow fails, sync by hand:

   ```bash
   gh pr create --base dev --head main --title "Sync dev with main" --body "Sync after release."
   gh pr merge --merge <number>
   ```

Never commit directly to `dev` or `main`. `main` only accepts pull requests from `dev`: the
`source-branch-is-dev` check (`.github/workflows/main-only-from-dev.yml`) fails for any other
source branch. The server only runs `main` (or a release tag), never `dev` or a work branch.

## Repository settings (GitHub)

Branch protection needs a public repository or GitHub Pro.

- Ruleset `protected-branches`, active, targets `main` (default branch) and `dev` (pattern
  `dev`): restrict deletions, block force pushes, require a pull request before merging with
  0 approvals (nobody can approve their own pull request).
- Ruleset `main-only-from-dev`, active, target `main` (default branch): require the status
  check `source-branch-is-dev`. The check can be selected only after it has run once, in a
  `dev` → `main` pull request.
- "Automatically delete head branches" on, so merged work branches are removed from GitHub.
- Settings → Actions → General → Workflow permissions: "Allow GitHub Actions to create and
  approve pull requests" on, required by `sync-dev-with-main`.
