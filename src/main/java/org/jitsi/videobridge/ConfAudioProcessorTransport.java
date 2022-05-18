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
import java.net.*;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class ConfAudioProcessorTransport implements PotentialPacketHandler {

    public static final String AUDIO_MIXER_EP_ID = "1";

    private static final int SO_RCVBUF = 1024 * 1024;

    private static final int SO_SNDBUF = 1024 * 1024;

    private final static OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .dispatcher(new Dispatcher(TaskPools.IO_POOL))
            .callTimeout(5, TimeUnit.SECONDS)
            .build();

    private final Map<String, PacketInfoQueue> outgoingPacketQueues =
            new ConcurrentHashMap<>();

    private final Logger logger;

    private final StreamInformationStore streamInformationStore;

    private final RtpReceiver rtpMixerReceiver;

    private final UdpTransport udpTransport;

    private volatile InetSocketAddress processorAddress = null;

    private final Conference conference;

    private final AtomicBoolean running = new AtomicBoolean(true);

    private static HttpUrl.Builder getRtpProcessorHttpUrlBuilder(String id) {
        return Objects.requireNonNull(HttpUrl.parse(getAudioProcessorPipelineUrl()))
                .newBuilder()
                .addPathSegment("pipeline")
                .addQueryParameter("id", id);
    }

    private static class PipelineSrcPort {
        public final int value;
        public final boolean created;
        public PipelineSrcPort(int value, boolean created) {
            this.value = value;
            this.created = created;
        }
    }

    private CompletableFuture<PipelineSrcPort> getAudioProcessorPipelineSrcPort(String id, String sinkHost, int sinkPort, int seqNum) {
        Request request = new Request.Builder()
                .url(getRtpProcessorHttpUrlBuilder(id)
                        .addQueryParameter("sinkHost", sinkHost)
                        .addQueryParameter("sinkPort", Integer.toString(sinkPort))
                        .addQueryParameter("seqNum", Integer.toString(seqNum))
                        .build()
                )
                .post(RequestBody.create("", null))
                .build();
        CompletableFuture<PipelineSrcPort> result = new CompletableFuture<>();
        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                result.completeExceptionally(e);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                try {
                    ResponseBody responseBody = response.body();
                    if (response.isSuccessful()) {
                        if (responseBody == null) {
                            throw new RuntimeException(String.format(
                                    "Successful response on getAudioProcessorPipelineSrcPort but body is empty. Code: %s",
                                    response.code()
                            ));
                        }
                        result.complete(new PipelineSrcPort(Integer.parseInt(responseBody.string()), response.code() == HttpURLConnection.HTTP_CREATED));
                    } else {
                        Throwable e = new RuntimeException(String.format(
                                "Unsuccessful response on getAudioProcessorPipelineSrcPort. Code: %s. Response: %s",
                                response.code(),
                                responseBody == null ? "" : responseBody.string()
                        ));
                        result.completeExceptionally(e);
                    }
                } finally {
                    response.close();
                }
            }
        });
        return result;
    }

    private static void deleteAudioProcessorPipeline(String id, Callback callback) {
        Request request = new Request.Builder()
                .url(getRtpProcessorHttpUrlBuilder(id).build())
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
                throw new RuntimeException("Can not get audio processor hostname");
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
                throw new RuntimeException("Can not get processor hostname");
            }
        }
        return audioProcessorPipelineUrl;
    }

    private int seqNum = 0;

    private void updateProcessorAddress() {
        if (!running.get()) {
            return;
        }

        getAudioProcessorPipelineSrcPort(
                conference.getGid(),
                udpTransport.getLocalAddress().getHostAddress(),
                udpTransport.getLocalPort(),
                seqNum
        ).whenComplete((pipelineSrcPort, e) -> {
            if (e != null) {
                logger.error("updateProcessorAddress error", e);
                processorAddress = null;
            } else {
                boolean processorAddressChanged = false;
                if (processorAddress == null || processorAddress.getPort() != pipelineSrcPort.value) {
                    processorAddress = new InetSocketAddress(getAudioProcessorIp(), pipelineSrcPort.value);
                    processorAddressChanged = true;
                }
                if (pipelineSrcPort.created || processorAddressChanged) {
                    this.scheduleUpdatePipeline(0);
                }
            }
        });
    }

    private final ScheduledFuture<?> updateProcessorAddressScheduledFuture;

    public ConfAudioProcessorTransport(Conference conference) throws SocketException, UnknownHostException {
        this.conference = conference;
        this.logger = conference.getLogger().createChildLogger(this.getClass().getName());

        streamInformationStore = new StreamInformationStoreImpl();

        rtpMixerReceiver = new OctoRtpReceiver(streamInformationStore, logger);
        rtpMixerReceiver.setPacketHandler(packetInfo -> {
            Packet packet = packetInfo.getPacket();
            if (packet instanceof RtpPacket) {
                seqNum = ((RtpPacket) packet).getSequenceNumber();
            }
            packetInfo.setEndpointId(AUDIO_MIXER_EP_ID);
            conference.handleIncomingPacket(packetInfo);
        });
        udpTransport = new UdpTransport(InetAddress.getLocalHost().getHostAddress(), 0, logger, SO_RCVBUF, SO_SNDBUF);

        updateProcessorAddressScheduledFuture = TaskPools.SCHEDULED_POOL.scheduleAtFixedRate(this::updateProcessorAddress, 0, 10, TimeUnit.SECONDS);

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

            rtpMixerReceiver.enqueuePacket(pktInfo);
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
                "audio-processor-outgoing-packet-queue",
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
        InetSocketAddress processorAddressCopy = processorAddress;
        // processorAddress may change after checking
        if (processorAddressCopy != null) {
            Packet packet = packetInfo.getPacket();
            udpTransport.send(packet.getBuffer(), packet.getOffset(), packet.getLength(), processorAddressCopy);
            packetInfo.sent();
        }

        return true;
    }

    public void expire() {
        if (running.compareAndSet(true, false)) {
            logger.info("Expiring");

            updateProcessorAddressScheduledFuture.cancel(false);

            outgoingPacketQueues.values().forEach(PacketInfoQueue::close);
            outgoingPacketQueues.clear();

            Callback callback = new Callback() {
                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    logger.error("deleteAudioProcessorPipeline error", e);
                }

                @Override
                public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                    try {
                        if (!response.isSuccessful()) {
                            logger.error("deleteAudioProcessorPipeline unsuccessful response. " + Objects.requireNonNull(response.body()).string());
                        }
                    } finally {
                        response.close();
                    }
                }
            };

            try {
                deleteAudioProcessorPipeline(conference.getGid(), callback);
            } catch (Exception e) {
                logger.error("Can not delete audio processor pipeline", e);
            }
        }
    }

    public void addPayloadType(PayloadType payloadType) {
        streamInformationStore.addRtpPayloadType(payloadType);
    }

    private void scheduleUpdatePipeline(int seconds) {
        if (running.get()) {
            TaskPools.SCHEDULED_POOL.schedule((Runnable) this::updatePipeline, seconds, TimeUnit.SECONDS);
        }
    }

    private void updatePipeline() {
        Map<Long, String> ssrcToEndpoint = conference.getLocalEndpoints().stream()
                .flatMap(e -> e.getRemoteAudioSsrcc().stream().map(s -> Maps.immutableEntry(s, e.getID())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        updatePipeline(ssrcToEndpoint);
    }

    public void updatePipeline(Map<Long, String> ssrcToEndpoint) {
        updatePipeline(conference.getGid(), ssrcToEndpoint);
    }

    private void updatePipeline(String id, Map<Long, String> ssrcToEndpoint) {
        if (!running.get() || ssrcToEndpoint.size()==0) {
            return;
        }
        if (processorAddress == null) {
            TaskPools.SCHEDULED_POOL.schedule(()->this.updatePipeline(id, ssrcToEndpoint), 5, TimeUnit.SECONDS);
            return;
        }
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("Ssrcs", ssrcToEndpoint);
        RequestBody body = RequestBody.create(
                jsonObject.toJSONString(),
                MediaType.parse("application/json")
        );
        Request request = new Request.Builder()
                .url(getRtpProcessorHttpUrlBuilder(id).build())
                .put(body)
                .build();
        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                logger.error("Failure on updatePipeline", e);
                ConfAudioProcessorTransport.this.scheduleUpdatePipeline(30);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                try {
                    if(!response.isSuccessful()) {
                        ResponseBody responseBody = response.body();
                        logger.error(String.format(
                                "Unsuccessful response on updatePipeline. Code: %s. Response: %s",
                                response.code(),
                                responseBody==null ? "" : responseBody.string()
                        ));
                        ConfAudioProcessorTransport.this.scheduleUpdatePipeline(10);
                    }
                } finally {
                    response.close();
                }
            }
        });
    }
}
