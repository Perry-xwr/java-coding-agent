package com.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded deterministic JSON page encoding for discovery-only tool results. */
final class PagedToolOutput {
    static final int MAX_OUTPUT_CHARS = 16_000;
    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int FRAGMENT_CHARS = 1_000;

    private PagedToolOutput() { }

    record Page(String json, boolean truncated, Integer nextOffset) { }

    static Page encode(ObjectMapper mapper, String itemsField, List<?> allItems,
                       Map<String, Object> counts, int offset, int limit)
            throws JsonProcessingException {
        if (offset < 0 || offset > allItems.size()) {
            throw new IllegalArgumentException("offset must be between 0 and totalItems inclusive");
        }
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }

        List<Object> pageItems = new ArrayList<>();
        String candidateJson = null;
        int cursor = offset;
        while (cursor < allItems.size() && pageItems.size() < limit) {
            pageItems.add(allItems.get(cursor));
            boolean more = cursor + 1 < allItems.size();
            candidateJson = serialize(mapper, itemsField, pageItems, counts, offset, limit,
                    allItems.size(), more ? cursor + 1 : null);
            if (candidateJson.length() > MAX_OUTPUT_CHARS) {
                pageItems.remove(pageItems.size() - 1);
                break;
            }
            cursor++;
        }
        if (pageItems.isEmpty() && offset < allItems.size()) {
            throw new IllegalArgumentException("A result fragment exceeds the safe output limit");
        }

        int next = offset + pageItems.size();
        boolean truncated = next < allItems.size();
        String json = serialize(mapper, itemsField, pageItems, counts, offset, limit,
                allItems.size(), truncated ? next : null);
        if (json.length() > MAX_OUTPUT_CHARS) {
            throw new IllegalArgumentException("Tool output exceeds the safe character limit");
        }
        return new Page(json, truncated, truncated ? next : null);
    }

    static List<Map<String, Object>> filePathItems(List<String> paths) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String path : paths) {
            int fragments = fragmentCount(path.length());
            for (int index = 0; index < fragments; index++) {
                String fragment = substring(path, index);
                Map<String, Object> item = new LinkedHashMap<>();
                if (fragments == 1) {
                    item.put("path", path);
                } else {
                    item.put("pathFragment", fragment);
                    item.put("pathLengthChars", path.length());
                    item.put("fragmentIndex", index + 1);
                    item.put("fragmentCount", fragments);
                    item.put("pathFragmented", true);
                }
                items.add(item);
            }
        }
        return List.copyOf(items);
    }

    static List<Map<String, Object>> matchItems(List<SearchCodeTool.Match> matches) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (SearchCodeTool.Match match : matches) {
            int fragments = fragmentCount(match.content().length());
            for (int index = 0; index < fragments; index++) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("file", match.file());
                item.put("line", match.line());
                item.put("content", substring(match.content(), index));
                item.put("fragmentIndex", index + 1);
                item.put("fragmentCount", fragments);
                item.put("lineTruncated", fragments > 1);
                items.add(item);
            }
        }
        return List.copyOf(items);
    }

    private static String serialize(ObjectMapper mapper, String field, List<?> items,
                                    Map<String, Object> counts, int offset, int limit,
                                    int totalItems, Integer nextOffset)
            throws JsonProcessingException {
        Map<String, Object> output = new LinkedHashMap<>(counts);
        output.put(field, List.copyOf(items));
        output.put("truncated", nextOffset != null);
        if (nextOffset != null) {
            output.put("offset", offset);
            output.put("limit", limit);
            output.put("totalItems", totalItems);
            output.put("returnedItems", items.size());
            output.put("nextOffset", nextOffset);
        }
        return mapper.writeValueAsString(output);
    }

    private static int fragmentCount(int length) {
        return Math.max(1, (length + FRAGMENT_CHARS - 1) / FRAGMENT_CHARS);
    }

    private static String substring(String value, int fragmentIndex) {
        int start = fragmentIndex * FRAGMENT_CHARS;
        return value.substring(start, Math.min(value.length(), start + FRAGMENT_CHARS));
    }
}
