package com.sportenth.data.services

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.CannedAccessControlList
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import java.io.ByteArrayInputStream
import java.util.UUID

class UploadService {
    private val accessKey  = System.getenv("S3_ACCESS_KEY") ?: ""
    private val secretKey  = System.getenv("S3_SECRET_KEY") ?: ""
    private val bucket     = System.getenv("S3_BUCKET") ?: "esport"
    private val endpoint   = System.getenv("S3_ENDPOINT") ?: "https://storage.yandexcloud.net"
    private val region     = System.getenv("S3_REGION") ?: "ru-central1"

    private val s3: AmazonS3 by lazy {
        AmazonS3ClientBuilder.standard()
            .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(endpoint, region))
            .withCredentials(AWSStaticCredentialsProvider(BasicAWSCredentials(accessKey, secretKey)))
            .withPathStyleAccessEnabled(true)
            .build()
    }

    fun upload(fileBytes: ByteArray, fileName: String, type: String, contentType: String): String {
        val ext = fileName.substringAfterLast('.', "bin")
        val key = "$type/${UUID.randomUUID()}.$ext"

        val metadata = ObjectMetadata().apply {
            this.contentType = contentType
            contentLength = fileBytes.size.toLong()
        }

        s3.putObject(
            PutObjectRequest(bucket, key, ByteArrayInputStream(fileBytes), metadata)
                .withCannedAcl(CannedAccessControlList.PublicRead)
        )

        return "https://$bucket.storage.yandexcloud.net/$key"
    }
}
