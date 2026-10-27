#!/usr/bin/env python3
"""Loopback-only lab receiver, not a TV integration or hardware qualification.

The optional HTTP IME controls keep editor content in memory only. They are for
explicit local tests; do not expose this fixture through a tunnel or LAN bind.
Pairing codes and non-IME remote key frames are printed for existing UI tests.
"""
import argparse
import hashlib
import io
import json
import pathlib
import socket
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_FRAME = 1_048_576
MAX_HTTP_BODY = 16_384


def vi(n):
    if not isinstance(n, int):
        raise ValueError('Invalid integer')
    n &= (1 << 64) - 1
    out = bytearray()
    while n > 127:
        out.append((n & 127) | 128)
        n >>= 7
    return bytes(out) + bytes([n])


def integer(n, value): return vi(n * 8) + vi(value)
def blob(n, value): return vi(n * 8 + 2) + vi(len(value)) + value
def text(n, value): return blob(n, value.encode('utf-8', errors='strict'))
def envelope(n, body=b''): return integer(1, 2) + integer(2, 200) + blob(n, body)
def unsigned(n): return n.to_bytes((n.bit_length() + 7) // 8, 'big')


def readvi(read):
    value = 0
    for index in range(10):
        chunk = read(1)
        if len(chunk) != 1:
            raise EOFError()
        byte = chunk[0]
        if index == 9 and byte > 1:
            raise ValueError('Invalid varint')
        value |= (byte & 127) << (7 * index)
        if byte < 128:
            return value
    raise ValueError('Invalid varint')


def parse(raw):
    stream = io.BytesIO(raw)
    fields = {}
    while stream.tell() < len(raw):
        tag = readvi(stream.read)
        number, kind = tag >> 3, tag & 7
        if not number:
            raise ValueError('Invalid field')
        if kind == 0:
            value = readvi(stream.read)
        elif kind == 2:
            size = readvi(stream.read)
            if size > MAX_FRAME or size > len(raw) - stream.tell():
                raise ValueError('Invalid field size')
            value = stream.read(size)
        elif kind in (1, 5):
            size = 8 if kind == 1 else 4
            if len(stream.read(size)) != size:
                raise ValueError('Truncated fixed field')
            continue
        else:
            raise ValueError('Unsupported field')
        fields.setdefault(number, []).append(value)
    return fields


def number(fields, key, default=0):
    value = fields.get(key, [default])[-1]
    if not isinstance(value, int):
        raise ValueError('Expected integer')
    return value


def message(fields, key):
    value = fields.get(key, [b''])[-1]
    if not isinstance(value, bytes):
        raise ValueError('Expected message')
    return parse(value)


def string(fields, key):
    value = fields.get(key, [b''])[-1]
    if not isinstance(value, bytes):
        raise ValueError('Expected text')
    return value.decode('utf-8', errors='strict')


def frame(connection):
    size = readvi(connection.recv)
    if not 0 < size <= MAX_FRAME:
        raise ValueError('Invalid frame')
    out = bytearray()
    while len(out) < size:
        chunk = connection.recv(size - len(out))
        if not chunk:
            raise EOFError()
        out.extend(chunk)
    return bytes(out)


def utf16_replace(value, start, end, replacement):
    """Use Android editor offsets; strict decoding rejects split surrogate pairs."""
    data = value.encode('utf-16-le', errors='strict')
    if not 0 <= start <= end <= len(data) // 2:
        raise ValueError('Invalid range')
    data[:start * 2].decode('utf-16-le', errors='strict')
    data[end * 2:].decode('utf-16-le', errors='strict')
    result = data[:start * 2] + replacement.encode('utf-16-le', errors='strict') + data[end * 2:]
    return result.decode('utf-16-le', errors='strict')


class IMEFixture:
    """A deliberately specified test editor; it is not reverse-engineering proof."""
    def __init__(self):
        self.lock = threading.RLock()
        self.active = False
        self.value = ''
        self.start = self.end = 0
        self.label = 'Search'
        self.ime, self.revision, self.context = 7, 42, 7
        self.app = 'com.google.android.youtube.tv'
        self.edit_count = self.received_edits = self.rejected_edits = 0
        self.key_counts = {}
        self.ack_paused = False
        self.omit_counters = False
        self.pending_acks = []
        self.previous = None
        self.clients = set()

    def snapshot(self):
        return dict(active=self.active, text=self.value, start=self.start, end=self.end,
                    label=self.label, ime=self.ime, revision=self.revision,
                    app=self.app, context=self.context, omitCounters=self.omit_counters)

    def state(self):
        with self.lock:
            return dict(self.snapshot(), editCount=self.edit_count,
                        receivedEdits=self.received_edits, rejectedEdits=self.rejected_edits,
                        keyCounts=dict(self.key_counts), ackPaused=self.ack_paused,
                        connectionCount=len(self.clients))

    def frames(self, snapshot=None, initial=False):
        state = self.snapshot() if snapshot is None else snapshot
        app = integer(1, state['context']) + text(12, state['app'])
        if not state['active']:
            return [blob(20, blob(1, app))]
        status = (integer(1, state['revision']) + text(2, state['text']) +
                  integer(3, state['start']) + integer(4, state['end']) + text(6, state['label']))
        current = blob(20, blob(1, app) + blob(2, status))
        counter = blob(21, integer(1, state['ime']) + integer(2, state['revision']))
        counters = [] if state.get('omitCounters', False) else [counter]
        return [current] + counters + [blob(22, blob(2, status))] if initial else counters + [current]

    def broadcast(self, frames):
        for client in tuple(self.clients):
            try:
                client.send_many(frames)
            except Exception:
                self.clients.discard(client)

    @staticmethod
    def validate(payload, defaults):
        result = dict(defaults)
        for key in ('text', 'label', 'app'):
            if key in payload:
                value = payload[key]
                if not isinstance(value, str):
                    raise ValueError('Invalid editor data')
                value.encode('utf-8', errors='strict')
                result[key] = value
        for key in ('start', 'end', 'ime', 'revision', 'context'):
            if key in payload:
                value = payload[key]
                if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value < 2**31:
                    raise ValueError('Invalid editor data')
                result[key] = value
        length = len(result['text'].encode('utf-16-le')) // 2
        if 'text' in payload:
            result['start'] = payload.get('start', length)
            result['end'] = payload.get('end', result['start'])
        utf16_replace(result['text'], result['start'], result['end'], '')
        return result

    def control(self, route, payload):
        with self.lock:
            if route in ('/open', '/remote', '/field'):
                defaults = self.snapshot()
                if route in ('/remote', '/field'):
                    defaults['revision'] += 1
                if route == '/field':
                    defaults['context'] += 1
                    defaults.update(text='', start=0, end=0)
                state = self.validate(payload, defaults)
                self.previous = self.snapshot()
                self.active = True
                if route in ('/open', '/field'):
                    self.omit_counters = payload.get('omitCounters', False) is True
                self.value, self.start, self.end = state['text'], state['start'], state['end']
                self.label, self.app = state['label'], state['app']
                self.ime, self.revision, self.context = state['ime'], state['revision'], state['context']
                self.broadcast(self.frames()[-1:] if route == '/remote' else self.frames(initial=True))
            elif route == '/close':
                self.previous = self.snapshot()
                self.active = False
                self.omit_counters = False
                self.value = ''
                self.start = self.end = 0
                self.context += 1
                self.broadcast(self.frames())
            elif route == '/ack-pause':
                self.ack_paused = True
            elif route == '/ack-resume':
                self.ack_paused = False
                pending, self.pending_acks = self.pending_acks, []
                for frames in pending:
                    self.broadcast(frames)
            elif route == '/stale':
                if self.previous is not None:
                    self.broadcast(self.frames(self.previous))
            else:
                raise KeyError('Unknown route')
            return self.state()

    def connect(self, client):
        with self.lock:
            self.clients.add(client)
            if self.active:
                client.send_many(self.frames(initial=True))

    def disconnect(self, client):
        with self.lock:
            self.clients.discard(client)

    def incoming(self, raw):
        fields = parse(raw)
        with self.lock:
            if 10 in fields:
                key = number(message(fields, 10), 1)
                self.key_counts[str(key)] = self.key_counts.get(str(key), 0) + 1
            if 21 not in fields:
                return None
            self.received_edits += 1
            try:
                batch = message(fields, 21)
                if not self.active or number(batch, 1) != self.ime or number(batch, 2) != self.revision:
                    raise ValueError('Stale editor')
                edits = batch.get(3, [])
                if len(edits) != 1:
                    raise ValueError('Unsupported batch')
                edit = parse(edits[0])
                if number(edit, 1) != 1:
                    raise ValueError('Unsupported operation')
                value = message(edit, 2)
                start, end, replacement = number(value, 1), number(value, 2), string(value, 3)
                updated = utf16_replace(self.value, start, end, replacement)
                self.previous = self.snapshot()
                self.value = updated
                self.start = self.end = start + len(replacement.encode('utf-16-le')) // 2
                self.revision += 1
                self.edit_count += 1
                frames = self.frames()
                if self.ack_paused:
                    self.pending_acks.append(frames)
                else:
                    self.broadcast(frames)
                return True
            except (ValueError, TypeError, UnicodeError, EOFError):
                self.rejected_edits += 1
                return False


def safe_frame_log(raw):
    """Do not accidentally print text in an IME request or nested error echo."""
    try:
        fields = parse(raw)
    except (ValueError, EOFError):
        return 'UNPARSED FRAME received'
    if any(field in fields for field in (20, 21, 22, 3)):
        return 'IME EDIT received' if 21 in fields else 'IME FRAME received'
    return 'REMOTE FRAME ' + raw.hex() if set(fields) == {10} else 'REMOTE FRAME received'


class Client:
    def __init__(self, connection):
        self.connection = connection
        self.lock = threading.Lock()

    def send_many(self, frames):
        with self.lock:
            for raw in frames:
                self.connection.sendall(vi(len(raw)) + raw)


def http_handler(editor):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, status, payload):
            raw = json.dumps(payload, ensure_ascii=True).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            if self.path == '/state':
                self.reply(200, editor.state())
            else:
                self.reply(404, {'error': 'Unknown route'})

        def do_POST(self):
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 <= size <= MAX_HTTP_BODY:
                    self.close_connection = True
                    self.reply(413, {'error': 'Request too large'})
                    return
                payload = json.loads(self.rfile.read(size) or b'{}')
                if not isinstance(payload, dict):
                    raise ValueError('Invalid request')
                self.reply(200, editor.control(self.path, payload))
            except KeyError:
                self.reply(404, {'error': 'Unknown route'})
            except (ValueError, TypeError, UnicodeError):
                self.reply(400, {'error': 'Invalid editor control'})
    return Handler


def serve(port, context, args, editor):
    from OpenSSL import SSL
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(('127.0.0.1', port))
    listener.listen()
    print('TEST FIXTURE listening', port, flush=True)
    while True:
        raw, _ = listener.accept()

        def handle(raw):
            connection = SSL.Connection(context, raw)
            connection.set_accept_state()
            client = Client(connection)
            closed = threading.Event()
            try:
                connection.do_handshake()
                if port == 6467:
                    frame(connection)
                    client.send_many([envelope(11)])
                    frame(connection)
                    client.send_many([envelope(20, blob(2, integer(1, 3) + integer(2, 6)) + integer(3, 2))])
                    frame(connection)
                    client.send_many([envelope(31)])
                    public = connection.get_peer_certificate().to_cryptography().public_key().public_numbers()
                    server = context.get_app_data().to_cryptography().public_key().public_numbers()
                    tail = b'\x12\x34'
                    digest = hashlib.sha256(unsigned(public.n) + unsigned(public.e) + unsigned(server.n) + unsigned(server.e) + tail).digest()
                    print('PAIR CODE: ' + f'{digest[0]:02X}1234', flush=True)
                    if digest not in frame(connection):
                        raise ValueError('Pairing rejected')
                    client.send_many([envelope(41)])
                    print('PAIR SUCCESS', flush=True)
                else:
                    client.send_many([blob(1, integer(1, 622) + blob(2, text(1, 'Test fixture') + text(2, 'Luma QA')))])
                    frame(connection)
                    print('CONFIG RESPONSE received', flush=True)
                    client.send_many([blob(2, integer(1, 622))])
                    frame(connection)
                    client.send_many([blob(40, integer(1, 1)), blob(50, integer(6, 100) + integer(7, 25))])
                    editor.connect(client)

                    def ping():
                        tick, last_token = 0, None
                        try:
                            while not closed.wait(1):
                                tick += 1
                                if args.ping_interval > 0 and tick % args.ping_interval == 0:
                                    client.send_many([blob(8, integer(1, 7))])
                                trigger = pathlib.Path(args.ime_trigger) if args.ime_trigger else None
                                if trigger and trigger.exists():
                                    token = trigger.read_text()[:100]
                                    if token != last_token:
                                        last_token = token
                                        editor.control('/open', dict(text='', start=0, end=0, ime=7, revision=42))
                                        print('IME SHOW REQUEST', flush=True)
                        except Exception:
                            closed.set()
                    threading.Thread(target=ping, daemon=True).start()
                    while True:
                        incoming = frame(connection)
                        print(safe_frame_log(incoming), flush=True)
                        editor.incoming(incoming)
            except Exception as error:
                print('CLOSED', port, type(error).__name__, flush=True)
            finally:
                closed.set()
                editor.disconnect(client)
                connection.close()
        threading.Thread(target=handle, args=(raw,), daemon=True).start()


def identity(directory):
    directory = pathlib.Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    key, cert = directory / 'key.pem', directory / 'cert.pem'
    if key.exists() != cert.exists():
        raise ValueError('Identity directory must contain both cert.pem and key.pem')
    if not key.exists():
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(key),
                        '-out', str(cert), '-days', '365', '-subj', '/CN=Luma QA fixture'], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        key.chmod(0o600)
    return key, cert


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ping-interval', type=int, default=4,
                        help='Seconds between pings; 0 tests a silent idle connection')
    parser.add_argument('--ime-trigger', help='Change this file to emit a keyboard-show event')
    parser.add_argument('--identity-dir', type=pathlib.Path,
                        help='Reuse cert.pem/key.pem here; an absent pair is generated once')
    parser.add_argument('--ime-control-port', type=int,
                        help='Enable memory-only IME test controls on 127.0.0.1 at this port')
    args = parser.parse_args()
    if args.ime_control_port is not None and not 1024 < args.ime_control_port < 65536:
        parser.error('IME control port must be between 1025 and 65535')
    from OpenSSL import SSL, crypto
    with tempfile.TemporaryDirectory(prefix='luma-tv-test-') as temporary:
        key, cert = identity(args.identity_dir or temporary)
        context = SSL.Context(SSL.TLS_SERVER_METHOD)
        context.set_min_proto_version(SSL.TLS1_2_VERSION)
        context.use_privatekey_file(str(key))
        context.use_certificate_file(str(cert))
        context.check_privatekey()
        context.set_verify(SSL.VERIFY_PEER, lambda *args: True)
        context.set_app_data(crypto.load_certificate(crypto.FILETYPE_PEM, cert.read_bytes()))
        editor = IMEFixture()
        if args.ime_control_port is not None:
            server = ThreadingHTTPServer(('127.0.0.1', args.ime_control_port), http_handler(editor))
            server.daemon_threads = True
            threading.Thread(target=server.serve_forever, daemon=True).start()
            print('IME TEST controls listening on loopback', args.ime_control_port, flush=True)
        threading.Thread(target=serve, args=(6467, context, args, editor), daemon=True).start()
        serve(6466, context, args, editor)


if __name__ == '__main__':
    main()
