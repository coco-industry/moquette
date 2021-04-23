package io.moquette.persistence;

import io.moquette.broker.SessionRegistry;
import io.moquette.broker.subscriptions.Topic;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttQoS;
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.StringDataType;

import java.nio.ByteBuffer;

public final class PublishedMessageValueType implements org.h2.mvstore.type.DataType {

    private enum MessageType {PUB_REL_MARKER, PUBLISHED_MESSAGE}

    private final StringDataType topicDataType = new StringDataType();

    @Override
    public int compare(Object a, Object b) {
        return 0;
    }

    @Override
    public int getMemory(Object obj) {
        final SessionRegistry.PublishedMessage casted = (SessionRegistry.PublishedMessage) obj;
        final int payloadSize = casted.getPayload().readableBytes();
        return 1 + // message type
            1 + // qos
            topicDataType.getMemory(casted.getTopic().toString()) + // topic
            4 + payloadSize; // payload
    }

    @Override
    public void write(WriteBuffer buff, Object obj) {
        if (obj instanceof SessionRegistry.PublishedMessage) {
            buff.put((byte) MessageType.PUBLISHED_MESSAGE.ordinal());

            final SessionRegistry.PublishedMessage casted = (SessionRegistry.PublishedMessage) obj;
            buff.put((byte) casted.getPublishingQos().value());

            final String token = casted.getTopic().toString();
            topicDataType.write(buff, token);

            final int payloadSize = casted.getPayload().readableBytes();
            byte[] rawBytes = new byte[payloadSize];
            casted.getPayload().copy().readBytes(rawBytes);
            buff.putInt(payloadSize);
            buff.put(rawBytes);
        } else if (obj instanceof SessionRegistry.PubRelMarker) {
            buff.put((byte) MessageType.PUB_REL_MARKER.ordinal());
        } else {
            throw new IllegalArgumentException("Unrecognized message class " + obj.getClass());
        }
    }

    @Override
    public void write(WriteBuffer buff, Object[] obj, int len, boolean key) {
        for (int i = 0; i < len; i++) {
            write(buff, obj[i]);
        }
    }

    @Override
    public Object read(ByteBuffer buff) {
        final byte messageType = buff.get();
        if (messageType == MessageType.PUB_REL_MARKER.ordinal()) {
            return new SessionRegistry.PubRelMarker();
        } else if (messageType == MessageType.PUBLISHED_MESSAGE.ordinal()) {
            final MqttQoS qos = MqttQoS.valueOf(buff.get());
            final String topicStr = topicDataType.read(buff);
            final int payloadSize = buff.getInt();
            byte[] payload = new byte[payloadSize];
            buff.get(payload);
            final ByteBuf byteBuf = Unpooled.wrappedBuffer(payload);
            return new SessionRegistry.PublishedMessage(Topic.asTopic(topicStr), qos, byteBuf);
        } else {
            throw new IllegalArgumentException("Can't recognize record of type: " + messageType);
        }
    }

    @Override
    public void read(ByteBuffer buff, Object[] obj, int len, boolean key) {
        for (int i = 0; i < len; i++) {
            obj[i] = read(buff);
        }
    }
}
