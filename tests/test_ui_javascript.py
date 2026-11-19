"""Guard the embedded Android WebView UI against malformed JS and stale callback tests.

This intentionally checks the actual bundled HTML, not a test-only copy.
"""
from pathlib import Path
import re
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
HTML = (ROOT / "app/src/main/assets/index.html").read_text(encoding="utf-8")


class UiJavascript(unittest.TestCase):
    def test_embedded_script_has_valid_javascript(self):
        self.assertEqual(HTML.count("<script>"), 1)
        self.assertEqual(HTML.count("</script>"), 1)
        javascript = HTML.split("<script>", 1)[1].split("</script>", 1)[0]
        node = shutil.which("node")
        if not node:
            self.skipTest("node not present; GitHub Actions installs Node.js")
        result = subprocess.run(
            [node, "--check", "-"], input=javascript, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_buttons_use_existing_callbacks_not_removed_claim_local(self):
        self.assertNotIn('onclick="claimLocal()"', HTML)
        callbacks = set(re.findall(r'(?:onclick|onchange)="([a-zA-Z]\w*)\(', HTML))
        # Template-generated buttons are constructed inside the JavaScript source.
        callbacks.update(re.findall(r'(?:onclick|onchange)=\\?"([a-zA-Z]\w*)\(', HTML))
        for name in callbacks:
            self.assertRegex(HTML, rf'(?:async\s+)?function\s+{re.escape(name)}\s*\(', name)
        self.assertIn('onclick="provisionDevice()"', HTML)
        self.assertIn('const ssid=el(\'homeSsid\').value,password=el(\'homePass\').value;', HTML)

    def test_legacy_test_expectation_is_not_reintroduced(self):
        # An obsolete historical UI test required a removed "claimLocal" button.
        # The current product uses automatic LAN discovery and optional AP provisioning.
        self.assertIn('async function provisionDevice()', HTML)
        self.assertNotIn('function claimLocal(', HTML)


if __name__ == "__main__":
    unittest.main()
