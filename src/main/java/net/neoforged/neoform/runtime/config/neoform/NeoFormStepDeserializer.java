package net.neoforged.neoform.runtime.config.neoform;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.stream.Collectors;

class NeoFormStepDeserializer implements JsonDeserializer<NeoFormStep> {
    @Override
    public NeoFormStep deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        JsonObject obj = json.getAsJsonObject();
        if (!obj.has("type"))
            throw new JsonParseException("Could not parse step: Missing 'type'");
        String type = obj.get("type").getAsString();
        String name = obj.has("name") ? obj.get("name").getAsString() : type;
        Map<String, String> values = obj.entrySet().stream()
                .filter(e -> !"type".equals(e.getKey()) && !"name".equals(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getAsString()));
        return new NeoFormStep(type, name, values);
    }
}
