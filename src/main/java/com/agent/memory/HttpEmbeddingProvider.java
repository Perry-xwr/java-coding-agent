package com.agent.memory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
/** Separate opt-in configuration. No chat-provider inference and no application retries. */
public final class HttpEmbeddingProvider implements EmbeddingProvider {
    private final URI endpoint; private final String model,key; private final int dimensions;
    private String usage="UNKNOWN";
    public String reportedUsage(){return usage;}
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public HttpEmbeddingProvider(String endpoint,String model,String key,int dimensions){
        this.endpoint=URI.create(endpoint);this.model=model;this.key=key;this.dimensions=dimensions;
        if(!"https".equals(this.endpoint.getScheme())||this.endpoint.getUserInfo()!=null||this.endpoint.getQuery()!=null||this.endpoint.getFragment()!=null||model==null||model.isBlank()||key==null||key.isBlank()||dimensions<1||dimensions>4096)throw new IllegalArgumentException("Invalid embedding configuration");
    }
    public static EmbeddingProvider configured(){
        if(!"true".equalsIgnoreCase(System.getenv("AGENT_EMBEDDING_ENABLED")))return null;
        try{return new HttpEmbeddingProvider(System.getenv("EMBEDDING_ENDPOINT"),System.getenv("EMBEDDING_MODEL"),System.getenv("EMBEDDING_API_KEY"),Integer.parseInt(System.getenv("EMBEDDING_DIMENSIONS")));}
        catch(RuntimeException invalid){return null;}
    }
    public String identity(){return endpoint+"#"+model;}
    public int dimensions(){return dimensions;}
    public double[] embed(String text)throws IOException{
        usage="UNKNOWN";
        if(text==null||text.isBlank()||text.length()>2048)throw new IOException("Embedding input budget exceeded");
        try{
            var json=new ObjectMapper();
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+key).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("model",model,"input",text)))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()){
                if(response.statusCode()!=200)throw new IOException("Embedding HTTP error");
                byte[] bytes=readBounded(body);if(bytes.length>262144)throw new IOException("Embedding response too large");
                var root=json.readTree(bytes);var data=root.path("data");
                var total=root.path("usage").path("total_tokens");
                if(total.isIntegralNumber()&&total.canConvertToLong()&&total.longValue()>=0)usage="PROVIDER_REPORTED_LAST_TOTAL_TOKENS="+total.longValue();
                if(!data.isArray()||data.size()!=1||!data.get(0).path("embedding").isArray())throw new IOException("Invalid embedding response");
                var values=data.get(0).get("embedding");double[] vector=new double[values.size()];
                for(int i=0;i<vector.length;i++){if(!values.get(i).isNumber())throw new IOException("Invalid embedding value");vector[i]=values.get(i).doubleValue();}
                return EmbeddingProvider.normalized(vector,dimensions);
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Embedding interrupted");}
        catch(RuntimeException invalid){throw new IOException("Invalid embedding response/configuration");}
    }
    private static byte[] readBounded(InputStream body)throws IOException {
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"embedding-body-read");t.setDaemon(true);return t;});
        var read=worker.submit(()->body.readNBytes(262145));
        try{return read.get(20,java.util.concurrent.TimeUnit.SECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Embedding read interrupted");}
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException e){throw new IOException("Embedding body unavailable/timeout");}
        finally{read.cancel(true);worker.shutdownNow();}
    }
}
