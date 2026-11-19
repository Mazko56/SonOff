#!/usr/bin/env python3
"""Read-only network probe for APK's private HTTPS dispatch/WSS ports.
Does NOT log in, onboard, send commands, or read device keys.
Self-signed TLS is accepted ONLY for this diagnostic and is NOT proof of
Sonoff factory TLS compatibility. Run from a PC on the same trusted Wi-Fi.
"""
import argparse
import ipaddress
import json
import os
import socket
import ssl
import sys


def local_ip(s):
    try:
        ip = ipaddress.IPv4Address(s)
    except (ValueError, ipaddress.AddressValueError):
        raise ValueError('Use a literal private IPv4 address of the phone')
    if not any(ip in block for block in (
        ipaddress.ip_network('10.0.0.0/8'),
        ipaddress.ip_network('172.16.0.0/12'),
        ipaddress.ip_network('192.168.0.0/16'),
    )):
        raise ValueError('The phone must have an RFC1918 LAN address')
    return str(ip)


def request(ip, port, data, timeout):
    # Our Android server uses a unique self-signed certificate. This probe
    # confirms TLS transport only, not trust in a vendor certificate.
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    with socket.create_connection((ip,port), timeout=timeout) as raw:
        raw.settimeout(timeout)
        with context.wrap_socket(raw,server_hostname=ip) as s:
            cipher = s.cipher()
            s.sendall(data)
            chunks = []
            received = 0
            while received<8192:
                block=s.recv(min(2048,8192-received))
                if not block:break
                chunks.append(block)
                received+=len(block)
                if b'\r\n\r\n' in b''.join(chunks):break
            return (b''.join(chunks).decode('utf-8','replace'),cipher[0] if cipher else '?')


def diagnose(host, https_port=1081, wss_port=8090, timeout=5):
    ip=local_ip(host)
    result={'target':ip,'https_port':https_port,'wss_port':wss_port,
            'note':'Transport test only; factory device compatibility NOT established'}
    try:
        reply,cipher=request(ip,https_port,
            b'GET /health HTTP/1.1\r\nHost: private.lan\r\nConnection: close\r\n\r\n',timeout)
        result['https_status']=reply.split('\r\n',1)[0]
        result['https_tls_cipher']=cipher
        result['https_ok']='200' in result['https_status']
    except (OSError,ssl.SSLError) as exc:
        result['https_ok']=False
        result['https_error']=type(exc).__name__
    try:
        key='dGhlIHNhbXBsZSBub25jZQ=='
        wire=(f'GET /api/ws HTTP/1.1\r\nHost: {ip}:{wss_port}\r\nUpgrade: websocket\r\n'
              f'Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\n'
              f'Sec-WebSocket-Key: {key}\r\n\r\n').encode('ascii')
        reply,cipher=request(ip,wss_port,wire,timeout)
        result['wss_status']=reply.split('\r\n',1)[0]
        result['wss_tls_cipher']=cipher
        result['wss_ok']='101' in result['wss_status']
    except (OSError,ssl.SSLError) as exc:
        result['wss_ok']=False
        result['wss_error']=type(exc).__name__
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description='Read-only private-Sonoff-server port check')
    p.add_argument('--host',required=True,help='Phone private IPv4 e.g. 192.168.0.192')
    p.add_argument('--timeout',type=float,default=5)
    opts=p.parse_args()
    try:
        output=diagnose(opts.host,timeout=max(1,min(opts.timeout,15)))
        print(json.dumps(output,ensure_ascii=False,indent=2))
        sys.exit(0 if output['https_ok'] and output['wss_ok'] else 2)
    except ValueError as e:
        print(str(e),file=sys.stderr);sys.exit(2)
