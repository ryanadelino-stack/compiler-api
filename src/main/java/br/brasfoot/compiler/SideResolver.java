package br.brasfoot.compiler;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Locale;

public final class SideResolver {

  private SideResolver() {}

  /**
   * Retorna lado no padrão Brasfoot:
   * 0 = Direito
   * 1 = Esquerdo
   */
  public static int resolveSideForBrasfoot(
      String foot,
      String sideHint,
      String posText,
      String playerName,
      JsonObject playerJson,
      ArrayList<String> secondaryPositions
  ) {
    // 1) sideHint explícito do PositionUtil (L/R)
    if (sideHint != null) {
      String h = sideHint.trim().toUpperCase(Locale.ROOT);
      if ("L".equals(h)) return 1;
      if ("R".equals(h)) return 0;
    }

    // 2) posText com “Esq/Dir”
    String p = normalize(posText);
    if (p.contains("esq")) return 1;
    if (p.contains("dir")) return 0;

    // 3) secundárias (quando a posição principal é “central” mas secundária indica lado)
    if (secondaryPositions != null) {
      for (String s : secondaryPositions) {
        String t = normalize(s);
        if (t.contains("esq")) return 1;
        if (t.contains("dir")) return 0;
      }
    }

    // 4) pé dominante
    String f = normalize(foot);
    if (f.contains("esq") || f.contains("left")) return 1;
    if (f.contains("dir") || f.contains("right")) return 0;

    // 5) default
    return 0;
  }

  private static String normalize(String s) {
    if (s == null) return "";
    return s.toLowerCase(Locale.ROOT).trim();
  }
}