package org.cloudburstmc.proxypass.ui.sequence;

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

import java.util.List;

public record PacketSequence(String name, List<PacketAction> actions) {
    public interface PacketAction {}

    public record Pause(long milliseconds) implements PacketAction {}

    public record SendPacket(BedrockPacket packet) implements PacketAction {}

    public record ReceivePacket(BedrockPacket packet) implements PacketAction {}
}
