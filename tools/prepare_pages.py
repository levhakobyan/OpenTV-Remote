#!/usr/bin/env python3
"""Prepare Pages from published Release assets, without rebuilding or resigning APKs."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

from prepare_downloads import APK_NAME, render_page


def choose_release(releases):
    published = [r for r in releases if not r.get("draft") and r.get("published_at")]
    if not published:
        return None
    # Includes prereleases: these are explicitly labeled development builds.
    return max(published, key=lambda r: datetime.fromisoformat(r["published_at"].replace("Z", "+00:00")))


def release_assets(release):
    selected = {}
    for name in (APK_NAME, APK_NAME + ".sha256"):
        matches = [a for a in release.get("assets", []) if a.get("name") == name]
        if len(matches) != 1:
            raise ValueError(f"Newest published release must contain exactly one {name}")
        asset = matches[0]
        if type(asset.get("id")) is not int or asset["id"] <= 0 or asset.get("state") != "uploaded":
            raise ValueError("Release asset is not ready")
        if not 0 < asset.get("size", 0) <= 200 * 1024 * 1024:
            raise ValueError("Release asset size is invalid")
        selected[name] = asset["id"]
    return selected


def verify_package(directory):
    directory = Path(directory)
    apk = directory / APK_NAME
    checksum = (directory / (APK_NAME + ".sha256")).read_text().strip()
    match = re.fullmatch(r"([a-fA-F0-9]{64})[ \t]+\*?" + re.escape(APK_NAME), checksum)
    if not match or hashlib.sha256(apk.read_bytes()).hexdigest() != match[1].lower():
        raise ValueError("Release checksum does not match the APK")
    with zipfile.ZipFile(apk) as package:
        if "AndroidManifest.xml" not in package.namelist():
            raise ValueError("Release asset is not an Android APK")
    return apk


def api(repository, endpoint, output=None):
    command = ["gh", "api", "--hostname", "github.com", f"repos/{repository}/{endpoint}"]
    if output is None:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    else:
        command += ["-H", "Accept: application/octet-stream"]
        with Path(output).open("wb") as stream:
            result = subprocess.run(command, stdout=stream, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise RuntimeError("GitHub release request failed; check repository access and Actions logs")
    return json.loads(result.stdout) if output is None else None


def prepare(repository, output):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+", repository) or repository.split("/")[1] in (".", ".."):
        raise ValueError("Use a GitHub OWNER/REPOSITORY name")
    release = choose_release(api(repository, "releases?per_page=100"))
    if release is None:
        render_page(output, label="Development preview · No release published", repository=repository)
        print("Pages prepared without an APK: no published release yet")
        return
    assets = release_assets(release)
    with tempfile.TemporaryDirectory(prefix="opentv-pages-") as folder:
        for name, asset_id in assets.items():
            api(repository, f"releases/assets/{asset_id}", Path(folder) / name)
        apk = verify_package(folder)
        label = f"Development build · {release['tag_name']} · {release['published_at'][:10]}"
        render_page(output, apk, label, repository)
    print("Pages prepared with verified published APK")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--output", type=Path, default=Path("build/pages"))
    args = parser.parse_args()
    prepare(args.repository, args.output)


if __name__ == "__main__":
    main()
