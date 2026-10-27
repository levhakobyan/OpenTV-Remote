#!/usr/bin/env python3
"""Prepare static APK downloads; never start a server or change APK signing."""
import argparse
from datetime import datetime, timezone
from html import escape
import hashlib
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
APK_NAME = "opentv-remote-debug.apk"


def render_page(output, apk=None, label="Development build", repository=None):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    # Remove only known obsolete packages, preserving unrelated output files.
    for name in ("luma-tv-companion-debug.apk", "luma-remote-debug.apk"):
        (output / name).unlink(missing_ok=True)
        (output / (name + ".sha256")).unlink(missing_ok=True)
    if apk is None:
        (output / APK_NAME).unlink(missing_ok=True)
        (output / (APK_NAME + ".sha256")).unlink(missing_ok=True)
        panel = '''<section class="download" aria-labelledby="android-title">
<h2 id="android-title">Android download coming soon</h2>
<p>A downloadable build has not been published yet. Check back after the first release.</p>
<p>Android 9+ · Android phone app</p></section>'''
    else:
        apk = Path(apk)
        target = output / APK_NAME
        if apk.resolve() != target.resolve():
            shutil.copyfile(apk, target)
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        (output / (APK_NAME + ".sha256")).write_text(f"{digest}  {APK_NAME}\n")
        panel = f'''<section class="download" aria-labelledby="android-title">
<h2 id="android-title">Android phone</h2>
<div class="meta">Android 9+ · {target.stat().st_size / 1048576:.1f} MiB</div>
<p>Install on your phone and connect to your TV. This is a development build.</p>
<a class="button" href="{APK_NAME}" download>Download Android APK</a>
<details><summary>Verify file · SHA-256</summary><code>{digest}</code>
<a href="{APK_NAME}.sha256" download>Download checksum</a></details></section>'''
    page = (ROOT / "downloads/index.html").read_text(encoding="utf-8")
    source = ""
    ios_source = "<p>Use the project folder on your Mac, then follow the steps below.</p>"
    if repository:
        source = f'<p><a href="https://github.com/{escape(repository, quote=True)}">Source code &amp; releases</a></p>'
        ios_source = f'<p><a href="https://github.com/{escape(repository, quote=True)}/archive/refs/heads/main.zip">Download project source for Xcode</a></p>'
    for key, value in {"DOWNLOAD_PANEL": panel, "BUILD_LABEL": escape(label), "SOURCE_LINK": source, "IOS_SOURCE_LINK": ios_source}.items():
        page = page.replace("{{" + key + "}}", value)
    if "{{" in page:
        raise ValueError("Unresolved download page placeholder")
    (output / "index.html").write_text(page, encoding="utf-8")
    (output / ".nojekyll").touch()
    shutil.copyfile(ROOT / "LICENSE", output / "LICENSE.txt")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/downloads")
    parser.add_argument("--repository", default="levhakobyan/OpenTV-Remote", help="GitHub OWNER/REPOSITORY for source links")
    args = parser.parse_args()
    apk = ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"
    if not apk.is_file():
        parser.error("Build the Android phone debug APK first.")
    date = datetime.fromtimestamp(apk.stat().st_mtime, timezone.utc).strftime("%Y-%m-%d UTC")
    render_page(args.output, apk, f"Development build · {date}", args.repository)
    print(f"Download page prepared: {args.output.resolve() / 'index.html'}")


if __name__ == "__main__":
    main()
