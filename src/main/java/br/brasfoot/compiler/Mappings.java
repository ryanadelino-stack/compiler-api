package br.brasfoot.compiler;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * mapping.json esperado (como você está usando agora):
 * {
 *   "posicoes": [{ "id": 0, "aliases": ["goleiro","gol","gk"] }, ...],
 *   "lados": [{ "id": 0, "aliases": ["direito","dir","right","r"] }, ...],
 *   "caracteristicas": [{ "id": 0, "aliases": ["colocação"] }, ...],
 *   "paises": [{ "id": 29, "nome": "Brasil" }, ...]
 * }
 *
 * Resolve por alias/nome com normalização (lowercase + trim + remove acentos).
 */
public final class Mappings {

  private static final String RESOURCE = "/mapping.json";
  private static final Gson GSON = new Gson();

  private static volatile Mappings INSTANCE;

  private final Map<String, Integer> positions = new HashMap<>();
  private final Map<String, Integer> sides = new HashMap<>();
  private final Map<String, Integer> characteristics = new HashMap<>();
  private final Map<String, Integer> countries = new HashMap<>();

  private Mappings() {
    load();
  }

  public static Mappings get() {
    Mappings v = INSTANCE;
    if (v == null) {
      synchronized (Mappings.class) {
        v = INSTANCE;
        if (v == null) {
          v = new Mappings();
          INSTANCE = v;
        }
      }
    }
    return v;
  }

  // ===== API esperada pelo BanCompiler =====

  public Integer positionIdByNameOrAlias(String s) {
    return lookup(positions, s);
  }

  public Integer sideIdByNameOrAlias(String s) {
    return lookup(sides, s);
  }

  public Integer characteristicIdByNameOrAlias(String s) {
    return lookup(characteristics, s);
  }

  public Integer countryIdByNameOrAlias(String s) {
    return lookup(countries, s);
  }

  // -------------------------
  // Load
  // -------------------------

  private void load() {
    JsonObject root = readResourceJson(RESOURCE);

    // Esses 3 grupos agora são baseados SÓ em aliases
    loadAliasOnlyGroup(root, "posicoes", positions);
    loadAliasOnlyGroup(root, "lados", sides);
    loadAliasOnlyGroup(root, "caracteristicas", characteristics);

    // Países: baseado em nome (e opcionalmente aliases se você adicionar no futuro)
    loadCountries(root, countries);
  }

  private void loadAliasOnlyGroup(JsonObject root, String key, Map<String, Integer> out) {
    JsonArray arr = optArray(root, key);
    if (arr == null) return;

    for (JsonElement el : arr) {
      if (!el.isJsonObject()) continue;
      JsonObject o = el.getAsJsonObject();

      Integer id = optInt(o, "id");
      if (id == null) continue;

      JsonArray aliases = optArray(o, "aliases");
      if (aliases != null) {
        for (JsonElement a : aliases) {
          String alias = optString(a);
          if (alias == null || alias.isBlank()) continue;
          putAllKeys(out, alias, id);
        }
      }
    }
  }

  private void loadCountries(JsonObject root, Map<String, Integer> out) {
    JsonArray arr = optArray(root, "paises");
    if (arr == null) return;

    for (JsonElement el : arr) {
      if (!el.isJsonObject()) continue;
      JsonObject o = el.getAsJsonObject();

      Integer id = optInt(o, "id");
      String nome = optString(o, "nome");
      if (id == null || nome == null || nome.isBlank()) continue;

      putAllKeys(out, nome, id);

      // opcional: se no futuro você quiser aliases para países, já suportamos:
      JsonArray aliases = optArray(o, "aliases");
      if (aliases != null) {
        for (JsonElement a : aliases) {
          String alias = optString(a);
          if (alias == null || alias.isBlank()) continue;
          putAllKeys(out, alias, id);
        }
      }
    }
  }

  // -------------------------
  // Lookup / normalization
  // -------------------------

  private static Integer lookup(Map<String, Integer> dict, String s) {
    if (s == null) return null;
    String k = norm(s);
    if (k.isEmpty()) return null;

    Integer v = dict.get(k);
    if (v != null) return v;

    // fallback: remove acentos
    String noAcc = deaccent(k);
    if (!noAcc.equals(k)) {
      v = dict.get(noAcc);
      if (v != null) return v;
    }

    return null;
  }

  private static void putAllKeys(Map<String, Integer> dict, String rawKey, Integer id) {
    if (rawKey == null) return;

    String k1 = norm(rawKey);
    if (!k1.isEmpty()) dict.putIfAbsent(k1, id);

    String k2 = deaccent(k1);
    if (!k2.isEmpty()) dict.putIfAbsent(k2, id);
  }

  private static String norm(String s) {
    if (s == null) return "";
    return s.trim().toLowerCase(Locale.ROOT);
  }

  private static String deaccent(String s) {
    if (s == null) return "";
    String n = Normalizer.normalize(s, Normalizer.Form.NFD);
    return n.replaceAll("\\p{M}+", "");
  }

  // -------------------------
  // JSON resource helpers
  // -------------------------

  private static JsonObject readResourceJson(String resourcePath) {
    try (InputStream in = Mappings.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("Resource nao encontrado no classpath: " + resourcePath);
      }
      byte[] bytes = in.readAllBytes();
      String raw = new String(bytes, StandardCharsets.UTF_8);

      JsonElement el = GSON.fromJson(raw, JsonElement.class);
      if (el == null || !el.isJsonObject()) {
        throw new IllegalStateException("mapping.json invalido: nao eh objeto JSON");
      }
      return el.getAsJsonObject();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Falha ao ler " + resourcePath + ": " + e.getMessage(), e);
    }
  }

  private static JsonArray optArray(JsonObject o, String k) {
    if (o == null || k == null || !o.has(k)) return null;
    try { return o.get(k).getAsJsonArray(); } catch (Exception ignored) { return null; }
  }

  private static Integer optInt(JsonObject o, String k) {
    if (o == null || k == null || !o.has(k)) return null;
    try { return o.get(k).getAsInt(); } catch (Exception ignored) { return null; }
  }

  private static String optString(JsonObject o, String k) {
    if (o == null || k == null || !o.has(k)) return null;
    try { return o.get(k).getAsString(); } catch (Exception ignored) { return null; }
  }

  private static String optString(JsonElement el) {
    if (el == null || el.isJsonNull()) return null;
    try { return el.getAsString(); } catch (Exception ignored) { return null; }
  }
}