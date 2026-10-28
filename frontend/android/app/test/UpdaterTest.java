package dev.bouldering.judge;
import java.nio.file.*;
public class UpdaterTest {
 static void ok(boolean b){if(!b)throw new AssertionError();}
 static String asset(String v,String hash,String url){return "[{\"draft\":false,\"prerelease\":true,\"tag_name\":\"judge-v"+v+"-test\",\"assets\":[{\"name\":\"tournament-judge-"+v+".apk\",\"state\":\"uploaded\",\"size\":50000,\"digest\":\""+hash+"\",\"browser_download_url\":\""+url+"\"}]}]";}
 public static void main(String[] args)throws Exception{
  String hash="sha256:"+"a".repeat(64), url=AppUpdater.PREFIX+"judge-v0.5.0-test/tournament-judge-0.5.0.apk", valid=asset("0.5.0",hash,url);
  ok(AppUpdater.discover(valid,"0.4.0").version.equals("0.5.0"));
  ok(AppUpdater.discover(valid,"0.5.0")==null);ok(AppUpdater.discover(valid,"0.6.0")==null);
  ok(AppUpdater.discover(valid.replace("\"draft\":false","\"draft\":true"),"0.4.0")==null);
  ok(AppUpdater.discover(valid.replace(url,"https://example.com/fake.apk"),"0.4.0")==null);
  ok(AppUpdater.discover(valid.replace(hash,"sha256:bad"),"0.4.0")==null);
  ok(AppUpdater.discover(valid.replace("50000","999999999"),"0.4.0")==null);
  ok(AppUpdater.discover(valid.replace("\"uploaded\"","\"open\""),"0.4.0")==null);
  ok(AppUpdater.compare("0.10.0","0.9.9")>0);
  boolean reject=false;try{AppUpdater.readBytes("http://github.com/fake",100);}catch(SecurityException e){reject=true;}ok(reject);
  reject=false;try{AppUpdater.readBytes("https://example.com/fake",100);}catch(SecurityException e){reject=true;}ok(reject);
  String live=new String(AppUpdater.readBytes(AppUpdater.REPO,2000000));
  ok(AppUpdater.discover(live,"0.2.0").version.equals("0.3.0"));ok(AppUpdater.discover(live,"0.4.0")==null);
  String actual="https://github.com/ilmolodoyli/bouldering-api/releases/download/judge-v0.3.0-test/tournament-judge-0.3.0.apk";
  byte[] bytes=AppUpdater.readBytes(actual,AppUpdater.LIMIT);ok(bytes.length==41992);
  ok(AppUpdater.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals("abe744ffb8bbff5da9c3bc22610d612c8bebf5127a90c6ae734786a2e8fc42b1"));
  System.out.println("PASS updater discovery prerelease/current/downgrade/draft/wrong URL/bad digest/oversize/incomplete rejects; HTTPS host enforcement; numeric version order; live release discovery and actual APK download/hash");
 }
}
