package com.agent.llm;

import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Reads complete Server-Sent Events and refuses to return a response without the protocol terminator. */
final class SseResponseReader {
    private SseResponseReader() {
    }

    static LLMResponse read(ResponseBody body, StreamingResponseAccumulator accumulator, String provider)
            throws IOException {
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(accumulator, "accumulator must not be null");
        StringBuilder eventData = new StringBuilder();
        boolean done = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                body.byteStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!done && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    done = processEvent(eventData, accumulator);
                } else if (line.startsWith("data:")) {
                    if (!eventData.isEmpty()) eventData.append('\n');
                    eventData.append(line.substring(5).stripLeading());
                }
            }
        }
        if (!done && !eventData.isEmpty()) {
            done = processEvent(eventData, accumulator);
        }
        if (!done) {
            throw new IOException(provider + " streaming response ended before data: [DONE]");
        }
        return accumulator.finish();
    }

    private static boolean processEvent(StringBuilder eventData, StreamingResponseAccumulator accumulator)
            throws IOException {
        if (eventData.isEmpty()) return false;
        String data = eventData.toString();
        eventData.setLength(0);
        if ("[DONE]".equals(data)) return true;
        accumulator.accept(data);
        return false;
    }
}
