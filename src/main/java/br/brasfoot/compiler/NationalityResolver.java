// NationalityResolver.java
package br.brasfoot.compiler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Resolve nacionalidade do jogador:
 *  - extrai SEMPRE a primeira nacionalidade do JSON (string/object/array)
 *  - mapeia para countryId via Mappings
 *  - aplica fallback: countryIdOverride -> time.vid/aid -> 0
 */
public final class NationalityResolver {

  private NationalityResolver() {}

  public static final class Nat {
    public final String rawName;
    public final int countryId;
    public final boolean usedFallback;

    public Nat(String rawName, int countryId, boolean usedFallback) {
      this.rawName = rawName;
      this.countryId = countryId;
      this.usedFallback = usedFallback;
    }
  }

  public static Nat resolveNationality(JsonObject playerJson, Object time, Integer countryIdOverride) {
    String natName = readFirstNationalityName(playerJson);
    Integer mapped = resolveCountryId(natName);

    boolean usedFallback = false;
    Integer countryId = mapped;

    if (countryId == null) {
      usedFallback = true;

      if (countryIdOverride != null) {
        countryId = countryIdOverride;
      } else if (time != null) {
        Object vid = getAnyField(time, "vid", "aid");
        if (vid instanceof Number) countryId = ((Number) vid).intValue();
      }
    }

    if (countryId == null) countryId = 0;
    return new Nat(natName, countryId, usedFallback);
  }

  // -------------------------
  // JSON extraction: SEMPRE primeira
  // -------------------------

  private static String readFirstNationalityName(JsonObject pj) {
    if (pj == null) return null;
    if (!pj.has("nationality") || pj.get("nationality").isJsonNull()) return null;

    JsonElement natEl = pj.get("nationality");

    // nationality: "Brasil"
    if (natEl.isJsonPrimitive()) {
      try { return natEl.getAsString().trim(); } catch (Exception ignored) {}
      return null;
    }

    // nationality: { name: "Brasil" } (ou label/raw)
    if (natEl.isJsonObject()) {
      String s = getString(natEl.getAsJsonObject(), "name");
      if (s != null && !s.isBlank()) return s;

      s = getString(natEl.getAsJsonObject(), "label");
      if (s != null && !s.isBlank()) return s;

      s = getString(natEl.getAsJsonObject(), "raw");
      if (s != null && !s.isBlank()) return s;

      return null;
    }

    // nationality: [ {name:"Brasil"}, {name:"Itália"} ]  OU  [ "Brasil", "Itália" ]
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
        String s = getString(first.getAsJsonObject(), "name");
        if (s != null && !s.isBlank()) return s;

        s = getString(first.getAsJsonObject(), "label");
        if (s != null && !s.isBlank()) return s;

        s = getString(first.getAsJsonObject(), "raw");
        if (s != null && !s.isBlank()) return s;
      }
    }

    return null;
  }

  // -------------------------
  // Mapping: Mappings + normalizações
  // -------------------------

  private static Integer resolveCountryId(String natName) {
    if (natName == null || natName.isBlank()) return null;

    Integer id = Mappings.get().countryIdByNameOrAlias(natName);
    if (id != null) return id;

    String noAcc = deaccent(natName);
    if (!noAcc.equals(natName)) {
      id = Mappings.get().countryIdByNameOrAlias(noAcc);
      if (id != null) return id;
    }

    // aliases em inglês
    String lowered = natName.trim().toLowerCase(Locale.ROOT);
    if (lowered.equals("brazil")) return Mappings.get().countryIdByNameOrAlias("Brasil");
    if (lowered.equals("uruguay")) return Mappings.get().countryIdByNameOrAlias("Uruguai");
    if (lowered.equals("paraguay")) return Mappings.get().countryIdByNameOrAlias("Paraguai");
    if (lowered.equals("argentina")) return Mappings.get().countryIdByNameOrAlias("Argentina");

    return null;
  }

  // -------------------------
  // Reflection helper local (sem depender do BanCompiler)
  // -------------------------

  private static Object getAnyField(Object target, String... candidateNames) {
    for (String name : candidateNames) {
      try {
        var f = findField(target.getClass(), name);
        if (f == null) continue;
        f.setAccessible(true);
        return f.get(target);
      } catch (Exception ignored) {}
    }
    return null;
  }

  private static java.lang.reflect.Field findField(Class<?> cls, String name) {
    Class<?> cur = cls;
    while (cur != null && cur != Object.class) {
      try {
        return cur.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        cur = cur.getSuperclass();
      }
    }
    return null;
  }

  // -------------------------
  // Small helpers
  // -------------------------

  private static String getString(JsonObject obj, String key) {
    if (obj == null || key == null) return null;
    if (!obj.has(key) || obj.get(key) == null || obj.get(key).isJsonNull()) return null;
    try { return obj.get(key).getAsString(); } catch (Exception ignored) { return null; }
  }

  private static String deaccent(String s) {
    if (s == null) return null;
    String n = Normalizer.normalize(s, Normalizer.Form.NFD);
    return n.replaceAll("\\p{M}+", "");
  }
}