package br.brasfoot.compiler;

final class PositionCodes {
  final int group;
  final int pos;
  final int side;

  private PositionCodes(int group, int pos, int side) {
    this.group = group;
    this.pos = pos;
    this.side = side;
  }

  static PositionCodes from(String label) {
    String t = label == null ? "" : label.toLowerCase();

    // Valores padrao (central)
    int side = 5;

    if (t.contains("gole")) {
      return new PositionCodes(0, 2, 0);
    }

    // Laterais
    if (t.contains("lateral")) {
      // heuristica: se mencionar esquerdo/direito
      if (t.contains("esq")) side = 10;
      else if (t.contains("dir")) side = 13;
      else side = 10; // default lateral -> esquerda
      return new PositionCodes(1, 6, side);
    }

    if (t.contains("zague")) {
      return new PositionCodes(2, 7, 5);
    }

    if (t.contains("volan")) {
      return new PositionCodes(3, 10, 11);
    }

    if (t.contains("meia")) {
      return new PositionCodes(3, 4, 11);
    }

    if (t.contains("ponta")) {
      return new PositionCodes(4, 9, 8);
    }

    if (t.contains("centro")) {
      return new PositionCodes(4, 8, 5);
    }

    if (t.contains("atac")) {
      return new PositionCodes(4, 9, 5);
    }

    // fallback: meia
    return new PositionCodes(3, 4, 11);
  }
}
