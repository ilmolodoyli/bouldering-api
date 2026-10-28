"""Local static app + read-only proxy. No live writes or credentials accepted."""
import http.server, urllib.request, urllib.error, os, json
from pathlib import Path
ROOT=Path(__file__).parent
http.server.SimpleHTTPRequestHandler.extensions_map['.mjs']='text/javascript'
BASE=os.environ.get('BOULDER_API','https://agile-sandbag-viewless.ngrok-free.dev').rstrip('/')
from urllib.parse import urlsplit
parsed=urlsplit(BASE)
if parsed.scheme not in ('http','https') or not parsed.netloc or parsed.username or parsed.password:
 raise ValueError('BOULDER_API must be an http(s) origin without credentials')
class Handler(http.server.SimpleHTTPRequestHandler):
 def __init__(self,*a,**kw):super().__init__(*a,directory=str(ROOT),**kw)
 def do_GET(self):
  if not self.path.startswith('/api/'):
   return super().do_GET()
  path=self.path[4:]
  if path.split('?')[0] not in ['/routes/','/competitors/','/openapi.json']:
   self.send_error(403,'Only documented read endpoints enabled');return
  try:
   req=urllib.request.Request(BASE+path,headers={'ngrok-skip-browser-warning':'1','User-Agent':'BoulderingJudgeDev/0.1'})
   with urllib.request.urlopen(req,timeout=12) as r:data=r.read(2000001)
   if len(data)>2000000:raise ValueError('Response too large')
   json.loads(data)
   self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(data)
  except Exception:self.send_error(502,'Backend not reachable or invalid JSON')
 def do_POST(self):self.send_error(403,'Live scoring blocked: missing auth and authoritative result readback')
 def do_PUT(self):self.send_error(403,'Not supported by current backend')
if __name__=='__main__':
 print('Open http://127.0.0.1:8781 . Live API is read-only. Training is device-local.')
 http.server.ThreadingHTTPServer(('127.0.0.1',8781),Handler).serve_forever()
