"""HTTP-only fixture for the historical Sonoff GET /device pairing response.

This does not create a Wi-Fi hotspot, assign 10.10.7.1, or emulate real S60TPF firmware.
Run only for development of the parsing and safety checks.
"""
import argparse, json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def make_handler(deviceid="1000abcdef", apikey="lab-test-key-only", mode="success"):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/device" and mode == "success":
                data={"deviceid":deviceid,"apikey":apikey}
                raw=json.dumps(data,separators=(",", ":")).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length",str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)
            elif self.path == "/device" and mode == "no-key":
                raw=json.dumps({"deviceid":deviceid}).encode()
                self.send_response(200);self.send_header("Content-Length",str(len(raw)));self.end_headers();self.wfile.write(raw)
            else:
                self.send_response(404);self.send_header("Content-Length","0");self.end_headers()
        def log_message(self, fmt, *args):
            print("[lab legacy AP] " + fmt % args)
    return Handler

if __name__ == "__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--host",default="127.0.0.1")
    p.add_argument("--port",type=int,default=8092)
    p.add_argument("--deviceid",default="1000abcdef")
    p.add_argument("--apikey",default="lab-test-key-only")
    p.add_argument("--mode",choices=["success","no-key","missing"],default="success")
    a=p.parse_args()
    print("Lab AP HTTP only: http://%s:%s/device; no Wi-Fi hotspot is created"%(a.host,a.port))
    ThreadingHTTPServer((a.host,a.port),make_handler(a.deviceid,a.apikey,a.mode)).serve_forever()
