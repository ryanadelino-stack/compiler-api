package br.brasfoot.compiler;

import java.io.ObjectInputFilter;

public final class SafeDeserialization {

  private SafeDeserialization() {}

  // Guarda o último tipo rejeitado por thread (cada request do Spring tende a rodar em uma thread)
  private static final ThreadLocal<String> LAST_REJECTED = new ThreadLocal<>();

  public static void clearLastRejected() {
    LAST_REJECTED.remove();
  }

  public static String getLastRejected() {
    return LAST_REJECTED.get();
  }

  public static ObjectInputFilter createFilter() {
    return info -> {
      Class<?> clazz = info.serialClass();
      if (clazz == null) {
        // Ainda não temos uma classe concreta: deixa o runtime decidir.
        return ObjectInputFilter.Status.UNDECIDED;
      }

      String name = clazz.getName();

      // Permite primitivos e arrays
      if (clazz.isPrimitive() || name.startsWith("[")) {
        return ObjectInputFilter.Status.ALLOWED;
      }

      // WHITELIST (mínima)
      if (name.startsWith("br.brasfoot.")
            || name.startsWith("e.")
          || name.startsWith("java.lang.")
          || name.startsWith("java.util.")
          || name.startsWith("java.time.")
          || name.startsWith("java.awt.")) {
        return ObjectInputFilter.Status.ALLOWED;
      }

      // Se chegar aqui, rejeita e registra o tipo
      LAST_REJECTED.set(name);
      return ObjectInputFilter.Status.REJECTED;
    };
  }
}
