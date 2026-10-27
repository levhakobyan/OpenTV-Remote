"""Branding checks must reject visible old names without breaking upgrade identities."""
import unittest
from check_branding import obsolete_text


class BrandingTests(unittest.TestCase):
    def test_visible_brand_is_rejected(self):
        for path, value in [('downloads/index.html', 'Luma Remote'),
                            ('ios/OpenTVRemote/UI/AppsView.swift', 'About Luma'),
                            ('README.md', 'Open ios/LumaRemote.xcodeproj')]:
            self.assertTrue(obsolete_text(path, value))

    def test_application_identity_is_not_display_branding(self):
        self.assertFalse(obsolete_text('ios/OpenTVRemote/Data/SecureStore.swift', 'com.luma.remote.credentials'))
        self.assertFalse(obsolete_text('README.md', 'android/app/src/main/java/com/luma/remote/'))

    def test_key_alias_exception_is_scoped_to_its_owner(self):
        value = '"luma-vault"'
        self.assertFalse(obsolete_text('android/app/src/main/java/com/luma/remote/data/Vault.kt', value))
        self.assertTrue(obsolete_text('downloads/index.html', value))

    def test_original_evidence_name_only_allowed_in_history(self):
        value = '`/private/tmp/luma-ios-live-ime-final-composition.log`'
        self.assertFalse(obsolete_text('docs/TESTING.md', value))
        self.assertTrue(obsolete_text('README.md', value))

    def test_retired_package_cleanup_is_scoped(self):
        value = 'luma-tv-companion-debug.apk'
        self.assertFalse(obsolete_text('tools/prepare_downloads.py', value))
        self.assertTrue(obsolete_text('downloads/index.html', value))

    def test_new_brand_passes(self):
        self.assertFalse(obsolete_text('README.md', 'OpenTV-Remote / ios/OpenTVRemote.xcodeproj'))


if __name__ == '__main__':
    unittest.main()
