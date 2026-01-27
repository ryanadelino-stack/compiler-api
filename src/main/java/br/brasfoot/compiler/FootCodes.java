package br.brasfoot.compiler;

final class FootCodes {
  private FootCodes() {}

  static int from(String foot) {
    String f = foot == null ? "" : foot.toLowerCase();
    if (f.contains("esq") || f.contains("left")) return 4;
    if (f.contains("ambi") || f.contains("both")) return 10;
    if (f.contains("dir") || f.contains("right")) return 2;
    return 2;
  }
}
