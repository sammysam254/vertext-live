#!/data/data/com.termux/files/usr/bin/bash
# ═══════════════════════════════════════════════════════════════
#  Vertext Live — Termux Auto-Deploy Script
#  Installs git, creates GitHub repo via API, then pushes code.
#  Run once from Termux after extracting the zip.
# ═══════════════════════════════════════════════════════════════

set -e  # stop on any error

# ── Config ──────────────────────────────────────────────────────
GH_USERNAME="sammysam254"
GH_EMAIL="sammyseth260@gmail.com"
REPO_NAME="vertext-live"
REPO_DESC="Vertext Live — Screen sharing app with 8-digit PIN"
REPO_PRIVATE="false"   # "true" to make it private
# ────────────────────────────────────────────────────────────────

echo ""
echo "═══════════════════════════════════"
echo "  VERTEXT LIVE — TERMUX DEPLOYER"
echo "═══════════════════════════════════"
echo ""

# ── Step 1: Install dependencies ────────────────────────────────
echo "[1/6] Installing git and curl..."
pkg update -y -q
pkg install -y git curl 2>/dev/null
echo "      ✓ Done"

# ── Step 2: Ask for GitHub token securely ───────────────────────
echo ""
echo "[2/6] GitHub Personal Access Token"
echo "      (go to github.com/settings/tokens → Generate new token)"
echo "      Scopes needed: repo, delete_repo"
echo ""
read -s -p "      Paste your token (hidden): " GH_TOKEN
echo ""
if [ -z "$GH_TOKEN" ]; then
  echo "      ✗ No token entered. Exiting."
  exit 1
fi
echo "      ✓ Token received"

# ── Step 3: Configure git identity ──────────────────────────────
echo ""
echo "[3/6] Configuring git..."
git config --global user.name "$GH_USERNAME"
git config --global user.email "$GH_EMAIL"
git config --global init.defaultBranch main
echo "      ✓ Git configured as $GH_USERNAME"

# ── Step 4: Create GitHub repo via API ──────────────────────────
echo ""
echo "[4/6] Creating GitHub repo '$REPO_NAME'..."

HTTP_STATUS=$(curl -s -o /tmp/gh_response.json -w "%{http_code}" \
  -X POST \
  -H "Authorization: token $GH_TOKEN" \
  -H "Accept: application/vnd.github.v3+json" \
  https://api.github.com/user/repos \
  -d "{
    \"name\": \"$REPO_NAME\",
    \"description\": \"$REPO_DESC\",
    \"private\": $REPO_PRIVATE,
    \"auto_init\": false
  }")

if [ "$HTTP_STATUS" = "201" ]; then
  echo "      ✓ Repo created: github.com/$GH_USERNAME/$REPO_NAME"
elif [ "$HTTP_STATUS" = "422" ]; then
  echo "      ⚠ Repo already exists — continuing with push"
else
  echo "      ✗ GitHub API error ($HTTP_STATUS):"
  cat /tmp/gh_response.json
  exit 1
fi

# ── Step 5: Add Render deploy hook as GitHub secret ─────────────
echo ""
echo "[5/6] GitHub Secret setup"
echo "      After deploying to Render, add your deploy hook:"
echo "      github.com/$GH_USERNAME/$REPO_NAME/settings/secrets/actions"
echo "      Name:  RENDER_DEPLOY_HOOK_URL"
echo "      Value: (your Render deploy hook URL)"
echo "      (Press Enter to continue...)"
read

# ── Step 6: Init repo and push ──────────────────────────────────
echo ""
echo "[6/6] Pushing code to GitHub..."

# Navigate to script's directory (project root)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Init git if not already
if [ ! -d ".git" ]; then
  git init
fi

# Set remote (replace if exists)
git remote remove origin 2>/dev/null || true
git remote add origin "https://$GH_TOKEN@github.com/$GH_USERNAME/$REPO_NAME.git"

git add .
git commit -m "🚀 Initial Vertext Live deployment" 2>/dev/null || \
  git commit --allow-empty -m "🚀 Update Vertext Live"

git branch -M main
git push -u origin main --force

echo ""
echo "═══════════════════════════════════════════════════════"
echo "  ✓ CODE PUSHED TO GITHUB SUCCESSFULLY!"
echo ""
echo "  Repo:    github.com/$GH_USERNAME/$REPO_NAME"
echo "  Actions: github.com/$GH_USERNAME/$REPO_NAME/actions"
echo ""
echo "  NEXT STEPS:"
echo "  1. GitHub Actions is now building your APK (~4 min)"
echo "  2. Deploy backend on render.com → New → Web Service"
echo "     Root Dir: backend   |   Runtime: Docker"
echo "     Env vars: LIVEKIT_API_KEY, LIVEKIT_API_SECRET, LIVEKIT_URL"
echo "  3. Copy Render URL → update MainActivity.kt BACKEND_URL"
echo "  4. Add Render deploy hook → GitHub Secrets"
echo "  5. git add . && git commit -m 'update url' && git push"
echo "  6. Download APK from Actions → Artifacts tab"
echo "  7. Web viewer is served at your Render URL (public)"
echo "═══════════════════════════════════════════════════════"
echo ""
