"""Prevent a stale legacy UI assertion from silently breaking the APK build."""
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from sync_ci_tests import expected_tests, reconcile  # noqa: E402


class TestReleaseInventory(unittest.TestCase):
    def test_all_manifest_tests_present_in_release(self):
        names = expected_tests()
        self.assertIn('test_release_inventory.py', names)
        self.assertIn('test_ui_javascript.py', names)
        for name in names:
            self.assertTrue((ROOT / 'tests' / name).is_file(), name)

    def test_obsolete_tests_preserved_and_excluded(self):
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            tests = tmp / 'tests'
            tests.mkdir()
            (tests / 'test_current.py').write_text('# maintained\n', encoding='utf-8')
            old = tests / 'test_old_claim_local.py'
            old.write_text('assert "onclick=\\"claimLocal()\\"" in ""\n', encoding='utf-8')
            quarantine = tmp / 'backup'
            changed = reconcile(tests, {'test_current.py'}, quarantine)
            self.assertEqual(changed, ['test_old_claim_local.py'])
            self.assertTrue((quarantine / 'test_old_claim_local.py').is_file())
            self.assertFalse(old.exists())
            self.assertTrue((tests / 'test_current.py').is_file())

    def test_stale_tests_fail_without_archive_target(self):
        with tempfile.TemporaryDirectory() as directory:
            tests = Path(directory)
            (tests / 'test_current.py').write_text('', encoding='utf-8')
            (tests / 'test_legacy.py').write_text('', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'older ZIPs'):
                reconcile(tests, {'test_current.py'}, None)


if __name__ == '__main__':
    unittest.main()
