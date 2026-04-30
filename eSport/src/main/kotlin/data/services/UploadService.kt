package com.sportenth.data.services

import io.minio.MinioClient
import io.minio.PutObjectArgs
import java.io.ByteArrayInputStream
import java.util.UUID

class UploadService {
    private val accessKey = System.getenv("S3_ACCESS_KEY") ?: ""
    private val secretKey = System.getenv("S3_SECRET_KEY") ?: ""
    private val bucket    = System.getenv("S3_BUCKET") ?: "esport"
    private val endpoint  = System.getenv("S3_ENDPOINT") ?: "https://storage.yandexcloud.net"

    private val minio: MinioClient by lazy {
        MinioClient.builder()
            .endpoint(endpoint)
            .credentials(accessKey, secretKey)
            .build()
    }

    fun upload(fileBytes: ByteArray, fileName: String, type: String, contentType: String): String {
        val ext = fileName.substringAfterLast('.', "bin")
        val key = "$type/${UUID.randomUUID()}.$ext"

        minio.putObject(
            PutObjectArgs.builder()
                .bucket(bucket)
                .`object`(key)
                .stream(ByteArrayInputStream(fileBytes), fileBytes.size.toLong(), -1)
                .contentType(contentType)
                .headers(mapOf("x-amz-acl" to "public-read"))
                .build()
        )

        return "https://$bucket.storage.yandexcloud.net/$key"
    }
}
