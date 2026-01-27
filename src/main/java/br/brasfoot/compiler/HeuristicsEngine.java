package br.brasfoot.compiler;

import java.util.ArrayList;
import java.util.Locale;

public final class HeuristicsEngine {

  private HeuristicsEngine() {}

  // IDs das características (conforme seu log já indica coerência):
  // GK
  public static final int COL = 0;
  public static final int DPE = 1;
  public static final int REF = 2;
  public static final int SGO = 3;

  // Linha
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
      int posCode,
      String posText,
      ArrayList<String> secondaryPositions,

      // 15 itens de stats (linha e goleiro)
      int matchesRelated,
      int matchesPlayed,
      int goals,
      int assists,
      int ownGoals,
      int fromBench,
      int substituted,
      int yellow,
      int yellowRed,
      int red,
      int penaltyGoals,
      double minutesPerGoal,
      int minutesPlayed,
      int goalsConceded,
      int cleanSheets,

      Integer age,
      double heightM
  ) {
    int apps = Math.max(0, matchesPlayed);
    int a = (age == null ? 0 : age);

    double gJ = div(goals, apps);
    double aJ = div(assists, apps);
    double caJ = div(yellow, apps); // manual: CA/J = amarelos / jogos
    double gaJ = div(goals + assists, apps);
    double ratioGA = (aJ <= 0.000001) ? Double.POSITIVE_INFINITY : (gJ / aJ);

    String p = norm(posText);

    boolean isGk = p.contains("gole");
    if (isGk) {
      double jsgJ = div(cleanSheets, apps);
      double gsJ = div(goalsConceded, apps);

      // Regras goleiro (ordem de prioridade) – conforme documento
      if (a >= 30 && apps > 100 && jsgJ > 0.3) {
        return pair(REF, DPE);
      }
      if (heightM >= 1.95 && jsgJ > 0.4) {
        return pair(SGO, REF);
      }
      if (heightM > 1.90 && a <= 25) { // NOVO
        return pair(REF, DPE);
      }
      if (jsgJ > 0.4 && gsJ < 1.0) {
        return pair(REF, COL);
      }
      return pair(REF, COL);
    }

    // Linha – identificar grupos
    boolean isLateral = p.contains("lateral");
    boolean isZagueiro = p.contains("zague");
    boolean isVolante = p.contains("volante");
    boolean isMeiaOf = p.contains("meia ofens") || p.contains("meia ofen");
    boolean isMeiaCentral = p.contains("meia") && !isMeiaOf && !isVolante;
    boolean isSegAtac = p.contains("seg") && p.contains("atac");
    boolean isCentroavante = p.contains("centroavante");
    boolean isPonta = p.contains("ponta");

    // LATERAL
    if (isLateral) {
      boolean secDef = hasAnySecondary(secondaryPositions, "zague", "volante");
      boolean secOf = hasAnySecondary(secondaryPositions, "meia", "ponta");

      // Se não tiver pista clara, trate como ofensivo (tende a ser mais comum).
      boolean lateralDefensivo = secDef && !secOf;
      boolean lateralOfensivo = secOf && !secDef;
      if (!lateralDefensivo && !lateralOfensivo) {
        // desempate simples: se tiver assistências razoáveis, ofensivo, senão defensivo
        lateralOfensivo = (aJ >= 0.06);
        lateralDefensivo = !lateralOfensivo;
      }

      if (lateralDefensivo) {
        // Lateral Defensivo (secundária = Zagueiro/Volante)
        if (gJ > 0.1 && caJ > 0.2) return pair(MAR, CRU);
        if (aJ > 0.1) return pair(VEL, PAS);
        if (a <= 32 && aJ > 0.05) return pair(MAR, VEL);
        if (heightM > 1.80 && gaJ < 0.05) return pair(VEL, RES);
        return pair(MAR, CRU);
      } else {
        // Lateral Ofensivo (secundária = Meia/Ponta)
        if (aJ > 0.15 && gJ < 0.1) return pair(CRU, PAS);
        if (gJ > 0.1 && aJ > 0.1) return pair(CRU, FIN);
        if (a <= 25 && aJ > 0.1) return pair(VEL, CRU);
        if (ratioGA < 0.5) return pair(CRU, PAS);
        return pair(CRU, VEL);
      }
    }

    // ZAGUEIRO
    if (isZagueiro) {
      boolean zOff = ((goals + assists) > 10) || (gJ > 0.05);

      if (zOff) {
        if (gJ > 0.05 && heightM > 1.85) return pair(DES, CAB);
        if (gJ > 0.03 && aJ > 0.03) return pair(MAR, VEL);
        if (gJ > 0.02 && heightM > 1.80) return pair(MAR, CAB);
        return pair(MAR, VEL);
      } else {
        if (caJ > 0.3) return pair(DES, MAR);
        if (aJ > 0.05) return pair(MAR, PAS);
        if (a > 30 && apps > 100) return pair(DES, RES);
        return pair(MAR, DES);
      }
    }

    // VOLANTE
    if (isVolante) {
      if (caJ > 0.2 && aJ > 0.1) return pair(DES, PAS);
      if (caJ > 0.3 && a > 25) return pair(MAR, RES);
      if (caJ > 0.25 && gJ < 0.05) return pair(DES, MAR);
      if (gJ > 0.1 && caJ > 0.2) return pair(MAR, FIN);
      if (gJ > 0.05 && aJ > 0.05) return pair(DES, FIN);
      if (a <= 25 && caJ > 0.2) return pair(DES, VEL);

      // Fator híbrido
      if (hasAnySecondary(secondaryPositions, "lateral")) return pair(MAR, VEL);
      if (hasAnySecondary(secondaryPositions, "volante")) return pair(DES, MAR);
      return pair(DES, MAR);
    }

    // MEIA CENTRAL
    if (isMeiaCentral) {
      if (aJ > 0.15) return pair(ARM, PAS);
      if (a <= 25 && aJ > 0.1) return pair(PAS, VEL);
      if (caJ > 0.2 && aJ > 0.1) return pair(DES, PAS);
      return pair(DES, PAS);
    }

    // MEIA OFENSIVO
    if (isMeiaOf) {
      if (gJ > 0.2) return pair(ARM, FIN);
      if (aJ > 0.2) return pair(ARM, PAS);
      if (gJ > 0.1 && a <= 25) return pair(ARM, VEL);
      if (gJ > 0.1 && aJ < 0.1) return pair(PAS, FIN);
      return pair(PAS, FIN);
    }

    // ATACANTES
    if (isSegAtac) {
      if (aJ > 0.15) return pair(CAB, VEL);
      if (gJ > 0.2) return pair(FIN, PAS);
      return pair(FIN, DRI);
    }

    if (isCentroavante) {
      if (gJ > 0.3 && heightM > 1.80) return pair(FIN, CAB);
      if (gJ > 0.2 && aJ > 0.1) return pair(CAB, FIN);
      if (gJ > 0.15 && a <= 27) return pair(FIN, DRI);
      if (caJ < 0.1 && a > 32) return pair(FIN, RES);
      return pair(FIN, RES);
    }

    if (isPonta) {
      if (gJ > 0.2 && aJ > 0.1) return pair(FIN, DRI);
      if (gJ > 0.2 && a <= 25) return pair(VEL, FIN);
      if (gJ > 0.1 && a > 25) return pair(FIN, VEL);
      return pair(VEL, FIN);
    }

    // fallback geral
    return pair(DES, PAS);
  }

  // -----------------
  // Helpers
  // -----------------

  private static double div(int num, int den) {
    if (den <= 0) return 0.0;
    return (double) num / (double) den;
  }

  private static int[] pair(int a, int b) {
    return new int[] { a, b };
  }

  private static String norm(String s) {
    if (s == null) return "";
    return s.toLowerCase(Locale.ROOT);
  }

  private static boolean hasAnySecondary(ArrayList<String> sec, String... needles) {
    if (sec == null || sec.isEmpty()) return false;
    for (String s : sec) {
      String t = norm(s);
      for (String n : needles) {
        if (t.contains(n.toLowerCase(Locale.ROOT))) return true;
      }
    }
    return false;
  }
}