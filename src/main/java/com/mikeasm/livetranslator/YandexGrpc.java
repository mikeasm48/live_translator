package com.mikeasm.livetranslator;

import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;

import java.util.concurrent.TimeUnit;

/** Создание каналов к сервисам Yandex AI Studio с авторизацией по Api-Key / IAM. */
public final class YandexGrpc {

    public static final String STT_ENDPOINT = "stt.api.cloud.yandex.net";
    public static final String TRANSLATE_ENDPOINT = "translate.api.cloud.yandex.net";
    public static final String LLM_ENDPOINT = "llm.api.cloud.yandex.net";
    private static final int PORT = 443;

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> FOLDER_ID =
            Metadata.Key.of("x-folder-id", Metadata.ASCII_STRING_MARSHALLER);

    private YandexGrpc() {}

    public static ManagedChannel channel(String host, Config config) {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, config.authHeader());
        // Каталог нужен при авторизации IAM-токеном; для Api-Key он определяется
        // по сервисному аккаунту, но лишний заголовок не мешает.
        if (!config.folderId.isBlank()) headers.put(FOLDER_ID, config.folderId);
        ClientInterceptor auth = MetadataUtils.newAttachHeadersInterceptor(headers);

        return NettyChannelBuilder.forAddress(host, PORT)
                .useTransportSecurity()
                .intercept(auth)
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .maxInboundMessageSize(16 * 1024 * 1024)
                .build();
    }
}
