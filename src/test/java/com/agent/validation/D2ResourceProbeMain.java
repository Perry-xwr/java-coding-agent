package com.agent.validation;
import java.nio.file.*;
import java.util.*;
import com.agent.llm.*;
/** Offline child-process lifecycle probe: interceptor response, no sockets or real Key. */
public final class D2ResourceProbeMain {
 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory(Path.of(args[0]),"d2-resource-probe-");
  var budget=new D2Budget(root,true);
  try(var client=new D2ChatClient(budget,"fictional-key",chain->new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("mock").body(okhttp3.ResponseBody.create("{\"choices\":[{\"message\":{\"content\":\"OFFLINE\"}}]}",okhttp3.MediaType.get("application/json"))).build())){
   if(!client.chat(List.of(Message.user("probe"))).content().equals("OFFLINE"))throw new IllegalStateException();
  }
  System.out.println("D2_RESOURCE_PROBE=PASS; TRANSPORT=MOCK; REAL_HTTP_REQUESTS=0");
 }
}
