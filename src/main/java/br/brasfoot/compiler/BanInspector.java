package br.brasfoot.compiler;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public final class BanInspector {

  private BanInspector() {}

  public static void inspect(Path banFile) throws IOException {
    Object team = readBanObject(banFile);

    System.out.println("class=" + team.getClass());

    // Campos do time (tenta getters e depois fields)
    Object id    = getProp(team, "id");
    Object nome  = getProp(team, "nome");
    Object cor1  = getProp(team, "cor1");
    Object cor2  = getProp(team, "cor2");
    Object vid   = getProp(team, "vid");
    Object a     = getProp(team, "a");
    Object b     = getProp(team, "b");
    Object c     = getProp(team, "c");
    Object g     = getProp(team, "g");
    Object i     = getProp(team, "i");
    Object n     = getProp(team, "n");
    Object o     = getProp(team, "o");
    Object mark  = getProp(team, "mark");
    Object valid = getProp(team, "valid");

    System.out.printf(
        "team id=%s nome=%s cor1=%s cor2=%s vid=%s a=%s b=%s c=%s g=%s i=%s n=%s o=%s mark=%s valid=%s%n",
        s(id), s(nome), fmtColor(cor1), fmtColor(cor2), s(vid), s(a), s(b), s(c), s(g), s(i), s(n), s(o), s(mark), s(valid)
    );

    // 1) Descobre listas/arrays de e.g dentro do time (não assume nome de campo)
    List<ListCandidate> candidates = findPlayerCollections(team);

    if (candidates.isEmpty()) {
      System.out.println("players=0 (nenhuma Collection/array com e.g encontrada por reflexao)");
    } else {
      // Ordena por maior qtd de e.g, depois por maior tamanho total
      candidates.sort(Comparator
          .comparingInt(ListCandidate::egCount).reversed()
          .thenComparingInt(ListCandidate::totalCount).reversed()
      );

      // Imprime diagnóstico (útil para você entender qual campo é qual)
      for (ListCandidate cnd : candidates) {
        System.out.printf(
            "cand field=%s kind=%s size=%d eg=%d%n",
            cnd.fieldPath, cnd.kind, cnd.totalCount, cnd.egCount
        );
      }

      // 2) Usa a melhor como "players"
      ListCandidate best = candidates.get(0);
      System.out.println("players=" + best.egCount);

      // 3) Imprime até 60 jogadores
      int printed = 0;
      int idx = 1;
      for (Object p : best.elements) {
        if (p == null) continue;
        if (!isEg(p)) continue;

        // Ajuste principal: tenta também "a" (muito comum em e.g) como nome
        String name = readPlayerName(p);

        Object cP = firstNonNull(getProp(p, "c"), getProp(p, "abil"));
        Object dP = firstNonNull(getProp(p, "d"), getProp(p, "idade"));
        Object eP = firstNonNull(getProp(p, "e"), getProp(p, "posicao"));
        Object fP = firstNonNull(getProp(p, "f"), getProp(p, "lado"));
        Object gP = firstNonNull(getProp(p, "g"), getProp(p, "cr1"));
        Object hP = firstNonNull(getProp(p, "h"), getProp(p, "cr2"));
        Object iP = firstNonNull(getProp(p, "i"), getProp(p, "status"));
        Object hash = firstNonNull(getProp(p, "hash"), getProp(p, "k"));
        Object estrela = firstNonNull(getProp(p, "b"), getProp(p, "estrela"));
        Object top = firstNonNull(getProp(p, "j"), getProp(p, "topMundial"));

        System.out.printf(
            "%02d name=%s c=%s d=%s e=%s f=%s g=%s h=%s i=%s hash=%s estrela=%s top=%s%n",
            idx++, safe(name), s(cP), s(dP), s(eP), s(fP), s(gP), s(hP), s(iP), s(hash), s(estrela), s(top)
        );

        printed++;
        if (printed >= 60) break;
      }

      // 4) Heurística para "juniores": segunda melhor lista com e.g (se existir)
      ListCandidate juniors = null;
      if (candidates.size() >= 2 && candidates.get(1).egCount > 0) {
        juniors = candidates.get(1);
      }
      System.out.println("juniores=" + (juniors == null ? 0 : juniors.egCount));
    }
  }

  // ===== IO =====
  private static Object readBanObject(Path p) throws IOException {
    try (InputStream in = new BufferedInputStream(Files.newInputStream(p));
         ObjectInputStream ois = new ObjectInputStream(in)) {
      try {
        return ois.readObject();
      } catch (ClassNotFoundException e) {
        throw new IOException("Falha ao desserializar .ban (classe nao encontrada no classpath): " + e.getMessage(), e);
      }
    }
  }

  // ===== Descoberta de listas =====

  private static List<ListCandidate> findPlayerCollections(Object team) {
    List<ListCandidate> out = new ArrayList<>();
    Class<?> c = team.getClass();

    while (c != null) {
      for (Field f : c.getDeclaredFields()) {
        f.setAccessible(true);
        Object v;
        try {
          v = f.get(team);
        } catch (Exception e) {
          continue;
        }

        if (v == null) continue;

        // Collection
        if (v instanceof Collection<?> col) {
          List<Object> els = new ArrayList<>();
          int eg = 0;
          for (Object it : col) {
            els.add(it);
            if (isEg(it)) eg++;
          }
          if (eg > 0 || (!els.isEmpty() && looksLikePlayerList(els))) {
            out.add(new ListCandidate(c.getName() + "." + f.getName(), "collection", els.size(), eg, els));
          }
          continue;
        }

        // Array
        if (v.getClass().isArray()) {
          int len = Array.getLength(v);
          List<Object> els = new ArrayList<>(len);
          int eg = 0;
          for (int i = 0; i < len; i++) {
            Object it = Array.get(v, i);
            els.add(it);
            if (isEg(it)) eg++;
          }
          if (eg > 0 || (len > 0 && looksLikePlayerList(els))) {
            out.add(new ListCandidate(c.getName() + "." + f.getName(), "array", len, eg, els));
          }
        }
      }
      c = c.getSuperclass();
    }

    return out;
  }

  // Detecção heurística: inclui "a" como possível nome (ofuscado)
  private static boolean looksLikePlayerList(List<Object> els) {
    int checked = 0;
    int score = 0;
    for (Object it : els) {
      if (it == null) continue;
      checked++;
      if (getProp(it, "nome") != null || getProp(it, "name") != null || getProp(it, "a") != null) score++;
      if (getProp(it, "idade") != null || getProp(it, "d") != null) score++;
      if (getProp(it, "posicao") != null || getProp(it, "e") != null) score++;
      if (getProp(it, "lado") != null || getProp(it, "f") != null) score++;
      if (getProp(it, "cr1") != null || getProp(it, "g") != null) score++;
      if (checked >= 3) break;
    }
    return score >= 3;
  }

  private static boolean isEg(Object obj) {
    if (obj == null) return false;
    return "e.g".equals(obj.getClass().getName());
  }

  private record ListCandidate(
      String fieldPath,
      String kind,
      int totalCount,
      int egCount,
      List<Object> elements
  ) {}

  // ===== Leitura robusta do nome =====

  private static String readPlayerName(Object p) {
    Object v = firstNonNull(
        getProp(p, "nome"),
        firstNonNull(
            getProp(p, "name"),
            getProp(p, "a") // fallback crítico para e.g ofuscado
        )
    );

    if (v == null) return null;
    if (v instanceof char[] cs) return new String(cs);
    return String.valueOf(v);
  }

  // ===== reflexão =====

  private static Object getProp(Object target, String prop) {
    if (target == null) return null;

    // getXxx
    String getter = "get" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
    try {
      Method m = target.getClass().getMethod(getter);
      m.setAccessible(true);
      return m.invoke(target);
    } catch (Exception ignore) {}

    // isXxx
    String isGetter = "is" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
    try {
      Method m = target.getClass().getMethod(isGetter);
      m.setAccessible(true);
      return m.invoke(target);
    } catch (Exception ignore) {}

    // field
    Field f = findField(target.getClass(), prop);
    if (f != null) {
      try {
        f.setAccessible(true);
        return f.get(target);
      } catch (Exception ignore) {}
    }
    return null;
  }

  private static Field findField(Class<?> cls, String name) {
    Class<?> c = cls;
    while (c != null) {
      try {
        return c.getDeclaredField(name);
      } catch (NoSuchFieldException ignore) {
        c = c.getSuperclass();
      }
    }
    return null;
  }

  private static Object firstNonNull(Object a, Object b) {
    return a != null ? a : b;
  }

  // ===== format =====

  private static String s(Object o) {
    return o == null ? "null" : String.valueOf(o);
  }

  private static String safe(String s) {
    return s == null ? "null" : s;
  }

  private static String fmtColor(Object c) {
    if (c == null) return "null";
    try {
      Method m = c.getClass().getMethod("getRGB");
      m.setAccessible(true);
      int rgb = (int) m.invoke(c);
      int rr = (rgb >> 16) & 0xFF;
      int gg = (rgb >> 8) & 0xFF;
      int bb = (rgb) & 0xFF;
      return String.format("#%02x%02x%02x", rr, gg, bb);
    } catch (Exception ignore) {
      return c.toString();
    }
  }
}