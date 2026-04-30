package com.sportenth.data.services

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.ObjectCannedACL
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI
import java.util.UUID

class UploadService {
    private val accessKey = System.getenv("S3_ACCESS_KEY") ?: ""
    private val secretKey = System.getenv("S3_SECRET_KEY") ?: ""
    private val bucket    = System.getenv("S3_BUCKET") ?: "esport"
    private val endpoint  = System.getenv("S3_ENDPOINT") ?: "https://storage.yandexcloud.net"
    private val region    = System.getenv("S3_REGION") ?: "ru-central1"

    private val s3: S3Client by lazy {
        S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
            )
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .chunkedEncodingEnabled(false)
                    .build()
            )
            .build()
    }

    /**
     * Загружает файл в S3 и возвращает публичный URL.
     * @param fileBytes байты файла
     * @param fileName  оригинальное имя (для определения расширения)
     * @param type      подкаталог: avatar | competition_image | competition_map
     * @param contentType MIME-тип файла
     */
    fun upload(fileBytes: ByteArray, fileName: String, type: String, contentType: String): String {
        val ext = fileName.substringAfterLast('.', "bin")
        val key = "$type/${UUID.randomUUID()}.$ext"

        s3.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .acl(ObjectCannedACL.PUBLIC_READ)
                .contentType(contentType)
                .contentLength(fileBytes.size.toLong())
                .build(),
            RequestBody.fromBytes(fileBytes)
        )

        return "https://$bucket.storage.yandexcloud.net/$key"
    }
}
