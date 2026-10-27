"""Offline regression checks for local downloads and GitHub Pages releases."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from prepare_downloads import APK_NAME, render_page
from prepare_pages import choose_release, prepare, release_assets, verify_package


def release(tag="v0.9.0", date="2026-10-28T10:00:00Z", draft=False):
    return {"tag_name": tag, "published_at": date, "draft": draft,
            "assets": [{"name": name, "id": i + 1, "size": 100, "state": "uploaded"}
                       for i, name in enumerate((APK_NAME, APK_NAME + ".sha256"))]}


class DownloadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / APK_NAME
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"synthetic manifest")
        self.digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.checksum = self.root / (APK_NAME + ".sha256")
        self.checksum.write_text(f"{self.digest}  {APK_NAME}\n")
        self.output = self.root / "site"

    def test_local_download_relative_links_and_metadata(self):
        render_page(self.output, self.apk, "Development build")
        page = (self.output / "index.html").read_text()
        self.assertIn(f'href="{APK_NAME}"', page)
        self.assertIn(f'href="{APK_NAME}.sha256"', page)
        self.assertIn(self.digest, page)
        self.assertIn("OpenTV-Remote", page)
        self.assertNotIn("{{", page)
        self.assertEqual((self.output / APK_NAME).read_bytes(), self.apk.read_bytes())
        self.assertTrue((self.output / ".nojekyll").exists())
        self.assertIn("MIT License", (self.output / "LICENSE.txt").read_text())
        self.assertIn('href="LICENSE.txt"', page)

    def test_pending_page_has_no_broken_download_and_removes_obsolete_apk(self):
        render_page(self.output, self.apk)
        (self.output / "notes.txt").write_text("keep")
        (self.output / "luma-tv-companion-debug.apk").touch()
        render_page(self.output)
        page = (self.output / "index.html").read_text()
        self.assertIn("download coming soon", page)
        self.assertNotIn('href="' + APK_NAME, page)
        self.assertFalse((self.output / APK_NAME).exists())
        self.assertFalse((self.output / "luma-tv-companion-debug.apk").exists())
        self.assertTrue((self.output / "notes.txt").exists())

    def test_pending_page_keeps_real_iphone_source_and_setup_links(self):
        render_page(self.output, repository="owner/project")
        page = (self.output / "index.html").read_text()
        self.assertIn('href="https://github.com/owner/project/archive/refs/heads/main.zip"', page)
        self.assertIn('href="#iphone-guide"', page)
        self.assertIn('id="iphone-guide"', page)
        self.assertIn("ios/LumaRemote.xcodeproj", page)
        self.assertNotIn("{{", page)
        self.assertNotIn('href="' + APK_NAME, page)
        self.assertNotIn('.ipa"', page)

    def test_offline_iphone_guide_has_no_unresolved_repository_link(self):
        render_page(self.output, self.apk)
        page = (self.output / "index.html").read_text()
        self.assertIn("Use the project folder on your Mac", page)
        self.assertNotIn("github.com/None", page)
        self.assertNotIn("{{IOS_SOURCE_LINK}}", page)

    def test_gallery_images_and_fullsize_links_are_published_locally(self):
        render_page(self.output, self.apk)
        page = (self.output / "index.html").read_text()
        for name in ["android-remote.png", "sky-remote.png", "iphone-remote.png"]:
            image = self.output / "assets" / name
            self.assertTrue(image.read_bytes().startswith(b"\x89PNG"))
            self.assertIn(f'src="assets/{name}"', page)
            self.assertIn(f'href="assets/{name}"', page)

    def test_release_label_is_html_escaped(self):
        render_page(self.output, self.apk, '<script>alert("bad")</script>')
        page = (self.output / "index.html").read_text()
        self.assertNotIn("<script>", page)
        self.assertIn("&lt;script&gt;", page)

    def test_valid_package_and_checksum(self):
        self.assertEqual(verify_package(self.root), self.apk)

    def test_bad_checksum_rejected(self):
        self.checksum.write_text("0" * 64 + f"  {APK_NAME}\n")
        with self.assertRaises(ValueError): verify_package(self.root)

    def test_wrong_checksum_filename_rejected(self):
        self.checksum.write_text(self.digest + "  other.apk\n")
        with self.assertRaises(ValueError): verify_package(self.root)

    def test_non_apk_zip_rejected(self):
        with zipfile.ZipFile(self.apk, "w") as archive: archive.writestr("notes.txt", "wrong")
        self.checksum.write_text(hashlib.sha256(self.apk.read_bytes()).hexdigest() + f"  {APK_NAME}\n")
        with self.assertRaises(ValueError): verify_package(self.root)

    def test_latest_published_including_prereleases_not_list_order(self):
        older = release(date="2026-10-27T10:00:00Z")
        newest = release("v1.0.0-beta")
        newest["prerelease"] = True
        self.assertEqual(choose_release([newest, older, release(draft=True)]), newest)
        self.assertIsNone(choose_release([release(draft=True)]))

    def test_missing_or_duplicate_assets_rejected(self):
        candidate = release(); candidate["assets"].pop()
        with self.assertRaises(ValueError): release_assets(candidate)
        candidate = release(); candidate["assets"].append(candidate["assets"][0])
        with self.assertRaises(ValueError): release_assets(candidate)

    def test_invalid_asset_id_and_upload_state_rejected(self):
        for key, value in [("id", "../../oops"), ("state", "new"), ("size", 0)]:
            candidate = release(); candidate["assets"][0][key] = value
            with self.assertRaises(ValueError): release_assets(candidate)

    def test_prepare_no_release(self):
        with patch("prepare_pages.api", return_value=[]): prepare("owner/project", self.output)
        self.assertIn("No release published", (self.output / "index.html").read_text())

    def test_prepare_downloads_exact_asset_ids_and_checksums(self):
        requests = []
        def fake_api(repo, endpoint, output=None):
            requests.append(endpoint)
            if output is None: return [release()]
            Path(output).write_bytes(self.apk.read_bytes() if endpoint.endswith("/1") else self.checksum.read_bytes())
        with patch("prepare_pages.api", side_effect=fake_api): prepare("owner/project", self.output)
        self.assertEqual(requests, ["releases?per_page=100", "releases/assets/1", "releases/assets/2"])
        self.assertEqual(verify_package(self.output).read_bytes(), self.apk.read_bytes())

    def test_incomplete_new_release_does_not_silently_publish_old_build(self):
        newest = release(); newest["assets"] = []
        old = release(date="2026-10-26T10:00:00Z")
        with patch("prepare_pages.api", return_value=[old, newest]), self.assertRaises(ValueError):
            prepare("owner/project", self.output)
        self.assertFalse((self.output / "index.html").exists())

    def test_bad_repository_is_rejected_before_api_request(self):
        for repo in ("owner/../secret", "owner/..", "owner/project?other", "https://github.com/o/r"):
            with patch("prepare_pages.api") as api_mock, self.assertRaises(ValueError):
                prepare(repo, self.output)
            api_mock.assert_not_called()

    def test_api_failure_is_not_a_no_release_page(self):
        with patch("prepare_pages.api", side_effect=RuntimeError("unavailable")), self.assertRaises(RuntimeError):
            prepare("owner/project", self.output)
        self.assertFalse((self.output / "index.html").exists())


if __name__ == "__main__": unittest.main()
