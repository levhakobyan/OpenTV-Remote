# OpenTV-Remote downloads

Public site: **https://levhakobyan.github.io/OpenTV-Remote/**. The project-naming cleanup is published as [v0.9.1](https://github.com/levhakobyan/OpenTV-Remote/releases/tag/v0.9.1); its APK and matching checksum are attached to that release.

`index.html` is the English template shared by local APK sharing and GitHub Pages. The page has no external scripts, fonts, analytics or account requirement. Its three-image remote gallery uses bundled PNG screenshots, full-size links and a touch-scroll layout on narrow screens. It describes the free/open-source MIT licensing and publishes the license beside the downloads. It includes a mobile-friendly iPhone source-install guide covering Xcode signing, Developer Mode, developer trust, free-profile renewal and local-network setup; the published version links directly to the repository source ZIP. Generated files live under ignored `build/` directories; do not publish the template with unresolved placeholders.

## Local APK sharing

Build the Android phone APK, then run from the workspace root:

```sh
python3 tools/prepare_downloads.py
```

`build/downloads/` contains `index.html`, `.nojekyll`, `LICENSE.txt`, `opentv-remote-debug.apk` and its `.sha256` file. Open the generated HTML locally to preview it. Preparation never starts a server. For a temporary link, run `./scripts/share-apk.sh` yourself; Ctrl-C stops its server/tunnel. Restart sharing after rebuilding, because each sharing session serves its initial copied APK.

## GitHub Pages: one-time setup

1. Create an empty GitHub repository, for example **OpenTV-Remote**, and push this source to `main`. Do not commit APKs, local configuration, signing keys or build directories.
2. In the repository, open **Settings → Pages → Build and deployment → Source → GitHub Actions**. GitHub Pages must be available for the repository/account.
3. In **Settings → Environments → github-pages**, allow deployments from `main` and your release tags (for example tags matching `v*`). Release-triggered Actions run on tag refs even though the workflow checks out trusted `main` source. If the environment does not exist yet, run the workflow once to create it, then adjust these rules and rerun.
4. The **Publish download page** workflow publishes the page on relevant pushes to `main`, published/edited/deleted releases, and manual runs from Actions. Before the first release, the page says the Android download is coming soon and has no broken download link.
5. After the first successful deployment, the workflow's `github-pages` environment shows the real site URL. A normal project site is `https://OWNER.github.io/REPOSITORY/`. No repository-specific URL is hardcoded in the HTML; APK/checksum links work under project subdirectories.

A public repository makes both source and download page straightforward to access. Review visibility before publishing: the workflow copies published Release APKs into the Pages artifact. A private repository must not be treated as making its Pages downloads private automatically.

## Publish an Android download

1. Build/test the Android APK locally with the same signing identity used for previous installations.
2. Run `python3 tools/prepare_downloads.py` again.
3. Open **Releases → Draft a new release** in GitHub. Choose a version tag, for example `v0.9.1`, and describe this as a development preview. A prerelease is supported.
4. Upload **both** files from `build/downloads/` before publishing:
   - `opentv-remote-debug.apk`
   - `opentv-remote-debug.apk.sha256`
5. Publish the release. **Publish download page** downloads the newest published release's assets, verifies SHA-256 and APK structure, then deploys the static page. The rendered page shows the release tag/date and APK size/checksum. No browser-side GitHub API request is needed.

New releases use the same process. If assets are uploaded after publication, manually run **Actions → Publish download page → Run workflow**, or edit the release after both uploads. Replacing an asset without republishing does not itself trigger this workflow.

The newest published release (including prereleases) is selected by publication time from the most recent100releases. Drafts are ignored. A release missing either exact asset, an invalid checksum, an unready upload, or a GitHub request failure fails deployment rather than silently distributing an older package. Keep unpublished work as a draft. Deleting the latest release selects the next published one; deleting all releases restores the explicit pending page.

### Signing and updates

Pages does **not** build or resign the APK. Fresh GitHub runners would otherwise generate different debug keys, preventing updates over existing installs. The current package is debug-signed and is intended for development testing. Keep its local signing key backed up privately if you need repeatable updates; never add it to Git. A later production signing change needs a deliberate upgrade/distribution plan. SHA-256 checks detect inconsistent files, not independent publisher identity.

The Android package ID and iOS bundle ID remain unchanged after the display-name rebrand, preserving the existing installation's pairing/layout namespaces. The iOS project/scheme is `ios/OpenTVRemote.xcodeproj` / `OpenTVRemote`. The page offers no signed IPA or TestFlight link; iPhone users build/sign through Xcode.

## Verification

```sh
python3 -m unittest discover -s tools -p test_downloads.py -v
bash -n scripts/share-apk.sh
```

Tests run offline against synthetic assets: ready/pending output, relative links, escaped labels, exact checksum/name validation, malformed packages, missing/duplicate assets, newest release selection and fail-closed API behavior. Live GitHub deployment still requires a pushed repository, enabled Pages and valid GitHub access.

References: [GitHub custom Pages workflows](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages), [publishing source](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site), [GitHub CLI API](https://cli.github.com/manual/gh_api).
