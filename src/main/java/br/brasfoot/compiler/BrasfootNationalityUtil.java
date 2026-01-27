package br.brasfoot.compiler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.text.Normalizer;
import java.util.Locale;

public final class BrasfootNationalityUtil {
  private BrasfootNationalityUtil() {}

  // Regra: SEMPRE a primeira nacionalidade listada (quando array).
  public static String readFirstNationalityName(JsonObject pj) {
    if (pj == null) return null;
    if (!pj.has("nationality") || pj.get("nationality").isJsonNull()) return null;

    JsonElement natEl = pj.get("nationality");

    if (natEl.isJsonPrimitive()) {
      try { return natEl.getAsString().trim(); } catch (Exception ignored) {}
      return null;
    }

    if (natEl.isJsonObject()) {
      String s = JsonUtil.getString(natEl.getAsJsonObject(), "name");
      if (s != null && !s.isBlank()) return s;

      s = JsonUtil.getString(natEl.getAsJsonObject(), "label");
      if (s != null && !s.isBlank()) return s;

      s = JsonUtil.getString(natEl.getAsJsonObject(), "raw");
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
        String s = JsonUtil.getString(first.getAsJsonObject(), "name");
        if (s != null && !s.isBlank()) return s;

        s = JsonUtil.getString(first.getAsJsonObject(), "label");
        if (s != null && !s.isBlank()) return s;

        s = JsonUtil.getString(first.getAsJsonObject(), "raw");
        if (s != null && !s.isBlank()) return s;
      }
    }

    return null;
  }

  public static Integer resolveCountryId(String natName) {
    if (natName == null || natName.isBlank()) return null;

    Integer id = Mappings.get().countryIdByNameOrAlias(natName);
    if (id != null) return id;

    String noAcc = deaccent(natName);
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

  private static String deaccent(String s) {
    if (s == null) return null;
    String n = Normalizer.normalize(s, Normalizer.Form.NFD);
    return n.replaceAll("\\p{M}+", "");
  }
}