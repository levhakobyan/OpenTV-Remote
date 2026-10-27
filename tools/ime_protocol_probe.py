#!/usr/bin/env python3
"""Explicitly selected lab receiver for studying Google TV keyboard interoperability.
Not a production TV integration. Publishes 'OpenTV Keyboard Test' on the selected LAN.
Only protocol operation types and non-text counters are logged. Text, its ranges,
lengths and hashes are never logged. Ctrl-C removes discovery and ephemeral certificates.
"""
import argparse
import hashlib
import ipaddress
import json
import pathlib
import socket
import struct
import subprocess
import tempfile
import threading
import time
from OpenSSL import SSL, crypto
from zeroconf import ServiceInfo, Zeroconf

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--host', required=True, help='This Mac LAN IPv4 address')
args = parser.parse_args()
assert ipaddress.ip_address(args.host).is_private and args.host != '127.0.0.1'

def vi(n):
    n &= (1 << 64) - 1
    out = bytearray()
    while n > 127:
        out.append((n & 127) | 128)
        n >>= 7
    return bytes(out) + bytes([n])

def integer(n, value): return vi(n * 8) + vi(value)
def blob(n, value): return vi(n * 8 + 2) + vi(len(value)) + value
def envelope(n, value=b''): return integer(1, 2) + integer(2, 200) + blob(n, value)
def unsigned(value): return value.to_bytes((value.bit_length() + 7) // 8, 'big')

def read_varint(read):
    value = 0
    for index in range(10):
        byte = read(1)
        if len(byte) != 1: raise EOFError()
        value |= (byte[0] & 127) << (index * 7)
        if not byte[0] & 128: return value
    raise ValueError('invalid varint')

def fields(data):
    import io
    source = io.BytesIO(data)
    result = {}
    while source.tell() < len(data):
        tag = read_varint(source.read)
        if tag & 7 == 0: value = read_varint(source.read)
        elif tag & 7 == 2: value = source.read(read_varint(source.read))
        else: raise ValueError('unsupported wire type')
        result.setdefault(tag >> 3, []).append(value)
    return result

def first(data, number, default=0): return data.get(number, [default])[0]
def frame(connection):
    length = read_varint(connection.recv)
    assert 0 < length <= 1048576
    data = b''
    while len(data) < length:
        part = connection.recv(length - len(data))
        if not part: raise EOFError()
        data += part
    return data

write_lock = threading.Lock()
def send(connection, value):
    with write_lock: connection.sendall(vi(len(value)) + value)
def status(): return integer(1, 42) + blob(2, b'abc') + integer(3, 3) + integer(4, 3) + integer(5, 1)
def event(kind, **values): print(json.dumps({'event': kind, **values}), flush=True)

stopping = threading.Event()
listeners = []
connections = []

def serve(port, context):
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind((args.host, port))
    listener.listen()
    listeners.append(listener)
    while not stopping.is_set():
        try: raw, _ = listener.accept()
        except OSError: return
        def handle(raw=raw):
            connection = SSL.Connection(context, raw)
            connections.append(connection)
            connection.set_accept_state()
            active = threading.Event()
            active.set()
            try:
                connection.do_handshake()
                if port == 6467:
                    frame(connection); send(connection, envelope(11))
                    frame(connection); send(connection, envelope(20, blob(2, integer(1, 3) + integer(2, 6)) + integer(3, 2)))
                    frame(connection); send(connection, envelope(31))
                    client = connection.get_peer_certificate().to_cryptography().public_key().public_numbers()
                    server = context.get_app_data().to_cryptography().public_key().public_numbers()
                    tail = b'\x12\x34'
                    digest = hashlib.sha256(unsigned(client.n) + unsigned(client.e) + unsigned(server.n) + unsigned(server.e) + tail).digest()
                    event('pairing-code', code=f'{digest[0]:02X}1234')
                    assert digest in frame(connection)
                    send(connection, envelope(41)); event('paired')
                else:
                    send(connection, blob(1, integer(1, 622) + blob(2, blob(1, b'OpenTV Keyboard Test') + blob(2, b'Protocol lab'))))
                    event('configure', fields=list(fields(frame(connection))))
                    send(connection, blob(2, integer(1, 622))); frame(connection)
                    send(connection, blob(40, integer(1, 1)))
                    time.sleep(1)
                    app = integer(1, 7) + blob(10, b'Keyboard test') + blob(12, b'com.opentv.protocol.lab')
                    send(connection, blob(20, blob(1, app) + blob(2, status())))
                    send(connection, blob(21, integer(1, 7) + integer(2, 42)))
                    send(connection, blob(22, blob(2, status())))
                    event('keyboard-shown')
                    def ping():
                        while active.is_set() and not stopping.wait(4):
                            try: send(connection, blob(8, integer(1, 7)))
                            except Exception: return
                    threading.Thread(target=ping, daemon=True).start()
                    while not stopping.is_set():
                        message = fields(frame(connection))
                        if 21 in message:
                            batch = fields(first(message, 21, b''))
                            edits = []
                            for raw_edit in batch.get(3, []):
                                edit = fields(raw_edit)
                                edits.append({'operation': first(edit, 1)})
                            event('ime-edit', ime=first(batch, 1), field=first(batch, 2), edits=edits)
                        elif 10 in message:
                            event('key', code=first(fields(first(message, 10, b'')), 1))
                        else: event('client-message', fields=list(message))
            except Exception as error: event('closed', port=port, reason=type(error).__name__)
            finally:
                active.clear()
                connection.close()
                connections.remove(connection)
        threading.Thread(target=handle, daemon=True).start()

with tempfile.TemporaryDirectory(prefix='opentv-ime-lab-') as work:
    key = pathlib.Path(work) / 'key.pem'
    cert = pathlib.Path(work) / 'cert.pem'
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(key), '-out', str(cert), '-days', '1', '-subj', '/CN=OpenTV Keyboard Test'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    context = SSL.Context(SSL.TLS_SERVER_METHOD)
    context.set_min_proto_version(SSL.TLS1_2_VERSION)
    context.use_privatekey_file(str(key)); context.use_certificate_file(str(cert))
    context.set_verify(SSL.VERIFY_PEER, lambda *unused: True)
    context.set_app_data(crypto.load_certificate(crypto.FILETYPE_PEM, cert.read_bytes()))
    discovery = Zeroconf(interfaces=[args.host])
    service = ServiceInfo('_androidtvremote2._tcp.local.', 'OpenTV Keyboard Test._androidtvremote2._tcp.local.', addresses=[socket.inet_aton(args.host)], port=6466, properties={'model': 'OpenTV Keyboard Test'}, server='opentv-keyboard-test.local.')
    try:
        for port in [6466, 6467]: threading.Thread(target=serve, args=(port, context), daemon=True).start()
        discovery.register_service(service, strict=False)
        event('ready', name='OpenTV Keyboard Test')
        while True: time.sleep(1)
    except KeyboardInterrupt: pass
    finally:
        stopping.set()
        discovery.unregister_service(service); discovery.close()
        for listener in listeners: listener.close()
        for connection in list(connections): connection.close()
