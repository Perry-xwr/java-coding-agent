package com.agent.tui.prototype;

import org.jline.terminal.Terminal;

import java.lang.reflect.Field;

/** Opt-in only for the verified JLine JNI Windows console path; restores the exact mode it observed. */
final class WindowsVtInputMode implements AutoCloseable {
    static final int ENABLE_VIRTUAL_TERMINAL_INPUT=0x0200;
    interface ConsoleModeAccess { int read() throws Exception; boolean write(int mode) throws Exception; }

    private final ConsoleModeAccess access;
    private final int originalMode;
    private final String status;
    private boolean restoreRequired,restored;

    private WindowsVtInputMode(ConsoleModeAccess access,int originalMode,String status,boolean restoreRequired){
        this.access=access;this.originalMode=originalMode;this.status=status;this.restoreRequired=restoreRequired;
    }
    static WindowsVtInputMode notAttempted(){return new WindowsVtInputMode(null,0,"BRACKETED_PASTE=NOT_APPLICABLE",false);}
    static boolean supportedBackend(boolean jniWindows,String terminalType){return jniWindows&&"windows-vtp".equals(terminalType);}
    static WindowsVtInputMode enable(Terminal terminal){
        if(!supportedBackend(TerminalMouseBinding.windowsJni(terminal),terminal.getType()))return new WindowsVtInputMode(null,0,"BRACKETED_PASTE=NOT_APPLICABLE backend="+terminal.getClass().getSimpleName()+" type="+terminal.getType(),false);
        try{
            Class<?> backend=Class.forName("org.jline.terminal.impl.AbstractWindowsTerminal");Field handleField=backend.getDeclaredField("inConsole");handleField.setAccessible(true);
            long handle=((Number)handleField.get(terminal)).longValue();Class<?> api=Class.forName("org.jline.nativ.Kernel32");
            ConsoleModeAccess access=new ConsoleModeAccess(){
                public int read()throws Exception{int[] value={0};int ok=(int)api.getMethod("GetConsoleMode",long.class,int[].class).invoke(null,handle,value);if(ok==0)throw new IllegalStateException("GET_CONSOLE_MODE_FAILED");return value[0];}
                public boolean write(int mode)throws Exception{return (int)api.getMethod("SetConsoleMode",long.class,int.class).invoke(null,handle,mode)!=0;}
            };
            return enable(access);
        }catch(Exception|LinkageError failure){return new WindowsVtInputMode(null,0,"BRACKETED_PASTE=UNAVAILABLE reason=CONSOLE_MODE_ACCESS",false);}
    }
    static WindowsVtInputMode enable(ConsoleModeAccess access){
        int original;
        try{original=access.read();}catch(Exception failure){return new WindowsVtInputMode(null,0,"BRACKETED_PASTE=UNAVAILABLE reason=GET_CONSOLE_MODE",false);}
        var activation=new WindowsVtInputMode(access,original,"BRACKETED_PASTE=UNAVAILABLE reason=SET_OR_VERIFY_MODE",true);
        int desired=original|ENABLE_VIRTUAL_TERMINAL_INPUT;
        try{
            if(!access.write(desired))return activation;
            int actual=access.read();
            if((actual&ENABLE_VIRTUAL_TERMINAL_INPUT)!=0){activation.restored=false;return new WindowsVtInputMode(access,original,"BRACKETED_PASTE=ENABLED input_mode=0x"+Integer.toHexString(actual),true);}
        }catch(Exception ignored){ }
        return activation;
    }
    String status(){return status;}
    synchronized boolean restore(){
        if(!restoreRequired||restored)return true;
        try{if(!access.write(originalMode))return false;restored=access.read()==originalMode;return restored;}
        catch(Exception failure){return false;}
    }
    @Override public void close(){restore();}
}
