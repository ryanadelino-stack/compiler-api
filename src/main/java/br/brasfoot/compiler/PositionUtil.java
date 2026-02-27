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
   * Mapeia o posText para o número de posição do Brasfoot:
   *   0 = Goleiro
   *   1 = Lateral
   *   2 = Zagueiro
   *   3 = Meia
   *   4 = Atacante
   *
   * ATENÇÃO: a verificação de posições GENÉRICAS do Transfermarkt
   * ("Defensor", "Meio-Campo", "Forward", etc.) deve ocorrer ANTES
   * do fallback final, para evitar que um Defensor seja classificado
   * como Atacante dentro do Brasfoot.
   */
  public static int mapPositionFromMapping(String posText) {
    String t = normalize(posText);

    // ── GOLEIRO ──────────────────────────────────────────────────────────────
    if (t.contains("gole") || t.contains("goalkeeper") || t.contains("torwart")) return 0;

    // ── ZAGUEIRO (subposição específica) ─────────────────────────────────────
    if (t.contains("zague")
        || t.contains("innenverteidiger")
        || t.equals("cb")) return 2;

    // ── LATERAL (subposição específica) ──────────────────────────────────────
    // Cobre "lateral dir", "lateral esq", "ala", "outside back", etc.
    if (t.contains("lateral")
        || t.contains("ala")
        || t.contains("außenverteidiger")
        || t.contains("wing back")) return 1;

    // ── MEIO-CAMPO (subposições específicas) ─────────────────────────────────
    if (t.contains("volante")) return 3;
    if (t.contains("meia"))    return 3;

    // ── ATAQUE (subposições específicas) ─────────────────────────────────────
    if (t.contains("ponta"))         return 4;
    if (t.contains("centroavante"))  return 4;
    if (t.contains("mittelstürmer")) return 4;
    if (t.contains("centre-forward")) return 4;
    if (t.contains("seg") && t.contains("atac")) return 4;
    if (t.contains("atac"))          return 4;

    // =========================================================================
    // Posições GENÉRICAS do Transfermarkt (sem subposição especificada).
    //
    // Essas verificações DEVEM vir antes do fallback final (return 4) para
    // evitar que "Defensor" e "Meio-Campo" sejam tratados como Atacantes.
    //
    // Critério de mapeamento para o Brasfoot:
    //   "Defensor" / "Defender"   → 2 (Zagueiro) — default conservador para defesa
    //   "Meio-Campo" / "Midfield" → 3 (Meia)
    //   "Atacante" / "Forward"    → 4 (Atacante)  — já seria coberto pelo fallback,
    //                                                mas deixamos explícito
    // =========================================================================

    // Defensor genérico
    // Tokens: "defensor", "defensores", "defender", "defenders", "abwehr", "verteidiger"
    // Rejeita compostos já tratados acima ("innenverteidiger", "außenverteidiger")
    if (t.equals("defensor")
        || t.equals("defensores")
        || t.equals("defender")
        || t.equals("defenders")
        || t.equals("abwehr")
        || t.equals("verteidiger")) return 2;

    // Meio-campo genérico
    // Tokens: "meio-campo", "meio campo", "midfield", "mittelfeld"
    // Nota: "meio-campo" NÃO contém "meia", então sem esta verificação cai no fallback 4!
    if (t.equals("meio-campo")
        || t.equals("meio campo")
        || t.equals("midfield")
        || t.equals("mittelfeld")) return 3;

    // Atacante genérico — explícito para clareza
    // Tokens: "forward", "forwards", "stürmer", "sturmer"
    if (t.equals("forward")
        || t.equals("forwards")
        || t.equals("stürmer")
        || t.equals("sturmer")) return 4;

    // ── Fallback final ───────────────────────────────────────────────────────
    // Qualquer texto não reconhecido. Mantido como 4 por compatibilidade histórica,
    // mas idealmente nunca deve ser atingido para posições válidas do Transfermarkt.
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
