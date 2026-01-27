package br.brasfoot.compiler;

import java.text.Normalizer;
import java.util.Locale;

public final class TextUtil {

  private TextUtil() {}

  public static String deaccent(String s) {
    if (s == null) return null;
    String n = Normalizer.normalize(s, Normalizer.Form.NFD);
    return n.replaceAll("\\p{M}+", "");
  }

  public static String safe(String s) {
    return s == null ? "null" : s;
  }

  public static String firstNonBlank(String... s) {
    if (s == null) return null;
    for (String v : s) {
      if (v != null && !v.isBlank()) return v;
    }
    return null;
  }

  public static String lowerDeaccent(String s) {
    if (s == null) return "";
    return deaccent(s).trim().toLowerCase(Locale.ROOT);
  }
}