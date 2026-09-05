package net.anweisen.chronit.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;

/**
 * One step in a command sequence. Exactly one of {@code command}, {@code chat}, {@code wait},
 * {@code click} or {@code closeScreen} must be set.
 *
 * @param command     a command without the leading slash, e.g. {@code "warp daily"}
 * @param chat        a plain chat message
 * @param pause       the config's {@code wait:} key — pause for this long and do nothing else.
 *                    Named {@code pause} because a record component cannot be called {@code wait}
 *                    without colliding with {@link Object#wait()}
 * @param click       click a slot in the container the server has opened
 * @param closeScreen close the open container, as pressing escape would
 * @param waitFor     block until a matching message or menu arrives before continuing
 * @param delayAfter  pause after this step, before the next one
 */
public record ActionConfig(
    String command,
    String chat,
    @JsonProperty("wait") Duration pause,
    ClickConfig click,
    Boolean closeScreen,
    WaitForConfig waitFor,
    Duration delayAfter) {

  public Kind kind() {
    if (command != null) {
      return Kind.COMMAND;
    }
    if (chat != null) {
      return Kind.CHAT;
    }
    if (click != null) {
      return Kind.CLICK;
    }
    if (closeScreen != null) {
      return Kind.CLOSE_SCREEN;
    }
    return Kind.WAIT;
  }

  public enum Kind { COMMAND, CHAT, WAIT, CLICK, CLOSE_SCREEN }

  /** Short description for logs, with the payload omitted so passwords are not echoed. */
  public String describe() {
    return switch (kind()) {
      case COMMAND -> "command /" + command;
      case CHAT -> "chat message";
      case WAIT -> "wait";
      case CLICK -> "click " + click.toSlotClick().describe();
      case CLOSE_SCREEN -> "close screen";
    };
  }
}
