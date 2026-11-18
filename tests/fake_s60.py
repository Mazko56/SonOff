#!/usr/bin/env python3
"""Test fixture: an emulated UIID 190 on a LOCAL LAB NETWORK, never a real SONOFF.
Dependencies: python -m pip install cryptography
Usage: python tests/fake_s60.py --port 8081 --devicekey my-test-key --advertise
This listens on 127.0.0.1 by default, so cannot be discovered from a phone.
Use --host 0.0.0.0 --advertise for lab LAN only (no authentication outside devicekey).
"""
import argparse
import base64
import hashlib
import json
import os
import socket
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives import padding

DEVICE_ID = '1000abcdef'
STATE = {'switches': [{'outlet': 0, 'switch': 'off'}], 'power': 0, 'current': 0, 'voltage': 23124, 'dayKwh': 17, 'monthKwh': 112, 'rssi': -43}


def encrypt_obj(plain: dict, secret: str):
    iv = os.urandom(16)
    key = hashlib.md5(secret.encode()).digest()
    pad = padding.PKCS7(128).padder()
    raw = json.dumps(plain, separators=(',', ':')).encode()
    enc = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
    data = enc.update(pad.update(raw) + pad.finalize()) + enc.finalize()
    return {'encrypt': True, 'data': base64.b64encode(data).decode(), 'iv': base64.b64encode(iv).decode()}


def decrypt_obj(cipher_b64: str, iv_b64: str, secret: str):
    key = hashlib.md5(secret.encode()).digest()
    dec = Cipher(algorithms.AES(key), modes.CBC(base64.b64decode(iv_b64))).decryptor()
    padded = dec.update(base64.b64decode(cipher_b64)) + dec.finalize()
    unpad = padding.PKCS7(128).unpadder()
    return json.loads((unpad.update(padded) + unpad.finalize()).decode())


def dnsname(value):
    return b''.join(bytes([len(t)]) + t.encode('ascii') for t in value.split('.')) + b'\x00'


def rr(name, rtype, rdata):
    return dnsname(name) + struct.pack('!HHIH', rtype, 1, 30, len(rdata)) + rdata


def mdns_advertisement(port: int, missing_a=False, advertised_ip='127.0.0.1', secret='lab-test-key-only'):
    service = '_ewelink._tcp.local'
    instance = f'eWeLink_{DEVICE_ID}.{service}'
    host = f'eWeLink_{DEVICE_ID}.local'
    encrypted = encrypt_obj(STATE, secret)
    data = encrypted['data']
    chunks = [data[i:i+100] for i in range(0, len(data), 100)]
    txt_items = [f'id={DEVICE_ID}', 'type=plug', 'encrypt=true', 'txtvers=1',
                 f'seq={int(time.time())}', 'iv=' + encrypted['iv']]
    txt_items.extend(f'data{i+1}={segment}' for i, segment in enumerate(chunks[:4]))
    txt = b''.join(bytes([len(s)]) + s.encode('ascii') for s in txt_items)
    records = [rr(service, 12, dnsname(instance)),
               rr(instance, 33, struct.pack('!HHH', 0, 0, port) + dnsname(host)),
               rr(instance, 16, txt)]
    if not missing_a:
        records.append(rr(host, 1, socket.inet_aton(advertised_ip)))
    header = struct.pack('!HHHHHH', 0, 0x8400, 0, len(records), 0, 0)
    return header + b''.join(records)


def announce(port, missing_a, advertised_ip, secret):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
    try:
        while True:
            sock.sendto(mdns_advertisement(port, missing_a, advertised_ip, secret), ('224.0.0.251', 5353))
            time.sleep(5)
    except (OSError, KeyboardInterrupt):
        pass


def make_handler(secret):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            print('[simulator HTTP]', format % args)

        def do_POST(self):
            try:
                if not self.path.startswith('/zeroconf/'):
                    self.send_error(404)
                    return
                n = min(int(self.headers.get('Content-Length', '0')), 65536)
                request = json.loads(self.rfile.read(n))
                if request.get('deviceid') != DEVICE_ID:
                    answer = {'error': 404}
                elif not request.get('encrypt'):
                    answer = {'error': 401}
                else:
                    data = decrypt_obj(request['data'], request['iv'], secret)
                    cmd = self.path.split('/')[-1]
                    if cmd == 'switches':
                        switch = data.get('switches', [{}])[0].get('switch')
                        if switch not in ('on', 'off'):
                            answer = {'error': 400}
                        else:
                            STATE['switches'][0]['switch'] = switch
                            STATE['power'] = 724 if switch == 'on' else 0
                            STATE['current'] = 5 if switch == 'on' else 0
                            answer = {'error': 0, 'seq': int(time.time()), **encrypt_obj(STATE, secret)}
                    elif cmd == 'getState':
                        answer = {'error': 0, 'seq': int(time.time()), **encrypt_obj(STATE, secret)}
                    else:
                        answer = {'error': 404}
                raw = json.dumps(answer, separators=(',', ':')).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)
            except Exception as e:
                print('[simulator ERROR]', type(e).__name__)
                self.send_error(400, 'Invalid encrypted protocol')
    return Handler


def main():
    p = argparse.ArgumentParser(description='Local S60 encrypted test fixture, NOT a real device')
    p.add_argument('--host', default='127.0.0.1')
    p.add_argument('--port', type=int, default=8081)
    p.add_argument('--devicekey', default='lab-test-key-only')
    p.add_argument('--advertise', action='store_true')
    p.add_argument('--advertise-ip', default='127.0.0.1', help='Use the LAN address of the desktop, not loopback, for Android tests')
    p.add_argument('--missing-a', action='store_true')
    opt = p.parse_args()
    if opt.advertise:
        threading.Thread(target=announce, args=(opt.port, opt.missing_a, opt.advertise_ip, opt.devicekey), daemon=True).start()
    server = ThreadingHTTPServer((opt.host, opt.port), make_handler(opt.devicekey))
    print(f'LAB SIMULATOR: http://{opt.host}:{opt.port}, deviceid={DEVICE_ID}, mDNS={opt.advertise}, missing-A={opt.missing_a}')
    print('Do not use this fixture to claim real S60 compatibility.')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()
