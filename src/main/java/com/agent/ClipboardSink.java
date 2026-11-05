package com.agent;
/** Injectable clipboard sink for offline tests. Production never records contents. */
@FunctionalInterface public interface ClipboardSink {
 final class Failure extends Exception {final String reason;public Failure(String reason){this.reason=reason;}}
 void write(String text)throws Exception;
 static ClipboardSink system(){return text->{
  if(java.awt.GraphicsEnvironment.isHeadless())throw new Failure("HEADLESS");
  try{java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text),null);}
  catch(java.awt.HeadlessException unavailable){throw new Failure("HEADLESS");}
  catch(IllegalStateException busy){throw new Failure("BUSY");}
  catch(SecurityException denied){throw new Failure("DENIED");}
  catch(java.awt.AWTError unavailable){throw new Failure("UNAVAILABLE");}
 };}
}
