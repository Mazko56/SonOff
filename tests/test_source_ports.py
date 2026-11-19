"""Source-port regression checks for AlexxIT & Urban82 protocol differences."""
import subprocess, tempfile, unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/com/mazko/sonoffs60'
class SourcePortTests(unittest.TestCase):
    def test_sonoff_profiles_selftest(self):
        with tempfile.TemporaryDirectory() as tmp:
            cmd=['javac','-d',tmp,str(JAVA/'SonoffProfiles.java'),str(ROOT/'tests/SonoffProfilesSelfTest.java')]
            result=subprocess.run(cmd,capture_output=True,text=True,timeout=20)
            self.assertEqual(result.returncode,0,result.stderr)
            result=subprocess.run(['java','-cp',tmp,'com.mazko.sonoffs60.SonoffProfilesSelfTest'],capture_output=True,text=True,timeout=20)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn('PASS',result.stdout)
    def test_correct_sonofflan_led_command_casing(self):
        controller=(JAVA/'Controller.java').read_text()
        self.assertIn('request(d,"sledOnline",',controller)
        self.assertNotIn('request(d,"sledonline",',controller)
        self.assertIn('lastLedEchoProbe',controller)
    def test_relay_command_from_decrypted_state(self):
        controller=(JAVA/'Controller.java').read_text()
        self.assertIn('if(d.hasOutletZero()) {',controller)
        self.assertIn('else if(d.values.has("switch"))',controller)
        self.assertIn('SonoffProfiles.needsOperSide',controller)
        self.assertIn('SonoffProfiles.thSwitchExtras',controller)
        self.assertIn('data.put("mainSwitch"',controller)
        self.assertIn('if(!d.hasKey && d.encrypted)',controller)
    def test_metric_profile(self):
        device=(JAVA/'S60Device.java').read_text()
        self.assertIn('SonoffProfiles.metricMultiplier',device)
        self.assertIn('hasOutletZero()',device)
        self.assertIn('outletZeroSwitch()',device)
if __name__=='__main__': unittest.main()
