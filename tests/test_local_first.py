import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
HTML = (ROOT / 'app/src/main/assets/index.html').read_text('utf-8')
CONTROLLER = (ROOT / 'app/src/main/java/com/mazko/sonoffs60/Controller.java').read_text('utf-8')
PROBE = (ROOT / 'app/src/main/java/com/mazko/sonoffs60/LocalPairingProbe.java').read_text('utf-8')

class LocalFirstTests(unittest.TestCase):
    def test_manual_key_field_removed_from_user_panel(self):
        self.assertNotIn('placeholder="Ключ доступу"', HTML)
        self.assertNotIn('async function saveKey(', HTML)
        self.assertIn('onclick="claimLocal()"', HTML)

    def test_ap_probe_read_only_and_wifi_scoped(self):
        self.assertIn('TRANSPORT_WIFI', PROBE)
        self.assertIn('http://10.10.7.1/device', PROBE)
        self.assertIn('setRequestMethod("GET")', PROBE)
        self.assertNotIn('setRequestMethod("POST")', PROBE)
        self.assertNotIn('/ap"', PROBE)

    def test_keystore_and_errors_still_present(self):
        self.assertIn('keys.put(result.id,result.key)', CONTROLLER)
        self.assertIn('LOCAL.PAIR.UNAVAILABLE', CONTROLLER)

if __name__ == '__main__': unittest.main()
