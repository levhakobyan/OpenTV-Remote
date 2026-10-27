#!/usr/bin/env python3
"""Loopback Tizen protocol fixture, not evidence of physical Samsung compatibility.
Requires pyOpenSSL. No auth tokens, key material or entered text are logged.
Approve by writing allow/deny to --approval-file. Control actions: drop, rotate,
secure-off, secure-on, reject-token. Credentials/certificates are temporary.
"""
import argparse, base64, hashlib, http.server, json, pathlib, secrets, select, socket, ssl, struct, tempfile, threading, time
from OpenSSL import crypto

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--http-port',type=int,default=8001)
parser.add_argument('--https-port',type=int,default=8002)
parser.add_argument('--approval-file',default='/private/tmp/opentv-samsung-approval')
parser.add_argument('--control-file',default='/private/tmp/opentv-samsung-control')
args=parser.parse_args()
work=tempfile.TemporaryDirectory(prefix='opentv-samsung-')
ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
peers=set();lock=threading.RLock();auth_token=secrets.token_urlsafe(24);secure_disabled=False

def certificate():
    key=crypto.PKey();key.generate_key(crypto.TYPE_RSA,2048)
    cert=crypto.X509();cert.set_serial_number(secrets.randbits(64));cert.get_subject().CN='OpenTV Samsung fixture'
    cert.gmtime_adj_notBefore(-60);cert.gmtime_adj_notAfter(86400);cert.set_issuer(cert.get_subject());cert.set_pubkey(key);cert.sign(key,'sha256')
    pem=pathlib.Path(work.name)/'cert.pem';priv=pathlib.Path(work.name)/'key.pem'
    pem.write_bytes(crypto.dump_certificate(crypto.FILETYPE_PEM,cert));priv.write_bytes(crypto.dump_privatekey(crypto.FILETYPE_PEM,key));priv.chmod(0o600)
    ctx.load_cert_chain(pem,priv)
certificate()

def metadata():
    return {'type':'Samsung SmartTV','id':'uuid:opentv-samsung-fixture','name':'Samsung fixture','device':{'id':'uuid:opentv-samsung-fixture','name':'Samsung fixture','type':'Samsung SmartTV','modelName':'Fixture','OS':'Tizen','ImeSyncedSupport':'true','TokenAuthSupport':'true'},'isSupport':json.dumps({'remote_available':'true','remote_fourDirections':'true','ImeSyncedSupport':'true'})}

class Peer:
    def __init__(self,conn):self.conn=conn;self.send_lock=threading.Lock()
    def send(self,body,opcode=1):
        data=json.dumps(body).encode() if isinstance(body,dict) else body
        length=len(data)
        header=bytes([0x80|opcode,length]) if length<126 else bytes([0x80|opcode,126])+struct.pack('!H',length)
        with self.send_lock:self.conn.sendall(header+data)
    def close(self):
        try:self.send(struct.pack('!H',1001),8)
        except OSError:pass
        try:self.conn.shutdown(socket.SHUT_RDWR)
        except OSError:pass
    def exact(self,n):
        out=b''
        while len(out)<n:
            chunk=self.conn.recv(n-len(out))
            if not chunk:raise EOFError()
            out+=chunk
        return out
    def read(self):
        a,b=self.exact(2);length=b&127
        if length==126:length=struct.unpack('!H',self.exact(2))[0]
        elif length==127:length=struct.unpack('!Q',self.exact(8))[0]
        if length>65536:raise ValueError('oversized frame')
        mask=self.exact(4) if b&128 else None;data=self.exact(length)
        if mask:data=bytes(c^mask[i%4] for i,c in enumerate(data))
        return a&15,data

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*a):pass
    def do_GET(self):
        from urllib.parse import urlsplit,parse_qs
        path=urlsplit(self.path)
        if self.server.secure and secure_disabled:
            self.send_response(503);self.send_header('Content-Length','0');self.end_headers();return
        if path.path=='/api/v2/':
            data=json.dumps(metadata()).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
        if path.path!='/api/v2/channels/samsung.remote.control' or self.headers.get('Upgrade','').lower()!='websocket':
            self.send_response(404);self.send_header('Content-Length','0');self.end_headers();return
        accept=base64.b64encode(hashlib.sha1((self.headers['Sec-WebSocket-Key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        self.send_response(101);self.send_header('Upgrade','websocket');self.send_header('Connection','Upgrade');self.send_header('Sec-WebSocket-Accept',accept);self.end_headers();self.wfile.flush()
        peer=Peer(self.connection);query=parse_qs(path.query);token=query.get('token',[None])[0];approved=False;started=time.monotonic()
        with lock:peers.add(peer)
        print('SAMSUNG SOCKET secure=%s token-present=%s'%(self.server.secure,token is not None),flush=True)
        try:
            if token is not None:
                if token!=auth_token:peer.send({'event':'ms.channel.unauthorized','data':{}});return
                approved=True;peer.send({'event':'ms.channel.connect','data':{'token':auth_token}});print('SAMSUNG READY reused-token=yes',flush=True)
            while True:
                if not approved:
                    with lock:
                        file=pathlib.Path(args.approval_file)
                        answer=file.read_text().strip() if file.exists() else None
                        if answer is not None:file.unlink(missing_ok=True)
                    if answer=='allow':
                        approved=True;peer.send({'event':'ms.channel.connect','data':{'token':auth_token}});print('SAMSUNG READY reused-token=no',flush=True)
                    elif answer=='deny':peer.send({'event':'ms.channel.unauthorized','data':{}});print('SAMSUNG DENIED',flush=True);return
                    elif time.monotonic()-started>60:peer.send({'event':'ms.channel.timeOut','data':{}});return
                if not (isinstance(self.connection,ssl.SSLSocket) and self.connection.pending()) and not select.select([self.connection],[],[],.2)[0]:continue
                opcode,data=peer.read()
                if opcode==8:return
                if opcode==9:peer.send(data,10);continue
                if opcode!=1:continue
                if not approved:raise ValueError('input before approval')
                message=json.loads(data);params=message.get('params',{})
                if params.get('TypeOfRemote')=='SendRemoteKey':print('SAMSUNG COMMAND '+params.get('DataOfCmd','unknown'),flush=True)
                elif params.get('TypeOfRemote')=='SendInputString':print('SAMSUNG TEXT bytes='+str(len(base64.b64decode(params['Cmd']))),flush=True)
        except (OSError,EOFError,ValueError):pass
        finally:
            with lock:peers.discard(peer)
            self.close_connection=True

class Server(http.server.ThreadingHTTPServer):
    daemon_threads=True
    def handle_error(self,*a):print('SAMSUNG fixture request ended',flush=True)

def controls():
    global auth_token,secure_disabled
    while True:
        file=pathlib.Path(args.control_file)
        try:
            action=file.read_text().strip() if file.exists() else None
            if action:
                file.unlink(missing_ok=True)
                with lock:
                    if action=='rotate':certificate()
                    if action=='reject-token':auth_token=secrets.token_urlsafe(24)
                    if action=='secure-off':secure_disabled=True
                    if action=='secure-on':secure_disabled=False
                    current=list(peers)
                if action!='secure-on':
                    for peer in current:peer.close()
                print('SAMSUNG CONTROL '+action,flush=True)
        except OSError:pass
        time.sleep(.15)

plain=Server(('127.0.0.1',args.http_port),Handler);plain.secure=False
secure=Server(('127.0.0.1',args.https_port),Handler);secure.secure=True;secure.socket=ctx.wrap_socket(secure.socket,server_side=True)
threading.Thread(target=plain.serve_forever,daemon=True).start();threading.Thread(target=controls,daemon=True).start()
print('Samsung loopback fixture: HTTP %d / TLS %d. Awaiting explicit TV approval.'%(args.http_port,args.https_port),flush=True)
try:secure.serve_forever()
finally:plain.shutdown();plain.server_close();secure.server_close();work.cleanup()
