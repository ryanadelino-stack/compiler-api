package br.brasfoot.compiler;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;

public final class CharacteristicsUtil {
  private CharacteristicsUtil() {}

  // IDs conforme mapping.json:
  // 0 Colocação, 1 Defesa Penalty, 2 Reflexo, 3 Saída gol,
  // 4 Armação, 5 Cabeceio, 6 Cruzamento, 7 Desarme, 8 Drible,
  // 9 Finalização, 10 Marcação, 11 Passe, 12 Resistência, 13 Velocidade
  public static final int COL = 0;
  public static final int DPE = 1;
  public static final int REF = 2;
  public static final int SGO = 3;

  public static final int ARM = 4;
  public static final int CAB = 5;
  public static final int CRU = 6;
  public static final int DES = 7;
  public static final int DRI = 8;
  public static final int FIN = 9;
  public static final int MAR = 10;
  public static final int PAS = 11;
  public static final int RES = 12;
  public static final int VEL = 13;

  public static int[] pickTop2CharacteristicsByManual(
      int pos,                       // 0..4
      String posText,                // texto (ex: "Ponta Direita", "Meia Ofensivo", "Volante")
      ArrayList<String> secondary,   // secundárias (texto)
      int apps,
      int goals,
      int assists,
      int yellows,
      int reds,
      int cleanSheets,
      int goalsConceded,
      int age,
      double heightM
  ) {
    // Fórmulas
    double gJ = ratio(goals, apps);
    double aJ = ratio(assists, apps);
    double caJ = ratio(yellows, apps); // manual usa só amarelos
    double jsgJ = ratio(cleanSheets, apps);
    double gsJ = ratio(goalsConceded, apps);

    String p = deaccent(posText == null ? "" : posText).toLowerCase(Locale.ROOT);

    // -------------------------
    // GOLEIRO
    // -------------------------
    if (pos == 0) {
      if (age >= 30 && apps > 100 && jsgJ > 0.3) return pair(REF, DPE);
      if (heightM >= 1.95 && jsgJ > 0.4) return pair(SGO, REF);
      if (jsgJ > 0.4 && gsJ < 1.0 && heightM > 0 && heightM < 1.85) return pair(REF, COL);
      if (jsgJ > 0.4 && gsJ < 1.0 && heightM >= 1.85) return pair(COL, REF);
      return pair(REF, COL);
    }

    boolean secHasZagueiro = hasAnySecondary(secondary, "zague", "def", "cb");
    boolean secHasMeia = hasAnySecondary(secondary, "meia", "mid", "volante", "arm");
    boolean secHasPontaOuAta = hasAnySecondary(secondary, "ponta", "wing", "atac", "centroav", "st", "fw", "seg. atacante", "seg atacante");

    // -------------------------
    // LATERAL
    // -------------------------
    if (pos == 1) {
      boolean lateralDef = secHasZagueiro;
      boolean lateralOf = (secHasMeia || secHasPontaOuAta);

      if (!lateralDef && !lateralOf) {
        if (p.contains("ala")) lateralOf = true;
      }

      if (lateralDef) {
        if (gJ > 0.1 && caJ > 0.2) return pair(MAR, FIN);
        if (aJ > 0.1) return pair(MAR, CRU);
        if (age <= 25 && aJ > 0.05) return pair(MAR, VEL);
        return pair(MAR, CRU);
      } else {
        if (aJ > 0.15 && gJ < 0.1) return pair(CRU, PAS);
        if (gJ > 0.1 && aJ > 0.1) return pair(CRU, FIN);
        if (age <= 25 && aJ > 0.1) return pair(CRU, VEL);
        return pair(CRU, VEL);
      }
    }

    // -------------------------
    // ZAGUEIRO
    // -------------------------
    if (pos == 2) {
      boolean zagueiroOf = ((goals + assists) > 10) || (gJ > 0.05);

      if (zagueiroOf) {
        if (gJ > 0.05 && heightM > 1.85) return pair(DES, CAB);
        if (gJ > 0.03 && aJ > 0.03) return pair(MAR, VEL);
        if (gJ > 0.02 && heightM > 1.80) return pair(MAR, CAB);
        return pair(MAR, VEL);
      } else {
        if (caJ > 0.3) return pair(DES, MAR);
        if (aJ > 0.05) return pair(DES, PAS);
        if (age > 28 && apps > 100) return pair(DES, RES);
        return pair(DES, MAR);
      }
    }

    // -------------------------
    // MEIO-CAMPO
    // -------------------------
    if (pos == 3) {
      boolean isVolante = p.contains("volante");
      boolean isMeiaCentral = p.contains("meia central");
      boolean isMeiaOf = p.contains("meia ofens");

      if (!isVolante && !isMeiaCentral && !isMeiaOf) {
        if (p.contains("defens")) isVolante = true;
        if (p.contains("ofens")) isMeiaOf = true;
        if (p.contains("meia")) isMeiaCentral = true;
      }

      if (isVolante) {
        if (caJ > 0.2 && aJ > 0.1) return pair(DES, PAS);
        if (caJ > 0.3 && age > 25) return pair(MAR, RES);
        if (caJ > 0.25 && gJ < 0.05) return pair(DES, MAR);
        if (gJ > 0.1 && caJ > 0.2) return pair(MAR, FIN);
        if (gJ > 0.05 && aJ > 0.05) return pair(DES, FIN);
        if (age <= 25 && caJ > 0.2) return pair(DES, VEL);
        return pair(DES, PAS);
      }

      if (isMeiaCentral) {
        if (aJ > 0.15) return pair(ARM, PAS);
        if (age <= 25 && aJ > 0.1) return pair(ARM, VEL);
        return pair(ARM, PAS);
      }

      if (gJ > 0.15 && aJ > 0.1) return pair(FIN, PAS);
      if (gJ > 0.2) return pair(FIN, ARM);
      if (aJ > 0.2) return pair(DRI, PAS);
      if (gJ > 0.1 && age <= 25) return pair(DRI, FIN);
      if (gJ > 0.1 && aJ < 0.1) return pair(FIN, DRI);
      return pair(FIN, PAS);
    }

    // -------------------------
    // ATACANTES
    // -------------------------
    if (pos == 4) {
      boolean isCentro = p.contains("centroav") || p.contains("st") || p.contains("centre") || p.contains("center");
      boolean isPonta = p.contains("ponta") || p.contains("wing") || p.contains("rw") || p.contains("lw");
      boolean secSegAtac = hasAnySecondary(secondary, "seg. atacante", "seg atacante", "second striker", "ss");

      if (secSegAtac) {
        if (aJ > 0.15) return pair(DRI, PAS);
        if (gJ > 0.2) return pair(DRI, FIN);
        return pair(DRI, PAS);
      }

      if (isCentro) {
        if (gJ > 0.3 && heightM > 1.80) return pair(FIN, CAB);
        if (gJ > 0.2 && aJ > 0.1) return pair(FIN, PAS);
        if (gJ > 0.15 && age <= 25) return pair(FIN, DRI);
        if (caJ < 0.1 && age > 28) return pair(FIN, RES);
        return pair(FIN, PAS);
      }

      if (isPonta) {
        if (gJ > 0.2 && age <= 25) return pair(VEL, FIN);
        if (gJ > 0.2 && age > 25) return pair(FIN, VEL);
        if (aJ > 0.2) return pair(VEL, DRI);
        if (gJ > 0.15 && aJ < 0.1) return pair(FIN, DRI);
        return pair(VEL, FIN);
      }

      return pair(FIN, PAS);
    }

    return pair(FIN, PAS);
  }

  private static boolean hasAnySecondary(ArrayList<String> secondary, String... needles) {
    if (secondary == null || secondary.isEmpty()) return false;
    for (String s : secondary) {
      String t = deaccent(s).toLowerCase(Locale.ROOT);
      for (String n : needles) {
        if (t.contains(deaccent(n).toLowerCase(Locale.ROOT))) return true;
      }
    }
    return false;
  }

  private static int[] pair(int a, int b) {
    if (a == b) {
      b = (a == REF) ? COL : REF;
    }
    return new int[]{a, b};
  }

  private static double ratio(int num, int den) {
    if (den <= 0) return 0.0;
    return (double) num / (double) den;
  }

  private static String deaccent(String s) {
    if (s == null) return null;
    String n = Normalizer.normalize(s, Normalizer.Form.NFD);
    return n.replaceAll("\\p{M}+", "");
  }
}