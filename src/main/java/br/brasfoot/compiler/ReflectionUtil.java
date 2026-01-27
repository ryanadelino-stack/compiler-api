package br.brasfoot.compiler;

import java.lang.reflect.Field;

/** Helpers de reflexão para set/get de campos obfuscados do Brasfoot. */
public final class ReflectionUtil {

  private ReflectionUtil() {}

  @FunctionalInterface
  public interface FieldSetter {
    void set(Object target, Object value, String... candidateNames);
  }

  public static void setAnyField(Object target, Object value, String... candidateNames) {
    if (target == null || candidateNames == null) return;

    for (String name : candidateNames) {
      Field f = findField(target.getClass(), name);
      if (f == null) continue;

      try {
        f.setAccessible(true);

        Class<?> ft = f.getType();
        Object coerced = coerce(value, ft);
        if (coerced == null && ft.isPrimitive()) continue;

        f.set(target, coerced);
        return;
      } catch (Exception ignored) {}
    }
  }

  public static Object getAnyField(Object target, String... candidateNames) {
    if (target == null || candidateNames == null) return null;

    for (String name : candidateNames) {
      Field f = findField(target.getClass(), name);
      if (f == null) continue;

      try {
        f.setAccessible(true);
        return f.get(target);
      } catch (Exception ignored) {}
    }
    return null;
  }

  private static Field findField(Class<?> cls, String name) {
    Class<?> cur = cls;
    while (cur != null && cur != Object.class) {
      try {
        return cur.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        cur = cur.getSuperclass();
      }
    }
    return null;
  }

  private static Object coerce(Object v, Class<?> targetType) {
    if (v == null) return null;
    if (targetType.isInstance(v)) return v;

    if (targetType.isPrimitive()) {
      if (targetType == int.class) targetType = Integer.class;
      else if (targetType == short.class) targetType = Short.class;
      else if (targetType == byte.class) targetType = Byte.class;
      else if (targetType == long.class) targetType = Long.class;
      else if (targetType == boolean.class) targetType = Boolean.class;
      else if (targetType == double.class) targetType = Double.class;
      else if (targetType == float.class) targetType = Float.class;
      else if (targetType == char.class) targetType = Character.class;
    }

    if (v instanceof Number n) {
      if (targetType == Integer.class) return n.intValue();
      if (targetType == Short.class) return n.shortValue();
      if (targetType == Byte.class) return n.byteValue();
      if (targetType == Long.class) return n.longValue();
      if (targetType == Double.class) return n.doubleValue();
      if (targetType == Float.class) return n.floatValue();
    }

    if (v instanceof String s) {
      String t = s.trim();
      try {
        if (targetType == Integer.class) return Integer.parseInt(t);
        if (targetType == Short.class) return Short.parseShort(t);
        if (targetType == Byte.class) return Byte.parseByte(t);
        if (targetType == Long.class) return Long.parseLong(t);
        if (targetType == Double.class) return Double.parseDouble(t);
        if (targetType == Float.class) return Float.parseFloat(t);
      } catch (Exception ignored) {}
    }

    if (targetType == Boolean.class) {
      if (v instanceof Boolean b) return b;
      if (v instanceof String s) return Boolean.parseBoolean(s.trim());
      if (v instanceof Number n) return n.intValue() != 0;
    }

    if (targetType == String.class) return String.valueOf(v);

    return v;
  }
}