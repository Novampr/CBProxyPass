package org.cloudburstmc.proxypass;

import com.formdev.flatlaf.intellijthemes.FlatArcDarkIJTheme;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ResourceLeakDetector;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.nbt.*;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.util.OptionalBoolean;
import org.cloudburstmc.proxypass.auth.Account;
import org.cloudburstmc.proxypass.auth.AuthHandler;
import org.cloudburstmc.proxypass.network.bedrock.jackson.*;
import org.cloudburstmc.proxypass.network.bedrock.session.*;
import org.cloudburstmc.proxypass.network.bedrock.util.NbtBlockDefinitionRegistry;
import org.cloudburstmc.proxypass.network.bedrock.util.NbtBlockDefinitionRegistry.NbtBlockDefinition;
import org.cloudburstmc.proxypass.network.bedrock.util.UnknownBlockDefinitionRegistry;
import org.cloudburstmc.proxypass.ui.PacketLoggingWindow;
import tools.jackson.core.Version;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.dataformat.yaml.YAMLMapper;

import javax.annotation.Nullable;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Log4j2
@Getter
public class ProxyPass {
    public static final ObjectMapper JSON_MAPPER;
    public static final YAMLMapper YAML_MAPPER;

    private static final SimpleModule MODULE = new SimpleModule("ProxyPass", Version.unknownVersion())
            .addSerializer(Color.class, new ColorSerializer())
            .addDeserializer(Color.class, new ColorDeserializer())
            .addSerializer(NbtBlockDefinition.class, new NbtDefinitionSerializer())
            .addSerializer(OptionalBoolean.class, new OptionalBooleanSerializer())
            .addSerializer(ByteBuf.class, new ByteBufSerializer());

    public static final String MINECRAFT_VERSION;

    public static final BedrockCodec BASE_CODEC = Bedrock_v2168.CODEC;

    public static final BedrockCodec CODEC = BASE_CODEC.toBuilder()
            .helper(() -> {
                BedrockCodecHelper helper = BASE_CODEC.createHelper();
                helper.setEncodingSettings(EncodingSettings.UNLIMITED);
                return helper;
            }).build();
    public static final int PROTOCOL_VERSION = CODEC.getProtocolVersion();

    private static final BedrockPong ADVERTISEMENT = new BedrockPong()
            .edition("MCPE")
            .gameType("Survival")
            .version(ProxyPass.MINECRAFT_VERSION)
            .protocolVersion(ProxyPass.PROTOCOL_VERSION)
            .motd("ProxyPass")
            .playerCount(0)
            .maximumPlayerCount(20)
            .subMotd("https://github.com/CloudburstMC/ProxyPass")
            .nintendoLimited(false);

    private static final DefaultPrettyPrinter PRETTY_PRINTER;

    public static Map<Integer, String> legacyIdMap = new HashMap<>();

    static {
        DefaultIndenter indenter = new DefaultIndenter("    ", "\n");
        Separators separators = Separators.createDefaultInstance()
                .withObjectNameValueSpacing(Separators.Spacing.AFTER);

        DefaultPrettyPrinter printer = new DefaultPrettyPrinter().withSeparators(separators);
        printer.indentArraysWith(indenter);
        printer.indentObjectsWith(indenter);

        PRETTY_PRINTER = printer;

        JSON_MAPPER = JsonMapper.builder()
                .addModule(MODULE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .defaultPrettyPrinter(PRETTY_PRINTER)
                .build();

        YAML_MAPPER = YAMLMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();

        MINECRAFT_VERSION = CODEC.getMinecraftVersion();
    }

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean fullShutdown = new AtomicBoolean(true);

    private final NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup();
    private final Set<Channel> clients = ConcurrentHashMap.newKeySet();
    private Channel server;
    private int maxClients = 0;
    private InetSocketAddress targetAddress;
    private InetSocketAddress proxyAddress;
    private Configuration configuration;
    private Path baseDir;
    private Path sessionsDir;
    private Path dataDir;
    private DefinitionRegistry<BlockDefinition> blockDefinitions;
    private DefinitionRegistry<BlockDefinition> blockDefinitionsHashed;
    private Account currentAccount;
    @Setter
    private Consumer<ProxyPlayerSession> sessionInitHandler = (ignored) -> {};

    public static void main(String[] args) {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
        List<String> arguments = List.of(args);

        try {
            if (arguments.contains("ui")) {
                FlatArcDarkIJTheme.setup();
                new PacketLoggingWindow();
                return;
            }
            log.info("Loading configuration...");
            Path configPath = Paths.get(".").resolve("config.yml");
            if (Files.notExists(configPath) || !Files.isRegularFile(configPath)) {
                Files.copy(ProxyPass.class.getClassLoader().getResourceAsStream("config.yml"), configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            ProxyPass proxy = new ProxyPass();
            Configuration config = Configuration.load(configPath);
            proxy.boot(config, AuthHandler.authenticateCli(config));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void boot(Configuration configuration, @Nullable Account currentAccount) throws IOException {
        this.configuration = configuration;
        this.currentAccount = currentAccount;
        if (this.currentAccount != null) {
            log.info("Authenticated as {}.", AuthHandler.getAccountName(this.currentAccount.toJson()));
        } else {
            log.info("Not authenticated.");
        }
        proxyAddress = configuration.getProxy().getAddress();
        targetAddress = configuration.getDestination().getAddress();
        maxClients = configuration.getMaxClients();

        baseDir = Paths.get(".").toAbsolutePath();
        sessionsDir = baseDir.resolve("sessions");
        dataDir = baseDir.resolve("data");
        Files.createDirectories(sessionsDir);
        Files.createDirectories(dataDir);

        // Load block palette, if it exists
        Object object = this.loadGzipNBT("block_palette.nbt");

        if (object instanceof NbtMap map) {
            this.blockDefinitions = new NbtBlockDefinitionRegistry(map.getList("blocks", NbtType.COMPOUND), false);
            this.blockDefinitionsHashed = new NbtBlockDefinitionRegistry(map.getList("blocks", NbtType.COMPOUND), true);
        } else {
            this.blockDefinitions = this.blockDefinitionsHashed = new UnknownBlockDefinitionRegistry();
            log.warn("Failed to load block palette. Blocks will appear as runtime IDs in packet traces and creative_content.json!");
        }

        log.info("Booting server...");
        ADVERTISEMENT.ipv4Port(this.proxyAddress.getPort())
                .ipv6Port(this.proxyAddress.getPort());
        this.server = new ServerBootstrap()
                .group(this.eventLoopGroup)
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .option(RakChannelOption.RAK_ADVERTISEMENT, ADVERTISEMENT.toByteBuf())
                .childHandler(new BedrockChannelInitializer<ProxyServerSession>() {

                    @Override
                    protected ProxyServerSession createSession0(BedrockPeer peer, int subClientId) {
                        return new ProxyServerSession(peer, subClientId, ProxyPass.this);
                    }

                    @Override
                    protected void initSession(ProxyServerSession session) {
                        session.setPacketHandler(new UpstreamPacketHandler(session, ProxyPass.this, ProxyPass.this.currentAccount));
                    }
                })
                .bind(this.proxyAddress)
                .awaitUninterruptibly()
                .channel();
        log.info("Bedrock server {} ({}) started on {}", MINECRAFT_VERSION, ProxyPass.CODEC.getProtocolVersion(), proxyAddress);

        running.set(true);
        loop();
    }

    public void newClient(InetSocketAddress socketAddress, Consumer<ProxyClientSession> sessionConsumer) {
        Channel channel = new Bootstrap()
                .group(this.eventLoopGroup)
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, ProxyPass.CODEC.getRaknetProtocolVersion())
                .handler(new BedrockChannelInitializer<ProxyClientSession>() {

                    @Override
                    protected ProxyClientSession createSession0(BedrockPeer peer, int subClientId) {
                        return new ProxyClientSession(peer, subClientId, ProxyPass.this);
                    }

                    @Override
                    protected void initSession(ProxyClientSession session) {
                        log.info("Player logged in with IP {}", session.getSocketAddress());
                        sessionConsumer.accept(session);
                    }
                })
                .connect(socketAddress)
                .awaitUninterruptibly()
                .channel();

        this.clients.add(channel);
    }

    private void loop() {
        new Thread(() -> {
            fullShutdown.set(false);
            while (running.get()) {
                try {
                    synchronized (this) {
                        this.wait();
                    }
                } catch (InterruptedException e) {
                    // ignore
                }

            }

            log.info("Shutting down server.");

            // Shutdown
            this.clients.forEach(Channel::disconnect);
            this.server.disconnect();
            this.server.close();
            fullShutdown.set(true);
            log.info("Shut down complete.");
        }).start();
    }

    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            synchronized (this) {
                this.notify();
            }
        }
    }

    public CompletableFuture<Void> awaitShutdown() {
        shutdown();
        return CompletableFuture.supplyAsync(() -> {
            while (!fullShutdown.get()) {}
            return null;
        });
    }

    public void saveCompressedNBT(String dataName, Object dataTag) {
        Path path = dataDir.resolve(dataName + ".nbt");
        try (OutputStream outputStream = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             NBTOutputStream nbtOutputStream = NbtUtils.createGZIPWriter(outputStream)) {
            nbtOutputStream.writeTag(dataTag);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void saveNBT(String dataName, Object dataTag) {
        Path path = dataDir.resolve(dataName + ".dat");
        try (OutputStream outputStream = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             NBTOutputStream nbtOutputStream = NbtUtils.createNetworkWriter(outputStream)) {
            nbtOutputStream.writeTag(dataTag);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Object loadNBT(String dataName) {
        Path path = dataDir.resolve(dataName + ".dat");
        try (InputStream inputStream = Files.newInputStream(path);
             NBTInputStream nbtInputStream = NbtUtils.createNetworkReader(inputStream)) {
            return nbtInputStream.readTag();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Object loadGzipNBT(String dataName) {
        Path path = dataDir.resolve(dataName);
        try (InputStream inputStream = Files.newInputStream(path);
             NBTInputStream nbtInputStream = NbtUtils.createGZIPReader(inputStream)) {
            return nbtInputStream.readTag();
        } catch (IOException e) {
            return null;
        }
    }

    public void saveJson(String name, Object object) {
        Path outPath = dataDir.resolve(name);
        try (OutputStream outputStream = Files.newOutputStream(outPath, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
            ProxyPass.JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputStream, object);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public <T> T loadJson(String name, TypeReference<T> reference) {
        Path path = dataDir.resolve(name);
        try (InputStream inputStream = Files.newInputStream(path, StandardOpenOption.READ)) {
            return ProxyPass.JSON_MAPPER.readValue(inputStream, reference);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void saveMojangson(String name, NbtMap nbt) {
        Path outPath = dataDir.resolve(name);
        try {
            Files.writeString(outPath, nbt.toString(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void savePacket(BedrockPacketWrapper wrapper) {
        String name = wrapper.getPacket().getPacketType().getName().toLowerCase() + "_" + System.currentTimeMillis() + ".dat";

        ByteBuf packetBuf = wrapper.getPacketBuffer().slice();
        packetBuf.skipBytes(wrapper.getHeaderLength()); // skip header

        ByteBuf buffer = packetBuf.alloc().ioBuffer();
        buffer.writeInt(wrapper.getPacketId()); // packet ID
        buffer.writeBytes(packetBuf); // packet data

        Path outPath = dataDir.resolve(name);
        try (OutputStream outputStream = Files.newOutputStream(outPath, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
            byte[] bytes = new byte[1024 * 8];
            while (buffer.isReadable()) {
                int read = Math.min(buffer.readableBytes(), bytes.length);
                buffer.readBytes(bytes, 0, read);
                outputStream.write(bytes, 0, read);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            buffer.release();
        }
    }

    public void resetConfig(Configuration configuration) {
        this.configuration = configuration;
        // Proxy Address and Target Address are **not** reset during runtime
        // Max clients aren't reset either since there isn't a UI element to set that currently
    }

    public boolean isIgnoredPacket(Class<?> clazz) {
        return this.configuration.getIgnoredPackets().contains(clazz.getSimpleName());
    }

    public boolean isBlockedPacket(Class<?> clazz) {
        return this.configuration.getBlockedPackets().contains(clazz.getSimpleName());
    }

    public void setBlockedPackets(Set<Class<? extends BedrockPacket>> blockedPackets) {
        this.configuration.setBlockedPackets(blockedPackets.stream().map(Class::getSimpleName).collect(Collectors.toSet()));
    }

    public boolean isFull() {
        return maxClients > 0 && this.clients.size() >= maxClients;
    }
}
