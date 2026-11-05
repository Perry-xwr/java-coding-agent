package com.agent.tui.prototype;

import com.agent.*;
import com.agent.llm.*;
import com.agent.validation.FinalApiBudget;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import static org.junit.jupiter.api.Assertions.*;

class FinalApiPreconfirmTest {
    @TempDir Path root;
    @Test void newLedgerIsOnlyAPreviewAndCancelCreatesNothing()throws Exception{
        Path run=root.resolve("new-live");var options=new FinalApiTuiMain.Options(run,true,true,true);
        var preview=FinalApiTuiMain.preconfirm(options);assertTrue(preview.newLedger());assertFalse(Files.exists(run));
        String summary=FinalApiTuiMain.confirmationSummary(options,preview);assertTrue(summary.contains("NEW_LEDGER"));assertTrue(summary.contains("0/4"));assertTrue(summary.contains("Memory OFF"));assertTrue(summary.contains("READ"));assertTrue(summary.contains("deepseek-flash"));assertTrue(summary.contains(run.toString()));
        assertThrows(IOException.class,()->FinalApiTuiMain.requireConfirmation(options,null));assertThrows(IOException.class,()->FinalApiTuiMain.requireConfirmation(options,"Y"));assertFalse(Files.exists(run));
        // Pure exact-material validation only; this does not launch or authorize any Provider.
        FinalApiTuiMain.requireConfirmation(options,"ENABLE DEEPSEEK 4 "+run);
        var opened=FinalApiBudget.openConfirmed(preview,true);assertEquals(0,opened.state().attempts());opened.reserve();assertEquals(1,opened.state().attempts());
    }
    @Test void existingCountsAreReadOnlyAndExhaustionCannotBeReset()throws Exception{
        Path w=FinalApiWorkspace.create(root.resolve("owned"),true);Path ledger=w.getParent().resolve("evidence/http-ledger");var budget=new FinalApiBudget(ledger,true);budget.reserve();
        var options=new FinalApiTuiMain.Options(w.getParent(),false,true,true);byte[] before=Files.readAllBytes(ledger.resolve("attempts.json"));
        var preview=FinalApiTuiMain.preconfirm(options);assertEquals(1,preview.state().attempts());assertTrue(FinalApiTuiMain.confirmationSummary(options,preview).contains("1/4"));assertArrayEquals(before,Files.readAllBytes(ledger.resolve("attempts.json")));
        var confirmed=FinalApiBudget.openConfirmed(preview,true);confirmed.reserve();confirmed.reserve();confirmed.reserve();assertEquals(4,confirmed.state().attempts());
        var four=FinalApiTuiMain.preconfirm(options);assertTrue(four.display().contains("4/4"));assertThrows(IOException.class,four::requireAvailable);assertThrows(IOException.class,()->confirmed.reserve());assertEquals(4,new FinalApiBudget(ledger,true).state().attempts());
    }
    @Test void corruptIdentityOrLockConflictRejectsWithoutRebuilding()throws Exception{
        Path ledger=root.resolve("bad");new FinalApiBudget(ledger,true);
        try(var channel=FileChannel.open(ledger.resolve("attempts.lock"),StandardOpenOption.WRITE);var lock=channel.lock()){
            assertThrows(IOException.class,()->FinalApiBudget.inspect(ledger));
        }
        Files.writeString(ledger.resolve("attempts.json"),"{corrupt");assertThrows(IOException.class,()->FinalApiBudget.inspect(ledger));assertEquals("{corrupt",Files.readString(ledger.resolve("attempts.json")));
        Path other=root.resolve("identity");new FinalApiBudget(other,true);Files.writeString(other.resolve("final-api.owner"),"WRONG_OWNER");assertThrows(IOException.class,()->FinalApiBudget.inspect(other));assertEquals("WRONG_OWNER",Files.readString(other.resolve("final-api.owner")));
    }
    @Test void changesDuringConfirmationOrBeforeFirstSendRejectWithoutHttp()throws Exception{
        Path ledger=root.resolve("changed");var other=new FinalApiBudget(ledger,true);other.reserve();var preview=FinalApiBudget.inspect(ledger);
        other.reserve();assertThrows(FinalApiBudget.ConfirmationChanged.class,()->FinalApiBudget.openConfirmed(preview,true));assertEquals(2,other.state().attempts());
        var current=FinalApiBudget.inspect(ledger);var confirmed=FinalApiBudget.openConfirmed(current,true);other.reserve();
        byte[] before=Files.readAllBytes(ledger.resolve("attempts.json"));
        try(var client=new FinalApiValidationClient(confirmed,r->{throw new AssertionError("changed ledger must never dispatch");})){
            assertThrows(IOException.class,()->client.chat(java.util.List.of(Message.user("offline"))));
            assertArrayEquals(before,Files.readAllBytes(ledger.resolve("attempts.json")));assertEquals(3,other.state().attempts());
        }
        Path race=root.resolve("raced-new");var absent=FinalApiBudget.inspect(race);new FinalApiBudget(race,true);assertThrows(FinalApiBudget.ConfirmationChanged.class,()->FinalApiBudget.openConfirmed(absent,true));
    }
}
