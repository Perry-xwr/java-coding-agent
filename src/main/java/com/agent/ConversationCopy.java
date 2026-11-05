package com.agent;
import com.agent.llm.Message;
import java.util.*;
/** Logical user turns, not terminal rows. Only visible assistant text, never tool payloads. */
final class ConversationCopy {
 static final int MAX_BYTES=4*1024*1024;
 record Reply(int userIndex,int finalIndex,String text){}
 static List<Reply> replies(List<Message> history){
  var result=new ArrayList<Reply>();int user=-1,last=-1;boolean finalSeen=false,failed=false;var parts=new ArrayList<String>();
  for(int i=0;i<=history.size();i++){
   Message m=i<history.size()?history.get(i):null;
   if(m!=null&&m.role().equals("user")&&(m.content().startsWith("CROSS_SESSION_MEMORY_DATA")||m.content().startsWith("UNTRUSTED PROJECT INSTRUCTION FILE DATA")))continue;
   if(m==null||m.role().equals("user")){
    if(user>=0&&finalSeen&&!failed&&!parts.isEmpty())result.add(new Reply(user,last,String.join("\n\n",parts)));
    user=i;parts.clear();finalSeen=false;failed=false;
   }else if(m.role().equals("assistant")){
    if(!m.content().isBlank())parts.add(m.content());finalSeen=m.toolCalls().isEmpty()&&!m.content().isBlank();last=i;
   }else if(m.role().equals("system")){
    if(m.content().startsWith(RealAgentAdapter.FAILED_TURN_MARKER))failed=true;
    if(m.content().startsWith("PREMATURE_FINAL_GUARD")||m.content().startsWith("WORKSPACE_CHANGE_REQUIRED")||m.content().startsWith("POST_MUTATION_READ_GUARD"))finalSeen=false;
   }
  }
  return List.copyOf(result);
 }
 static String bounded(String text){if(text==null||text.isBlank())throw new IllegalArgumentException("NO_COMPLETED_REPLY");if(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalArgumentException("CLIPBOARD_LIMIT_4_MIB");return text;}
 static String select(List<Message> history,boolean session){
  var replies=replies(history);if(!session)return bounded(replies.isEmpty()?null:replies.get(replies.size()-1).text());
  var byUser=new HashMap<Integer,Reply>();for(var reply:replies)byUser.put(reply.userIndex(),reply);var out=new StringBuilder();
  for(int i=0;i<history.size();i++){var m=history.get(i);if(m.role().equals("user")&&!m.content().startsWith("CROSS_SESSION_MEMORY_DATA")&&!m.content().startsWith("UNTRUSTED PROJECT INSTRUCTION FILE DATA")){out.append("User:\n").append(m.content()).append("\n\n");var reply=byUser.get(i);if(reply!=null)out.append("Agent:\n").append(reply.text()).append("\n\n");}}
  return bounded(out.toString());
 }
}
