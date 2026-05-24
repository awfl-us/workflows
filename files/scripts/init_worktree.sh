# SESSION_ID='evals-experiments-SweBenchLite'
# REPO_URL='astropy/astropy'
# BRANCH='d16bfe05a744909de4b27f5875fe0d4ed41ce607'

set -euo pipefail

ROOT_DIR="$(pwd)"

repo_name_from_url() {
  local url="$1"
  local name
  name="$(basename "$url")"
  name="${name%.git}"
  echo "$name"
}

sanitize() {
  printf '%s' "$1" | sed 's#[/: @]#-#g; s#[^A-Za-z0-9._-]#-#g'
}

normalize_repo_url() {
  case "$1" in
    http://*|https://*|git@*) echo "$1" ;;
    */*) echo "https://github.com/$1.git" ;;
    *) echo "$1" ;;
  esac
}

REPO_URL_NORM="$(normalize_repo_url "$REPO_URL")"
REPO_NAME="$(repo_name_from_url "$REPO_URL_NORM")"
SAFE_SESSION="$(sanitize "$SESSION_ID")"
SAFE_BRANCH="$(sanitize "$BRANCH")"

BASE_DIR="${ROOT_DIR}/../worktrees/${REPO_NAME}"
CANONICAL_REPO="${BASE_DIR}/repo"
WORKTREE_ID="${SAFE_SESSION}-${SAFE_BRANCH}"
WORKTREE_DIR="${BASE_DIR}/${WORKTREE_ID}"

mkdir -p "$BASE_DIR"

if [ ! -d "$CANONICAL_REPO/.git" ]; then
  git clone "$REPO_URL_NORM" "$CANONICAL_REPO" >/dev/null 2>&1
fi

cd "$CANONICAL_REPO"

git fetch origin --prune >/dev/null 2>&1

if [ -d "$WORKTREE_DIR" ]; then
  printf '%s\n' "$WORKTREE_DIR"
  exit 0
fi

if git show-ref --verify --quiet "refs/heads/$BRANCH"; then
  BASE_REF="$BRANCH"
elif git show-ref --verify --quiet "refs/remotes/origin/$BRANCH"; then
  BASE_REF="origin/$BRANCH"
else
  BASE_REF="$BRANCH"
fi

LOCAL_BRANCH="awfl-${SAFE_SESSION}-${SAFE_BRANCH}"

if git show-ref --verify --quiet "refs/heads/$LOCAL_BRANCH"; then
  git worktree add "$WORKTREE_DIR" "$LOCAL_BRANCH" >/dev/null 2>&1
else
  git worktree add -b "$LOCAL_BRANCH" "$WORKTREE_DIR" "$BASE_REF" >/dev/null 2>&1
fi

printf '%s\n' "$WORKTREE_DIR"