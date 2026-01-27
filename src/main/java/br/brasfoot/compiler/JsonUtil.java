package br.brasfoot.compiler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class JsonUtil {

  private JsonUtil() {}

  public static JsonElement dig(JsonObject obj, String... path) {
    if (obj == null) return null;
    JsonElement cur = obj;
    for (String key : path) {
      if (cur == null || !cur.isJsonObject()) return null;
      JsonObject jo = cur.getAsJsonObject();
      if (!jo.has(key)) return null;
      cur = jo.get(key);
    }
    return cur;
  }

  public static String getString(JsonObject obj, String... path) {
    JsonElement el = dig(obj, path);
    if (el == null || el.isJsonNull()) return null;
    try { return el.getAsString(); } catch (Exception ignored) { return null; }
  }

  public static Integer getInt(JsonObject obj, String... path) {
    JsonElement el = dig(obj, path);
    if (el == null || el.isJsonNull()) return null;
    try { return el.getAsInt(); } catch (Exception ignored) { return null; }
  }

  public static int getIntOrZero(JsonObject obj, String... path) {
    Integer v = getInt(obj, path);
    return v == null ? 0 : v;
  }

  public static JsonArray getArray(JsonObject obj, String... path) {
    JsonElement el = dig(obj, path);
    if (el == null || el.isJsonNull()) return null;
    try { return el.getAsJsonArray(); } catch (Exception ignored) { return null; }
  }
}