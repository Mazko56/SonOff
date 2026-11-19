"""Regression for independent WSS socket, dispatch admission and versioned routing.
No hardware/TLS compatibility claim follows from these host JVM tests.
"""
import pathlib
import subprocess
import tempfile
import unittest
ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA = ROOT/'app/src/main/java/com/mazko/sonoffs60'

class LegacyCompatibilityTests(unittest.TestCase):
    def test_probe_disallows_public_and_local_addresses(self):
        import sys
        sys.path.insert(0,str(ROOT/'tests'))
        from probe_private_server import local_ip
        self.assertEqual(local_ip('192.168.0.192'),'192.168.0.192')
        self.assertEqual(local_ip('10.1.2.3'),'10.1.2.3')
        with self.assertRaises(ValueError):local_ip('8.8.8.8')
        with self.assertRaises(ValueError):local_ip('127.0.0.1')
        with self.assertRaises(ValueError):local_ip('example.com')

    def test_dispatch_admission_single_use_and_expiry(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=pathlib.Path(tmp)
            (base/'com/mazko/sonoffs60').mkdir(parents=True)
            (base/'com/mazko/sonoffs60/LanDiagnostics.java').write_text(
                'package com.mazko.sonoffs60;class LanDiagnostics{static boolean privateIpv4(String s){return true;}}')
            (base/'org/json').mkdir(parents=True)
            (base/'org/json/JSONObject.java').write_text('package org.json; public class JSONObject{public JSONObject put(String k,Object o){return this;}public String optString(String k,String d){return d;}public String optString(String k){return "";}public int optInt(String k,int d){return d;}public JSONArray optJSONArray(String k){return null;}}')
            (base/'org/json/JSONArray.java').write_text('package org.json; public class JSONArray{public JSONArray put(Object v){return this;}public int length(){return 0;}public JSONObject optJSONObject(int i){return null;}}')
            source=[str(JAVA/'LegacyCloudProtocol.java'),str(JAVA/'LegacyDispatchAdmission.java'),
                    str(ROOT/'tests/LegacyDispatchAdmissionSelfTest.java'),
                    str(base/'com/mazko/sonoffs60/LanDiagnostics.java'),
                    str(base/'org/json/JSONObject.java'),str(base/'org/json/JSONArray.java')]
            result=subprocess.run(['javac','-d',tmp,*source],capture_output=True,text=True,timeout=25)
            self.assertEqual(result.returncode,0,result.stderr)
            result=subprocess.run(['java','-cp',tmp,'com.mazko.sonoffs60.LegacyDispatchAdmissionSelfTest'],
                                  capture_output=True,text=True,timeout=15)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn('PASS DISPATCH ADMISSION',result.stdout)
    def test_https_and_wss_separate_and_ap_points_at_https(self):
        s=(JAVA/'LegacyPrivateServer.java').read_text()
        self.assertIn('static final int PORT=1081;',s)
        self.assertIn('static final int WEBSOCKET_PORT=8090;',s)
        self.assertIn('LegacyCloudProtocol.dispatchReply(bindIp,WEBSOCKET_PORT)',s)
        self.assertIn('dispatchAdmission.authorizedDispatch(',s)
        self.assertIn('dispatchAdmission.consume(',s)
        self.assertIn('LegacyCloudProtocol.isWebSocketPath(path)',s)
        self.assertIn('!wireKey.isEmpty()',s)
        p=(JAVA/'LegacyApProvision.java').read_text()
        self.assertIn('http://10.10.7.1/ap',p)
if __name__=='__main__':unittest.main()
