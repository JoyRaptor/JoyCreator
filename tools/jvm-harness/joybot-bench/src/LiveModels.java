import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Which OpenRouter models a live run may use: free ones only, unless the owner said otherwise. */
final class LiveModels {

    private LiveModels() {}

    static boolean isFree(String id) { return id.endsWith(":free") || id.equals("openrouter/free"); }

    /** The free router first, then up to {@code n - 1} free models that take tool calls (public list, no key). */
    static List<String> freeToolModels(int n) throws Exception {
        List<String> out = new ArrayList<>();
        out.add("openrouter/free");
        HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
                .send(HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/models")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        for (JsonElement e : JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("data")) {
            if (out.size() >= n) break;
            JsonObject m = e.getAsJsonObject();
            String id = m.get("id").getAsString();
            if (!id.endsWith(":free") || !m.has("supported_parameters")) continue;
            if (m.getAsJsonArray("supported_parameters").toString().contains("\"tools\"")) out.add(id);
        }
        return out;
    }
}
