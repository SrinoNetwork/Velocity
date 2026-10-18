/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.protocol.packet.scoreboard;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.ProtocolUtils.Direction;
import com.velocitypowered.proxy.protocol.util.DeferredByteBufHolder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Srino: a scoreboard packet that starts with a name and a mode byte. The proxy only reads those
 * two fields to track what the backend created, the rest is passed through unchanged, so a
 * seamless server switch can remove leftovers the client would otherwise keep.
 */
public abstract class ScoreboardPeekPacket extends DeferredByteBufHolder implements MinecraftPacket {

  public static final byte MODE_ADD = 0;
  public static final byte MODE_REMOVE = 1;

  private String name;
  private byte mode;

  protected ScoreboardPeekPacket() {
    super(null);
  }

  /**
   * Builds a packet that only carries a name and a mode, e.g. a remove.
   */
  protected ScoreboardPeekPacket(String name, byte mode) {
    super(null);
    ByteBuf buf = Unpooled.buffer();
    ProtocolUtils.writeString(buf, name);
    buf.writeByte(mode);
    this.replace(buf);
    this.name = name;
    this.mode = mode;
  }

  @Override
  public void decode(ByteBuf buf, Direction direction, ProtocolVersion protocolVersion) {
    ByteBuf peek = buf.duplicate();
    this.name = ProtocolUtils.readString(peek);
    this.mode = peek.readByte();
    this.replace(buf.readRetainedSlice(buf.readableBytes()));
  }

  @Override
  public void encode(ByteBuf buf, Direction direction, ProtocolVersion protocolVersion) {
    buf.writeBytes(content(), content().readerIndex(), content().readableBytes());
  }

  @Override
  public int encodeSizeHint(Direction direction, ProtocolVersion version) {
    return content().readableBytes();
  }

  public String getName() {
    return name;
  }

  public byte getMode() {
    return mode;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{name=" + name + ", mode=" + mode + '}';
  }
}
