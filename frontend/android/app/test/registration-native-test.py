from http.server import BaseHTTPRequestHandler,HTTPServer
import threading,subprocess,http.client,json
calls=[];mode='success'
class H(BaseHTTPRequestHandler):
 def do_GET(self):self.send_response(200);self.end_headers();self.wfile.write(b'[]')
 def do_POST(self):
  data=json.loads(self.rfile.read(int(self.headers['Content-Length'])));calls.append(self.path);assert set(data)=={'username','password'}
  if mode=='redirect':self.send_response(307);self.send_header('Location','http://127.0.0.1:1/leak');self.end_headers();return
  if mode=='conflict':self.send_response(400);self.end_headers();self.wfile.write(b'{"detail":"private echo must never pass"}');return
  self.send_response(201);self.end_headers();self.wfile.write(json.dumps({'id':42,'username':data['username'],'role':'organizer' if mode=='role' else 'athlete'}).encode())
 def log_message(self,*a):pass
h=HTTPServer(('127.0.0.1',0),H);threading.Thread(target=h.serve_forever,daemon=True).start()
p=subprocess.Popen(['sdk/jdk17/bin/java','-cp','build/expansion-jvm','Serve','verified-public/frontend/android/app/assets','http://127.0.0.1:'+str(h.server_port)],stdout=subprocess.PIPE);assert b'SERVING' in p.stdout.readline()
base={'Origin':'http://127.0.0.1:8781','Content-Type':'application/json','X-Judge-Action':'register-account'}
body=json.dumps({'username':'fixture','password':'fixture-only-not-real'}).encode()
def req(path='/api/auth/register',method='POST',headers=None,payload=body):
 c=http.client.HTTPConnection('127.0.0.1',8781);c.request(method,path,body=payload,headers=base if headers is None else headers);r=c.getresponse();out=r.read();code=r.status;cache=r.getheader('Cache-Control');c.close();return code,out,cache
try:
 code,out,cache=req();assert code==201 and json.loads(out)=={'id':42,'username':'fixture','role':'athlete'} and cache=='no-store'
 n=len(calls)
 for args in [dict(headers={}),dict(headers={**base,'Origin':'http://evil.invalid'}),dict(headers={**base,'Host':'evil.invalid'}),dict(headers={**base,'X-Judge-Action':'delete-route'}),dict(headers={**base,'Content-Type':'text/plain'}),dict(path='/api/auth/register?x=1'),dict(path='/api/auth/login'),dict(path='/api/results/'),dict(path='/api/users/1/role',method='PATCH'),dict(path='/api/users/1',method='GET'),dict(payload=b'x'*2049),dict(payload=b'{"username":"fixture","password":"x","role":"organizer"}'),dict(payload=b'{"username":"fixture","username":"x","password":"x"}')]:
  assert req(**args)[0] in [400,403,502]
 assert len(calls)==n
 for m,want in [('conflict',409),('redirect',502),('role',502)]:
  mode=m;code,out,cache=req();assert code==want and b'private echo' not in out and b'password' not in out and cache=='no-store'
 print('PASS native registration: explicit201 receipt; origin/host/action/type/path/payload/duplicates guards; conflict sanitized; redirect and role mismatch rejected; other writes/users remain403; controlled backend only')
finally:p.terminate();p.wait();h.shutdown()
