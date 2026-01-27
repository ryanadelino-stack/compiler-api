package br.brasfoot.compiler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Locale;

public final class PositionUtil {

  private PositionUtil() {}

  public static final class PosInfo {
    public final String raw;
    public final String sideHint;

    public PosInfo(String raw, String sideHint) {
      this.raw = raw;
      this.sideHint = sideHint;
    }

    public String bestText() {
      String t = TextUtil.safe(raw);
      if (!t.isBlank()) return t;
      return "Atacante";
    }
  }

  /**
   * Estrutura real do JSON (palmeiras.json):
   * "position": { "primary":"Goleiro", "secondary":[...] }
   */
  public static PosInfo readPosInfo(JsonObject player) {
    String primary = null;

    JsonObject pos = asObj(player.get("position"));
    if (pos != null) {
      primary = getString(pos, "primary");
    }

    if (primary == null || primary.isBlank()) {
      // fallback
      primary = getString(player, "positionText");
    }

    String sideHint = inferSideHint(primary);
    return new PosInfo(primary, sideHint);
  }

  public static ArrayList<String> readSecondaryPositionsAsText(JsonObject player) {
    ArrayList<String> out = new ArrayList<>();
    JsonObject pos = asObj(player.get("position"));
    if (pos == null) return out;

    JsonArray sec = asArr(pos.get("secondary"));
    if (sec == null) return out;

    for (JsonElement e : sec) {
      if (e == null || e.isJsonNull()) continue;
      try {
        String s = e.getAsString();
        if (s != null && !s.isBlank()) out.add(s.trim());
      } catch (Exception ignored) {}
    }
    return out;
  }

  /**
   * IMPORTANTE:
   * Ajustado para o código REAL que o Brasfoot espera (pela inversão observada no jogo):
   * 0=Goleiro, 1=Lateral, 2=Zagueiro, 3=Meia, 4=Atacante
   */
  public static int mapPositionFromMapping(String posText) {
    String t = normalize(posText);

    // GOLEIRO
    if (t.contains("gole")) return 0;

    // DEFESA
    if (t.contains("zague")) return 2;

    // LATERAIS
    // cobre "lateral dir", "lateral esq", "ala", etc.
    if (t.contains("lateral") || t.contains("ala")) return 1;

    // MEIO CAMPO
    if (t.contains("volante")) return 3;
    if (t.contains("meia")) return 3;

    // ATAQUE
    if (t.contains("ponta")) return 4;
    if (t.contains("centroavante")) return 4;
    if (t.contains("seg") && t.contains("atac")) return 4;
    if (t.contains("atac")) return 4;

    // fallback
    return 4;
  }

  // -----------------
  // Helpers
  // -----------------

  private static String inferSideHint(String pos) {
    String t = normalize(pos);
    if (t.contains("esq")) return "L";
    if (t.contains("dir")) return "R";
    return null;
  }

  private static String normalize(String s) {
    if (s == null) return "";
    return s.toLowerCase(Locale.ROOT);
  }

  private static JsonObject asObj(JsonElement el) {
    if (el == null || el.isJsonNull()) return null;
    return el.isJsonObject() ? el.getAsJsonObject() : null;
  }

  private static JsonArray asArr(JsonElement el) {
    if (el == null || el.isJsonNull()) return null;
    return el.isJsonArray() ? el.getAsJsonArray() : null;
  }

  private static String getString(JsonObject obj, String key) {
    try {
      JsonElement el = obj.get(key);
      if (el == null || el.isJsonNull()) return null;
      return el.getAsString();
    } catch (Exception ignored) {
      return null;
    }
  }
}