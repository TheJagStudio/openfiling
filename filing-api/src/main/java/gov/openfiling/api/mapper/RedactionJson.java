package gov.openfiling.api.mapper;

import gov.openfiling.api.dto.CategoryCount;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** (De)serializes jsonb columns: redaction categories and audit details. */
public final class RedactionJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private RedactionJson() {
    }

    public static List<CategoryCount> parseCategories(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            List<CategoryCount> result = new ArrayList<>();
            for (JsonNode item : node) {
                JsonNode category = item.get("category");
                JsonNode count = item.get("count");
                if (category != null && count != null) {
                    result.add(new CategoryCount(category.asString(), count.asLong()));
                }
            }
            return result;
        } catch (JacksonException e) {
            return List.of();
        }
    }

    /** Merges duplicate categories and writes {@code [{"category":…,"count":…}]}. */
    public static String writeCategories(List<CategoryCount> categories) {
        Map<String, Long> merged = new LinkedHashMap<>();
        if (categories != null) {
            for (CategoryCount c : categories) {
                merged.merge(c.category(), c.count(), Long::sum);
            }
        }
        List<Map<String, Object>> rows = merged.entrySet().stream()
                .<Map<String, Object>>map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("category", e.getKey());
                    row.put("count", e.getValue());
                    return row;
                })
                .toList();
        return MAPPER.writeValueAsString(rows);
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (JacksonException e) {
            return MAPPER.createObjectNode();
        }
    }
}
