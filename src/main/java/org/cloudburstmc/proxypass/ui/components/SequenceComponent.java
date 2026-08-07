package org.cloudburstmc.proxypass.ui.components;

import it.unimi.dsi.fastutil.Pair;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.proxypass.network.bedrock.session.ProxyPlayerSession;
import org.cloudburstmc.proxypass.ui.sequence.PacketSequence;

import javax.swing.*;
import java.time.Instant;
import java.util.*;

public class SequenceComponent extends JPanel {
    private final Map<UUID, PacketSequence> sequences = new HashMap<>();

    private final ProxyPlayerSession session;

    private final List<PacketSequence.PacketAction> recordedActions = new ArrayList<>();
    private Instant lastRecordedPacket = Instant.now();

    public SequenceComponent(ProxyPlayerSession session) {
        this.session = session;
    }

    public void startRecording() {
        session.setPacketHandler((packet, upstream) -> {
            Instant now = Instant.now();
            long delay = now.toEpochMilli() - lastRecordedPacket.toEpochMilli();
            lastRecordedPacket = now;

            recordedActions.add(new PacketSequence.Pause(delay));
            if (upstream) recordedActions.add(new PacketSequence.ReceivePacket(packet));
            else recordedActions.add(new PacketSequence.SendPacket(packet));
        });
    }

    public void stopRecording(String name) {
        session.setPacketHandler((packet, upstream) -> {});

        sequences.put(UUID.randomUUID(), new PacketSequence(name, new ArrayList<>(recordedActions)));
        recordedActions.clear();
    }
}
