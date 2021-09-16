package org.jitsi.videobridge;

import com.google.common.collect.Maps;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.RtpReceiver;
import org.jitsi.nlj.format.PayloadType;
import org.jitsi.nlj.rtp.AudioRtpPacket;
import org.jitsi.nlj.util.PacketInfoQueue;
import org.jitsi.nlj.util.StreamInformationStore;
import org.jitsi.nlj.util.StreamInformationStoreImpl;
import org.jitsi.rtp.Packet;
import org.jitsi.rtp.UnparsedPacket;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.octo.config.OctoRtpReceiver;
import org.jitsi.videobridge.transport.udp.UdpTransport;
import org.jitsi.videobridge.util.ByteBufferPool;
import org.jitsi.videobridge.util.PropertyUtil;
import org.jitsi.videobridge.util.TaskPools;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class ConfAudioMixerTransport implements PotentialPacketHandler {

    public static final String AUDIO_MIXER_EP_ID = "1";

    private static final int SO_RCVBUF = 1024 * 1024;

    private static final int SO_SNDBUF = 1024 * 1024;

    private final static OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .dispatcher(new Dispatcher(Executors.newSingleThreadExecutor()))
            .build();

    private final Map<String, PacketInfoQueue> outgoingPacketQueues =
            new ConcurrentHashMap<>();

    private final Logger logger;

    private final StreamInformationStore streamInformationStore;

    private final RtpReceiver rtpReceiver;

    private final UdpTransport udpTransport;

    private InetSocketAddress mixerAddress = null;

    private final Conference conference;

    private final AtomicBoolean running = new AtomicBoolean(true);

    private static HttpUrl.Builder getRtpMixerHttpUrlBuilder(String id) {
        return Objects.requireNonNull(HttpUrl.parse(getAudioProcessorPipelineUrl()))
                .newBuilder()
                .addPathSegment("pipeline")
                .addQueryParameter("id", id);
    }

    private int getAudioMixPipelineSrcPort(String id, String sinkHost, int sinkPort, int seqNum) throws IOException {
        Request request = new Request.Builder()
                .url(getRtpMixerHttpUrlBuilder(id)
                        .addQueryParameter("sinkHost", sinkHost)
                        .addQueryParameter("sinkPort", Integer.toString(sinkPort))
                        .addQueryParameter("seqNum", Integer.toString(seqNum))
                        .build()
                )
                .post(RequestBody.create("", null))
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                if (response.code() == 201) {
                    TaskPools.SCHEDULED_POOL.submit((Runnable) this::updatePipeline);
                }
                return Integer.parseInt(response.body().string());
            } else {
                throw new RuntimeException("Unsuccessful response. " + response.body().string());
            }
        }
    }

    private static void deleteAudioMixPipeline(String id, Callback callback) {
        Request request = new Request.Builder()
                .url(getRtpMixerHttpUrlBuilder(id).build())
                .delete().build();
        okHttpClient.newCall(request).enqueue(callback);
    }

    private static String audioProcessorIp;

    private static String getAudioProcessorIp() {
        if (StringUtils.isBlank(audioProcessorIp)) {
            audioProcessorIp = PropertyUtil.getValue(
                    "audio.processor.ip",
                    "AUDIO_PROCESSOR_IP"
            );
            if (StringUtils.isBlank(audioProcessorIp)) {
                throw new RuntimeException("Can not get mixer hostname");
            }
        }
        return audioProcessorIp;
    }

    private static String audioProcessorPipelineUrl;

    private static String getAudioProcessorPipelineUrl() {
        if (StringUtils.isBlank(audioProcessorPipelineUrl)) {
            audioProcessorPipelineUrl = PropertyUtil.getValue(
                    "audio.processor.http.url",
                    "AUDIO_PROCESSOR_HTTP_URL"
            );
            if (StringUtils.isBlank(audioProcessorPipelineUrl)) {
                throw new RuntimeException("Can not get mixer hostname");
            }
        }
        return audioProcessorPipelineUrl;
    }

    private int seqNum = 0;

    private void updateMixerAddress() {
        if (!running.get()) {
            return;
        }

        try {
            int mixerPort = getAudioMixPipelineSrcPort(conference.getGid(), udpTransport.getLocalAddress().getHostAddress(), udpTransport.getLocalPort(), seqNum);
            if (mixerAddress == null || mixerAddress.getPort() != mixerPort) {
                mixerAddress = new InetSocketAddress(getAudioProcessorIp(), mixerPort);
            }
        } catch (Exception e) {
            logger.error("updateMixerAddress error", e);
            mixerAddress = null;
        }
    }

    private final ScheduledFuture<?> updateMixerAddressScheduledFuture;

    public ConfAudioMixerTransport(Conference conference) throws SocketException, UnknownHostException {
        this.conference = conference;
        this.logger = conference.getLogger().createChildLogger(this.getClass().getName());

        streamInformationStore = new StreamInformationStoreImpl();

        rtpReceiver = new OctoRtpReceiver(streamInformationStore, logger);
        rtpReceiver.setPacketHandler(packetInfo -> {
            Packet packet = packetInfo.getPacket();
            if (packet instanceof RtpPacket) {
                seqNum = ((RtpPacket) packet).getSequenceNumber();
            }
            packetInfo.setEndpointId(AUDIO_MIXER_EP_ID);
            conference.handleIncomingPacket(packetInfo);
        });
        udpTransport = new UdpTransport(InetAddress.getLocalHost().getHostAddress(), 0, logger, SO_RCVBUF, SO_SNDBUF);

        updateMixerAddressScheduledFuture = TaskPools.SCHEDULED_POOL.scheduleAtFixedRate(this::updateMixerAddress, 0, 10, TimeUnit.SECONDS);

        udpTransport.setIncomingDataHandler((data, offset, length, receivedTime) -> {
            byte[] copy = ByteBufferPool.getBuffer(
                    length +
                            RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET +
                            RtpPacket.BYTES_TO_LEAVE_AT_END_OF_PACKET
            );
            System.arraycopy(data, offset, copy, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, length);
            Packet pkt = new UnparsedPacket(copy, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, length);
            PacketInfo pktInfo = new PacketInfo(pkt);
            pktInfo.setReceivedTime(receivedTime.toEpochMilli());

            rtpReceiver.enqueuePacket(pktInfo);
        });
        TaskPools.IO_POOL.submit(udpTransport::startReadingData);
    }

    @Override
    public boolean wants(PacketInfo packetInfo) {
        if (!running.get()) {
            return false;
        }

        Packet packet = Objects.requireNonNull(packetInfo.getPacket(), "packet");
        return packet instanceof AudioRtpPacket && !AUDIO_MIXER_EP_ID.equals(packetInfo.getEndpointId());
    }

    private PacketInfoQueue createQueue(String epId) {
        PacketInfoQueue q = new PacketInfoQueue(
                "audio-mixer-outgoing-packet-queue",
                TaskPools.IO_POOL,
                this::doSend,
                1024);
        return q;
    }

    @Override
    public void send(PacketInfo packet) {
        if (!running.get()) {
            ByteBufferPool.returnBuffer(packet.getPacket().getBuffer());
            return;
        }

        PacketInfoQueue queue =
                outgoingPacketQueues.computeIfAbsent(packet.getEndpointId(),
                        this::createQueue);

        queue.add(packet);
    }

    private boolean doSend(PacketInfo packetInfo) {
        if (mixerAddress != null) {
            Packet packet = packetInfo.getPacket();
            udpTransport.send(packet.getBuffer(), packet.getOffset(), packet.getLength(), mixerAddress);
            packetInfo.sent();
        }

        return true;
    }

    public void expire() {
        if (running.compareAndSet(true, false)) {
            logger.info("Expiring");

            updateMixerAddressScheduledFuture.cancel(false);

            outgoingPacketQueues.values().forEach(PacketInfoQueue::close);
            outgoingPacketQueues.clear();

            Callback callback = new Callback() {
                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    logger.error("deleteAudioMixPipeline error", e);
                }

                @Override
                public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                    try {
                        if (!response.isSuccessful()) {
                            logger.error("deleteAudioMixPipeline unsuccessful response. " + Objects.requireNonNull(response.body()).string());
                        }
                    } finally {
                        response.close();
                    }
                }
            };

            try {
                deleteAudioMixPipeline(conference.getGid(), callback);
            } catch (Exception e) {
                logger.error("Can not delete audio mix pipeline", e);
            }
        }
    }

    public void addPayloadType(PayloadType payloadType) {
        streamInformationStore.addRtpPayloadType(payloadType);
    }

    private void updatePipeline() {
        Map<Long, String> collect = conference.getLocalEndpoints().stream()
                .flatMap(e -> e.getRemoteAudioSsrcc().stream().map(s -> Maps.immutableEntry(s, e.getID())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        updatePipeline(collect);
    }

    public void updatePipeline(Map<Long, String> ssrcToEndpoint) {
        updatePipeline(conference.getGid(), ssrcToEndpoint);
    }

    private void updatePipeline(String id, Map<Long, String> ssrcToEndpoint) {
        if (ssrcToEndpoint.size()==0) return;
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("Ssrcs", ssrcToEndpoint);
        RequestBody body = RequestBody.create(
                jsonObject.toJSONString(),
                MediaType.parse("application/json")
        );
        Request request = new Request.Builder()
                .url(getRtpMixerHttpUrlBuilder(id).build())
                .put(body)
                .build();
        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                logger.error("Failure updatePipeline", e);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                try {
                    if(!response.isSuccessful()) {
                        logger.error("Unsuccessful response on updatePipeline. Response: " + response.body().string());
                    }
                } finally {
                    response.close();
                }
            }
        });
    }
}
