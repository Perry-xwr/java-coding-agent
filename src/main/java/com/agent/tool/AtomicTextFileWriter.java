package com.agent.tool;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@FunctionalInterface
interface AtomicTextFileWriter {
    void write(Path target, String content) throws IOException;

    static AtomicTextFileWriter utf8() {
        return AtomicTextFileWriter::writeUtf8;
    }

    static AtomicTextFileWriter createUtf8() {
        return AtomicTextFileWriter::createUtf8;
    }

    private static void writeUtf8(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".agent-edit-", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write(content);
                writer.flush();
            }
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void createUtf8(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".agent-create-", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write(content);
                writer.flush();
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
