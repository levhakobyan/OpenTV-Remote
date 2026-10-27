#!/usr/bin/env python3
"""Loopback-only XCTest bridge for local protocol fixtures, never a production server.
Start fake_android_tv.py separately and pass its log. Samsung is started/stopped
only by explicit test POSTs. Optional editor endpoints expose synthetic lab state
in memory only; never point them at a physical TV or enter personal credentials.
No key material is exposed and HTTP request bodies are never logged.
"""
from pathlib import Path
from http.server import BaseHTTPRequestHandler, HTTPServer
import argparse, atexit, json, re, subprocess, sys, tempfile, time, threading, urllib.request, urllib.error

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--android-log',type=Path,required=True)
parser.add_argument('--port',type=int,default=8771)
parser.add_argument('--android-hardware-host',help='Explicitly authorized physical Android TV address')
parser.add_argument('--android-hardware-code-file',type=Path,help='Temporary user-provided pairing code; never logged')
parser.add_argument('--android-ime-trigger',type=Path,help='Trigger file for an explicitly configured Android fixture')
parser.add_argument('--ime-control-port',type=int,help='Explicit local fixture control port; never a hardware address')
args=parser.parse_args()
if args.ime_control_port is not None and not 1024 < args.ime_control_port < 65536:
    parser.error('IME fixture control port must be between 1025 and 65535')
work=tempfile.TemporaryDirectory(prefix='luma-ios-qa-')
folder=Path(work.name);samsung=None;output=None
def stop():
    global samsung,output
    if samsung is not None:
        samsung.terminate()
        try:samsung.wait(timeout=3)
        except subprocess.TimeoutExpired:samsung.kill();samsung.wait()
    samsung=None
    if output is not None:output.close();output=None
atexit.register(stop)
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def reply(self,body,status=200):
        data=body.encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
    def ime(self,path,body=None):
        if args.ime_control_port is None:
            self.reply('{}',404);return
        try:
            request=urllib.request.Request('http://127.0.0.1:'+str(args.ime_control_port)+path,data=body,headers={'Content-Type':'application/json'})
            with urllib.request.urlopen(request,timeout=3) as response:self.reply(response.read().decode(),response.status)
        except urllib.error.HTTPError as error:self.reply('{}',error.code)
        except (OSError,ValueError):self.reply('{}',503)
    def do_GET(self):
        if self.path=='/android/ime/state':
            self.ime('/state');return
        log=args.android_log.read_text() if args.android_log.exists() else ''
        if self.path=='/hardware/android':
            self.reply(json.dumps({'host':args.android_hardware_host}),200 if args.android_hardware_host else 404)
        elif self.path=='/hardware/android/code':
            value=args.android_hardware_code_file.read_text().strip() if args.android_hardware_code_file and args.android_hardware_code_file.exists() else ''
            self.reply(value,200 if re.fullmatch('[0-9A-Fa-f]{6}',value) else 404)
        elif self.path=='/pair-code':
            codes=re.findall(r'PAIR CODE: ([0-9A-F]{6})',log)
            self.reply(codes[-1] if codes else '',200 if codes else 404)
        elif self.path=='/counts':
            sl=(folder/'samsung.log').read_text() if (folder/'samsung.log').exists() else ''
            self.reply(json.dumps({'okDown':log.count('REMOTE FRAME 520408171001'),'okUp':log.count('REMOTE FRAME 520408171002'),'okShort':log.count('REMOTE FRAME 520408171003'),'delete':log.count('REMOTE FRAME 520408431003'),'rightDown':log.count('REMOTE FRAME 520408161001'),'rightUp':log.count('REMOTE FRAME 520408161002'),'home':log.count('REMOTE FRAME 520408031003'),'volume':log.count('REMOTE FRAME 520408181003'),'text':log.count('REMOTE FRAME aa01')+log.count('IME EDIT received'),'samsungHome':sl.count('SAMSUNG COMMAND KEY_HOME'),'samsungText':sl.count('SAMSUNG TEXT bytes='),'samsungSecure':sl.count('SAMSUNG SOCKET secure=True'),'samsungPlain':sl.count('SAMSUNG SOCKET secure=False'),'samsungReuse':sl.count('SAMSUNG READY reused-token=yes')}))
        else:self.reply('{}',404)
    def do_POST(self):
        global samsung,output
        prefix='/android/ime/live/'
        if self.path.startswith(prefix):
            action=self.path[len(prefix):]
            if action not in {'open','remote','field','close','ack-pause','ack-resume','stale'}:
                self.reply('{}',404);return
            try:size=int(self.headers.get('Content-Length','0'))
            except ValueError:self.reply('{}',400);return
            if not 0<=size<=16384:
                self.close_connection=True;self.reply('{}',413);return
            self.ime('/'+action,self.rfile.read(size) or b'{}');return
        if self.path=='/android/ime/close' and args.ime_control_port is not None:
            self.ime('/close',b'{}');return
        if self.path=='/android/ime/close' and args.android_ime_trigger:
            args.android_ime_trigger.unlink(missing_ok=True)
            self.reply('{}')
        elif self.path=='/android/ime/open' and args.android_ime_trigger:
            args.android_ime_trigger.write_text(str(time.monotonic_ns()))
            threading.Timer(2, lambda: args.android_ime_trigger.unlink(missing_ok=True)).start()
            self.reply('{}')
        elif self.path=='/samsung/start':
            stop()
            for name in ('approval','control'):(folder/name).unlink(missing_ok=True)
            output=(folder/'samsung.log').open('w')
            samsung=subprocess.Popen([sys.executable,str(Path(__file__).with_name('fake_samsung_tv.py')),'--approval-file',str(folder/'approval'),'--control-file',str(folder/'control')],stdout=output,stderr=subprocess.STDOUT)
            time.sleep(.6)
            self.reply('{}',200 if samsung.poll() is None else 503)
        elif self.path=='/samsung/stop':stop();self.reply('{}')
        elif self.path in ('/samsung/allow','/samsung/deny'):
            (folder/'approval').write_text(self.path.rsplit('/',1)[-1]);self.reply('{}')
        elif self.path.startswith('/samsung/control/') and self.path.rsplit('/',1)[-1] in ('drop','rotate','secure-off','secure-on','reject-token'):
            (folder/'control').write_text(self.path.rsplit('/',1)[-1]);self.reply('{}')
        else:self.reply('{}',404)
print('XCTest bridge: loopback port',args.port,'(no source/APK hosting)',flush=True)
try:HTTPServer(('127.0.0.1',args.port),Handler).serve_forever()
finally:stop();work.cleanup()
