package com.agent.tui.prototype;

import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.utils.NonBlockingPumpReader;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NativePasteDiagnosticTest {
    @Test void idleReadWaitsAndReportsTimeoutRatherThanEof(){
        var pipe=new NonBlockingPumpReader();var reader=new BindingReader(pipe);long start=System.nanoTime();
        var result=assertDoesNotThrow(()->NativePasteDiagnostic.awaitCharacter(reader,350));
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        assertEquals(NativePasteDiagnostic.WaitStatus.TIMEOUT,result.status());assertTrue(elapsed>=300,"idle input must remain interactive until timeout");
    }

    @Test void delayedKeyIsReceivedAndCtrlQBindingCanExit(){
        var pipe=new NonBlockingPumpReader();var reader=new BindingReader(pipe);var map=new KeyMap<String>();map.setNomatch("TEXT");map.setUnicode("TEXT");map.bind("EXIT",KeyMap.ctrl('Q'));
        Thread sender=new Thread(()->{try{Thread.sleep(120);pipe.getWriter().write("x\u0011");pipe.getWriter().flush();}catch(Exception e){throw new RuntimeException(e);}});sender.start();
        var first=assertDoesNotThrow(()->NativePasteDiagnostic.awaitCharacter(reader,1500));assertEquals(NativePasteDiagnostic.WaitStatus.READY,first.status());assertEquals((int)'x',first.character());
        assertEquals("TEXT",reader.readBinding(map));assertEquals("EXIT",reader.readBinding(map));assertDoesNotThrow(()->sender.join(1000));assertFalse(sender.isAlive());
    }

    @Test void closedInputIsReportedAsEof(){
        var pipe=new NonBlockingPumpReader();var reader=new BindingReader(pipe);assertDoesNotThrow(pipe::close);
        var result=assertDoesNotThrow(()->NativePasteDiagnostic.awaitCharacter(reader,1000));assertEquals(NativePasteDiagnostic.WaitStatus.EOF,result.status());
    }
}
