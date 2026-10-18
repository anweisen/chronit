package net.anweisen.chronit.driver.mcpl;

import net.kyori.adventure.text.Component;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.adventure.text.serializer.nbt.NbtComponentSerializer;
import org.geysermc.mcprotocollib.protocol.data.DefaultComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads chat components with the numeric leniency of the vanilla client.
 *
 * <p>MCProtocolLib's NBT serializer accepts {@code shadow_color} only as an int tag or a list of
 * four floats. The vanilla client reads any numeric tag there, and servers do send narrower ones:
 * converting a JSON component to NBT (ViaVersion, many plugins) picks the smallest tag that fits,
 * so a {@code "shadow_color":0} arrives as a byte. MCProtocolLib then throws, and since that
 * happens while decoding a packet, the whole session is dropped. Coercing the tags back to what
 * the library expects before handing the tree over keeps its parser untouched.
 */
final class LenientComponentSerializer implements NbtComponentSerializer {

  private static final String SHADOW_COLOR = "shadow_color";

  private final NbtComponentSerializer delegate;

  private LenientComponentSerializer(NbtComponentSerializer delegate) {
    this.delegate = delegate;
  }

  static synchronized void install() {
    if (!(DefaultComponentSerializer.nbt() instanceof LenientComponentSerializer)) {
      DefaultComponentSerializer.setNbt(new LenientComponentSerializer(DefaultComponentSerializer.nbt()));
    }
  }

  @Override
  public Component deserialize(Object input) {
    return delegate.deserialize(normalize(input));
  }

  @Override
  public Object serialize(Component component) {
    return delegate.serialize(component);
  }

  /** Returns the input itself when nothing needed changing, so the common case allocates nothing. */
  static Object normalize(Object tag) {
    if (tag instanceof NbtMap map) {
      NbtMapBuilder changed = null;
      for (Map.Entry<String, Object> entry : map.entrySet()) {
        Object value = entry.getValue();
        Object fixed = SHADOW_COLOR.equals(entry.getKey()) ? argb(value) : normalize(value);
        if (fixed != value) {
          if (changed == null) {
            changed = map.toBuilder();
          }
          changed.put(entry.getKey(), fixed);
        }
      }
      return changed == null ? map : changed.build();
    }
    if (tag instanceof NbtList<?> list && (list.getType() == NbtType.COMPOUND || list.getType() == NbtType.LIST)) {
      List<Object> elements = null;
      for (int i = 0; i < list.size(); i++) {
        Object element = list.get(i);
        Object fixed = normalize(element);
        if (fixed != element && elements == null) {
          elements = new ArrayList<>(list.subList(0, i));
        }
        if (elements != null) {
          elements.add(fixed);
        }
      }
      return elements == null ? list : rebuild(list.getType(), elements);
    }
    return tag;
  }

  private static Object argb(Object value) {
    if (value instanceof Number number && !(value instanceof Integer)) {
      return number.intValue();
    }
    if (value instanceof NbtList<?> list && list.size() == 4 && list.getType() != NbtType.FLOAT
      && list.stream().allMatch(Number.class::isInstance)) {
      List<Float> floats = list.stream().map(n -> ((Number) n).floatValue()).toList();
      return new NbtList<>(NbtType.FLOAT, floats);
    }
    return value;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static NbtList<?> rebuild(NbtType<?> type, List<Object> elements) {
    return new NbtList(type, elements);
  }
}
