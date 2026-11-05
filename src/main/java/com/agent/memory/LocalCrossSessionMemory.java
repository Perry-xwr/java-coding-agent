package com.agent.memory;

import com.agent.CrossSessionMemory;
import com.agent.SharedMemoryStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.text.Normalizer;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Consent is checked before every read/write. Retrieval has no cross-turn cache. */
public final class LocalCrossSessionMemory implements CrossSessionMemory {
    public static final int TOP_K = 3;
    public static final int INJECTION_ESTIMATED_TOKEN_LIMIT = 512;
    private final SharedMemoryStore store;
    private final BooleanSupplier consent;
    private final String scope;
    private String mode="keyword";
    private EmbeddingProvider embedding;
    private SemanticMemoryIndex index;
    public LocalCrossSessionMemory retrieval(String mode,EmbeddingProvider provider,SemanticMemoryIndex index){this.mode=mode;this.embedding=provider;this.index=index;return this;}

    public LocalCrossSessionMemory(SharedMemoryStore store, BooleanSupplier consent, Path workspace) {
        this.store = Objects.requireNonNull(store); this.consent = Objects.requireNonNull(consent);
        scope = workspace.toAbsolutePath().normalize().toString();
    }
    public boolean enabled() { return consent.getAsBoolean(); }
    private void requireConsent() {
        if (!enabled()) throw new IllegalStateException("Cross-session memory is OFF; shared access is denied");
    }
    public List<LongTermMemory> listAuthorized() throws IOException {
        requireConsent();
        return store.read().stream().filter(m -> scope.equals(m.workspaceScope()))
                .sorted(Comparator.comparing(LongTermMemory::memoryId)).toList();
    }
    public LongTermMemory updateAuthorized(String memoryId, String text) throws IOException {
        requireConsent();
        return store.update(memoryId, scope, text);
    }
    public void forgetAuthorized(String memoryId) throws IOException {
        requireConsent();
        store.forget(memoryId, scope);
    }
    public List<SharedMemoryHit> retrieveAuthorized(String sessionId, String query) throws IOException {
        if (!enabled()) return List.of();
        UUID.fromString(sessionId);
        Set<String> queryTokens = tokens(query);
        Set<String> identifiers = identifiers(query);
        if (queryTokens.isEmpty()) return List.of();
        List<LongTermMemory> authorized=store.read().stream().filter(m->scope.equals(m.workspaceScope())).toList();
        List<SharedMemoryHit> hits = new ArrayList<>();
        for (LongTermMemory memory : authorized) {
            if (!scope.equals(memory.workspaceScope())) continue;
            Set<String> contentTokens = tokens(memory.content());
            long common = queryTokens.stream().filter(contentTokens::contains).count();
            double queryCoverage = (double) common / queryTokens.size();
            double memoryCoverage = contentTokens.isEmpty() ? 0 : (double) common / contentTokens.size();
            // Balance verbose-query coverage with how much of the stored memory is actually matched.
            // This is still local lexical retrieval; it does not infer synonyms or call an embedding API.
            double score = 0.65 * queryCoverage + 0.35 * memoryCoverage;
            // Exact full identifier evidence must not disappear merely because the question
            // adds Chinese explanation/boilerplate. This is lexical, not synonym inference.
            if(identifiers.stream().anyMatch(identifiers(memory.content())::contains))score=Math.max(score,0.25);
            // Keyword mode cannot infer that a preference about explanation style applies
            // to a new explanation request (for example, “explain cache” vs. “lead with
            // the conclusion, then give reasons and steps”). Bridge only this narrow,
            // locally recognizable preference domain; never send another model request.
            boolean explanationPreference = explanationPreference(memory.content())
                    && explanationRequest(query);
            if (explanationPreference) score = Math.max(score, 0.25);
            // A code-style preference can be relevant even when a task names only a
            // source file ("calculator.py") and never repeats the language name.
            // Bridge only when the stored memory names the same language and is
            // explicitly about writing code; this does not make unrelated memories
            // eligible for every coding request.
            boolean codeLanguagePreference = codeLanguagePreference(memory.content(), query);
            if (codeLanguagePreference) score = Math.max(score, 0.25);
            if ((common > 0 || explanationPreference || codeLanguagePreference) && score >= 0.25)
                hits.add(new SharedMemoryHit(memory, score));
        }
        hits.sort(Comparator.comparingDouble(SharedMemoryHit::score).reversed()
                .thenComparing(hit -> hit.memory().memoryId()));
        List<SharedMemoryHit> keyword=List.copyOf(hits.subList(0, Math.min(TOP_K, hits.size())));
        if(mode.equals("keyword")||embedding==null||index==null||authorized.isEmpty())return keyword;
        try {
            double threshold=Double.parseDouble(System.getProperty("agent.memory.semanticThreshold","0.5"));
            if(!Double.isFinite(threshold)||threshold<0||threshold>1)return keyword;
            var semantic=index.rank(authorized,query,embedding,threshold);
            if(mode.equals("semantic"))return semantic;
            Map<String,Double> scores=new HashMap<>();Map<String,LongTermMemory> memories=new HashMap<>();
            for(var ranked:List.of(keyword,semantic))for(int i=0;i<ranked.size();i++){
                var hit=ranked.get(i);scores.merge(hit.memory().memoryId(),1.0/(60+i+1),Double::sum);memories.put(hit.memory().memoryId(),hit.memory());
            }
            return scores.entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                    .limit(TOP_K).map(e->new SharedMemoryHit(memories.get(e.getKey()),e.getValue())).toList();
        }catch(RuntimeException unavailable){return keyword;}

    }
    public LongTermMemory rememberAuthorized(String sessionId, String text) throws IOException {
        if (!enabled()) throw new IllegalStateException("Cross-session memory is OFF; shared writes are denied");
        LongTermMemory.validateContent(text);
        String now = Instant.now().toString();
        LongTermMemory memory = new LongTermMemory(UUID.randomUUID().toString(), text, sessionId, now, now,
                "USER_MEMORY_COMMAND", "EXPLICIT_USER", scope, null);
        store.append(memory);
        return memory;
    }
    public List<String> retrieve(String sessionId, String query) {
        try { return retrieveAuthorized(sessionId, query).stream().map(hit -> hit.memory().content()).toList(); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    public void saveAuthorized(String sessionId, String text) {
        try { rememberAuthorized(sessionId, text); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private static final Set<String> STOP = Set.of("the", "this", "that", "what", "which", "does", "use", "using", "please", "project", "version",
            "该项", "项目", "什么", "当前", "版本", "这个", "请问", "使用", "统一", "么是");
    private static Set<String> tokens(String value) {
        String text = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Set<String> result = new HashSet<>();
        var english = Pattern.compile("[a-z][a-z0-9_]*|[0-9]+").matcher(text);
        while (english.find()) {
            String token = english.group(); result.add(token);
            var parts = Pattern.compile("[a-z]+|[0-9]+").matcher(token);
            while (parts.find()) result.add(parts.group());
        }
        var chinese = Pattern.compile("[\\p{IsHan}]+").matcher(text);
        while (chinese.find()) {
            String part = chinese.group();
            for (int i = 0; i + 1 < part.length(); i++) result.add(part.substring(i, i + 2));
        }
        result.removeAll(STOP);
        return result;
    }
    private static Set<String> identifiers(String text){
        Set<String> result=new HashSet<>();
        var matcher=Pattern.compile("[A-Za-z][A-Za-z0-9_]*").matcher(text==null?"":text);
        while(matcher.find()){
            String token=matcher.group().toLowerCase(Locale.ROOT);
            if(token.length()>=3&&!STOP.contains(token))result.add(token);
        }
        return result;
    }
    private static boolean explanationPreference(String text) {
        return text != null && text.matches("(?is).*(?:先给结论|先说结论|结论.{0,12}(?:理由|步骤)|先用.{0,12}(?:例子|示例)|讲解.{0,12}(?:偏好|习惯)|解释.{0,12}(?:偏好|习惯)).*");
    }
    private static boolean explanationRequest(String text) {
        return text != null && text.matches("(?is).*(?:解释|讲解|介绍|说明|如何理解|是什么).*");
    }
    private static boolean codeLanguagePreference(String memory, String query) {
        if (memory == null || query == null) return false;
        String normalizedMemory = Normalizer.normalize(memory, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String normalizedQuery = Normalizer.normalize(query, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        boolean sourceCodePreference = containsAny(normalizedMemory, "编写", "写代码", "代码", "函数")
                && containsAny(normalizedMemory, "偏好", "习惯", "以后", "尽量", "应该", "类型注解", "type annotation");
        if (!sourceCodePreference) return false;
        return (normalizedMemory.contains("python") && normalizedQuery.matches("(?s).*\\.py(?:\\b|$).*") )
                || (normalizedMemory.contains("javascript") && normalizedQuery.matches("(?s).*\\.(?:js|mjs|cjs)(?:\\b|$).*") )
                || (normalizedMemory.contains("typescript") && normalizedQuery.matches("(?s).*\\.(?:ts|tsx)(?:\\b|$).*") )
                || (normalizedMemory.contains("java") && normalizedQuery.matches("(?s).*\\.java(?:\\b|$).*") );
    }
    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
}
