package net.anweisen.chronit.driver.mcpl;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.adventure.text.serializer.nbt.NbtComponentSerializer;
import org.geysermc.mcprotocollib.protocol.data.DefaultComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LenientComponentSerializerTest {

  /** {@code {"text":"","extra":[{"text":"hi","shadow_color":0b}]}}, as ViaVersion converts it. */
  private static NbtMap byteShadow() {
    NbtMap child = NbtMap.builder().putString("text", "hi").putByte("shadow_color", (byte) 0).build();
    return NbtMap.builder().putString("text", "").putList("extra", NbtType.COMPOUND, child).build();
  }

  @Test
  void libraryRejectsANarrowShadowColor() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
      () -> NbtComponentSerializer.nbt().deserialize(byteShadow()));
    assertEquals("Don't know how to parse ARGB colour: 0", e.getMessage());
  }

  @Test
  void readsANarrowShadowColor() {
    LenientComponentSerializer.install();
    Component component = DefaultComponentSerializer.nbt().deserialize(byteShadow());
    assertEquals("hi", Components.plain(component));
    assertEquals(ShadowColor.shadowColor(0), component.children().getFirst().style().shadowColor());
  }

  @Test
  void readsANonFloatVector() {
    NbtMap tag = NbtMap.builder().putString("text", "x")
      .putList("shadow_color", NbtType.DOUBLE, 1.0, 0.0, 0.0, 1.0).build();
    NbtMap fixed = (NbtMap) LenientComponentSerializer.normalize(tag);
    assertEquals(NbtType.FLOAT, assertInstanceOf(NbtList.class, fixed.get("shadow_color")).getType());
  }

  @Test
  void leavesAWellFormedTreeAlone() {
    NbtMap tag = NbtMap.builder().putString("text", "x").putInt("shadow_color", 0xFF000000).build();
    assertSame(tag, LenientComponentSerializer.normalize(tag));
  }
}
