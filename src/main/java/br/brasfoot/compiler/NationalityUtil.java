package br.brasfoot.compiler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

public final class NationalityUtil {

  private NationalityUtil() {}

  public static String readFirstNationalityName(JsonObject pj) {
    if (pj == null) return null;
    if (!pj.has("nationality") || pj.get("nationality").isJsonNull()) return null;

    JsonElement natEl = pj.get("nationality");

    if (natEl.isJsonPrimitive()) {
      try { return natEl.getAsString().trim(); } catch (Exception ignored) {}
      return null;
    }

    if (natEl.isJsonObject()) {
      JsonObject o = natEl.getAsJsonObject();
      String s = JsonUtil.getString(o, "name");
      if (s != null && !s.isBlank()) return s;
      s = JsonUtil.getString(o, "label");
      if (s != null && !s.isBlank()) return s;
      s = JsonUtil.getString(o, "raw");
      if (s != null && !s.isBlank()) return s;
      return null;
    }

    if (natEl.isJsonArray()) {
      JsonArray arr = natEl.getAsJsonArray();
      if (arr.size() == 0) return null;

      JsonElement first = arr.get(0);
      if (first == null || first.isJsonNull()) return null;

      if (first.isJsonPrimitive()) {
        try { return first.getAsString().trim(); } catch (Exception ignored) {}
        return null;
      }
      if (first.isJsonObject()) {
        JsonObject o = first.getAsJsonObject();
        String s = JsonUtil.getString(o, "name");
        if (s != null && !s.isBlank()) return s;
        s = JsonUtil.getString(o, "label");
        if (s != null && !s.isBlank()) return s;
        s = JsonUtil.getString(o, "raw");
        if (s != null && !s.isBlank()) return s;
      }
    }

    return null;
  }

  public static Integer resolveCountryId(String natName) {
    if (natName == null || natName.isBlank()) return null;

    Integer id = Mappings.get().countryIdByNameOrAlias(natName);
    if (id != null) return id;

    String noAcc = TextUtil.deaccent(natName);
    if (!noAcc.equals(natName)) {
      id = Mappings.get().countryIdByNameOrAlias(noAcc);
      if (id != null) return id;
    }

    String lowered = natName.trim().toLowerCase(Locale.ROOT);
    if (lowered.equals("brazil")) return Mappings.get().countryIdByNameOrAlias("Brasil");
    if (lowered.equals("uruguay")) return Mappings.get().countryIdByNameOrAlias("Uruguai");
    if (lowered.equals("paraguay")) return Mappings.get().countryIdByNameOrAlias("Paraguai");
    if (lowered.equals("argentina")) return Mappings.get().countryIdByNameOrAlias("Argentina");

    return null;
  }
}