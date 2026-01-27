package e;

import java.io.Serializable;

/**
 * Jogador (classe original do Brasfoot: e.g)
 *
 * IMPORTANTE:
 * - Mantemos o nome do pacote/classe e os campos (nomes + tipos) compatíveis com o stream de serialização.
 * - O serialVersionUID foi extraído diretamente de um .ban funcional.
 */
public class g implements Serializable {
  private static final long serialVersionUID = 16L;

  // Campos serializados (nomes e tipos conforme a classe original)
  public String a;      // nome
  public int aid;       // (desconhecido; mantido)
  public boolean b;     // (desconhecido)
  public int c;         // atributo/skill (heurístico)
  public int d;         // idade
  public int e;         // grupo de posição (0 GK, 1 DEF-LAT, 2 DEF-ZAG, 3 MID, 4 ATT)
  public int f;         // flag extra (ex.: estrela/top) – heurístico
  public int g;         // posição detalhada (código interno)
  public int h;         // lado/função (código interno)
  public int hash;      // código interno (usado aqui para pé dominante)
  public int i;         // característica (código interno)
  public boolean j;     // top mundial (heurístico)
  public int sid;       // (desconhecido)
  public int tid;       // (desconhecido)
}
