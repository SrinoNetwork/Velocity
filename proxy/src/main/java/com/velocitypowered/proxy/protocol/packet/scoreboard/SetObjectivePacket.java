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

import com.velocitypowered.proxy.connection.MinecraftSessionHandler;

/**
 * Srino: clientbound scoreboard objective packet, see {@link ScoreboardPeekPacket}.
 */
public class SetObjectivePacket extends ScoreboardPeekPacket {

  public SetObjectivePacket() {
  }

  public static SetObjectivePacket remove(String name) {
    return new SetObjectivePacket(name, MODE_REMOVE);
  }

  private SetObjectivePacket(String name, byte mode) {
    super(name, mode);
  }

  @Override
  public boolean handle(MinecraftSessionHandler handler) {
    return handler.handle(this);
  }
}
