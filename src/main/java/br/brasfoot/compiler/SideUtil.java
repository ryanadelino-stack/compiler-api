package br.brasfoot.compiler;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;

public final class SideUtil {
  private SideUtil() {}

  /**
   * Retorna 0/1 conforme nossa convenção interna:
   * 0 = Direito, 1 = Esquerdo.
   *
   * Observação: A escrita no objeto Brasfoot será tratada no BanCompiler setando múltiplos aliases.
   */
  public static int resolveSideForBrasfoot(
      String footText,
      String primaryPosText,
      ArrayList<String> secondaryPositions,
      String playerName
  ) {
    int footStrict = parseFootStrict(footText);
    if (footStrict == 0 || footStrict == 1) return footStrict;

    boolean isAmbi = (footStrict == 2) || isAmbidextrous(footText);

    int byPrimary = parseBrasfootSideFromText(primaryPosText);

    int bySecondary = -1;
    if (secondaryPositions != null) {
      for (String s : secondaryPositions) {
        int t = parseBrasfootSideFromText(s);
        if (t == 0 || t == 1) { bySecondary = t; break; }
      }
    }

    if (isAmbi) {
      int by = (byPrimary != -1) ? byPrimary : bySecondary;
      if (by == 0 || by == 1) return by;

      String seed = String.valueOf(playerName) + "|" + String.valueOf(primaryPosText);
      int h = seed.hashCode();
      return (h & 1) == 0 ? 0 : 1;
    }

    if (byPrimary == 0 || byPrimary == 1) return byPrimary;
    if (bySecondary == 0 || bySecondary == 1) return bySecondary;

    return 0; // default direito
  }

  /** 0=destro, 1=canhoto, 2=ambidestro, -1=desconhecido */
  private static int parseFootStrict(String foot) {
    if (foot == null) return -1;
    String s = deaccent(foot).trim().toLowerCase(Locale.ROOT);
    if (s.isBlank()) return -1;

    if (s.contains("ambi") || s.contains("both") || s.contains("two-foot") || s.contains("two foot")) return 2;

    if (s.equals("d") || s.equals("dir") || s.equals("direito") || s.equals("destro")
        || s.equals("right") || s.equals("r")) return 0;

    if (s.equals("e") || s.equals("esq") || s.equals("esquerdo") || s.equals("canhoto")
        || s.equals("left") || s.equals("l")) return 1;

    if (s.contains("r/") || s.contains("d/")) return 0;
    if (s.contains("l/") || s.contains("e/")) return 1;

    return -1;
  }

  private static boolean isAmbidextrous(String foot) {
    if (foot == null) return false;
    String s = deaccent(foot).trim().toLowerCase(Locale.ROOT);
    return s.contains("ambi") || s.contains("both") || s.contains("two-foot") || s.contains("two foot");
  }

  /**
   * 0 direito, 1 esquerdo, -1 desconhecido.
   * Regras de abreviação seletivas (não usa MD/ME).
   */
  private static int parseBrasfootSideFromText(String any) {
    if (any == null) return -1;

    String s = deaccent(any).trim().toLowerCase(Locale.ROOT);
    if (s.isBlank()) return -1;

    if (s.contains("direit") || s.equals("r") || s.equals("right") || s.equals("destro") || s.contains("rechts")) return 0;
    if (s.contains("esquerd") || s.equals("l") || s.equals("left") || s.equals("canhoto") || s.contains("links")) return 1;

    if (s.contains("lateral dir") || s.contains("lateral direit")) return 0;
    if (s.contains("lateral esq") || s.contains("lateral esquerd")) return 1;

    if (s.contains("ponta dir") || s.contains("ponta direit")) return 0;
    if (s.contains("ponta esq") || s.contains("ponta esquerd")) return 1;

    if (s.contains("meia dir") || s.contains("meia direit")) return 0;
    if (s.contains("meia esq") || s.contains("meia esquerd")) return 1;

    if (s.contains("ala dir") || s.contains("ala direit")) return 0;
    if (s.contains("ala esq") || s.contains("ala esquerd")) return 1;

    if (s.matches(".*\\bld\\b.*") || s.matches(".*\\brb\\b.*") || s.matches(".*\\brw\\b.*") || s.matches(".*\\brm\\b.*") || s.matches(".*\\brwb\\b.*")) return 0;
    if (s.matches(".*\\ble\\b.*") || s.matches(".*\\blb\\b.*") || s.matches(".*\\blw\\b.*") || s.matches(".*\\blm\\b.*") || s.matches(".*\\blwb\\b.*")) return 1;

    if (s.matches(".*\\bpd\\b.*")) return 0;
    if (s.matches(".*\\bpe\\b.*")) return 1;

    return -1;
  }

  private static String deaccent(String s) {
    if (s == null) return null;
    String n = Normalizer.normalize(s, Normalizer.Form.NFD);
    return n.replaceAll("\\p{M}+", "");
  }
}