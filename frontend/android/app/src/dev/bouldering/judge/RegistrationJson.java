package dev.bouldering.judge;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
/** Strict flat registration JSON, with no retained request or error text. */
final class RegistrationJson {
 private final String s; private int p;
 private RegistrationJson(String value){s=value;}
 static Map<String,Object> parse(String s)throws IOException{return new RegistrationJson(s).object();}
 private void ws(){while(p<s.length()&&" \r\n\t".indexOf(s.charAt(p))>=0)p++;}
 private char take()throws IOException{if(p>=s.length())throw new IOException();return s.charAt(p++);}
 private Map<String,Object> object()throws IOException{
  Map<String,Object> out=new LinkedHashMap<>();ws();if(take()!='{')throw new IOException();ws();
  if(p<s.length()&&s.charAt(p)=='}'){p++;ws();if(p!=s.length())throw new IOException();return out;}
  while(true){ws();String key=string();ws();if(take()!=':')throw new IOException();ws();Object value;
   if(p<s.length()&&s.charAt(p)=='"')value=string();else{int start=p;while(p<s.length()&&s.charAt(p)>='0'&&s.charAt(p)<='9')p++;String n=s.substring(start,p);if(n.isEmpty()||(n.length()>1&&n.charAt(0)=='0'))throw new IOException();try{value=Long.parseLong(n);}catch(Exception e){throw new IOException();}}
   if(out.put(key,value)!=null)throw new IOException();ws();char end=take();if(end=='}')break;if(end!=',')throw new IOException();
  }ws();if(p!=s.length())throw new IOException();return out;
 }
 private String string()throws IOException{
  if(take()!='"')throw new IOException();StringBuilder b=new StringBuilder();
  while(true){char c=take();if(c=='"')return b.toString();if(c<32)throw new IOException();if(c=='\\'){char e=take();switch(e){case '"':case '\\':case '/':c=e;break;case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;case 'u':int v=0;for(int i=0;i<4;i++){int d=Character.digit(take(),16);if(d<0)throw new IOException();v=v*16+d;}c=(char)v;break;default:throw new IOException();}}b.append(c);}
 }
 static String quote(String s){StringBuilder b=new StringBuilder("\"");for(char c:s.toCharArray()){if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();}
}
