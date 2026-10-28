package com.competra.data.services

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.ObjectCannedACL
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.UUID
import javax.imageio.ImageIO

class UploadService {
    init {
        // ImageIO/Graphics2D в контейнере без дисплея падают без этого — на Alpine JRE
        // font-subsystem иначе может кинуть InternalError даже без рендера текста.
        System.setProperty("java.awt.headless", "true")
    }

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

    fun upload(fileBytes: ByteArray, fileName: String, type: String, contentType: String): String {
        val (bytes, resolvedContentType, resolvedExt) = if (type == DISTANCE_MAP_TYPE) {
            resizeIfOversized(fileBytes, contentType, fileName)
        } else {
            Triple(fileBytes, contentType, fileName.substringAfterLast('.', "bin"))
        }
        val key = "$type/${UUID.randomUUID()}.$resolvedExt"

        s3.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .acl(ObjectCannedACL.PUBLIC_READ)
                .contentType(resolvedContentType)
                .contentLength(bytes.size.toLong())
                .build(),
            RequestBody.fromBytes(bytes)
        )

        return "https://$bucket.storage.yandexcloud.net/$key"
    }

    /**
     * Удаляет файл карты дистанции по URL, который вернул [upload]. Чужие URL и файлы других
     * типов (аватары, обложки) не трогаем — возвращаем false.
     */
    fun deleteDistanceMap(url: String): Boolean {
        val key = url.removePrefix("https://$bucket.storage.yandexcloud.net/")
        if (key == url || !key.startsWith("$DISTANCE_MAP_TYPE/")) return false
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
        return true
    }

    /**
     * Карты дистанций из mapper часто печатного разрешения (десятки мегабайт, тысячи пикселей
     * по стороне) — синхронное декодирование такого PNG через Skia в браузере блокирует основной
     * поток на десятки секунд и выглядит как зависшая страница. Даунскейлим перед сохранением
     * в S3, чтобы веб-клиент никогда не видел файл такого размера. При любой ошибке декодирования
     * (например, недостающие системные библиотеки на Alpine) — тихо откатываемся к оригиналу,
     * так как это не должно ронять сам аплоад.
     */
    private fun resizeIfOversized(bytes: ByteArray, contentType: String, fileName: String): Triple<ByteArray, String, String> {
        val originalExt = fileName.substringAfterLast('.', "bin")
        if (!contentType.startsWith("image/")) return Triple(bytes, contentType, originalExt)
        return try {
            val original = ImageIO.read(ByteArrayInputStream(bytes)) ?: return Triple(bytes, contentType, originalExt)
            val maxSide = maxOf(original.width, original.height)
            if (maxSide <= MAX_DISTANCE_MAP_DIMENSION) return Triple(bytes, contentType, originalExt)

            val scale = MAX_DISTANCE_MAP_DIMENSION.toDouble() / maxSide
            val newWidth = (original.width * scale).toInt().coerceAtLeast(1)
            val newHeight = (original.height * scale).toInt().coerceAtLeast(1)

            val resized = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB)
            val g = resized.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(original, 0, 0, newWidth, newHeight, null)
            g.dispose()

            val output = ByteArrayOutputStream()
            ImageIO.write(resized, "png", output)
            Triple(output.toByteArray(), "image/png", "png")
        } catch (e: Exception) {
            Triple(bytes, contentType, originalExt)
        }
    }

    private companion object {
        const val DISTANCE_MAP_TYPE = "distance-map"
        const val MAX_DISTANCE_MAP_DIMENSION = 2000
    }
}
