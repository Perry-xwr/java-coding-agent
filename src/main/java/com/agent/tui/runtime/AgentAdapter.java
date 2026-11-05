package com.agent.tui.runtime;

/** UI execution boundary. TUI-3 accepts injected offline Agent Core; online/native approval remains disabled. */
public interface AgentAdapter extends AutoCloseable {
    enum Mode { FAKE_ONLY, AGENT_CORE_OFFLINE, RESTRICTED_DEEPSEEK, DAILY, REAL_NATIVE }
    default boolean dailyMode(){return false;}
    default java.nio.file.Path writableWorkspace(java.nio.file.Path path)throws java.io.IOException{return com.agent.TuiSessionWorkspace.writable(path);}
    default String providerLabel(){return mode()==Mode.FAKE_ONLY?"FAKE":"MOCK";}
    default String modelLabel(){return mode()==Mode.FAKE_ONLY?"scripted-tui1":"agent-core-offline";}
    default boolean restrictedValidation(){return false;}
    default boolean fixedValidationPrompts(){return false;}
    default String validationStatus(){return "Not a restricted validation run";}
    default boolean available(){return true;}
    default boolean canSubmit(){return available();}
    default String submissionRejection(String text){return null;}
    default String capabilities(){return "READ_ONLY";}
    record Request(String sessionId,String turnId,String text,boolean memory,String provider,String model) { }
    @FunctionalInterface interface EventSink { void publish(UiEvent.Task event) throws InterruptedException; }
    interface RunHandle {
        void cancel(); // Must not block the UI waiting for a task result.
        default boolean resolveSimulatedApproval(String requestId,boolean approved){return false;}
    }
    Mode mode();
    RunHandle start(Request request,EventSink events);
    @Override void close();
}
