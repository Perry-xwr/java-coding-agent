package com.agent.tui.prototype;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WindowsVtInputModeTest {
    private static final class FakeMode implements WindowsVtInputMode.ConsoleModeAccess {
        int mode;boolean failWrite;
        FakeMode(int mode){this.mode=mode;}
        public int read(){return mode;}
        public boolean write(int next){if(failWrite)return false;mode=next;return true;}
    }
    @Test void onlyEnablesOnVerifiedJniWindowsVtpBackend(){
        assertTrue(WindowsVtInputMode.supportedBackend(true,"windows-vtp"));
        assertFalse(WindowsVtInputMode.supportedBackend(false,"windows-vtp"));
        assertFalse(WindowsVtInputMode.supportedBackend(true,"xterm-256color"));
        assertFalse(WindowsVtInputMode.supportedBackend(true,"windows"));
    }
    @Test void enablesByOrAndRestoresExactEffectiveMode(){
        var nativeMode=new FakeMode(0x99);var activation=WindowsVtInputMode.enable(nativeMode);
        assertEquals(0x299,nativeMode.mode);assertTrue(activation.status().startsWith("BRACKETED_PASTE=ENABLED"));
        assertTrue(activation.restore());assertEquals(0x99,nativeMode.mode);assertTrue(activation.restore());assertEquals(0x99,nativeMode.mode);
    }
    @Test void preservesEveryExistingConsoleFlag(){
        var nativeMode=new FakeMode(0x1e7);var activation=WindowsVtInputMode.enable(nativeMode);
        assertEquals(0x3e7,nativeMode.mode);assertTrue(activation.restore());assertEquals(0x1e7,nativeMode.mode);
    }
    @Test void reportsFailureAndStillAttemptsRestore(){
        var nativeMode=new FakeMode(0x99);nativeMode.failWrite=true;var activation=WindowsVtInputMode.enable(nativeMode);
        assertTrue(activation.status().startsWith("BRACKETED_PASTE=UNAVAILABLE"));assertFalse(activation.restore());assertEquals(0x99,nativeMode.mode);
    }
}
