/**
 * Starving - Bukkit API server mod with Zombies.
 * Copyright (c) 2015, Matej Kormuth <http://www.github.com/dobrakmato>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package eu.matejkormuth.starving.remoteconnections;

import eu.matejkormuth.starving.main.DelayedTask;
import eu.matejkormuth.starving.remoteconnections.netty.Packet;
import eu.matejkormuth.starving.remoteconnections.netty.packets.CommandPacket;
import eu.matejkormuth.starving.remoteconnections.netty.packets.DisconnectPacket;
import io.netty.channel.ChannelHandlerContext;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class ServerPacketProcessor {

    private ServerPacketProcessor() {
    }

    public static final void incoming(ChannelHandlerContext ctx, String playerName, Packet msg) {
        Player player = Bukkit.getPlayer(playerName);
        // Check if player is online.
        if (!player.isOnline()) {
            ctx.writeAndFlush(new DisconnectPacket("Player disconnected from server!"));
            ctx.close();
            return;
        }

        if (msg instanceof CommandPacket) {
            sync(() -> player.performCommand(((CommandPacket) msg).command));
        }
    }

    public static void sync(Runnable runnable) {
        DelayedTask.of(runnable).schedule(0);
    }

}